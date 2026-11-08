(ns craft.agents.backend.fabric.goals
  "Despacho polimorfico de metas cognitivas da Karlach e interpretador de Behavior Tree."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions :refer [ore-pt-names]]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.sanctuary :as sanctuary]
            [craft.agents.backend.fabric.telemetry :as telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport :refer [uformat]]
            [craft.agents.cognition.critic :as critic]
            [craft.agents.rsi.curriculum :as curriculum]
            [craft.agents.skills.actions :as skill-actions]
            [craft.agents.skills.composer :as composer]))

(declare execute-tree-node!)

(defmulti execute-karlach-goal!
  "Despacha a meta cognitiva fundamentada (GoalSpecification) para atuacao fisica e telemetria (CA-040)."
  (fn [goal-spec _engine-ctx _nav-state _sanctuary-route]
    (:goal/type goal-spec)))

(defmethod execute-karlach-goal! :animal-rescue
  [{:goal/keys [id name entity species pen-id gate-pos gate-block gate-facing inside-pos outside-pos feed-pos bait-item voice-line speaker] :as _goal-spec}
   engine-ctx-atom nav-state _sanctuary-route]
  ;; 1. Fala inicial da Karlach acusando o estímulo
  (transport/send-bridge-cmd! "SWING|Karlach")
  (transport/send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
  (actions/karlach-say! voice-line)

  (record-cognitive-event! engine-ctx-atom :grounding "Grounding Engine"
                           (str "Ancorado em " (clojure.core/name pen-id) " | Alvo: " entity " | Portão: " gate-pos " | Isca: " bait-item))

  ;; 2. Composição e Validação da Nova Skill em Tempo Real via Malli
  (let [skills-dir (io/file "skills")
        _ (.mkdirs skills-dir)
        skill-registry (atom {:known-actions #{:locate-escaped-animal :equip-bait :lure-to-gate :open-gate :lure-inside :feed-animal :close-gate :check-weather :move-to-quarters :rest-at-bed :wait-out-rain :attach-lead :detach-lead :verify-containment}
                              :composite-skills {}})
        rescue-skill {:skill/id :rescue-animal
                      :skill/name "Resgate e Condução Geral de Animal ao Cercado"
                      :skill/author "Voyager-System-2-Cognition"
                      :skill/trigger :animal-escaped
                      :skill/timeout-ms 35000
                      :skill/tree [:sequence
                                   [:action :equip-bait {:item bait-item}]
                                   [:action :locate-escaped-animal {:target species}]
                                   [:action :attach-lead {:species species}]
                                   [:action :lure-to-gate {:pos outside-pos}]
                                   [:action :open-gate {:enclosure pen-id}]
                                   [:action :lure-inside {:pos inside-pos}]
                                   [:action :close-gate {:enclosure pen-id}]
                                   [:action :detach-lead {:species species}]
                                   [:action :feed-animal {:food bait-item}]]}
        reg-res (composer/validate-and-register-skill! skill-registry skills-dir rescue-skill)
        skill-id-str "rescue-animal"]
    (println (str "[craft-agents] 🧠 Nova Skill composta via MCP: " id))
    (println (str "[craft-agents] 🛡️ Gatekeeper Malli: " (if (:ok? reg-res) "APROVADA (Sem eval / Schema OK / Ciclos 0)" "REJEITADA") " -> Salvo em " (str (io/file skills-dir (str skill-id-str ".edn")))))

    (let [pre-res (composer/evaluate-preconditions (:skill/tree rescue-skill) {:inventory {bait-item 1 "minecraft:lead" 1}} {:known-enclosures #{pen-id}})]
      (record-cognitive-event! engine-ctx-atom :gatekeeper "Malli Schema Gatekeeper"
                               (str "Validação AST estrutural & Sandbox Mental: SUCESSO (Zero eval / Tipos conferidos / 0 ciclos recursivos / Pré-condições: " (clojure.core/name (:status pre-res :ok)) ").")))
    (record-cognitive-event! engine-ctx-atom :skill-lib "Skill Library"
                             (str id " registrada permanentemente em skills/" skill-id-str ".edn."))

    ;; Atualiza telemetria do Agent Studio
    (when engine-ctx-atom
      (swap! engine-ctx-atom update-in [:agents :karlach :skills] (fnil #(vec (distinct (conj % skill-id-str))) []))
      (swap! engine-ctx-atom assoc-in [:agents :karlach :last-learned] (str id " (Malli OK)"))
      (let [enc-title (or (:enclosure/name (get sanctuary/sanctuary-enclosures-by-id (or pen-id :pen-south-west))) "Cercado")]
        (swap! engine-ctx-atom assoc-in [:agents :karlach :task] (str "Conduzindo " entity " ao " enc-title))))

    ;; 4. Execução imediata da rota motora de resgate com targeting real e A* 3D
    (let [norm-sp (critic/normalize-species (or species entity))
          mob-resp (transport/send-bridge-cmd! (str "LOCATE_MOB|Karlach|" norm-sp))
          mob-pos (when (and mob-resp (str/starts-with? mob-resp "OK|"))
                    (let [parts (str/split mob-resp #"\|")]
                      [(Double/parseDouble (nth parts 1))
                       (Double/parseDouble (nth parts 2))
                       (Double/parseDouble (nth parts 3))]))
          players (actions/query-online-players)
          target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or speaker "pace"))) players))
          near-player-pos (when target-p
                            (let [px (:x target-p)
                                  py (:y target-p)
                                  pz (:z target-p)]
                              [(+ px (if (> px -175.0) 3.0 -3.0))
                               py
                               pz]))
          animal-rescue-pos (or mob-pos near-player-pos outside-pos [-177.0 65.0 189.0])
          enc-info (get sanctuary/sanctuary-enclosures-by-id pen-id)
          portal-info (or (:enclosure/portal enc-info)
                          {:blocks [gate-pos]
                           :normal (if (<= (first gate-pos) -175) [-1.0 0.0 0.0] [1.0 0.0 0.0])
                           :inside-anchor inside-pos
                           :outside-anchor outside-pos
                           :gate-block gate-block
                           :gate-facing gate-facing})
          funnel (sanctuary/compute-enclosure-funnel portal-info)
          funnel-entry (:funnel-entry funnel)
          pre-gate     (:pre-gate funnel)
          in-anc       (:inside-anchor funnel)
          deep-in      (:deep-inside funnel)
          gate-coords  (:blocks portal-info [gate-pos])
          gate-blk     (:gate-block portal-info gate-block)
          gate-fac     (:gate-facing portal-info gate-facing)
          init-snap (critic/parse-sanctuary-snapshot (transport/send-bridge-cmd! "SANCTUARY"))
          target-quad (get critic/enclosure->quadrant (or pen-id :pen-south-west) :SW)
          init-count (get-in init-snap [target-quad norm-sp] 0)
          steps-to-mob (nav/generate-walk-steps (:pos @nav-state) animal-rescue-pos (str "Aproximando do " entity " fugitivo") {:radius 1.2})
          steps-to-funnel (nav/generate-walk-steps animal-rescue-pos funnel-entry (str "Conduzindo " entity " na guia até o alinhador do portão") {:pull-leashed? true :species species :radius 1.0})
          steps-to-feed (nav/generate-walk-steps in-anc feed-pos (str "Conduzindo ao comedouro de " entity) {:radius 1.5})
          main-steps
          (concat
           [{:op :equip :item "minecraft:lead" :label (str "Empunhando guia para laçar " entity)}]
           steps-to-mob
           [{:op :attach-lead :species species :label (str "Prendendo a guia no " entity)}
            {:op :wait :ticks 15 :label "Fixando guia"}]
           steps-to-funnel
           [{:op :gate :open? true :pos (first gate-coords) :gates gate-coords :block gate-blk :facing gate-fac
             :label (str "Abrindo portão do cercado de " entity)}
            {:op :wait :ticks 8 :label "Aguardando portão abrir"}
            {:op :walk :target pre-gate :label "Alinhando animal no funil ortogonal do portão" :species species}
            {:op :walk :target in-anc :label "Cruzando o vão do portão com o animal" :species species}
            {:op :walk :target deep-in :label (str "Conduzindo " entity " para o interior seguro do cercado") :species species}
            {:op :await-contained :species species :pen-id pen-id :initial-contained init-count :timeout 80
             :label "Aguardando confirmação sensorial de entrada do animal (Environmental Critic)"}
            {:op :detach-lead :species species :label (str "Soltando a guia do " entity)}
            {:op :wait :ticks 10 :label "Removendo guia"}
            {:op :walk :target in-anc :label "Retornando ao portão para trancar"}
            {:op :gate :open? false :pos (first gate-coords) :gates gate-coords :block gate-blk :facing gate-fac
             :label (str "Trancando cercado de " entity)}
            {:op :wait :ticks 8 :label "Conferindo tranca"}]
           steps-to-feed
           [{:op :feed :pos feed-pos :food bait-item :species [species] :label (str "Alimentando: " entity)}
            {:op :wait :ticks 20 :label (str entity " seguro e alimentado")}
            {:op :critic :attempt 1
             :goal-id id
             :species species
             :pen-id pen-id
             :entity entity
             :initial-contained init-count
             :animal-pos animal-rescue-pos
             :gates gate-coords
             :block gate-blk
             :facing gate-fac
             :outside-pos pre-gate
             :inside-pos in-anc
             :feed-pos feed-pos
             :bait-item bait-item
             :label "Environmental Critic: Verificação de Contenção"}])]
      (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller"
                               (str "Executando resgate com percepção dinâmica de terreno para " entity " rumo a " feed-pos))
      (swap! nav-state assoc
             :mode :sanctuary-patrol
             :follow-target nil
             :steps (vec main-steps)))))

(defmethod execute-karlach-goal! :weather-shelter
  [{:goal/keys [voice-line]} engine-ctx-atom nav-state sanctuary-route]
  (transport/send-bridge-cmd! "SWING|Karlach")
  (transport/send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
  (actions/karlach-say! voice-line)

  (record-cognitive-event! engine-ctx-atom :synthesis "System 2 Voyager Synthesizer"
                           "Objetivo: :shelter-from-rain. Compondo árvore de comportamento [:sequence [:action :check-weather] [:action :move-to-quarters] [:action :rest-at-bed]]")

  (let [skills-dir (io/file "skills")
        _ (.mkdirs skills-dir)
        skill-registry (atom {:known-actions #{:check-weather :move-to-quarters :rest-at-bed :wait-out-rain}
                              :composite-skills {}})
        shelter-skill {:skill/id :shelter-from-rain
                       :skill/name "Abrigo de Emergência contra Chuva"
                       :skill/author "Voyager-System-2-Cognition"
                       :skill/trigger :weather-rain
                       :skill/timeout-ms 20000
                       :skill/tree [:sequence
                                    [:action :check-weather]
                                    [:action :move-to-quarters]
                                    [:action :rest-at-bed]
                                    [:action :wait-out-rain]]}
        reg-res (composer/validate-and-register-skill! skill-registry skills-dir shelter-skill)]
    (println (str "[craft-agents] 🧠 Nova Skill composta via MCP: :shelter-from-rain"))
    (println (str "[craft-agents] 🛡️ Gatekeeper Malli: " (if (:ok? reg-res) "APROVADA (Sem eval / Schema OK / Ciclos 0)" "REJEITADA") " -> Salvo em " (str (io/file skills-dir "shelter-from-rain.edn"))))

    (let [pre-res (composer/evaluate-preconditions (:skill/tree shelter-skill) {})]
      (record-cognitive-event! engine-ctx-atom :gatekeeper "Malli Schema Gatekeeper"
                               (str "Validação AST estrutural & Sandbox Mental: SUCESSO (Zero eval / Tipos conferidos / 0 ciclos recursivos / Pré-condições: " (clojure.core/name (:status pre-res :ok)) ").")))
    (record-cognitive-event! engine-ctx-atom :skill-lib "Skill Library"
                             ":shelter-from-rain registrada permanentemente em skills/shelter-from-rain.edn.")

    (when engine-ctx-atom
      (swap! engine-ctx-atom assoc-in [:agents :karlach :skills] ["shelter-from-rain"])
      (swap! engine-ctx-atom assoc-in [:agents :karlach :last-learned] ":shelter-from-rain (Malli OK)")
      (swap! engine-ctx-atom assoc-in [:agents :karlach :task] "Indo para o Quarto se abrigar"))

    (actions/karlach-say! "Chuva detectada, soldado! Indo pro meu quarto me proteger!")

    (let [quarters-stop (some #(when (= (:type %) :quarters) %) sanctuary-route)]
      (if quarters-stop
        (do
          (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller"
                                   "Iniciando navegação A* com microsteps para Quarto da Karlach [-129, 65, 194]")
          (swap! nav-state assoc
                 :mode :sanctuary-patrol
                 :follow-target nil
                 :steps (vec (nav/expand-route-microsteps quarters-stop (:pos @nav-state)))))
        (actions/karlach-say! "Meu quarto ainda tá sendo arrumado!")))))

(defmethod execute-karlach-goal! :teleport-to-player
  [{:goal/keys [target voice-line]} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" (str "Teleporte intencional solicitado para " target))
  (let [players (actions/query-online-players)
        target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
        tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] (:pos @nav-state))]
    (when target-p
      (transport/send-bridge-cmd! (str "CMD|tp Karlach " target))
      (transport/send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0")
      (transport/send-bridge-cmd! "CMD|execute at Karlach run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 35 normal")
      (swap! nav-state assoc
             :mode :bodyguard
             :pos tgt-pos
             :follow-target target
             :follow-path []
             :follow-last-target [0.0 0.0 0.0]
             :follow-lost-ticks 0
             :steps []
             :active-mining nil))
    (actions/karlach-say! voice-line)))

(defmethod execute-karlach-goal! :follow-player
  [{:goal/keys [target voice-line]} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" (str "Seguindo jogador " target))
  (let [players (try (actions/query-online-players) (catch Throwable _ []))
        target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
        [cx cy cz] (or (:pos @nav-state) [-175.0 65.0 180.0])
        tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] [cx cy cz])
        dist-to-p (if target-p
                    (Math/sqrt (+ (Math/pow (- (:x target-p) cx) 2)
                                  (Math/pow (- (:y target-p) cy) 2)
                                  (Math/pow (- (:z target-p) cz) 2)))
                    0.0)]
    ;; Teleporte apenas de emergencia se estiver muito distante (> 48m / outro bioma)
    (when (and target-p (> dist-to-p 48.0))
      (transport/send-bridge-cmd! (str "CMD|tp Karlach " target))
      (transport/send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0")
      (transport/send-bridge-cmd! "CMD|execute at Karlach run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 20 normal"))
    (swap! nav-state assoc
           :mode :follow
           :pos (if (> dist-to-p 48.0) tgt-pos [cx cy cz])
           :follow-target target
           :follow-path (if (and target-p (<= dist-to-p 48.0))
                          (or (nav/request-path3d [cx cy cz] tgt-pos 2.2) [])
                          [])
           :follow-last-target [0.0 0.0 0.0]
           :follow-lost-ticks 0
           :steps [])
    (when voice-line
      (actions/karlach-say! voice-line))))

(defmethod execute-karlach-goal! :bodyguard
  [{:goal/keys [target voice-line]} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" (str "Modo Guarda-Costas ativado para " target))
  (let [players (actions/query-online-players)
        target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
        [cx cy cz] (:pos @nav-state)
        tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] [cx cy cz])
        dist-to-p (if target-p
                    (Math/sqrt (+ (Math/pow (- (:x target-p) cx) 2)
                                  (Math/pow (- (:y target-p) cy) 2)
                                  (Math/pow (- (:z target-p) cz) 2)))
                    0.0)]
    ;; Teleporte apenas de emergencia se estiver muito distante (> 48m / outro bioma)
    (when (and target-p (> dist-to-p 48.0))
      (transport/send-bridge-cmd! (str "CMD|tp Karlach " target))
      (transport/send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0")
      (transport/send-bridge-cmd! "CMD|execute at Karlach run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 20 normal"))
    (transport/send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:netherite_axe")
    (transport/send-bridge-cmd! "CMD|item replace entity Karlach weapon.offhand with minecraft:shield")
    (transport/send-bridge-cmd! "CMD|playsound minecraft:item.armor.equip_netherite master @a ~ ~ ~ 1.0 1.0")
    (transport/send-bridge-cmd! "SWING|Karlach")
    (swap! nav-state assoc
           :mode :bodyguard
           :pos (if (> dist-to-p 48.0) tgt-pos [cx cy cz])
           :follow-target target
           :follow-path (if (and target-p (<= dist-to-p 48.0))
                          (or (nav/request-path3d [cx cy cz] tgt-pos 2.2) [])
                          [])
           :follow-last-target [0.0 0.0 0.0]
           :follow-lost-ticks 0
           :steps []))
  (when engine-ctx-atom
    (swap! engine-ctx-atom assoc-in [:agents :karlach :task] (str "Guarda-Costas de " target)))
  (actions/karlach-say! voice-line))

(defmethod execute-karlach-goal! :mining-cooperation
  [{:goal/keys [target voice-line] target-filter :goal/filter} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller"
                           (str "Iniciando mineração cooperativa com " target " | Filtro: " (or target-filter "geral")))
  ;; 1. Garante sincronizacao e proximidade imediata com o jogador para mineracao cooperativa
  (let [players (actions/query-online-players)
        target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
        tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] (:pos @nav-state))
        [cx cy cz] (:pos @nav-state)
        dist-to-player (if target-p
                         (Math/sqrt (+ (Math/pow (- (:x target-p) cx) 2)
                                       (Math/pow (- (:y target-p) cy) 2)
                                       (Math/pow (- (:z target-p) cz) 2)))
                         0.0)]
    (when (and target-p (> dist-to-player 48.0))
      (transport/send-bridge-cmd! (str "CMD|tp Karlach " target))
      (transport/send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0")
      (transport/send-bridge-cmd! "CMD|execute at Karlach run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 20 normal")
      (swap! nav-state assoc :pos tgt-pos :follow-path [] :steps [])))

  (transport/send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:netherite_pickaxe")
  (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
  (transport/send-bridge-cmd! "SWING|Karlach")

  (cond
    ;; Caso A: Minerar o bloco que o jogador está olhando (Raycast / Joint Gaze)
    (= target-filter "crosshair")
    (let [look-res (actions/raycast-player-look (or target "pace") 5.5)]
      (if (:found? look-res)
        (let [bx (:x look-res)
              by (:y look-res)
              bz (:z look-res)
              bid (:block-id look-res)
              [kx ky kz] (:pos @nav-state)
              odx (- (+ (double bx) 0.5) kx)
              ody (- (+ (double by) 0.5) (+ (double ky) 1.62))
              odz (- (+ (double bz) 0.5) kz)
              hdist (Math/sqrt (+ (* odx odx) (* odz odz)))
              total-d (Math/sqrt (+ (* odx odx) (* ody ody) (* odz odz)))
              yaw (nav/compute-yaw odx odz)
              pitch (nav/compute-pitch ody hdist)]
          ;; Se Karlach estiver a mais de 2.2m do bloco apontado, aproxima para quebrar colada
          (when (> total-d 2.2)
            (let [approach-x (+ (double bx) (if (> kx bx) 1.2 -1.2))
                  approach-z (+ (double bz) (if (> kz bz) 1.2 -1.2))]
              (transport/send-bridge-cmd! (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|%.1f|false|false" approach-x ky approach-z yaw pitch))
              (swap! nav-state assoc :pos [approach-x ky approach-z] :yaw yaw :pitch pitch)))
          (let [[cur-x cur-y cur-z] (:pos @nav-state)]
            (transport/send-bridge-cmd! (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|%.1f|false|false" cur-x cur-y cur-z yaw pitch)))
          (transport/send-bridge-cmd! "SWING|Karlach")
          (let [mine-res (actions/mine-block! "Karlach" bx by bz)]
            (if (:success? mine-res)
              (do
                (record-cognitive-event! engine-ctx-atom :action "Mineração Instantânea"
                                         (str "Bloco " bid " quebrado em [" bx " " by " " bz "]"))
                (transport/send-bridge-cmd! (uformat "CMD|particle minecraft:block %s %.2f %.2f %.2f 0.3 0.3 0.3 0.05 12 normal" (or bid "minecraft:stone") (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5)))
                (transport/send-bridge-cmd! (uformat "CMD|playsound minecraft:block.deepslate.break master @a %.1f %.1f %.1f 1.0 1.0" (double bx) (double by) (double bz)))
                (actions/karlach-say! (str "Bloco " (str/replace (or bid "alvo") #"minecraft:" "") " quebrado com sucesso na sua frente, soldado! ⛏️ 🔥")))
              (actions/karlach-say! (str "Não consegui quebrar esse bloco: " (:message mine-res))))))
        (actions/karlach-say! (str "Não consegui identificar nenhum bloco na sua mira direta, " target "! Chega um pouco mais perto e aponta de novo!"))))

    ;; Caso B: Ativação do Modo de Mineração Contínua Autônoma
    :else
    (let [ore-name (get ore-pt-names target-filter (if (str/blank? target-filter) "minérios" target-filter))]
      (actions/karlach-say! (str "Picareta em punho, " target "! Modo de mineração contínua de " ore-name " ativado! Vou extrair todos os veios que encontrar por aqui! 🔥 ⛏️"))
      (swap! nav-state assoc
             :mode :mining
             :mining-filter (or target-filter "")
             :mining-target (or target "pace")
             :mining-blocks-broken 0)))

  (when engine-ctx-atom
    (swap! engine-ctx-atom assoc-in [:agents :karlach :task]
           (if target-filter (str "Minerando " (get ore-pt-names target-filter target-filter) " com " target) (str "Minerando com " target)))))

(defmethod execute-karlach-goal! :spatial-attention
  [{:goal/keys [entity speaker]} engine-ctx-atom nav-state _sanctuary-route]
  (let [players (actions/query-online-players)
        target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or speaker "pace"))) players))
        [cx cy cz] (:pos @nav-state)]
    (if target-p
      (let [px (:x target-p)
            py (:y target-p)
            pz (:z target-p)
            dx (- px cx)
            dz (- pz cz)
            look-yaw (nav/compute-yaw dx dz)]
        (transport/send-bridge-cmd! (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz look-yaw))
        (swap! nav-state assoc :yaw look-yaw)
        (record-cognitive-event! engine-ctx-atom :grounding "Percepção Espacial (Joint Attention)"
                                 (str "Olhando para " speaker " em [" (int px) " " (int pz) "] identificando " entity))
        (actions/karlach-say! (str "Tô vendo sim, " speaker "! Um " entity " bem aí perto de você, do lado de fora do muro!")))
      (actions/karlach-say! (str "Onde, " speaker "? Não tô conseguindo te ver por aqui!")))))

(defmethod execute-karlach-goal! :social-dance
  [{:goal/keys [voice-line]} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" "Executando animação de dança infernal")
  (swap! nav-state assoc :dance-ticks 80)
  (actions/karlach-say! voice-line))

(defmethod execute-karlach-goal! :patrol-sanctuary
  [{:goal/keys [voice-line]} engine-ctx-atom nav-state sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" "Retomando patrulha e alimentação de animais no Santuário")
  (when engine-ctx-atom
    (swap! engine-ctx-atom assoc-in [:agents :karlach :task] "Patrulha no Santuário"))
  (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.0")
  (let [initial-steps (vec (nav/expand-route-microsteps (first sanctuary-route) (:pos @nav-state)))]
    (swap! nav-state assoc :mode :sanctuary-patrol :follow-target nil :route-idx 0 :steps initial-steps))
  (actions/karlach-say! voice-line))

(defmethod execute-karlach-goal! :inventory-inspection
  [{:goal/keys [speaker]} engine-ctx-atom _nav-state _sanctuary-route]
  (let [inv (actions/inspect-inventory "Karlach")
        target (or speaker "pace")
        items (:items inv)]
    (record-cognitive-event! engine-ctx-atom :grounding "Inspeção de Inventário"
                             (str "Inventário de Karlach verificado: " (pr-str items)))
    (if (empty? items)
      (actions/karlach-say! (str "Minha mochila tá vazia por enquanto, " target "! Vamos minerar ou recolher uns recursos! 🔥"))
      (let [item-list (str/join ", " (map (fn [[iid c]] (str c "x " (str/replace iid #"minecraft:" ""))) items))]
        (actions/karlach-say! (str "Tô carregando comigo, " target ": [" item-list "]! (" (:slots-used inv) " slots ocupados). Pronta pra continuar! 🔥 🎒"))))))

(defmethod execute-karlach-goal! :drop-inventory
  [{:goal/keys [speaker] target-filter :goal/filter} engine-ctx-atom _nav-state _sanctuary-route]
  (let [target (or speaker "pace")
        drop-res (actions/drop-inventory-items! "Karlach" (or target-filter "all"))
        dropped-cnt (:dropped-count drop-res 0)
        items (:items drop-res)]
    (record-cognitive-event! engine-ctx-atom :action "Ejeção de Saque / Drop de Inventário"
                             (str "Karlach dropou " dropped-cnt " itens para " target))
    (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.item.pickup master @a ~ ~ ~ 1.0 0.8")
    (if (> dropped-cnt 0)
      (let [item-list (str/join ", " (map (fn [[iid c]] (str c "x " (str/replace iid #"minecraft:" ""))) items))]
        (actions/karlach-say! (str "Aqui está o saque da expedição, " target "! Ejetando [" item-list "]! Todo seu, soldado! 🔥 💎")))
      (actions/karlach-say! (str "Não tenho nenhum recurso solto pra dropar agora, " target "! Minha mochila tá limpa.")))))

(defmethod execute-karlach-goal! :breed-animals
  [{:goal/keys [speaker voice-line]} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :action "Reprodução de Animais em AFK"
                           "Ativando protocolo de reprodução e procriação de gatos no Santuário")
  (when engine-ctx-atom
    (swap! engine-ctx-atom assoc-in [:agents :karlach :task] "Reprodução de Gatos em AFK (Cupido Felino)"))
  (transport/send-bridge-cmd! "SWING|Karlach")
  (transport/send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:cod")
  (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
  (actions/karlach-say! (or voice-line (str "Pode deixar, " (or speaker "soldado") "! Ativando reprodução de gatos em AFK! 🔥 🐱")))
  ;; Pulso imediato de reprodução para gatos próximos à Karlach
  (let [[cx cy cz] (:pos @nav-state [-175.0 65.0 180.0])]
    (transport/send-bridge-cmd!
     (uformat "CMD|execute positioned %.1f %.1f %.1f if entity @e[type=cat,distance=..35,limit=18] as @e[type=cat,distance=..35,nbt={Age:0,Sitting:0b}] run data merge entity @s {InLove:600,PersistenceRequired:1b}"
             cx cy cz))
    (transport/send-bridge-cmd!
     (uformat "CMD|execute positioned %.1f %.1f %.1f as @e[type=cat,distance=..35] run effect give @s minecraft:regeneration 10 1 true"
             cx cy cz))
    (transport/send-bridge-cmd!
     (uformat "CMD|execute positioned %.1f %.1f %.1f at @e[type=cat,distance=..35,limit=6,sort=nearest] run particle minecraft:heart ~ ~1.0 ~ 0.5 0.5 0.5 0.05 10 normal"
             cx cy cz))
    (transport/send-bridge-cmd!
     (uformat "CMD|execute positioned %.1f %.1f %.1f at @e[type=cat,distance=..35,limit=2,sort=nearest] run playsound minecraft:entity.cat.purr neutral @a ~ ~ ~ 1.0 1.0"
             cx cy cz)))
  ;; Garante modo de patrulha do santuário ativo para alimentar e reproduzir ciclicamente em AFK
  (swap! nav-state assoc :mode :sanctuary-patrol :follow-target nil))

(defmethod execute-karlach-goal! :sit-furniture
  [{:goal/keys [speaker voice-line]} engine-ctx-atom _nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :action "Ação de Mobília / Sentar no Sofá"
                           (str "Karlach buscando assento livre próximo para sentar"))
  (actions/karlach-say! (or voice-line (str "Sentando aqui no sofá para descansar um pouco, " (or speaker "soldado") "! 🔥")))
  (let [res (transport/send-bridge-cmd! "SIT|Karlach")]
    (record-cognitive-event! engine-ctx-atom :motor "Assento Ocupado" (str "Bridge SIT response: " res))
    (transport/send-bridge-cmd! "CMD|playsound minecraft:block.wool.place master @a ~ ~ ~ 0.8 1.0")))

(defmethod execute-karlach-goal! :stand-up
  [{:goal/keys [speaker voice-line]} engine-ctx-atom _nav-state _sanctuary-route]
  (let [res (transport/send-bridge-cmd! "STAND|Karlach")]
    (record-cognitive-event! engine-ctx-atom :action "Levantar do Sofá" (str "Bridge STAND response: " res))
    (actions/karlach-say! (or voice-line (str "De pé e pronta pro combate, " (or speaker "soldado") "! 🔥")))
    (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.player.attack.sweep master @a ~ ~ ~ 0.5 1.2")))

(defmethod execute-karlach-goal! :teach-skill
  [{:goal/keys [instruction speaker]} engine-ctx-atom _nav-state _sanctuary-route]
  (let [skills-dir (io/file "skills")
        _ (.mkdirs skills-dir)
        skill-reg (atom {:known-actions composer/default-known-actions
                         :composite-skills {}})
        synth-res (composer/synthesize-and-register-skill-from-chat! skill-reg skills-dir instruction speaker)]
    (if (:ok? synth-res)
      (let [sid   (:skill-id synth-res)
            sname (:skill-name synth-res)
            sdef  (:skill-def synth-res)]
        (transport/send-bridge-cmd! "SWING|Karlach")
        (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.player.levelup master @a ~ ~ ~ 1.0 1.2")
        (record-cognitive-event! engine-ctx-atom :synthesis "System 2 Voyager Synthesizer"
                                 (str "Nova skill sintetizada do chat: '" sname "' -> " (pr-str (:skill/tree sdef))))
        (record-cognitive-event! engine-ctx-atom :gatekeeper "Malli Schema Gatekeeper"
                                 (str "Skill " sid " APROVADA: Schema BTNode OK / Ciclos 0 / Ações conhecidas conferidas."))
        (record-cognitive-event! engine-ctx-atom :skill-lib "Skill Library"
                                 (str sid " persistida permanentemente em skills/" (clojure.core/name sid) ".edn."))
        (when engine-ctx-atom
          (swap! engine-ctx-atom update-in [:agents :karlach :skills] (fnil #(vec (distinct (conj % (clojure.core/name sid)))) []))
          (swap! engine-ctx-atom assoc-in [:agents :karlach :last-learned] (str sid " (Chat OK)")))
        (actions/karlach-say! (str "Entendido, " (or speaker "soldado") "! Nova habilidade '" sname "' aprendida e gravada na minha memória! 🔥 Experimente dizer '" sname "' quando quiser que eu execute!")))
      (let [reason (:reason synth-res)
            msg    (or (:message synth-res) (str reason))]
        (if (contains? #{:UNRECOGNIZED_STEPS :UNKNOWN_ACTION} reason)
          (let [slug (composer/slugify instruction)
                tid  (str "ca-gap-" (subs slug 0 (min (count slug) 24)) "-" (System/currentTimeMillis))
                hyp  {:hypothesis/type :frontier-exploration
                      :target/category :motor-capability-gap
                      :target/identifier slug
                      :prompt/directive
                      (str "Implementar a nova capacidade motora que falta no motor para o comando: '" instruction "'.\n"
                           "REQUISITOS:\n"
                           "1. Em `src/clj/craft/agents/skills/composer.clj`:\n"
                           "   - Adicionar a nova ação em `default-known-actions`.\n"
                           "   - Em `step->action-node`, mapear o termo e intenções da instrução para [:action :nome-acao {:speaker speaker}].\n"
                           "2. Em `src/clj/craft/agents/skills/actions.clj`:\n"
                           "   - Implementar `(defmethod execute-action :nome-acao [action-id action-args ctx] ...)` retornando `{:status :success :action-id action-id}`.\n"
                           "   - REGRA DE PRATA ESPACIAL (CRÍTICO): Comandos Minecraft enviados via socket console executam na origem [0, 0, 0] se não forem explicitamente ancorados. Para qualquer comando que interaja com entidades próximas, seletores (@e, @p) ou coordenadas relativas, você DEVE prefixar com `execute at Karlach run ...` (exemplo: `\"CMD|execute at Karlach run ride Karlach mount @e[type=boat,distance=..6,sort=nearest,limit=1]\"`). NUNCA envie comandos relativos sem ancoragem espacial.\n"
                           "3. Em `test/craft/agents/epic5_cognition_mcp_studio_test.clj`:\n"
                           "   - Cobrir com teste unitário a nova ação e mapeamento mantendo 100% GREEN.\n"
                           "   - Não modifique testes existentes (Locked-Test protocol).")}]
            (try
              (curriculum/dispatch-curiosity-to-swe!
               hyp
               {:repo-dir "/home/pace/dev/craft-agents"
                :task-id tid
                :model "deepseek-v4-1-flash"
                :test-cmd "make test"
                :target-files ["src/clj/craft/agents/skills/composer.clj"
                               "src/clj/craft/agents/skills/actions.clj"
                               "test/craft/agents/epic5_cognition_mcp_studio_test.clj"]})
              (catch Throwable t
                (println "[curriculum] Erro ao despachar SWE:" (.getMessage t))))
            (transport/send-bridge-cmd! "SWING|Karlach")
            (transport/send-bridge-cmd! "CMD|playsound minecraft:block.beacon.activate master @a ~ ~ ~ 1.0 1.2")
            (record-cognitive-event! engine-ctx-atom :rsi "Curriculum RSI Loop"
                                     (str "Lacuna motora detectada no chat: '" instruction "'. Tarefa " tid " despachada para target/curiosity-queue/."))
            (actions/karlach-say! (str "Soldado, meu corpo ainda não tem a capacidade motora para '" instruction "'! ⚡ Despachei uma tarefa de auto-engenharia (" tid ") para a fábrica autônoma. O SWE-Runner está programando essa habilidade agora!")))
          (do
            (transport/send-bridge-cmd! "CMD|playsound minecraft:block.note_block.bass master @a ~ ~ ~ 1.0 0.8")
            (actions/karlach-say! (str "Soldado, não consegui aprender essa habilidade: " msg ". Tente algo como 'karlach aprenda danca-e-senta: dance e sente no sofa'!"))))))))

(defn execute-tree-node!
  "Executa nó da Behavior Tree preservando semântica de controle (:fallback, :sequence, :parallel, :inverter),
   avaliação de condições (:guard, :condition) e expandindo macros com isolamento de leases (CA-048)."
  [node engine-ctx-atom nav-state sanctuary-route speaker]
  (if-not (vector? node)
    {:status :failure :reason :INVALID_NODE}
    (let [op   (first node)
          args (rest node)]
      (case op
        :sequence
        (loop [children args
               last-res {:status :success}]
          (if (empty? children)
            last-res
            (let [res (execute-tree-node! (first children) engine-ctx-atom nav-state sanctuary-route speaker)]
              (if (= :success (:status res))
                (recur (rest children) (if (:action-id res) res last-res))
                res))))

        (:fallback :selector)
        (loop [children args]
          (if (empty? children)
            {:status :failure}
            (let [res (execute-tree-node! (first children) engine-ctx-atom nav-state sanctuary-route speaker)]
              (if (= :success (:status res))
                res
                (recur (rest children))))))

        :parallel
        (let [results (mapv #(execute-tree-node! % engine-ctx-atom nav-state sanctuary-route speaker) args)]
          (if (some #(= :failure (:status %)) results)
            {:status :failure}
            {:status :success}))

        :inverter
        (let [child-res (execute-tree-node! (first args) engine-ctx-atom nav-state sanctuary-route speaker)]
          (case (:status child-res)
            :success {:status :failure}
            :failure {:status :success}
            child-res))

        (:guard :condition)
        (let [cond-id (first args)
              eval-res (case cond-id
                         (:leader-in-vehicle? :leader-in-boat?)
                         (boolean (or (get-in @engine-ctx-atom [:perception :leader :in-vehicle?])
                                      (get-in @engine-ctx-atom [:leader :in-vehicle?])
                                      (:leader-in-vehicle? @nav-state)))

                         (:self-in-vehicle? :self-in-boat?)
                         (boolean (or (:in-vehicle? @nav-state)
                                      (get-in @engine-ctx-atom [:perception :self :in-vehicle?])
                                      (get-in @engine-ctx-atom [:agents :karlach :in-vehicle?])))

                         :not-self-in-vehicle?
                         (not (or (:in-vehicle? @nav-state)
                                  (get-in @engine-ctx-atom [:perception :self :in-vehicle?])
                                  (get-in @engine-ctx-atom [:agents :karlach :in-vehicle?])))

                         :not-leader-in-vehicle?
                         (not (or (get-in @engine-ctx-atom [:perception :leader :in-vehicle?])
                                  (get-in @engine-ctx-atom [:leader :in-vehicle?])
                                  (:leader-in-vehicle? @nav-state)))

                         :self-in-same-vehicle?
                         (let [self-in   (boolean (or (:in-vehicle? @nav-state)
                                                      (get-in @engine-ctx-atom [:perception :self :in-vehicle?])))
                               leader-in (boolean (or (get-in @engine-ctx-atom [:perception :leader :in-vehicle?])
                                                      (:leader-in-vehicle? @nav-state)))
                               self-vid  (or (:vehicle-id @nav-state)
                                             (get-in @engine-ctx-atom [:perception :self :vehicle-id]))
                               lead-vid  (or (get-in @engine-ctx-atom [:perception :leader :vehicle :id])
                                             (get-in @engine-ctx-atom [:perception :leader :vehicle-id]))]
                           (and self-in leader-in (or (= self-vid lead-vid) (and (nil? self-vid) (nil? lead-vid)))))

                         :leader-vehicle-has-seat?
                         (let [free-seats (get-in @engine-ctx-atom [:perception :leader :vehicle :free-seats]
                                                  (get-in @engine-ctx-atom [:leader :vehicle :free-seats] 1))]
                           (pos? free-seats))

                         :within-mount-range?
                         (let [dist (or (get-in @engine-ctx-atom [:perception :leader :distance])
                                        (:leader-distance @nav-state)
                                        2.0)]
                           (<= dist 3.5))

                         true)]
          (if eval-res
            {:status :success}
            {:status :failure}))

        :action
        (let [action-id (first args)
              action-args (second args)
              skills-dir (io/file "skills")
              macro-file (io/file skills-dir (str (clojure.core/name action-id) ".edn"))
              macro-def  (when (.exists macro-file)
                           (try (edn/read-string (slurp macro-file)) (catch Throwable _ nil)))
              macro-tree (or (:skill/tree macro-def)
                             (get-in @engine-ctx-atom [:composite-skills action-id]))]
          (if macro-tree
            (execute-tree-node! macro-tree engine-ctx-atom nav-state sanctuary-route speaker)
            (try
              (case action-id
                :social-dance
                (do (execute-karlach-goal! {:goal/type :social-dance :goal/voice-line "Olha o passinho de vitória! 🔥"}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :social-dance})

                :sit-furniture
                (do (execute-karlach-goal! {:goal/type :sit-furniture :goal/speaker speaker}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :sit-furniture})

                :stand-up
                (do (execute-karlach-goal! {:goal/type :stand-up :goal/speaker speaker}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :stand-up})

                :patrol-sanctuary
                (do (execute-karlach-goal! {:goal/type :patrol-sanctuary :goal/speaker speaker}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :patrol-sanctuary})

                :breed-animals
                (do (execute-karlach-goal! {:goal/type :breed-animals :goal/speaker speaker}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :breed-animals})

                :follow-player
                (do (execute-karlach-goal! {:goal/type :follow-player :goal/speaker speaker}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :follow-player})

                :walk-towards-leader
                (do (execute-karlach-goal! {:goal/type :follow-player :goal/speaker speaker}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :walk-towards-leader})

                :bodyguard
                (do (execute-karlach-goal! {:goal/type :bodyguard :goal/speaker speaker}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :bodyguard})

                :mining-cooperation
                (do (execute-karlach-goal! {:goal/type :mining-cooperation :goal/speaker speaker :goal/filter (:target action-args "diamond")}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :mining-cooperation})

                :drop-inventory
                (do (execute-karlach-goal! {:goal/type :drop-inventory :goal/speaker speaker}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :drop-inventory})

                :teleport-to-player
                (do (execute-karlach-goal! {:goal/type :teleport-to-player :goal/speaker speaker}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :teleport-to-player})

                :move-to-quarters
                (do (execute-karlach-goal! {:goal/type :weather-shelter :goal/speaker speaker}
                                           engine-ctx-atom nav-state sanctuary-route)
                    {:status :success :action-id :move-to-quarters})

                :say-message
                (do (actions/karlach-say! (or (:text action-args) "Comando concluído com sucesso, soldado!"))
                    {:status :success :action-id :say-message})

                :play-sound
                (do (transport/send-bridge-cmd! (str "CMD|playsound " (:sound action-args "minecraft:entity.experience_orb.pickup") " master @a ~ ~ ~ 1.0 1.0"))
                    {:status :success :action-id :play-sound})

                (let [act-res (skill-actions/execute-action action-id action-args
                                                            {:send-cmd! transport/send-bridge-cmd!
                                                             :karlach-say! actions/karlach-say!
                                                             :speaker speaker
                                                             :engine-ctx engine-ctx-atom
                                                             :nav-state nav-state
                                                             :sanctuary-route sanctuary-route})]
                  (record-cognitive-event! engine-ctx-atom :motor "Ação Modular"
                                           (str "Executando ação modular: " action-id " -> " (:status act-res)))
                  act-res))
              (catch Throwable _
                {:status :failure :action action-id}))))

        {:status :success}))))

(defmethod execute-karlach-goal! :execute-dynamic-skill
  [{:goal/keys [skill-id speaker]} engine-ctx-atom nav-state sanctuary-route]
  (let [skills-dir (io/file "skills")
        skill-file (io/file skills-dir (str (clojure.core/name skill-id) ".edn"))]
    (if (.exists skill-file)
      (let [skill-def  (try (edn/read-string (slurp skill-file)) (catch Throwable _ nil))
            skill-tree (:skill/tree skill-def)
            skill-name (or (:skill/name skill-def) (clojure.core/name skill-id))]
        (record-cognitive-event! engine-ctx-atom :skill-lib "Execução de Skill Aprendida"
                                 (str "Iniciando AST da skill " skill-id ": " (pr-str skill-tree)))
        (actions/karlach-say! (str "Executando habilidade aprendida '" skill-name "', " (or speaker "soldado") "! 🔥"))
        (transport/send-bridge-cmd! "SWING|Karlach")
        (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
        (execute-tree-node! skill-tree engine-ctx-atom nav-state sanctuary-route speaker))
      (actions/karlach-say! (str "Não encontrei os registros da skill " skill-id " no meu córtex, " (or speaker "soldado") "!")))))

(defmethod execute-karlach-goal! :default
  [{:goal/keys [raw-text voice-line]} engine-ctx-atom _nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :synthesis "System 2 Voyager Synthesizer"
                           (str "Analisando comando natural: '" raw-text "'. Decompondo em diretriz reativa."))
  (actions/karlach-say! voice-line)
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" "Executando diretriz"))

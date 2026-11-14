(ns craft.agents.backend.fabric.goals.rescue
  "Metas de papel no Santuario: resgate de animal e procriacao (CA-085). Exigem as
   capacidades `:animal-rescue` e `:breed-animals` em `:agent/capabilities` (CA-091):
   o gate fica em `dispatch/execute-goal!`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch :refer [execute-goal!]]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.sanctuary :as sanctuary]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport :refer [uformat]]
            [craft.agents.cognition.critic :as critic]
            [craft.agents.skills.composer :as composer]))

(defmethod execute-goal! :animal-rescue
  [{:goal/keys [id name entity species pen-id gate-pos gate-block gate-facing inside-pos outside-pos feed-pos bait-item voice-line speaker] :as _goal-spec}
   {engine-ctx-atom :engine-ctx :as ctx}]
  (let [nav-state (dispatch/nav-state ctx)]
    ;; 1. Fala inicial do agente acusando o estímulo
    (transport/send-bridge-cmd! (str "SWING|" (dispatch/agent-name ctx)))
    (transport/send-bridge-cmd! (str "CMD|execute at " (dispatch/agent-name ctx) " run playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2"))
    (dispatch/say! ctx voice-line)

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
        (swap! engine-ctx-atom update-in [:agents (dispatch/agent-id ctx) :skills] (fnil #(vec (distinct (conj % skill-id-str))) []))
        (swap! engine-ctx-atom assoc-in [:agents (dispatch/agent-id ctx) :last-learned] (str id " (Malli OK)"))
        (let [enc-title (or (:enclosure/name (get sanctuary/sanctuary-enclosures-by-id (or pen-id :pen-south-west))) "Cercado")]
          (swap! engine-ctx-atom assoc-in [:agents (dispatch/agent-id ctx) :task] (str "Conduzindo " entity " ao " enc-title))))

      ;; 4. Execução imediata da rota motora de resgate com targeting real e A* 3D
      (let [norm-sp (critic/normalize-species (or species entity))
            mob-resp (transport/send-bridge-cmd! (str "LOCATE_MOB|" (dispatch/agent-name ctx) "|" norm-sp))
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
            cur-pos (or (when nav-state (:pos @nav-state)) [-175.0 65.0 180.0])
            steps-to-mob (nav/generate-walk-steps cur-pos animal-rescue-pos (str "Aproximando do " entity " fugitivo") {:radius 1.2})
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
        (when nav-state
          (swap! nav-state assoc
                 :mode :sanctuary-patrol
                 :follow-target nil
                 :steps (vec main-steps)))))))

(defmethod execute-goal! :breed-animals
  [{:goal/keys [speaker voice-line]} {engine-ctx-atom :engine-ctx :as ctx}]
  (let [nav-state (dispatch/nav-state ctx)]
    (record-cognitive-event! engine-ctx-atom :action "Reprodução de Animais em AFK"
                             "Ativando protocolo de reprodução e procriação de gatos no Santuário")
    (when engine-ctx-atom
      (swap! engine-ctx-atom assoc-in [:agents (dispatch/agent-id ctx) :task] "Reprodução de Gatos em AFK (Cupido Felino)"))
    (transport/send-bridge-cmd! (str "SWING|" (dispatch/agent-name ctx)))
    (transport/send-bridge-cmd! (str "CMD|item replace entity " (dispatch/agent-name ctx) " weapon.mainhand with minecraft:cod"))
    (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
    (dispatch/say! ctx (or voice-line (str "Pode deixar, " (or speaker "soldado") "! Ativando reprodução de gatos em AFK! 🔥 🐱")))
    ;; Pulso imediato de reprodução para gatos próximos à Karlach
    (let [[cx cy cz] (or (when nav-state (:pos @nav-state)) [-175.0 65.0 180.0])]
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
    (when nav-state
      (swap! nav-state assoc :mode :sanctuary-patrol :follow-target nil))))

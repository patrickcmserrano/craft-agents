(ns craft.agents.backend.fabric.goals.work
  "Metas de trabalho: mineracao, inventario, drop e patrulha do Santuario (CA-085).
   Mineracao, inventario e drop sao genericas; `:patrol-sanctuary` e meta de papel e exige
   a capacidade `:patrol-sanctuary` (CA-091)."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions :refer [ore-pt-names]]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch :refer [execute-goal!]]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport :refer [uformat]]))

(defmethod execute-goal! :mining-cooperation
  [{:goal/keys [target] target-filter :goal/filter} {engine-ctx-atom :engine-ctx :as ctx}]
  (let [nav-state (dispatch/nav-state ctx)
        nm        (dispatch/agent-name ctx)]
    (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller"
                             (str "Iniciando mineração cooperativa com " target " | Filtro: " (or target-filter "geral")))
    ;; 1. Garante sincronizacao e proximidade imediata com o jogador para mineracao cooperativa
    (let [players (actions/query-online-players)
          target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
          tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] (when nav-state (:pos @nav-state)))
          [cx cy cz] (or (when nav-state (:pos @nav-state)) [-175.0 65.0 180.0])
          dist-to-player (if target-p
                           (Math/sqrt (+ (Math/pow (- (:x target-p) cx) 2)
                                         (Math/pow (- (:y target-p) cy) 2)
                                         (Math/pow (- (:z target-p) cz) 2)))
                           0.0)]
      (when (and target-p (> dist-to-player 48.0))
        (actions/teleport-agent-to! nm [(:x target-p) (:y target-p) (:z target-p)])
        (transport/send-bridge-cmd! (str "CMD|execute at " nm " run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0"))
        (transport/send-bridge-cmd! (str "CMD|execute at " nm " run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 20 normal"))
        (when nav-state
          (swap! nav-state assoc :pos tgt-pos :follow-path [] :steps []))))

    (transport/send-bridge-cmd! (str "CMD|item replace entity " nm " weapon.mainhand with minecraft:netherite_pickaxe"))
    (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
    (transport/send-bridge-cmd! (str "SWING|" nm))

    (cond
      ;; Caso A: Minerar o bloco que o jogador está olhando (Raycast / Joint Gaze)
      (= target-filter "crosshair")
      (let [look-res (actions/raycast-player-look (or target "pace") 5.5)]
        (if (:found? look-res)
          (let [bx (:x look-res)
                by (:y look-res)
                bz (:z look-res)
                bid (:block-id look-res)
                [kx ky kz] (or (when nav-state (:pos @nav-state)) [0.0 65.0 0.0])
                odx (- (+ (double bx) 0.5) kx)
                ody (- (+ (double by) 0.5) (+ (double ky) 1.62))
                odz (- (+ (double bz) 0.5) kz)
                hdist (Math/sqrt (+ (* odx odx) (* odz odz)))
                total-d (Math/sqrt (+ (* odx odx) (* ody ody) (* odz odz)))
                yaw (nav/compute-yaw odx odz)
                pitch (nav/compute-pitch ody hdist)]
            ;; Se o agente estiver a mais de 2.2m do bloco apontado, aproxima para quebrar colado
            (when (> total-d 2.2)
              (let [approach-x (+ (double bx) (if (> kx bx) 1.2 -1.2))
                    approach-z (+ (double bz) (if (> kz bz) 1.2 -1.2))]
                (transport/send-bridge-cmd! (uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|%.1f|false|false" nm approach-x ky approach-z yaw pitch))
                (when nav-state
                  (swap! nav-state assoc :pos [approach-x ky approach-z] :yaw yaw :pitch pitch))))
            (let [[cur-x cur-y cur-z] (or (when nav-state (:pos @nav-state)) [0.0 65.0 0.0])]
              (transport/send-bridge-cmd! (uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|%.1f|false|false" nm cur-x cur-y cur-z yaw pitch)))
            (transport/send-bridge-cmd! (str "SWING|" nm))
            (let [mine-res (actions/mine-block! nm bx by bz)]
              (if (:success? mine-res)
                (do
                  (record-cognitive-event! engine-ctx-atom :action "Mineração Instantânea"
                                           (str "Bloco " bid " quebrado em [" bx " " by " " bz "]"))
                  (transport/send-bridge-cmd! (uformat "CMD|particle minecraft:block %s %.2f %.2f %.2f 0.3 0.3 0.3 0.05 12 normal" (or bid "minecraft:stone") (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5)))
                  (transport/send-bridge-cmd! (uformat "CMD|playsound minecraft:block.deepslate.break master @a %.1f %.1f %.1f 1.0 1.0" (double bx) (double by) (double bz)))
                  (dispatch/say! ctx (str "Bloco " (str/replace (or bid "alvo") #"minecraft:" "") " quebrado com sucesso na sua frente, soldado! ⛏️ 🔥")))
                (dispatch/say! ctx (str "Não consegui quebrar esse bloco: " (:message mine-res))))))
          (dispatch/say! ctx (str "Não consegui identificar nenhum bloco na sua mira direta, " target "! Chega um pouco mais perto e aponta de novo!"))))

      ;; Caso B: Ativação do Modo de Mineração Contínua Autônoma
      :else
      (let [ore-name (get ore-pt-names target-filter (if (str/blank? target-filter) "minérios" target-filter))]
        (dispatch/say! ctx (str "Picareta em punho, " target "! Modo de mineração contínua de " ore-name " ativado! Vou extrair todos os veios que encontrar por aqui! 🔥 ⛏️"))
        (when nav-state
          (swap! nav-state assoc
                 :mode :mining
                 :mining-filter (or target-filter "")
                 :mining-target (or target "pace")
                 :mining-blocks-broken 0))))

    (when engine-ctx-atom
      (swap! engine-ctx-atom assoc-in [:agents (dispatch/agent-id ctx) :task]
             (if target-filter (str "Minerando " (get ore-pt-names target-filter target-filter) " com " target) (str "Minerando com " target))))))

(defmethod execute-goal! :patrol-sanctuary
  [{:goal/keys [voice-line]} {engine-ctx-atom :engine-ctx sanctuary-route :sanctuary-route :as ctx}]
  (let [nav-state (dispatch/nav-state ctx)]
    (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" "Retomando patrulha e alimentação de animais no Santuário")
    (when engine-ctx-atom
      (swap! engine-ctx-atom assoc-in [:agents (dispatch/agent-id ctx) :task] "Patrulha no Santuário"))
    (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.0")
    (when (and nav-state (seq sanctuary-route))
      (let [initial-steps (vec (nav/expand-route-microsteps (first sanctuary-route) (or (:pos @nav-state) [0.0 65.0 0.0])))]
        (swap! nav-state assoc :mode :sanctuary-patrol :follow-target nil :route-idx 0 :steps initial-steps)))
    (dispatch/say! ctx voice-line)))

(defmethod execute-goal! :inventory-inspection
  [{:goal/keys [speaker]} {engine-ctx-atom :engine-ctx :as ctx}]
  (let [nm (dispatch/agent-name ctx)
        inv (actions/inspect-inventory nm)
        target (or speaker "pace")
        items (:items inv)]
    (record-cognitive-event! engine-ctx-atom :grounding "Inspeção de Inventário"
                             (str "Inventário de " nm " verificado: " (pr-str items)))
    (if (empty? items)
      (dispatch/say! ctx (str "Minha mochila tá vazia por enquanto, " target "! Vamos minerar ou recolher uns recursos! 🔥"))
      (let [item-list (str/join ", " (map (fn [[iid c]] (str c "x " (str/replace iid #"minecraft:" ""))) items))]
        (dispatch/say! ctx (str "Tô carregando comigo, " target ": [" item-list "]! (" (:slots-used inv) " slots ocupados). Pronta pra continuar! 🔥 🎒"))))))

(defmethod execute-goal! :drop-inventory
  [{:goal/keys [speaker] target-filter :goal/filter} {engine-ctx-atom :engine-ctx :as ctx}]
  (let [nm (dispatch/agent-name ctx)
        target (or speaker "pace")
        drop-res (actions/drop-inventory-items! nm (or target-filter "all"))
        dropped-cnt (:dropped-count drop-res 0)
        items (:items drop-res)]
    (record-cognitive-event! engine-ctx-atom :action "Ejeção de Saque / Drop de Inventário"
                             (str nm " dropou " dropped-cnt " itens para " target))
    (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.item.pickup master @a ~ ~ ~ 1.0 0.8")
    (if (> dropped-cnt 0)
      (let [item-list (str/join ", " (map (fn [[iid c]] (str c "x " (str/replace iid #"minecraft:" ""))) items))]
        (dispatch/say! ctx (str "Aqui está o saque da expedição, " target "! Ejetando [" item-list "]! Todo seu, soldado! 🔥 💎")))
      (dispatch/say! ctx (str "Não tenho nenhum recurso solto pra dropar agora, " target "! Minha mochila tá limpa.")))))

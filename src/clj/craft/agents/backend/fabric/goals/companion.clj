(ns craft.agents.backend.fabric.goals.companion
  "Metas de companhia da Karlach: teleporte, follow, bodyguard, atencao espacial e danca (CA-085)."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.goals.dispatch :refer [execute-karlach-goal!]]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport :refer [uformat]]))

(defmethod execute-karlach-goal! :teleport-to-player
  [{:goal/keys [target voice-line]} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" (str "Teleporte intencional solicitado para " target))
  (let [players (actions/query-online-players)
        target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
        tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] (:pos @nav-state))]
    (when target-p
      (actions/teleport-agent-to! "Karlach" [(:x target-p) (:y target-p) (:z target-p)])
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
      (actions/teleport-agent-to! "Karlach" [(:x target-p) (:y target-p) (:z target-p)])
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
      (actions/teleport-agent-to! "Karlach" [(:x target-p) (:y target-p) (:z target-p)])
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

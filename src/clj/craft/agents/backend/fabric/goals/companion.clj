(ns craft.agents.backend.fabric.goals.companion
  "Metas de companhia: teleporte, follow, bodyguard, atencao espacial e danca (CA-085).
   Genericas: valem para qualquer agente; o nome vem de `(agent-name ctx)` (CA-091)."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch :refer [execute-goal!]]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.body :as body]))

(defn- emergency-teleport!
  "Teleporte de emergencia do agente `nm` ate `target-p`, com som e particulas."
  [nm target-p particles]
  (actions/teleport-agent-to! nm [(:x target-p) (:y target-p) (:z target-p)])
  (transport/send-bridge-cmd! (str "CMD|execute at " nm " run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0"))
  (transport/send-bridge-cmd! (str "CMD|execute at " nm " run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 " particles " normal")))

(defmethod execute-goal! :teleport-to-player
  [{:goal/keys [target voice-line]} {engine-ctx-atom :engine-ctx :as ctx}]
  (let [nav-state (dispatch/nav-state ctx)
        nm        (dispatch/agent-name ctx)]
    (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" (str "Teleporte intencional solicitado para " target))
    (let [players (actions/query-online-players)
          target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
          tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] (when nav-state (:pos @nav-state)))]
      (when target-p
        (emergency-teleport! nm target-p 35)
        (when nav-state
          (swap! nav-state assoc
                 :mode :bodyguard
                 :pos tgt-pos
                 :follow-target target
                 :follow-path []
                 :follow-last-target [0.0 0.0 0.0]
                 :follow-lost-ticks 0
                 :steps []
                 :active-mining nil)))
      (dispatch/say! ctx voice-line))))

(defmethod execute-goal! :follow-player
  [{:goal/keys [target voice-line]} {engine-ctx-atom :engine-ctx :as ctx}]
  (let [nav-state (dispatch/nav-state ctx)
        nm        (dispatch/agent-name ctx)]
    (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" (str "Seguindo jogador " target))
    (let [players (try (actions/query-online-players) (catch Throwable _ []))
          target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
          [cx cy cz] (or (when nav-state (:pos @nav-state)) [-175.0 65.0 180.0])
          tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] [cx cy cz])
          dist-to-p (if target-p
                      (Math/sqrt (+ (Math/pow (- (:x target-p) cx) 2)
                                    (Math/pow (- (:y target-p) cy) 2)
                                    (Math/pow (- (:z target-p) cz) 2)))
                      0.0)]
      ;; Teleporte apenas de emergencia se estiver muito distante (> 48m / outro bioma)
      (when (and target-p (> dist-to-p 48.0))
        (emergency-teleport! nm target-p 20))
      (when nav-state
        (swap! nav-state assoc
               :mode :follow
               :pos (if (> dist-to-p 48.0) tgt-pos [cx cy cz])
               :follow-target target
               :follow-path (if (and target-p (<= dist-to-p 48.0))
                              (or (nav/request-path3d [cx cy cz] tgt-pos 2.2) [])
                              [])
               :follow-last-target [0.0 0.0 0.0]
               :follow-lost-ticks 0
               :steps []))
      (when voice-line
        (dispatch/say! ctx voice-line)))))

(defmethod execute-goal! :bodyguard
  [{:goal/keys [target voice-line]} {engine-ctx-atom :engine-ctx :as ctx}]
  (let [nav-state (dispatch/nav-state ctx)
        nm        (dispatch/agent-name ctx)]
    (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" (str "Modo Guarda-Costas ativado para " target))
    (let [players (actions/query-online-players)
          target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
          [cx cy cz] (or (when nav-state (:pos @nav-state)) [-175.0 65.0 180.0])
          tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] [cx cy cz])
          dist-to-p (if target-p
                      (Math/sqrt (+ (Math/pow (- (:x target-p) cx) 2)
                                    (Math/pow (- (:y target-p) cy) 2)
                                    (Math/pow (- (:z target-p) cz) 2)))
                      0.0)]
      ;; Teleporte apenas de emergencia se estiver muito distante (> 48m / outro bioma)
      (when (and target-p (> dist-to-p 48.0))
        (emergency-teleport! nm target-p 20))
      (transport/send-bridge-cmd! (str "CMD|item replace entity " nm " weapon.mainhand with minecraft:netherite_axe"))
      (transport/send-bridge-cmd! (str "CMD|item replace entity " nm " weapon.offhand with minecraft:shield"))
      (transport/send-bridge-cmd! "CMD|playsound minecraft:item.armor.equip_netherite master @a ~ ~ ~ 1.0 1.0")
      (transport/send-bridge-cmd! (str "SWING|" nm))
      (when nav-state
        (swap! nav-state assoc
               :mode :bodyguard
               :pos (if (> dist-to-p 48.0) tgt-pos [cx cy cz])
               :follow-target target
               :follow-path (if (and target-p (<= dist-to-p 48.0))
                              (or (nav/request-path3d [cx cy cz] tgt-pos 2.2) [])
                              [])
               :follow-last-target [0.0 0.0 0.0]
               :follow-lost-ticks 0
               :steps [])))
    (when engine-ctx-atom
      (swap! engine-ctx-atom assoc-in [:agents (dispatch/agent-id ctx) :task] (str "Guarda-Costas de " target)))
    (dispatch/say! ctx voice-line)))

(defmethod execute-goal! :spatial-attention
  [{:goal/keys [entity speaker]} {engine-ctx-atom :engine-ctx :as ctx}]
  (let [nav-state (dispatch/nav-state ctx)
        nm        (dispatch/agent-name ctx)
        players   (actions/query-online-players)
        target-p  (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or speaker "pace"))) players))
        [cx cy cz] (or (when nav-state (:pos @nav-state)) [-175.0 65.0 180.0])]
    (if target-p
      (let [px (:x target-p)
            py (:y target-p)
            pz (:z target-p)
            dx (- px cx)
            dz (- pz cz)
            look-yaw (nav/compute-yaw dx dz)]
        (body/move! {:agent (dispatch/agent-id ctx) :agent-name nm :x cx :y cy :z cz
                     :yaw look-yaw :pitch 0.0 :priority :player-goal})
        (when nav-state
          (swap! nav-state assoc :yaw look-yaw))
        (record-cognitive-event! engine-ctx-atom :grounding "Percepção Espacial (Joint Attention)"
                                 (str "Olhando para " speaker " em [" (int px) " " (int pz) "] identificando " entity))
        (dispatch/say! ctx (str "Tô vendo sim, " speaker "! Um " entity " bem aí perto de você, do lado de fora do muro!")))
      (dispatch/say! ctx (str "Onde, " speaker "? Não tô conseguindo te ver por aqui!")))))

(defmethod execute-goal! :social-dance
  [{:goal/keys [voice-line]} {engine-ctx-atom :engine-ctx :as ctx}]
  (let [nav-state (dispatch/nav-state ctx)]
    (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" "Executando animação de dança infernal")
    (when nav-state
      (swap! nav-state assoc :dance-ticks 80))
    (dispatch/say! ctx voice-line)))

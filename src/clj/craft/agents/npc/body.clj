(ns craft.agents.npc.body
  "Arbitro unico de atuadores corporais (CA-102, SUG-01 parte 1). Unico namespace que serializa o
   prefixo wire \"MOVE|\": subsistemas pedem intencoes por canal (`request!`) e `flush!` compoe
   UM comando por agente por tick. `move!` e a API direta para passo unico/teleporte.

   Arbitragem: por [agent channel] vence a maior prioridade; EMPATE: o primeiro pedido do tick
   permanece (determinístico). Wire: MOVE|nome|x|y|z|yaw|pitch|sprint|sneak[|jump|use|attack]."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.leases :as leases]
            [craft.agents.npc.registry :as registry]))

(def channels #{:legs :gaze :hands :pose})

(def priorities
  {:survival 100 :combat 80 :player-goal 60 :bt 40 :atelier 20 :idle 0})

(defn priority-of
  "Prioridade numerica de uma fonte keyword ou de um numero."
  [p]
  (cond (number? p) p
        :else (get priorities p 0)))

;; ---------------------------------------------------------------- puro

(defn arbitrate
  "Mantem a intencao vigente ou a nova conforme a prioridade (empate: a vigente)."
  [current incoming]
  (if (or (nil? current)
          (> (priority-of (:source incoming)) (priority-of (:source current))))
    incoming
    current))

(defn look-at-angles
  "Yaw/pitch (graus) de `from` para `to`, convencao Minecraft."
  [[fx fy fz] [tx ty tz]]
  (let [dx (- tx fx) dy (- ty fy) dz (- tz fz)
        h  (Math/sqrt (+ (* dx dx) (* dz dz)))]
    {:yaw   (Math/toDegrees (- (Math/atan2 dx dz)))
     :pitch (Math/toDegrees (- (Math/atan2 dy h)))}))

(defn compose
  "Funde as intencoes de um agente {channel intent} num estado de corpo unico, ou nil se nao ha
   posicao conhecida (pernas :target ou :pos de qualquer intencao)."
  [by-channel]
  (let [{:keys [legs gaze hands pose]} by-channel
        pos   (or (:target legs) (:pos legs) (:pos gaze) (:pos pose) (:pos hands))
        angle (when (and gaze (:look-at gaze) pos)
                (look-at-angles pos (:look-at gaze)))]
    (when pos
      {:pos    (mapv double pos)
       :yaw    (double (or (:yaw gaze) (:yaw angle) (:yaw legs) 0.0))
       :pitch  (double (or (:pitch gaze) (:pitch angle) (:pitch legs) 0.0))
       :sprint (boolean (or (:sprint pose) (:sprint legs)))
       :sneak  (boolean (or (:sneak pose) (:sneak legs)))
       :jump   (boolean (or (:jump legs) (:jump pose)))
       :use    (boolean (:use hands))
       :attack (boolean (:attack hands))})))

(defn agent-name-of
  "Nome de jogador do agente (registro) com fallback para o id capitalizado."
  [agent-id]
  (or (some-> (try (registry/agent-by-id agent-id) (catch Throwable _ nil)) :agent/name)
      (str/capitalize (name agent-id))))

(defn ->wire
  "Serializa um estado de corpo `compose`d na linha MOVE| (unico ponto do codigo base)."
  [agent-name {:keys [pos yaw pitch sprint sneak jump use attack]}]
  (let [[x y z] pos
        base (transport/uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|%.1f|%s|%s"
                                agent-name (double x) (double y) (double z)
                                (double yaw) (double pitch) (str sprint) (str sneak))]
    (if (or jump use attack)
      (str base "|" jump "|" use "|" attack)
      base)))

;; ---------------------------------------------------------------- efeitos

(defonce ^:private buffer (atom {}))
(defonce ^:private tick-metrics (atom {:cmds-per-tick 0}))

(defn reset-buffer! [] (reset! buffer {}) nil)

(defn pending
  "Intencoes enfileiradas no tick: {[agent channel] intent}."
  [] @buffer)

(defn metrics [] @tick-metrics)

(defn request!
  "Enfileira intencao {:agent :channel :source ...dados} no buffer do tick, arbitrando por prioridade."
  [{:keys [agent channel] :as intent}]
  (when-not (contains? channels channel)
    (throw (ex-info "canal corporal desconhecido" {:channel channel :supported channels})))
  (swap! buffer update [agent channel] arbitrate intent)
  nil)

(defn flush!
  "Compoe e envia no maximo 1 MOVE por agente (ordem estavel por id), limpa o buffer e atualiza
   `:cmds-per-tick`. Retorna [{:agent id :cmd linha}]."
  []
  (let [snapshot (first (reset-vals! buffer {}))
        by-agent (reduce-kv (fn [m [aid ch] intent] (assoc-in m [aid ch] intent)) {} snapshot)
        sent     (vec (for [[aid chs] (sort-by (comp str key) by-agent)
                            :let [state (compose chs)]
                            :when state
                            :let [cmd (->wire (agent-name-of aid) state)]]
                        (do (transport/send-bridge-cmd! cmd)
                            {:agent aid :cmd cmd})))]
    (swap! tick-metrics assoc :cmds-per-tick (count sent))
    sent))

(defn move!
  "API direta: {:agent :x :y :z :yaw :pitch :jump :sprint :sneak :use :attack} (+ :agent-name).
   Com :lease-mgr, adquire :legs/:gaze de :agent para :owner (:priority, :ttl-ticks) e nao envia
   se negado. Retorna {:sent? bool :cmd linha-ou-nil}."
  [{:keys [agent agent-name x y z lease-mgr owner priority ttl-ticks] :as m}]
  (let [granted? (or (nil? lease-mgr)
                     (:granted? (leases/acquire-leases!
                                 lease-mgr agent
                                 (cond-> {:channels #{:legs :gaze} :owner-id owner
                                          :priority (priority-of priority)}
                                   ttl-ticks (assoc :ttl-ticks ttl-ticks)))))]
    (if granted?
      (let [cmd (->wire (or agent-name (agent-name-of agent))
                        (compose {:legs (assoc (select-keys m [:yaw :pitch :sprint :sneak :jump])
                                               :target [x y z])
                                  :hands (select-keys m [:use :attack])}))]
        (transport/send-bridge-cmd! cmd)
        {:sent? true :cmd cmd})
      {:sent? false :cmd nil})))

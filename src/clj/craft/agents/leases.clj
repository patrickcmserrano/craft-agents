(ns craft.agents.leases
  "Arbitragem transacional de atuadores (:legs, :hands, :gaze) com preempcao por prioridade e compensators (CA-029).")

(defrecord LeaseManager [state-atom])

(defn create-lease-manager
  "Cria gerenciador de posse exclusiva dos canais :legs, :hands e :gaze (CA-029)."
  []
  (->LeaseManager (atom {:legs nil :hands nil :gaze nil})))

(defn current-holders
  "Retorna mapa imutavel {:legs holder-info, :hands holder-info, :gaze holder-info} (CA-029)."
  [lease-mgr]
  @(:state-atom lease-mgr))

(defn acquire-leases!
  "Tenta adquirir conjunto de canais {:channels #{:legs} :owner-id ... :priority N :on-preempt (fn [])} (CA-029)."
  [lease-mgr {:keys [channels owner-id priority on-preempt] :or {priority 0}}]
  (let [to-preempt (atom [])
        res (swap! (:state-atom lease-mgr)
                   (fn [current]
                     (let [can-acquire? (every? (fn [ch]
                                                  (let [holder (get current ch)]
                                                    (or (nil? holder)
                                                        (= (:owner-id holder) owner-id)
                                                        (> priority (or (:priority holder) 0)))))
                                                channels)]
                       (if can-acquire?
                         (let [preempted (keep (fn [ch]
                                                 (let [holder (get current ch)]
                                                   (when (and holder (not= (:owner-id holder) owner-id))
                                                     holder)))
                                               channels)
                               new-entry {:owner-id owner-id
                                          :priority priority
                                          :on-preempt on-preempt}]
                           (reset! to-preempt (vec preempted))
                           (reduce (fn [acc ch] (assoc acc ch new-entry)) current channels))
                         current))))]
    ;; Dispara callbacks de preempcao fora do swap!
    (let [unique-preempted (distinct @to-preempt)]
      (doseq [holder unique-preempted]
        (when-let [cb (:on-preempt holder)]
          (try (cb) (catch Throwable _ nil)))))
    (let [first-ch (first channels)]
      (if (= owner-id (get-in res [first-ch :owner-id]))
        {:granted? true}
        {:granted? false :reason :LOWER_PRIORITY}))))

(defn release-leases!
  "Libera canais detidos pelo owner-id executando cleanup pos-condicao se registrado (CA-029)."
  [lease-mgr owner-id]
  (swap! (:state-atom lease-mgr)
         (fn [current]
           (into {}
                 (map (fn [[ch holder]]
                        [ch (if (= (:owner-id holder) owner-id) nil holder)])
                      current))))
  lease-mgr)

;; ============================================================================
;; Árbitro Multiagente de Recursos Espaciais e Físicos (P3.1 - CA-046)
;; ============================================================================

(defrecord MultiagentArbiter [state-atom])

(defn create-multiagent-arbiter
  "Cria árbitro distribuído de atuadores por agente e de recursos físicos compartilhados (CA-046 / P3.1)."
  []
  (->MultiagentArbiter
   (atom {:agents {}
          :resources {}})))

(defn acquire-shared-resource!
  "Tenta adquirir posse exclusiva de um recurso espacial compartilhado (portal, baia, ferramenta).
   Suporta TTL (timeout-ms), preempção por prioridade e callback on-preempt."
  [arbiter {:keys [resource-id agent-id timeout-ms priority on-preempt]
            :or {priority 0 timeout-ms 10000}}]
  (let [now         (System/currentTimeMillis)
        expires-at  (+ now timeout-ms)
        preempted   (atom nil)
        res         (swap! (:state-atom arbiter)
                           (fn [st]
                             (let [curr-lease (get-in st [:resources resource-id])
                                   expired?   (and curr-lease (<= (:expires-at curr-lease) now))
                                   can-claim? (or (nil? curr-lease)
                                                  expired?
                                                  (= (:holder curr-lease) agent-id)
                                                  (> priority (or (:priority curr-lease) 0)))]
                               (if can-claim?
                                 (do
                                   (when (and curr-lease (not expired?) (not= (:holder curr-lease) agent-id))
                                     (reset! preempted curr-lease))
                                   (assoc-in st [:resources resource-id]
                                             {:holder agent-id
                                              :priority priority
                                              :expires-at expires-at
                                              :on-preempt on-preempt}))
                                 st))))]
    (when-let [p @preempted]
      (when-let [cb (:on-preempt p)]
        (try (cb) (catch Throwable _ nil))))
    (if (= agent-id (get-in res [:resources resource-id :holder]))
      {:granted? true :resource-id resource-id :agent-id agent-id :expires-at expires-at}
      {:granted? false :reason :RESOURCE_LOCKED :held-by (get-in res [:resources resource-id :holder])})))

(defn release-shared-resource!
  "Libera posse de um recurso físico compartilhado."
  [arbiter resource-id agent-id]
  (swap! (:state-atom arbiter)
         (fn [st]
           (if (= (get-in st [:resources resource-id :holder]) agent-id)
             (update st :resources dissoc resource-id)
             st)))
  arbiter)

(defn query-shared-resources
  "Retorna mapa de recursos ativos e não-expirados."
  ([arbiter] (query-shared-resources arbiter (System/currentTimeMillis)))
  ([arbiter now]
   (into {}
         (filter (fn [[_ lease]] (> (:expires-at lease 0) now))
                 (get @(:state-atom arbiter) :resources {})))))

(defn clean-expired-leases!
  "Remove ativamente todas as concessões cujo TTL expirou."
  ([arbiter] (clean-expired-leases! arbiter (System/currentTimeMillis)))
  ([arbiter now]
   (swap! (:state-atom arbiter)
          (fn [st]
            (update st :resources
                    (fn [res]
                      (into {} (filter (fn [[_ lease]] (> (:expires-at lease 0) now)) res))))))))

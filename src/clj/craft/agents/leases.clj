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

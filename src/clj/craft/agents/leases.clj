(ns craft.agents.leases
  "Arbitragem transacional de atuadores (:legs, :hands, :gaze, :pose) com preempcao por prioridade,
   particionamento por agent-id e expiracao por :ttl-ticks (CA-029, CA-046, CA-101).")

(def default-channels
  {:legs nil :hands nil :gaze nil})

(defrecord LeaseManager [state-atom])

(defn- state-atom-of [mgr]
  (if (instance? clojure.lang.IAtom mgr)
    mgr
    (:state-atom mgr)))

(defn create-lease-manager
  "Cria gerenciador de posse exclusiva dos canais :legs, :hands e :gaze (CA-029)."
  []
  (->LeaseManager (atom default-channels)))

(defn current-holders
  "Retorna mapa imutavel {:legs holder-info, :hands holder-info, :gaze holder-info} (CA-029, CA-101)."
  ([lease-mgr]
   (dissoc @(state-atom-of lease-mgr) :by-agent :agents :resources))
  ([lease-mgr agent-id]
   (merge default-channels (get-in @(state-atom-of lease-mgr) [:by-agent agent-id]))))

(defn- lease-expired?
  [holder current-tick]
  (or (nil? holder)
      (and (some? (:remaining-ticks holder))
           (<= (long (:remaining-ticks holder)) 0))
      (and (some? current-tick)
           (some? (:expires-at-tick holder))
           (<= (long (:expires-at-tick holder)) (long current-tick)))))

(defn- acquire-in-channel-map
  [ch-map {:keys [channels channel owner-id owner priority ttl-ticks tick on-preempt] :or {priority 0}} to-preempt]
  (let [chs          (or (not-empty (set channels))
                         (when channel #{channel})
                         #{})
        oid          (or owner-id owner)
        base-map     (merge default-channels ch-map)
        can-acquire? (every? (fn [ch]
                               (let [holder (get base-map ch)]
                                 (or (nil? holder)
                                     (lease-expired? holder tick)
                                     (= (:owner-id holder) oid)
                                     (> priority (or (:priority holder) 0)))))
                             chs)]
    (if can-acquire?
      (let [preempted (keep (fn [ch]
                              (let [holder (get base-map ch)]
                                (when (and holder
                                           (not (lease-expired? holder tick))
                                           (not= (:owner-id holder) oid))
                                  holder)))
                            chs)
            new-entry (cond-> {:owner-id   oid
                               :owner      oid
                               :priority   priority
                               :on-preempt on-preempt}
                        (some? ttl-ticks)
                        (assoc :ttl-ticks ttl-ticks
                               :remaining-ticks ttl-ticks)
                        (and (some? ttl-ticks) (some? tick))
                        (assoc :acquired-at-tick tick
                               :expires-at-tick (+ (long tick) (long ttl-ticks))))]
        (reset! to-preempt (vec preempted))
        (reduce (fn [acc ch] (assoc acc ch new-entry)) base-map chs))
      base-map)))

(defn- fire-preempt-callbacks! [preempted-holders]
  (doseq [holder (distinct preempted-holders)]
    (when-let [cb (:on-preempt holder)]
      (try (cb) (catch Throwable _ nil)))))

(defn- sync-ctx-agent-state! [mgr agent-id ch-map]
  (when (instance? clojure.lang.IAtom mgr)
    (when-let [a (get-in @mgr [:agent-states agent-id])]
      (when (instance? clojure.lang.IAtom a)
        (swap! a assoc :leases (merge default-channels ch-map))))))

(defn acquire-agent-leases!
  "Tenta adquirir conjunto de canais para `agent-id` isoladamente, com suporte a `:ttl-ticks`
   e preempcao por prioridade (CA-101)."
  ([lease-mgr {:keys [agent-id] :as opts}]
   (acquire-agent-leases! lease-mgr (or agent-id :karlach) opts))
  ([lease-mgr agent-id {:keys [channels channel owner-id owner] :as opts}]
   (let [chs        (or (not-empty (set channels))
                        (when channel #{channel})
                        #{})
         oid        (or owner-id owner)
         to-preempt (atom [])
         s-atom     (state-atom-of lease-mgr)
         res        (swap! s-atom
                           (fn [current]
                             (let [cur-agent (get-in current [:by-agent agent-id])
                                   updated   (acquire-in-channel-map cur-agent opts to-preempt)]
                               (assoc-in current [:by-agent agent-id] updated))))
         agent-map  (get-in res [:by-agent agent-id])
         first-ch   (first chs)]
     (sync-ctx-agent-state! lease-mgr agent-id agent-map)
     (fire-preempt-callbacks! @to-preempt)
     (if (= oid (get-in agent-map [first-ch :owner-id]))
       {:granted? true :agent-id agent-id :channels chs}
       {:granted? false :reason :LOWER_PRIORITY :agent-id agent-id}))))

(defn acquire-leases!
  "Tenta adquirir conjunto de canais {:channels #{:legs} :owner-id ... :priority N :on-preempt (fn []) :ttl-ticks T}
   no gerenciador global (aridade 2) ou particionado por `agent-id` (aridade 3 / `:agent-id` em opts) (CA-029, CA-101)."
  ([lease-mgr {:keys [agent-id channels channel owner-id owner] :as opts}]
   (if agent-id
     (acquire-agent-leases! lease-mgr agent-id opts)
     (let [chs        (or (not-empty (set channels))
                          (when channel #{channel})
                          #{})
           oid        (or owner-id owner)
           to-preempt (atom [])
           s-atom     (state-atom-of lease-mgr)
           res        (swap! s-atom
                             (fn [current]
                               (let [by-agent (get current :by-agent)
                                     root-ch  (dissoc current :by-agent :agents :resources)
                                     updated  (acquire-in-channel-map root-ch opts to-preempt)]
                                 (cond-> updated
                                   by-agent (assoc :by-agent by-agent)))))]
       (fire-preempt-callbacks! @to-preempt)
       (let [first-ch (first chs)]
         (if (= oid (get-in res [first-ch :owner-id]))
           {:granted? true}
           {:granted? false :reason :LOWER_PRIORITY})))))
  ([lease-mgr agent-id opts]
   (acquire-agent-leases! lease-mgr agent-id opts)))

(defn release-agent-leases!
  "Libera canais detidos por `owner-id` (ou todos os canais, na aridade 2) para `agent-id` (CA-101)."
  ([lease-mgr agent-id]
   (let [s-atom (state-atom-of lease-mgr)
         res    (swap! s-atom assoc-in [:by-agent agent-id] default-channels)]
     (sync-ctx-agent-state! lease-mgr agent-id (get-in res [:by-agent agent-id]))
     lease-mgr))
  ([lease-mgr agent-id owner-id]
   (let [s-atom (state-atom-of lease-mgr)
         res    (swap! s-atom update-in [:by-agent agent-id]
                       (fn [ch-map]
                         (into default-channels
                               (map (fn [[ch holder]]
                                      [ch (if (= (:owner-id holder) owner-id) nil holder)])
                                    (or ch-map default-channels)))))]
     (sync-ctx-agent-state! lease-mgr agent-id (get-in res [:by-agent agent-id]))
     lease-mgr)))

(defn release-leases!
  "Libera canais detidos pelo owner-id executando cleanup pos-condicao se registrado (CA-029, CA-101)."
  ([lease-mgr owner-id]
   (swap! (state-atom-of lease-mgr)
          (fn [current]
            (let [by-agent (get current :by-agent)
                  root-ch  (dissoc current :by-agent :agents :resources)
                  cleaned  (into {}
                                 (map (fn [[ch holder]]
                                        [ch (if (= (:owner-id holder) owner-id) nil holder)])
                                      root-ch))]
              (cond-> cleaned
                by-agent (assoc :by-agent by-agent)))))
   lease-mgr)
  ([lease-mgr agent-id owner-id]
   (release-agent-leases! lease-mgr agent-id owner-id)))

(defn- filter-active-channels
  [ch-map current-tick]
  (into {}
        (keep (fn [[ch holder]]
                (when (and holder (not (lease-expired? holder current-tick)))
                  [ch holder])))
        ch-map))

(defn active-agent-leases
  "Retorna os leases ativos e nao-expirados por agente (aridade 1) ou para `agent-id` (aridades 2 e 3) (CA-101)."
  ([lease-mgr]
   (let [by-agent (get @(state-atom-of lease-mgr) :by-agent {})]
     (into {}
           (map (fn [[aid ch-map]]
                  [aid (filter-active-channels ch-map nil)]))
           by-agent)))
  ([lease-mgr agent-id]
   (active-agent-leases lease-mgr agent-id nil))
  ([lease-mgr agent-id current-tick]
   (let [ch-map (get-in @(state-atom-of lease-mgr) [:by-agent agent-id] {})]
     (filter-active-channels ch-map current-tick))))

(defn- expire-channel-map
  [ch-map step-or-tick]
  (into {}
        (map (fn [[ch holder]]
               [ch (cond
                     (nil? holder)
                     nil

                     (and (some? (:acquired-at-tick holder)) (some? step-or-tick))
                     (let [rem (- (long (:expires-at-tick holder)) (long step-or-tick))]
                       (when (pos? rem)
                         (assoc holder :remaining-ticks rem)))

                     (some? (:remaining-ticks holder))
                     (let [step (long (or step-or-tick 1))
                           rem  (- (long (:remaining-ticks holder)) step)]
                       (when (pos? rem)
                         (assoc holder :remaining-ticks rem)))

                     :else
                     holder)]))
        ch-map))

(defn tick-expire-leases!
  "Avanca o relogio de ticks e libera automaticamente todos os canais cujo `:ttl-ticks` expirou (CA-101)."
  ([lease-mgr]
   (tick-expire-leases! lease-mgr 1))
  ([lease-mgr agent-id-or-ticks]
   (if (keyword? agent-id-or-ticks)
     (tick-expire-leases! lease-mgr agent-id-or-ticks 1)
     (let [s-atom (state-atom-of lease-mgr)
           res    (swap! s-atom
                         (fn [current]
                           (let [by-agent     (get current :by-agent)
                                 root-ch      (dissoc current :by-agent :agents :resources)
                                 expired-root (expire-channel-map root-ch agent-id-or-ticks)
                                 expired-ag   (when by-agent
                                                (into {}
                                                      (map (fn [[aid ch-map]]
                                                             [aid (expire-channel-map ch-map agent-id-or-ticks)]))
                                                      by-agent))]
                             (cond-> expired-root
                               expired-ag (assoc :by-agent expired-ag)))))]
       (doseq [[aid ch-map] (:by-agent res)]
         (sync-ctx-agent-state! lease-mgr aid ch-map))
       lease-mgr)))
  ([lease-mgr agent-id ticks]
   (let [s-atom (state-atom-of lease-mgr)
         res    (swap! s-atom update-in [:by-agent agent-id]
                       #(expire-channel-map (or % default-channels) ticks))]
     (sync-ctx-agent-state! lease-mgr agent-id (get-in res [:by-agent agent-id]))
     lease-mgr)))


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

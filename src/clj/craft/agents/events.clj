(ns craft.agents.events
  "Barramento pub/sub nao-bloqueante de alta vazao sobre Virtual Threads do Java 21 (CA-005)."
  (:import [java.util.concurrent Executors ExecutorService]))

(defrecord EventBus [opts subs-atom ^ExecutorService executor])

(defn create-bus
  "Cria nova instancia isolada do barramento de eventos (CA-005)."
  ([] (create-bus {}))
  ([opts]
   (->EventBus opts
               (atom {})
               (Executors/newVirtualThreadPerTaskExecutor))))

(defn subscribe!
  "Registra handler (fn [event]) para um topico especifico. Retorna token de unsubscribe (CA-005)."
  [bus topic handler-fn]
  (let [token (java.util.UUID/randomUUID)]
    (swap! (:subs-atom bus) assoc-in [topic token] handler-fn)
    {:token token :topic topic}))

(defn unsubscribe!
  "Remove subscricao ativa do barramento (CA-005)."
  [bus sub-token]
  (let [token (if (map? sub-token) (:token sub-token) sub-token)
        topic (when (map? sub-token) (:topic sub-token))]
    (if topic
      (swap! (:subs-atom bus) update topic dissoc token)
      (swap! (:subs-atom bus) (fn [subs]
                                (into {} (map (fn [[k v]] [k (dissoc v token)]) subs))))))
  bus)

(defn publish!
  "Publica evento de forma nao-bloqueante (nunca trava a ServerThread) (CA-005)."
  [bus event-map]
  (let [topic (:topic event-map)]
    (when-let [handlers (seq (vals (get @(:subs-atom bus) topic)))]
      (let [^ExecutorService exec (:executor bus)]
        (when (and exec (not (.isShutdown exec)))
          (.submit exec ^Runnable
                   (fn []
                     (doseq [h handlers]
                       (try
                         (h event-map)
                         (catch Throwable _ nil)))))))))
  bus)

(defn shutdown-bus!
  "Encerra canais e workers do barramento limpamente (CA-005)."
  [bus]
  (when-let [^ExecutorService exec (:executor bus)]
    (when-not (.isShutdown exec)
      (.shutdown exec)))
  bus)

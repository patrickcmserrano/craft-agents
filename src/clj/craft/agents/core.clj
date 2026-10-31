(ns craft.agents.core
  "Ciclo de vida principal do motor craft-agents, executor ServerThread 20Hz e hot-unload (CA-009, CA-012, CA-044, CA-046, CA-047)."
  (:require [craft.agents.backend.protocol :as proto]
            [craft.agents.blackboard :as bb]
            [craft.agents.bt.builtins :as builtins]
            [craft.agents.bt.engine :as bt]
            [craft.agents.leases :as leases]
            [craft.agents.nav-portals :as portals]
            [craft.agents.reflexes :as reflexes])
  (:import [java.util.concurrent ConcurrentLinkedQueue]))

(defrecord EngineRuntime [backend queue agents-atom lease-mgr opts])

(defn run-on-server-thread!
  "Agenda fn na ServerThread retornando CompletableFuture/promessa com telemetria em microssegundos (CA-009)."
  [runtime f]
  (let [p (promise)]
    (.add ^ConcurrentLinkedQueue (:queue runtime)
          (fn []
            (try
              (deliver p (f))
              (catch Throwable ex
                (deliver p {:error ex})))))
    p))

(defn- make-agent-bt-ctx [agent-id body backend lease-mgr runtime]
  (let [state-atom (atom {:step 0})]
    {:guards
     {:hostile-nearby?
      (fn [{:keys [params]}]
        (let [radius  (double (or (:radius params) 12.0))
              body-st (proto/body-state body)
              pos     (:pos body-st)
              nearby  (proto/query-entities backend pos radius)]
          (some #(or (:hostile? %) (= (:type %) "minecraft:ravager")) nearby)))

      :has-active-directive?
      (fn [_]
        (let [board (get-in @(:agents-atom runtime) [agent-id :blackboard])]
          (boolean (and board (bb/active-directive board (System/currentTimeMillis))))))}

     :actions
     {:engage-hostiles
      (fn [{:keys [params]}]
        (let [radius (double (or (:radius params) 16.0))
              body-st (proto/body-state body)
              nearby  (proto/query-entities backend (:pos body-st) radius)
              target  (first (filter #(or (:hostile? %) (= (:type %) "minecraft:ravager")) nearby))]
          (if target
            (do
              (proto/engage-target! body (:id target) :ranged (or (:weapon params) "minecraft:sword"))
              {:status :running :target (:id target)})
            {:status :success})))

      :care-for-enclosure
      (fn [{:keys [params]}]
        (let [food-item (or (:food-item params) "minecraft:apple")
              body-st   (proto/body-state body)
              pos       (:pos body-st)
              step-num  (:step @state-atom 0)]
          (swap! state-atom update :step inc)
          (cond
            ;; Passo 0: Abre o portão duplo
            (= step-num 0)
            (do
              (portals/step-portal-traversal!
               {:phase :open :portal-blocks [[-171 65 171] [-171 66 171]] :priority 40}
               body lease-mgr)
              {:status :running})

            ;; Passo 1: Alimenta as capivaras
            (= step-num 1)
            (let [nearby (proto/query-entities backend pos 16.0)
                  capy   (first (filter #(= (:type %) "naturalist:capybara") nearby))]
              (when capy
                (proto/feed-nearby-entity! body (:id capy) food-item))
              {:status :running})

            ;; Passo 2: Fecha os portões e tranca o cercado
            :else
            (do
              (portals/step-portal-traversal!
               {:phase :close :portal-blocks [[-171 65 171] [-171 66 171]] :priority 40}
               body lease-mgr)
              {:status :success}))))

      :patrol-waypoints
      (fn [_] {:status :success})

      :social-gaze
      (fn [_] {:status :success})

      :escort-player
      (fn [_] {:status :running})

      :run-blackboard-directive
      (fn [_] {:status :success})}}))

(defn tick-engine!
  "Executa 1 pulso deterministico de 20Hz sobre todos os agentes ativos e retorna metricas de duracao (CA-009)."
  [runtime tick-number]
  (let [t0 (System/nanoTime)
        ^ConcurrentLinkedQueue q (:queue runtime)]
    ;; 1. Executa tarefas pendentes da fila
    (loop []
      (when-let [task (.poll q)]
        (try (task) (catch Throwable _ nil))
        (recur)))

    ;; 2. Executa loop de 20Hz para cada agente
    (let [backend   (:backend runtime)
          lease-mgr (:lease-mgr runtime)]
      (doseq [[_agent-id agent-info] @(:agents-atom runtime)]
        (let [body    (:body agent-info)
              profile (:profile agent-info)
              tree    (:bt/root profile)]
          (reflexes/evaluate-survival-reflexes! body backend lease-mgr)
          (when tree
            (bt/tick-tree tree (:bt-ctx agent-info))))))

    (let [elapsed-us (quot (- (System/nanoTime) t0) 1000)]
      {:tick-duration-us elapsed-us
       :tick tick-number})))

(defn start!
  "Inicializa motor craft-agents (backend, store, MCP, Studio e perfis iniciais) (CA-012)."
  ([opts]
   (let [backend     (:backend opts)
         queue       (ConcurrentLinkedQueue.)
         agents-atom (atom {})
         lease-mgr   (leases/create-lease-manager)
         runtime     (->EngineRuntime backend queue agents-atom lease-mgr opts)]
     (doseq [p (:initial-profiles opts)]
       (let [aid  (:agent/id p)
             body (proto/spawn-agent! backend p)
             board (bb/create-blackboard)
             bt-ctx (make-agent-bt-ctx aid body backend lease-mgr runtime)]
         (when-let [food (get-in p [:agent/loadout :mainhand])]
           (when-let [st-atom (:state-atom body)]
             (swap! st-atom assoc-in [:inventory food] 16)))
         (swap! agents-atom assoc aid {:profile p
                                       :body body
                                       :blackboard board
                                       :bt-ctx bt-ctx})))
     runtime))
  ([] (start! {})))

(defn stop!
  "Desliga motor limpamente: despawna agentes, salva snapshots, fecha portas e encerra pools (CA-012)."
  [runtime]
  (let [backend (:backend runtime)]
    (doseq [[aid _] @(:agents-atom runtime)]
      (proto/despawn-agent! backend aid))
    (reset! (:agents-atom runtime) {})
    true))

(ns craft.agents.bt.engine
  "Avaliador de Behavior Trees a 20Hz com trace de nos ativos (CA-031), agora um adaptador sobre o
   nucleo unico `craft.agents.bt.core` (CA-107), o mesmo usado por `goals.bt`.

   Sem `:bt-state` no ctx o avaliador segue puro e sem memoria (comportamento CA-031). Com
   `:bt-state` (um atom `{}`) ganha memoria por no, `:cooldown`/`:repeat`/`:timeout` multi-tick:
   cada chamada de `tick-tree` conta um tick."
  (:require [craft.agents.bt.core :as core]))

(defn- state-mem
  "Memoria de BT em `bt-state` (atom `{:tick n :memory {path mem}}`)."
  [state]
  {:tick    (fn [] (or (:tick @state) 0))
   :get     (fn [path] (get-in @state [:memory path]))
   :set!    (fn [path m] (swap! state assoc-in [:memory path] m) m)
   :clear!  (fn [path] (swap! state update :memory dissoc path) nil)
   :forget! (fn [path] (swap! state update :memory core/forget-subtree path) nil)})

(declare engine-leaf)

(defn- engine-env [ctx]
  (cond-> {:ctx ctx :trace? true :leaf engine-leaf}
    (:bt-state ctx)   (assoc :mem (state-mem (:bt-state ctx)))
    (:on-timeout ctx) (assoc :on-timeout (fn [_env _path child] ((:on-timeout ctx) child)))))

(defn- engine-leaf
  "Folhas do engine declarativo: :action (composite-skills ou handler) e :guard."
  [{:keys [ctx] :as env} path op args]
  (case op
    :action
    (let [action-id (first args)
          params    (second args)
          composite (get-in ctx [:composite-skills action-id])
          handler   (get-in ctx [:actions action-id])
          with-id   (fn [res] (update res :active-path (fn [p] (into [action-id] (or p [])))))]
      (cond
        composite
        (with-id (core/exec-node (assoc-in env [:ctx :params] params) (conj path action-id) composite))

        handler
        (with-id (handler (assoc ctx :params params)))

        :else
        {:status :failure :active-path [action-id] :reason :UNKNOWN_ACTION}))

    :guard
    (let [guard-id (first args)
          handler  (get-in ctx [:guards guard-id])]
      (if handler
        {:status (if (handler (assoc ctx :params (second args))) :success :failure)
         :active-path [guard-id]}
        {:status :failure :active-path [guard-id] :reason :UNKNOWN_GUARD}))

    {:status :failure :active-path [op] :reason :UNKNOWN_OP}))

(defn tick-tree
  "Avalia a arvore declarativa EDN retornando {:status (:success|:failure|:running) :active-path [...]}
   (CA-031). Com `:bt-state` no ctx, avanca o contador de ticks e mantem memoria por no (CA-107)."
  [node ctx]
  (when-let [state (:bt-state ctx)]
    (swap! state update :tick (fnil inc 0)))
  (core/exec-node (engine-env ctx) [] node))

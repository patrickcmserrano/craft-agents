(ns craft.agents.backend.fabric.goals.bt
  "Runtime de Behavior Tree (CA-048, CA-085, CA-091, CA-106, CA-107). O interpretador e o nucleo
   unico `craft.agents.bt.core` (o mesmo de `bt.engine`); aqui ficam as folhas live, a memoria por
   agente e o :timeout com liberacao de leases. Interpretador com estado :running, memoria por no em `[:agent-states id :bt-memory]`
   (chave: caminho do no) e :cooldown/:repeat multi-tick por `[agent-id node-path]`.
   As acoes que viram metas chamam o multimethod `execute-goal!` de goals.dispatch com o
   agente da arvore (6o argumento de `execute-tree-node!`; sem ele, a Karlach); os defmethod
   sao registrados pela fachada goals.clj."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [craft.agents.backend.fabric.goals.bt-actions :as bt-actions]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch]
            [craft.agents.bt.core :as core]
            [craft.agents.npc.body :as body]
            [craft.agents.npc.state :as npc-state]))

;; Guards fail-closed (eval-guard) e o registro unico de acoes (CA-094, CA-108) vivem em
;; goals.bt-actions; reexportados aqui. Nao ha `case` de acoes neste arquivo.
(def bt-action bt-actions/bt-action)
(def eval-guard bt-actions/eval-guard)
(def guard-holds? bt-actions/guard-holds?)
(def register-action! bt-actions/register-action!)
(def unregister-action! bt-actions/unregister-action!)
(def registered-actions bt-actions/registered-actions)
(def known-action-ids bt-actions/known-action-ids)
(def ^:private stamp-cooldowns! bt-actions/stamp-cooldowns!)

(def ^:private eval-condition bt-actions/eval-condition)

(defn- execute-action-node
  "Executa um nó :action: macro (arquivo skills/ ou :composite-skills) ou, senao, `bt-action`,
   que resolve pelo registro unico de acoes (goals.bt-actions) e pelas acoes modulares."
  [action-id action-args engine-ctx-atom nav-state sanctuary-route speaker agent run-macro]
  (let [macro-file (io/file (io/file "skills") (str (clojure.core/name action-id) ".edn"))
        macro-def  (when (.exists macro-file)
                     (try (edn/read-string (slurp macro-file)) (catch Throwable _ nil)))
        macro-tree (or (:skill/tree macro-def)
                       (get-in @engine-ctx-atom [:composite-skills action-id]))
        ctx        {:agent agent :state nav-state :engine-ctx engine-ctx-atom
                    :sanctuary-route sanctuary-route}]
    (if macro-tree
      (run-macro macro-tree)
      (try
        (bt-action action-id action-args (assoc ctx :speaker speaker))
        (catch Throwable _
          {:status :failure :action action-id})))))

(defn- mem-id
  "Id sob o qual a memoria de BT do agente fica em `[:agent-states id :bt-memory]`."
  [agent]
  (or (:agent/id agent) :anonymous))

(defn- agent-mem
  "Memoria de BT do agente (CA-106) no formato `:mem` do nucleo `bt.core`."
  [engine-ctx-atom agent]
  (let [id (mem-id agent)]
    {:tick    (fn [] (npc-state/bt-tick engine-ctx-atom id))
     :get     (fn [path] (npc-state/bt-node-memory engine-ctx-atom id path))
     :set!    (fn [path m] (npc-state/set-bt-node-memory! engine-ctx-atom id path m))
     :clear!  (fn [path] (npc-state/clear-bt-node-memory! engine-ctx-atom id path))
     :forget! (fn [path] (npc-state/forget-bt-subtree! engine-ctx-atom id path))}))

(defn- abort-timed-out!
  "Timeout de BT (CA-107): libera os pedidos pendentes do :bt e os leases do agente cujos donos sao
   o proprio :bt ou as acoes da subarvore abortada (`:lease-mgr` do engine-ctx)."
  [{:keys [engine-ctx-atom agent]} _path child]
  (body/release-owners! (:lease-mgr (some-> engine-ctx-atom deref))
                        (mem-id agent)
                        (into #{:bt} (core/subtree-action-ids child))))

(defn- goals-leaf
  "Folhas do runtime live: guards/conditions e acoes (macros, metas, sons, acoes modulares)."
  [{:keys [engine-ctx-atom nav-state sanctuary-route speaker agent] :as env} path op args]
  (case op
    (:guard :condition)
    (if (eval-condition (first args) args engine-ctx-atom nav-state (:agent/id agent))
      {:status :success}
      {:status :failure})

    :action
    (execute-action-node (first args) (second args) engine-ctx-atom nav-state sanctuary-route speaker agent
                         (fn [macro-tree] (core/exec-node env (conj path (first args)) macro-tree)))

    {:status :success}))

(defn execute-tree-node!
  "Executa a raiz de uma Behavior Tree como um tick do agente (CA-048, CA-106, CA-107) sobre o
   nucleo unico `bt.core`: :sequence, :selector/:fallback (retomam o filho :running), :parallel,
   :inverter, :cooldown, :repeat e :timeout (cadencia/relogio multi-tick por `[agent-id node-path]`),
   guards/conditions e macros com isolamento de leases. Status: :success | :failure | :running.
   Cada chamada conta um tick de BT do agente."
  ([node engine-ctx-atom nav-state sanctuary-route speaker]
   (execute-tree-node! node engine-ctx-atom nav-state sanctuary-route speaker (dispatch/karlach-agent)))
  ([node engine-ctx-atom nav-state sanctuary-route speaker agent]
   (npc-state/advance-bt-tick! engine-ctx-atom (mem-id agent))
   (core/exec-node {:engine-ctx-atom engine-ctx-atom :nav-state nav-state :sanctuary-route sanctuary-route
                    :speaker speaker :agent agent
                    :mem (agent-mem engine-ctx-atom agent)
                    :leaf goals-leaf
                    :on-sequence-done (fn [kids] (stamp-cooldowns! engine-ctx-atom kids))
                    :on-timeout abort-timed-out!}
                   [] node)))

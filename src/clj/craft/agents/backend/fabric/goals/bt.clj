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
            [craft.agents.backend.fabric.engine.combat :as combat]
            [craft.agents.backend.fabric.goals.bt-actions :as bt-actions]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch :refer [execute-goal!]]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.bt.core :as core]
            [craft.agents.npc.body :as body]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.state :as npc-state]))

;; Guards puros e registro de acoes (CA-094) vivem em goals.bt-actions; reexportados aqui.
(def bt-action bt-actions/bt-action)
(def guard-holds? bt-actions/guard-holds?)
(def ^:private stamp-cooldowns! bt-actions/stamp-cooldowns!)

(def ^:private eval-condition bt-actions/eval-condition)

(defn- goal-action
  "Acao de BT que delega a uma meta do multimethod. Devolve {:status :success :action-id result-id}."
  [goal-type result-id extra ctx speaker]
  (execute-goal! (merge {:goal/type goal-type :goal/speaker speaker} extra) ctx)
  {:status :success :action-id result-id})

(defn- evade-towards-ally!
  "Acao `:evade-towards-ally` (CA-093): decide por `combat-decision` (politica :flee) e, se ha
   fuga, delega a meta :follow-player rumo ao aliado mais proximo (`goal!`). Aliados: `:ally-ids`
   da acao ou o `:flee-target` dos traits; posicoes vem de `[:agents id :pos]` do engine-ctx.
   Sem hostil ou sem posicao de aliado conhecida devolve :failure (o selector segue adiante)."
  [action-args engine-ctx-atom nav-state agent goal!]
  (let [ctx       @engine-ctx-atom
        allies    (into {} (keep (fn [[id a]] (when-let [p (:pos a)] [id (vec p)]))) (:agents ctx))
        decision  (combat/combat-decision
                   (assoc agent :agent/combat-policy :flee)
                   {:bot-pos  (:pos @nav-state)
                    :hostiles (get-in ctx [:perception :hostiles] [])
                    :allies   allies
                    :ally-ids (:ally-ids action-args)})
        ally-name (some-> (:ally-id decision) registry/agent-by-id :agent/name)]
    (if (and (= :evade-towards-ally (:action decision)) ally-name)
      (goal! :follow-player :evade-towards-ally {:goal/target ally-name})
      {:status :failure :action :evade-towards-ally})))

(defn- execute-action-node
  "Executa um nó :action: macro (arquivo skills/ ou :composite-skills), meta embutida,
   som/fala ou acao modular de skills.actions."
  [action-id action-args engine-ctx-atom nav-state sanctuary-route speaker agent run-macro]
  (let [macro-file (io/file (io/file "skills") (str (clojure.core/name action-id) ".edn"))
        macro-def  (when (.exists macro-file)
                     (try (edn/read-string (slurp macro-file)) (catch Throwable _ nil)))
        macro-tree (or (:skill/tree macro-def)
                       (get-in @engine-ctx-atom [:composite-skills action-id]))
        ctx        {:agent agent :state nav-state :engine-ctx engine-ctx-atom
                    :sanctuary-route sanctuary-route}
        goal!      (fn [goal-type result-id extra]
                     (goal-action goal-type result-id extra ctx speaker))]
    (if macro-tree
      (run-macro macro-tree)
      (try
        (case action-id
          :social-dance        (do (execute-goal! {:goal/type :social-dance :goal/voice-line "Olha o passinho de vitória! 🔥"}
                                                  ctx)
                                   {:status :success :action-id :social-dance})
          :sit-furniture       (goal! :sit-furniture :sit-furniture {})
          :stand-up            (goal! :stand-up :stand-up {})
          :patrol-sanctuary    (goal! :patrol-sanctuary :patrol-sanctuary {})
          :breed-animals       (goal! :breed-animals :breed-animals {})
          :follow-player       (goal! :follow-player :follow-player {})
          :walk-towards-leader (goal! :follow-player :walk-towards-leader {})
          :bodyguard           (goal! :bodyguard :bodyguard {})
          :mining-cooperation  (goal! :mining-cooperation :mining-cooperation {:goal/filter (:target action-args "diamond")})
          :drop-inventory      (goal! :drop-inventory :drop-inventory {})
          :teleport-to-player  (goal! :teleport-to-player :teleport-to-player {})
          :move-to-quarters    (goal! :weather-shelter :move-to-quarters {})
          :evade-towards-ally  (evade-towards-ally! action-args engine-ctx-atom nav-state agent goal!)

          :say-message
          (do (dispatch/say! ctx (or (:text action-args) "Comando concluído com sucesso, soldado!"))
              {:status :success :action-id :say-message})

          :play-sound
          (do (transport/send-bridge-cmd! (str "CMD|playsound " (:sound action-args "minecraft:entity.experience_orb.pickup") " master @a ~ ~ ~ 1.0 1.0"))
              {:status :success :action-id :play-sound})

          (bt-action action-id action-args (assoc ctx :speaker speaker)))
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

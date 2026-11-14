(ns craft.agents.backend.fabric.goals.bt
  "Interpretador de Behavior Tree (CA-048, CA-085, CA-091, CA-106).
   Um unico interpretador com estado :running, memoria por no em `[:agent-states id :bt-memory]`
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

(defn- node-mem [{:keys [engine-ctx-atom agent]} path]
  (npc-state/bt-node-memory engine-ctx-atom (mem-id agent) path))

(defn- set-node-mem! [{:keys [engine-ctx-atom agent]} path mem]
  (npc-state/set-bt-node-memory! engine-ctx-atom (mem-id agent) path mem))

(defn- clear-node-mem! [{:keys [engine-ctx-atom agent]} path]
  (npc-state/clear-bt-node-memory! engine-ctx-atom (mem-id agent) path))

(defn- forget-subtree! [{:keys [engine-ctx-atom agent]} path]
  (npc-state/forget-bt-subtree! engine-ctx-atom (mem-id agent) path))

(defn- decorator-args
  "`[opts child]` de um decorador: `opts` (mapa) e opcional; o filho e o ultimo argumento."
  [args]
  (if (map? (first args))
    [(first args) (second args)]
    [{} (first args)]))

(declare exec-node)

(defn- exec-sequence
  "`:sequence` com memoria: um filho :running guarda `{:idx i}` e o proximo tick retoma dele,
   sem re-executar os anteriores; so conclui (e limpa a memoria) com :success de todos ou falha."
  [env path children]
  (let [kids (vec children)]
    (loop [i        (or (:idx (node-mem env path)) 0)
           last-res {:status :success}]
      (if (>= i (count kids))
        (do (clear-node-mem! env path)
            (stamp-cooldowns! (:engine-ctx-atom env) kids)
            last-res)
        (let [res (exec-node env (conj path i) (kids i))]
          (case (:status res)
            :success (recur (inc i) (if (:action-id res) res last-res))
            :running (do (set-node-mem! env path {:idx i}) res)
            (do (clear-node-mem! env path) res)))))))

(defn- exec-selector
  "`:selector`/`:fallback` com memoria: um filho :running guarda `{:idx i}` e o proximo tick
   retoma dele, sem reavaliar os irmaos anteriores (que ja falharam)."
  [env path children]
  (let [kids (vec children)]
    (loop [i (or (:idx (node-mem env path)) 0)]
      (if (>= i (count kids))
        (do (clear-node-mem! env path) {:status :failure})
        (let [res (exec-node env (conj path i) (kids i))]
          (case (:status res)
            :success (do (clear-node-mem! env path) res)
            :running (do (set-node-mem! env path {:idx i}) res)
            (recur (inc i))))))))

(defn- exec-parallel
  "`:parallel`: falha se algum filho falha; :running enquanto algum estiver :running (filhos ja
   concluidos em :success ficam em `{:done #{idx}}` e nao reexecutam); senao :success."
  [env path children]
  (let [kids     (vec children)
        done     (or (:done (node-mem env path)) #{})
        results  (into {} (keep (fn [i] (when-not (contains? done i)
                                          [i (exec-node env (conj path i) (kids i))])))
                       (range (count kids)))
        statuses (map :status (vals results))]
    (cond
      (some #(= :failure %) statuses)
      (do (clear-node-mem! env path) (forget-subtree! env path) {:status :failure})

      (some #(= :running %) statuses)
      (do (set-node-mem! env path {:done (into done (keep (fn [[i r]] (when (= :success (:status r)) i))) results)})
          {:status :running})

      :else
      (do (clear-node-mem! env path) {:status :success}))))

(defn- exec-cooldown
  "`[:cooldown {:ticks N} child]`: depois que o filho conclui (:success ou :failure) ele fica
   bloqueado nos N ticks de BT seguintes, por `[agent-id node-path]`; bloqueado devolve
   {:status :failure :reason :cooldown}. Enquanto o filho estiver :running nada e armado."
  [env path args]
  (let [[{:keys [ticks] :or {ticks 0}} child] (decorator-args args)
        now   (npc-state/bt-tick (:engine-ctx-atom env) (mem-id (:agent env)))
        until (:until (node-mem env path))]
    (if (and until (< now until))
      {:status :failure :reason :cooldown}
      (let [res (exec-node env (conj path 0) child)]
        (when (not= :running (:status res))
          (if (pos? ticks)
            (set-node-mem! env path {:until (+ now (long ticks) 1)})
            (clear-node-mem! env path)))
        res))))

(defn- exec-repeat
  "`[:repeat {:times K} child]`: uma repeticao por tick; devolve :running ate o filho completar
   K :success e entao :success. Contrato: :failure do filho encerra o :repeat em :failure e
   zera a contagem (`{:count c}` na memoria do no)."
  [env path args]
  (let [[{:keys [times] :or {times 1}} child] (decorator-args args)
        c (or (:count (node-mem env path)) 0)]
    (if (<= times 0)
      {:status :success}
      (let [res (exec-node env (conj path 0) child)]
        (case (:status res)
          :running res
          :success (let [c' (inc c)]
                     (if (>= c' times)
                       (do (clear-node-mem! env path) {:status :success})
                       (do (set-node-mem! env path {:count c'}) {:status :running})))
          (do (clear-node-mem! env path) res))))))

(defn- exec-node
  "Interpretador unico (CA-106). `path` (vetor de indices de filhos desde a raiz) e, junto do id do
   agente, a chave da memoria de BT em `[:agent-states id :bt-memory]`."
  [{:keys [engine-ctx-atom nav-state sanctuary-route speaker agent] :as env} path node]
  (if-not (vector? node)
    {:status :failure :reason :INVALID_NODE}
    (let [op   (first node)
          args (rest node)]
      (case op
        :sequence             (exec-sequence env path args)
        (:fallback :selector) (exec-selector env path args)
        :parallel             (exec-parallel env path args)
        :cooldown             (exec-cooldown env path args)
        :repeat               (exec-repeat env path args)

        :inverter
        (let [child-res (exec-node env (conj path 0) (first args))]
          (case (:status child-res)
            :success {:status :failure}
            :failure {:status :success}
            child-res))

        (:guard :condition)
        (if (eval-condition (first args) args engine-ctx-atom nav-state (:agent/id agent))
          {:status :success}
          {:status :failure})

        :action
        (execute-action-node (first args) (second args) engine-ctx-atom nav-state sanctuary-route speaker agent
                             (fn [macro-tree] (exec-node env (conj path (first args)) macro-tree)))

        {:status :success}))))

(defn execute-tree-node!
  "Executa a raiz de uma Behavior Tree como um tick do agente (CA-048, CA-106): :sequence,
   :selector/:fallback (retomam o filho :running), :parallel, :inverter, :cooldown e :repeat
   (cadencia real multi-tick por `[agent-id node-path]`), guards/conditions e macros com isolamento
   de leases. Status: :success | :failure | :running. Cada chamada conta um tick de BT do agente."
  ([node engine-ctx-atom nav-state sanctuary-route speaker]
   (execute-tree-node! node engine-ctx-atom nav-state sanctuary-route speaker (dispatch/karlach-agent)))
  ([node engine-ctx-atom nav-state sanctuary-route speaker agent]
   (npc-state/advance-bt-tick! engine-ctx-atom (mem-id agent))
   (exec-node {:engine-ctx-atom engine-ctx-atom :nav-state nav-state :sanctuary-route sanctuary-route
               :speaker speaker :agent agent}
              [] node)))

(ns craft.agents.backend.fabric.goals.bt
  "Interpretador de Behavior Tree (CA-048, CA-085, CA-091).
   As acoes que viram metas chamam o multimethod `execute-goal!` de goals.dispatch com o
   agente da arvore (6o argumento de `execute-tree-node!`; sem ele, a Karlach); os defmethod
   sao registrados pela fachada goals.clj."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [craft.agents.backend.fabric.engine.combat :as combat]
            [craft.agents.backend.fabric.goals.bt-actions :as bt-actions]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch :refer [execute-goal!]]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.registry :as registry]))

(declare execute-tree-node!)

;; Guards puros e registro de acoes (CA-094) vivem em goals.bt-actions; reexportados aqui.
(def bt-action bt-actions/bt-action)
(def guard-holds? bt-actions/guard-holds?)
(def ^:private guard-world bt-actions/guard-world)
(def ^:private stamp-cooldowns! bt-actions/stamp-cooldowns!)

(defn- eval-condition
  "Avalia a condicao `cond-id` de um nó :guard/:condition contra engine-ctx e nav-state."
  [cond-id args engine-ctx-atom nav-state agent-id]
  (case cond-id
    (:leader-in-vehicle? :leader-in-boat?)
    (boolean (or (get-in @engine-ctx-atom [:perception :leader :in-vehicle?])
                 (get-in @engine-ctx-atom [:leader :in-vehicle?])
                 (:leader-in-vehicle? @nav-state)))

    (:self-in-vehicle? :self-in-boat?)
    (boolean (or (:in-vehicle? @nav-state)
                 (get-in @engine-ctx-atom [:perception :self :in-vehicle?])
                 (get-in @engine-ctx-atom [:agents agent-id :in-vehicle?])))

    :not-self-in-vehicle?
    (not (or (:in-vehicle? @nav-state)
             (get-in @engine-ctx-atom [:perception :self :in-vehicle?])
             (get-in @engine-ctx-atom [:agents agent-id :in-vehicle?])))

    :not-leader-in-vehicle?
    (not (or (get-in @engine-ctx-atom [:perception :leader :in-vehicle?])
             (get-in @engine-ctx-atom [:leader :in-vehicle?])
             (:leader-in-vehicle? @nav-state)))

    :self-in-same-vehicle?
    (let [self-in   (boolean (or (:in-vehicle? @nav-state)
                                 (get-in @engine-ctx-atom [:perception :self :in-vehicle?])))
          leader-in (boolean (or (get-in @engine-ctx-atom [:perception :leader :in-vehicle?])
                                 (:leader-in-vehicle? @nav-state)))
          self-vid  (or (:vehicle-id @nav-state)
                        (get-in @engine-ctx-atom [:perception :self :vehicle-id]))
          lead-vid  (or (get-in @engine-ctx-atom [:perception :leader :vehicle :id])
                        (get-in @engine-ctx-atom [:perception :leader :vehicle-id]))]
      (and self-in leader-in (or (= self-vid lead-vid) (and (nil? self-vid) (nil? lead-vid)))))

    :leader-vehicle-has-seat?
    (let [free-seats (get-in @engine-ctx-atom [:perception :leader :vehicle :free-seats]
                             (get-in @engine-ctx-atom [:leader :vehicle :free-seats] 1))]
      (pos? free-seats))

    :within-mount-range?
    (let [dist (or (get-in @engine-ctx-atom [:perception :leader :distance])
                   (:leader-distance @nav-state)
                   2.0)]
      (<= dist 3.5))

    :has-active-directive?
    (boolean (or (get-in @engine-ctx-atom [:active-directive])
                 (get-in @engine-ctx-atom [:directive])))

    :hostile-nearby?
    (let [radius (or (:radius (second args)) 10.0)]
      (boolean (some #(<= (:distance %) radius) (get-in @engine-ctx-atom [:perception :hostiles] []))))

    (:ally-within-range? :interesting-block-nearby? :cooldown-ready?)
    (guard-holds? cond-id (second args) (guard-world engine-ctx-atom nav-state agent-id))

    :ally-distance-exceeds?
    (let [min-dist (or (:min-dist (second args)) 8.0)
          cur-dist (or (get-in @engine-ctx-atom [:perception :ally :distance]) 2.5)]
      (> cur-dist min-dist))

    :in-quiet-corner? true

    true))

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
  [action-id action-args engine-ctx-atom nav-state sanctuary-route speaker agent]
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
      (execute-tree-node! macro-tree engine-ctx-atom nav-state sanctuary-route speaker agent)
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

(defn execute-tree-node!
  "Executa nó da Behavior Tree preservando semântica de controle (:fallback, :sequence, :parallel, :inverter),
   avaliação de condições (:guard, :condition) e expandindo macros com isolamento de leases (CA-048)."
  ([node engine-ctx-atom nav-state sanctuary-route speaker]
   (execute-tree-node! node engine-ctx-atom nav-state sanctuary-route speaker (dispatch/karlach-agent)))
  ([node engine-ctx-atom nav-state sanctuary-route speaker agent]
  (if-not (vector? node)
    {:status :failure :reason :INVALID_NODE}
    (let [op   (first node)
          args (rest node)
          run  (fn [child] (execute-tree-node! child engine-ctx-atom nav-state sanctuary-route speaker agent))]
      (case op
        :sequence
        (loop [children args
               last-res {:status :success}]
          (if (empty? children)
            (do (stamp-cooldowns! engine-ctx-atom args) last-res)
            (let [res (run (first children))]
              (if (= :success (:status res))
                (recur (rest children) (if (:action-id res) res last-res))
                res))))

        (:fallback :selector)
        (loop [children args]
          (if (empty? children)
            {:status :failure}
            (let [res (run (first children))]
              (if (= :success (:status res))
                res
                (recur (rest children))))))

        :parallel
        (let [results (mapv run args)]
          (if (some #(= :failure (:status %)) results)
            {:status :failure}
            {:status :success}))

        :inverter
        (let [child-res (run (first args))]
          (case (:status child-res)
            :success {:status :failure}
            :failure {:status :success}
            child-res))

        (:guard :condition)
        (if (eval-condition (first args) args engine-ctx-atom nav-state (:agent/id agent))
          {:status :success}
          {:status :failure})

        :action
        (execute-action-node (first args) (second args) engine-ctx-atom nav-state sanctuary-route speaker agent)

        {:status :success})))))

(ns craft.agents.backend.fabric.goals.bt
  "Interpretador de Behavior Tree da Karlach (CA-048, CA-085).
   As acoes que viram metas chamam o multimethod de goals.dispatch; os defmethod
   sao registrados pela fachada goals.clj."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.goals.dispatch :refer [execute-karlach-goal!]]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.skills.actions :as skill-actions]))

(declare execute-tree-node!)

(defn- eval-condition
  "Avalia a condicao `cond-id` de um nó :guard/:condition contra engine-ctx e nav-state."
  [cond-id args engine-ctx-atom nav-state]
  (case cond-id
    (:leader-in-vehicle? :leader-in-boat?)
    (boolean (or (get-in @engine-ctx-atom [:perception :leader :in-vehicle?])
                 (get-in @engine-ctx-atom [:leader :in-vehicle?])
                 (:leader-in-vehicle? @nav-state)))

    (:self-in-vehicle? :self-in-boat?)
    (boolean (or (:in-vehicle? @nav-state)
                 (get-in @engine-ctx-atom [:perception :self :in-vehicle?])
                 (get-in @engine-ctx-atom [:agents :karlach :in-vehicle?])))

    :not-self-in-vehicle?
    (not (or (:in-vehicle? @nav-state)
             (get-in @engine-ctx-atom [:perception :self :in-vehicle?])
             (get-in @engine-ctx-atom [:agents :karlach :in-vehicle?])))

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

    :ally-within-range?
    (let [max-dist (or (:max-dist (second args)) 3.5)
          cur-dist (or (get-in @engine-ctx-atom [:perception :ally :distance]) 2.5)]
      (<= cur-dist max-dist))

    :ally-distance-exceeds?
    (let [min-dist (or (:min-dist (second args)) 8.0)
          cur-dist (or (get-in @engine-ctx-atom [:perception :ally :distance]) 2.5)]
      (> cur-dist min-dist))

    :cooldown-ready? true
    :interesting-block-nearby? true
    :in-quiet-corner? true

    true))

(defn- goal-action
  "Acao de BT que delega a uma meta do multimethod. Devolve {:status :success :action-id result-id}."
  [goal-type result-id extra engine-ctx-atom nav-state sanctuary-route speaker]
  (execute-karlach-goal! (merge {:goal/type goal-type :goal/speaker speaker} extra)
                         engine-ctx-atom nav-state sanctuary-route)
  {:status :success :action-id result-id})

(defn- execute-action-node
  "Executa um nó :action: macro (arquivo skills/ ou :composite-skills), meta embutida,
   som/fala ou acao modular de skills.actions."
  [action-id action-args engine-ctx-atom nav-state sanctuary-route speaker]
  (let [macro-file (io/file (io/file "skills") (str (clojure.core/name action-id) ".edn"))
        macro-def  (when (.exists macro-file)
                     (try (edn/read-string (slurp macro-file)) (catch Throwable _ nil)))
        macro-tree (or (:skill/tree macro-def)
                       (get-in @engine-ctx-atom [:composite-skills action-id]))
        goal!      (fn [goal-type result-id extra]
                     (goal-action goal-type result-id extra engine-ctx-atom nav-state sanctuary-route speaker))]
    (if macro-tree
      (execute-tree-node! macro-tree engine-ctx-atom nav-state sanctuary-route speaker)
      (try
        (case action-id
          :social-dance        (do (execute-karlach-goal! {:goal/type :social-dance :goal/voice-line "Olha o passinho de vitória! 🔥"}
                                                          engine-ctx-atom nav-state sanctuary-route)
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

          :say-message
          (do (actions/karlach-say! (or (:text action-args) "Comando concluído com sucesso, soldado!"))
              {:status :success :action-id :say-message})

          :play-sound
          (do (transport/send-bridge-cmd! (str "CMD|playsound " (:sound action-args "minecraft:entity.experience_orb.pickup") " master @a ~ ~ ~ 1.0 1.0"))
              {:status :success :action-id :play-sound})

          (let [act-res (skill-actions/execute-action action-id action-args
                                                      {:send-cmd! transport/send-bridge-cmd!
                                                       :karlach-say! actions/karlach-say!
                                                       :speaker speaker
                                                       :engine-ctx engine-ctx-atom
                                                       :nav-state nav-state
                                                       :sanctuary-route sanctuary-route})]
            (record-cognitive-event! engine-ctx-atom :motor "Ação Modular"
                                     (str "Executando ação modular: " action-id " -> " (:status act-res)))
            act-res))
        (catch Throwable _
          {:status :failure :action action-id})))))

(defn execute-tree-node!
  "Executa nó da Behavior Tree preservando semântica de controle (:fallback, :sequence, :parallel, :inverter),
   avaliação de condições (:guard, :condition) e expandindo macros com isolamento de leases (CA-048)."
  [node engine-ctx-atom nav-state sanctuary-route speaker]
  (if-not (vector? node)
    {:status :failure :reason :INVALID_NODE}
    (let [op   (first node)
          args (rest node)
          run  (fn [child] (execute-tree-node! child engine-ctx-atom nav-state sanctuary-route speaker))]
      (case op
        :sequence
        (loop [children args
               last-res {:status :success}]
          (if (empty? children)
            last-res
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
        (if (eval-condition (first args) args engine-ctx-atom nav-state)
          {:status :success}
          {:status :failure})

        :action
        (execute-action-node (first args) (second args) engine-ctx-atom nav-state sanctuary-route speaker)

        {:status :success}))))

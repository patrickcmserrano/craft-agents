(ns craft.agents.npc-body-integration-test
  "CA-105 — Cenario integrado multi-agente da sprint `body-01` (:karlach :picard :coco :richeh):
   arbitragem corporal unica, snapshot unico de percepcao, estado isolado por agente e
   roteamento de chat ate `goals/execute-goal!` com `:agent-id`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.goals :as goals]
            [craft.agents.backend.fabric.sentry :as sentry]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.body :as body]
            [craft.agents.npc.perception-tick :as perception-tick]
            [craft.agents.npc.state :as npc-state]))

(def ^:private agent-ids [:karlach :picard :coco :richeh])

(def ^:private players-wire
  (str "OK|"
       "pace,10.0,65.0,10.0,true,false,false;"
       "Karlach,-175.0,65.0,180.0,false,false,false;"
       "Picard,-65.0,145.0,101.0,false,false,false;"
       "Coco,-30.0,66.0,190.0,false,false,false;"
       "Richeh,-30.0,66.0,192.0,false,true,false"))

(defn- move-agent [cmd] (second (str/split cmd #"\|")))

(deftest competicao-de-subsistemas-gera-no-maximo-1-move-por-agente-test
  (let [sent (atom [])]
    (try
      (body/reset-buffer!)
      (with-redefs [transport/send-bridge-cmd! (fn [cmd] (swap! sent conj cmd) "OK")]
        (body/begin-tick!)
        (doseq [[i id] (map-indexed vector agent-ids)
                source [:atelier :bt :survival]
                :let [x (double (+ (* 10 i) ({:atelier 1 :bt 2 :survival 3} source)))]]
          (body/step! {:agent id :source source :x x :y 64.0 :z 0.0 :yaw 0.0 :pitch 0.0}))
        (is (empty? @sent) "em tick aberto nada sai antes do flush!")
        (let [res (body/flush!)
              ms  (filter #(str/starts-with? % "MOVE|") @sent)]
          (testing "no maximo 1 MOVE| por agente"
            (is (= 4 (count ms)))
            (is (= 4 (count (distinct (map move-agent ms))))))
          (testing "vence :survival (maior prioridade)"
            (doseq [[i id] (map-indexed vector agent-ids)]
              (let [nm (str/capitalize (name id))
                    c  (first (filter #(str/starts-with? % (str "MOVE|" nm "|")) ms))]
                (is (some? c) (str id))
                (is (str/starts-with? c (format "MOVE|%s|%.3f|" nm (double (+ (* 10 i) 3)))))))
            (is (= 4 (count res))))
          (is (= 4 (:cmds-per-tick (body/metrics))))))
      (finally
        (body/flush!)
        (body/reset-buffer!)))
    (is (false? (body/tick-open?)))))

(deftest snapshot-unico-de-percepcao-por-tick-test
  (let [sent (atom [])
        ctx  (atom {:tick 9})]
    (doseq [id agent-ids] (npc-state/agent-atom ctx id))
    (with-redefs [transport/send-bridge-cmd!
                  (fn [cmd] (swap! sent conj cmd) (if (= cmd "PLAYERS") players-wire "OK"))]
      (let [snap (perception-tick/capture-snapshot! ctx {:tick 9 :captured-at-ms 1})]
        (doseq [id agent-ids]
          (is (map? (get-in snap [:by-agent id])) (str id)))
        (doseq [id agent-ids] (perception-tick/agent-perception snap id))))
    (is (= 1 (count (filter #(= "PLAYERS" %) @sent))))))

(deftest estados-isolados-por-agente-test
  (let [ctx (atom {})
        sts (into {} (map (fn [id] [id (npc-state/agent-atom ctx id)])) agent-ids)]
    (npc-state/set-cooldown! ctx :karlach :strike 123)
    (npc-state/record-agent-event! ctx :coco {:type :note :text "so da coco"})
    (npc-state/agent-leases ctx :picard {:legs :survival})
    (is (= 4 (count (:agent-states @ctx))))
    (is (= 4 (count (distinct (map #(System/identityHashCode %) (vals sts))))))
    (testing ":cooldowns"
      (is (= {:strike 123} (:cooldowns @(get-in @ctx [:agent-states :karlach]))))
      (doseq [id [:picard :coco :richeh]]
        (is (empty? (:cooldowns @(get-in @ctx [:agent-states id]))) (str id))))
    (testing ":events"
      (is (= 1 (count (:events @(get-in @ctx [:agent-states :coco])))))
      (doseq [id [:karlach :picard :richeh]]
        (is (empty? (:events @(get-in @ctx [:agent-states id]))) (str id))))
    (testing ":leases"
      (is (= :survival (get-in @(get-in @ctx [:agent-states :picard]) [:leases :legs])))
      (doseq [id [:karlach :coco :richeh]]
        (is (nil? (get-in @(get-in @ctx [:agent-states id]) [:leases :legs])) (str id))))))

(deftest chat-enderecado-a-picard-roteia-para-execute-goal-test
  (let [calls (atom [])
        ectx  (atom {})]
    (with-redefs [goals/execute-goal! (fn [goal ctx] (swap! calls conj [goal ctx]) nil)
                  transport/send-bridge-cmd! (fn [_] "OK")]
      (sentry/handle-player-command! "Pace" "picard me segue"
                                     ectx (atom {:pos [0.0 65.0 0.0]}) (atom {:pos [0.0 65.0 0.0]})
                                     [] (atom {}) (atom {}) (str (random-uuid))))
    (is (= 1 (count @calls)))
    (is (= [:picard] (map (comp :agent-id second) @calls)))
    (is (= "Picard" (get-in (first @calls) [1 :agent-name])))))

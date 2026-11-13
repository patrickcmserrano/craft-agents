(ns craft.agents.karlach-gait-mirror-test
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.locomotion :as loco]))

(deftest mirror-gait-follows-leader-posture
  (testing "lider correndo -> ela corre na velocidade de sprint"
    (let [g (loco/mirror-gait {:sprinting? true} 4.0)]
      (is (true? (:sprint g))) (is (false? (:sneak g)))
      (is (>= (:speed g) 0.28))))
  (testing "lider andando perto -> anda sem sprint"
    (let [g (loco/mirror-gait {} 4.0)]
      (is (false? (:sprint g))) (is (false? (:sneak g)))
      (is (= 0.22 (:speed g)))))
  (testing "lider andando mas longe -> corre pra alcancar"
    (let [g (loco/mirror-gait {} 10.0)]
      (is (true? (:sprint g))) (is (= 0.28 (:speed g)))))
  (testing "lider agachado -> agacha devagar e nunca corre"
    (let [g (loco/mirror-gait {:sneaking? true :sprinting? true} 4.0)]
      (is (true? (:sneak g))) (is (false? (:sprint g)))
      (is (< (:speed g) 0.12))))
  (testing "lider agachado mas muito longe -> levanta e alcanca"
    (let [g (loco/mirror-gait {:sneaking? true} 12.0)]
      (is (false? (:sneak g))) (is (pos? (:speed g)))))
  (testing "parsing de PLAYERS com flags e sem flags (compat)"
    (is (= {:name "pace" :x 1.0 :y 2.0 :z 3.0 :sprinting? true :sneaking? false :gliding? false}
           (loco/parse-player-entry "pace,1.0,2.0,3.0,true,false")))
    (is (= {:name "pace" :x 1.0 :y 2.0 :z 3.0 :sprinting? false :sneaking? false :gliding? false}
           (loco/parse-player-entry "pace,1.0,2.0,3.0")))))

(deftest move-applies-crouching-pose-when-sneaking
  ;; setSneaking sozinho nao muda a pose; sem EntityPose.CROUCHING (field_18081)
  ;; ela fica em pe mesmo com sneak=true (bug da validacao in-game).
  (let [src (slurp "src/java/com/craft/agents/fabric/SyntheticPlayerFactory.java")]
    (is (re-find #"field_18081" src) "MOVE seta a pose CROUCHING quando sneak=true")))

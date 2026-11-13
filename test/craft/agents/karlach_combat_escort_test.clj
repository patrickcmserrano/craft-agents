(ns craft.agents.karlach-combat-escort-test
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.engine :as engine]))

(defn- mob [x dist] {:id 1 :type "minecraft:zombie" :x x :y 64.0 :z 0.0 :distance dist})

(deftest combat-stays-on-escort
  (testing "mob perto do lider -> engaja"
    (is (= :combat-intercept
           (:action (engine/evaluate-combat-preemption [0.0 64.0 0.0] [10.0 64.0 0.0] [(mob 7.0 7.0)] false)))))
  (testing "mob que ficou pra tras (longe do lider, nao colado nela) -> segue o lider"
    (is (= :follow-player
           (:action (engine/evaluate-combat-preemption [0.0 64.0 0.0] [10.0 64.0 0.0] [(mob -6.0 6.0)] false)))))
  (testing "mob colado nela -> autodefesa mesmo longe do lider"
    (is (= :combat-strike
           (:action (engine/evaluate-combat-preemption [0.0 64.0 0.0] [10.0 64.0 0.0] [(mob -3.0 3.0)] false)))))
  (testing "lider a mais de 12m -> recall, larga a luta"
    (is (= :tether-snap-recall
           (:action (engine/evaluate-combat-preemption [0.0 64.0 0.0] [14.0 64.0 0.0] [(mob 1.0 1.0)] false))))))

(deftest battle-cry-is-rate-limited
  (is (true? (engine/battle-cry-ready? nil 100)))
  (is (false? (engine/battle-cry-ready? 100 130)))
  (is (false? (engine/battle-cry-ready? 100 (+ 100 (dec engine/battle-cry-cooldown-ticks)))))
  (is (true? (engine/battle-cry-ready? 100 (+ 100 engine/battle-cry-cooldown-ticks))))
  (is (>= engine/battle-cry-cooldown-ticks 600)))

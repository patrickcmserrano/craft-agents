(ns craft.agents.karlach-strike-cooldown-test
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.engine :as engine]))

(deftest strike-cooldown-gates-melee
  (testing "primeiro golpe sempre liberado"
    (is (true? (engine/strike-ready? nil 100))))
  (testing "dentro do cooldown nao golpeia (sem spam por tick)"
    (is (false? (engine/strike-ready? 100 101)))
    (is (false? (engine/strike-ready? 100 (+ 100 (dec engine/strike-cooldown-ticks))))))
  (testing "apos o cooldown libera de novo"
    (is (true? (engine/strike-ready? 100 (+ 100 engine/strike-cooldown-ticks)))))
  (testing "cooldown de machado ~1s a 20Hz"
    (is (>= engine/strike-cooldown-ticks 16))))

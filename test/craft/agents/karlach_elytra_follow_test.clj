(ns craft.agents.karlach-elytra-follow-test
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.locomotion :as loco]))

(defn- d3 [[ax ay az] [bx by bz]]
  (Math/sqrt (+ (Math/pow (- ax bx) 2) (Math/pow (- ay by) 2) (Math/pow (- az bz) 2))))

(deftest players-entry-carries-gliding
  (is (true? (:gliding? (loco/parse-player-entry "pace,1,2,3,false,false,true"))))
  (is (false? (:gliding? (loco/parse-player-entry "pace,1,2,3,false,false")))))

(deftest glide-phase-decision
  (testing "lider voando -> voa"
    (is (= :fly (loco/glide-phase {:gliding? true} false 30.0))))
  (testing "lider pousou e ela ainda no ar longe -> voa ate ele (pouso)"
    (is (= :land (loco/glide-phase {:gliding? false} true 12.0))))
  (testing "pousou perto -> encerra o voo"
    (is (= :touchdown (loco/glide-phase {:gliding? false} true 2.0))))
  (testing "ninguem voando -> chao"
    (is (= :ground (loco/glide-phase {} false 12.0)))))

(deftest flight-step-chases-in-3d
  (testing "aproxima do lider pelo ar, sem passar do rastro, com passo limitado"
    (let [kp [0.0 100.0 0.0] lp [40.0 120.0 0.0]
          r  (loco/flight-step {:pos kp :leader lp :trail 3.0 :max-step 2.5})]
      (is (< (d3 (:pos r) lp) (d3 kp lp)))
      (is (<= (d3 (:pos r) kp) 2.5001))
      (is (neg? (:pitch r)) "subindo -> pitch negativo (convencao MC)")))
  (testing "ja no rastro -> nao se mexe"
    (let [r (loco/flight-step {:pos [0.0 100.0 2.0] :leader [0.0 100.0 0.0] :trail 3.0 :max-step 2.5})]
      (is (= [0.0 100.0 2.0] (:pos r))))))

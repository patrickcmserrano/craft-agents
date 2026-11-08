(ns craft.agents.rsi.bench-test
  "Suíte de verificação do Craft-Bench e Gate de Promoção de Skills (CA-RSI-P2).
   Garante que a melhoria recursiva seja falsificável com tarefas held-out e semântica preservada."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.rsi.bench :as bench]))

(deftest test-benchmark-metrics-calculation
  (testing "Calcula métricas de benchmark incluindo tarefas abertas e held-out"
    (let [sample-library {:rescue-duck [:sequence
                                        [:action :equip-bait {:item "apple"}]
                                        [:action :attach-lead {:target "duck"}]]}
          metrics (bench/run-benchmark sample-library)]
      (is (number? (:success-rate metrics)))
      (is (number? (:held-out-success-rate metrics)))
      (is (number? (:avg-ticks metrics)))
      (is (pos? (:mdl-node-count metrics))))))

(deftest test-promotion-gate-falsifiability
  (testing "Gate de promoção REJEITA mutação que cause regressão em tarefas held-out"
    (let [baseline {:success-rate 0.85
                    :held-out-success-rate 0.80
                    :early-abort-rate 0.05
                    :mdl-node-count 50}
          regressed-candidate {:success-rate 0.90
                               :held-out-success-rate 0.60 ;; Regressão em tarefas escondidas!
                               :early-abort-rate 0.05
                               :mdl-node-count 45}
          decision (bench/evaluate-promotion-gate baseline regressed-candidate)]
      (is (false? (:promoted? decision)))
      (is (contains? (set (:rejection-reasons decision)) :held-out-regression))))

  (testing "Gate de promoção APROVA refatoração com compressão de nós (MDL) e zero regressão"
    (let [baseline {:success-rate 0.90
                    :held-out-success-rate 0.80
                    :early-abort-rate 0.05
                    :mdl-node-count 60}
          compressed-candidate {:success-rate 0.90
                                :held-out-success-rate 0.80
                                :early-abort-rate 0.05
                                :mdl-node-count 42} ;; Economia de 18 nós!
          decision (bench/evaluate-promotion-gate baseline compressed-candidate)]
      (is (true? (:promoted? decision)))
      (is (pos? (get-in decision [:deltas :node-savings]))))))

(deftest test-semantic-equivalence-property
  (testing "Macro-compressão preserva exatamente o trace de ações da árvore original"
    (let [original-tree [:sequence
                         [:action :locate]
                         [:sequence
                          [:action :equip-bait]
                          [:action :attach-lead]]
                         [:action :close-gate]]
          compressed-tree [:sequence
                           [:action :locate]
                           [:action :macro-leash]
                           [:action :close-gate]]
          composites {:macro-leash [:sequence
                                    [:action :equip-bait]
                                    [:action :attach-lead]]}]
      (is (true? (bench/semantic-equivalence? original-tree compressed-tree composites))))))

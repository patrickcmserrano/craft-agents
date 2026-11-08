(ns craft.agents.epic-rsi-curriculum-test
  "Especificação comportamental TDD para Curriculum Agent & Recursive Self-Improvement (CA-RSI.1..RSI.4).
   Verifica Memória Negativa, Motor de Curiosidade, Síntese de Fallbacks e Dreamer RSI."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [craft.agents.schemas :as schemas]
            [craft.agents.cognition.ledger :as ledger]
            [craft.agents.cognition.critic :as critic]
            [craft.agents.rsi.curriculum :as curriculum]))

(def test-ledger-file "target/test-abort-ledger.edn")

(deftest test-negative-memory-ledger-ca-rsi-1
  (testing "Ledger armazena, recupera e filtra Early Aborts de forma determinística"
    (ledger/clear-ledger! test-ledger-file)
    (let [empty-data (ledger/read-ledger test-ledger-file)]
      (is (= {} empty-data)))

    ;; Registra 3 falhas na skill :rescue-duck para a mesma ação :attach-lead
    (dotimes [i 3]
      (ledger/record-abort!
       test-ledger-file
       {:skill-id           :rescue-duck
        :failed-action      :attach-lead
        :node-path          [:sequence 2]
        :violated-invariant :leash-attached
        :diagnostic         "O laço arrebentou durante a condução."}))

    ;; Registra 1 falha isolada na skill :mine-ore
    (ledger/record-abort!
     test-ledger-file
     {:skill-id           :mine-ore
      :failed-action      :excavate-block
      :node-path          [:sequence 1]
      :violated-invariant :pickaxe-durability
      :diagnostic         "Picareta sem durabilidade."})

    (let [loaded (ledger/read-ledger test-ledger-file)]
      (is (= 3 (count (get loaded :rescue-duck))))
      (is (= 1 (count (get loaded :mine-ore))))

      (testing "Filtra abortos que atingem o limiar de recorrência (>= 3)"
        (let [frequent (ledger/frequent-aborts loaded 3)]
          (is (= 1 (count frequent)))
          (is (= :rescue-duck (:skill-id (first frequent))))
          (is (= :attach-lead (:failed-action (first frequent))))
          (is (= 3 (:failure-count (first frequent))))))

      (testing "Gera prompt de restrições negativas formatado"
        (let [prompt-text (ledger/build-negative-constraints-prompt (ledger/frequent-aborts loaded 3))]
          (is (.contains prompt-text "RESTRIÇÕES NEGATIVAS EMPÍRICAS"))
          (is (.contains prompt-text ":rescue-duck"))
          (is (.contains prompt-text ":attach-lead"))
          (is (.contains prompt-text ":leash-attached")))))

    ;; Limpeza
    (ledger/clear-ledger! test-ledger-file)))

(deftest test-critic-integration-with-ledger
  (testing "critic/record-early-abort-event! acopla a reflexão com o ledger de persistência"
    (ledger/clear-ledger! test-ledger-file)
    (let [reflection (critic/generate-early-abort-reflection
                      {:violated? true :invariant :leash-attached :diagnostic "Laço arrebentou!"}
                      {:label "Laçar animal" :op :attach-lead})]
      (critic/record-early-abort-event! reflection :patrol-sanctuary test-ledger-file)
      (let [loaded (ledger/read-ledger test-ledger-file)]
        (is (= 1 (count (get loaded :patrol-sanctuary))))
        (is (= :attach-lead (:failed-action (first (get loaded :patrol-sanctuary)))))
        (is (= :leash-attached (:violated-invariant (first (get loaded :patrol-sanctuary)))))))
    (ledger/clear-ledger! test-ledger-file)))

(deftest test-server-idle-detector-ca-rsi-2
  (testing "Detecta ociosidade do servidor baseado em TPS, ticks sem comandos e hostis"
    (is (true? (curriculum/server-idle? {:tps 20.0 :idle-ticks 120 :hostiles-count 0})))
    (is (false? (curriculum/server-idle? {:tps 15.0 :idle-ticks 120 :hostiles-count 0}))
        "Rejeita se TPS estiver abaixo do limiar")
    (is (false? (curriculum/server-idle? {:tps 20.0 :idle-ticks 50 :hostiles-count 0}))
        "Rejeita se não tiver ticks ociosos suficientes")
    (is (false? (curriculum/server-idle? {:tps 20.0 :idle-ticks 200 :hostiles-count 2}))
        "Rejeita se houver hostis detectados")))

(deftest test-curiosity-hypothesis-generation-ca-rsi-2
  (let [sample-skills {:rescue-sheep
                       {:skill/id :rescue-sheep
                        :skill/tree [:sequence
                                     [:action :equip-bait {:item "minecraft:wheat"}]
                                     [:action :attach-lead {:target "Sheep"}]]}}
        sample-digest {:self {:inventory {"minecraft:wheat" 5
                                          "minecraft:lead" 2
                                          "minecraft:carrot" 3}}
                       :entities {:animals-summary {"Sheep" 2 "Capybara" 1}}}]

    (testing "Prioridade 1: Auto-cura quando há abortos recorrentes no ledger"
      (let [mock-ledger {:rescue-sheep [{:failed-action :attach-lead
                                         :violated-invariant :leash-attached}
                                        {:failed-action :attach-lead
                                         :violated-invariant :leash-attached}
                                        {:failed-action :attach-lead
                                         :violated-invariant :leash-attached}]}
            hyp (curriculum/generate-curiosity-hypothesis
                 {:skills-map sample-skills
                  :abort-ledger mock-ledger
                  :scene-digest sample-digest})]
        (is (= :self-healing-fallback (:hypothesis/type hyp)))
        (is (= :high (:hypothesis/priority hyp)))
        (is (= :rescue-sheep (:target/skill-id hyp)))
        (is (= :attach-lead (:target/failed-action hyp)))))

    (testing "Prioridade 2: Exploração de fronteira quando há novos itens no inventário"
      (let [hyp (curriculum/generate-curiosity-hypothesis
                 {:skills-map sample-skills
                  :abort-ledger {}
                  :scene-digest sample-digest})]
        (is (= :frontier-exploration (:hypothesis/type hyp)))
        (is (= :normal (:hypothesis/priority hyp)))
        (is (contains? #{"minecraft:carrot" "minecraft:lead"} (:target/identifier hyp)))))

    (testing "Prioridade 3: Consolidação / Dreamer quando todo o ambiente está coberto"
      (let [covered-skills {:full-cover
                            {:skill/id :full-cover
                             :skill/tree [:sequence
                                          [:action :eat {:item "minecraft:wheat"}]
                                          [:action :eat {:item "minecraft:lead"}]
                                          [:action :eat {:item "minecraft:carrot"}]
                                          [:action :pet {:target "Sheep"}]
                                          [:action :pet {:target "Capybara"}]]}}
            hyp (curriculum/generate-curiosity-hypothesis
                 {:skills-map covered-skills
                  :abort-ledger {}
                  :scene-digest sample-digest})]
        (is (= :dream-consolidation (:hypothesis/type hyp)))
        (is (= :low (:hypothesis/priority hyp)))))))

(deftest test-fallback-mutation-ca-rsi-3
  (testing "wrap-action-with-fallback injeta nó [:fallback ...] mantendo validação Malli"
    (let [original-tree [:sequence
                         [:action :locate-target]
                         [:action :attach-lead {:target "Duck"}]
                         [:action :walk-home]]
          fallback-sub  [:sequence
                         [:action :equip-bait {:item "minecraft:apple"}]
                         [:action :attach-lead {:target "Duck"}]]
          mutated       (curriculum/wrap-action-with-fallback original-tree :attach-lead fallback-sub)]
      (is (= [:sequence
              [:action :locate-target]
              [:fallback
               [:action :attach-lead {:target "Duck"}]
               [:sequence
                [:action :equip-bait {:item "minecraft:apple"}]
                [:action :attach-lead {:target "Duck"}]]]
              [:action :walk-home]]
             mutated))
      (is (true? (schemas/valid-bt-node? mutated))))))

(deftest test-the-dreamer-ast-compression-ca-rsi-4
  (testing "Dreamer detecta padrões repetidos entre skills e comprime a biblioteca"
    (let [shared-pattern [:sequence
                          [:action :equip-bait {:item "minecraft:apple"}]
                          [:action :attach-lead {:target "animal"}]]
          registry (atom {:composite-skills
                          {:rescue-duck
                           [:sequence
                            [:action :locate-duck]
                            shared-pattern
                            [:action :close-gate]]
                           :rescue-pig
                           [:sequence
                            [:action :locate-pig]
                            shared-pattern
                            [:action :feed-animal]]}})]

      (let [candidate (curriculum/find-repeated-sequences (:composite-skills @registry))]
        (is (some? candidate))
        (is (= shared-pattern (:pattern candidate)))
        (is (= #{:rescue-duck :rescue-pig} (:affected-skills candidate))))

      (let [result (curriculum/dream-compress-library! registry {:macro-prefix "macro-leash"})]
        (is (true? (:ok? result)))
        (is (= :compressed-successfully (:rsi/status result)))
        (let [macro-id (:macro-id result)]
          (is (.startsWith (name macro-id) "macro-leash"))
          ;; A nova macro foi registrada no registry
          (is (= shared-pattern (get-in @registry [:composite-skills macro-id])))
          ;; Ambas as skills originais foram refatoradas para usar a macro
          (let [refactored-duck (get-in @registry [:composite-skills :rescue-duck])
                refactored-pig  (get-in @registry [:composite-skills :rescue-pig])]
            (is (= [:sequence
                    [:action :locate-duck]
                    [:action macro-id]
                    [:action :close-gate]]
                   refactored-duck))
            (is (= [:sequence
                    [:action :locate-pig]
                    [:action macro-id]
                    [:action :feed-animal]]
                   refactored-pig))
            ;; As árvores refatoradas continuam atendendo estritamente ao schema Malli
            (is (true? (schemas/valid-bt-node? refactored-duck)))
            (is (true? (schemas/valid-bt-node? refactored-pig)))))))))

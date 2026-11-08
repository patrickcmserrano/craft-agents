(ns craft.agents.epic-rsi-curriculum-test
  "Especificação comportamental TDD para Curriculum Agent & Recursive Self-Improvement (CA-RSI.1..RSI.4).
   Verifica Memória Negativa, Motor de Curiosidade, Síntese de Fallbacks e Dreamer RSI."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [craft.agents.schemas :as schemas]
            [craft.agents.cognition.ledger :as ledger]
            [craft.agents.cognition.critic :as critic]
            [craft.agents.skills.composer :as composer]
            [craft.agents.bt.engine :as bt]
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
        "Rejeita se houver hostis detectados")
    (testing "Fail-closed: Rejeita se status for incompleto ou nulo"
      (is (false? (curriculum/server-idle? {})))
      (is (false? (curriculum/server-idle? nil)))
      (is (false? (curriculum/server-idle? {:tps 20.0})))
      (is (false? (curriculum/server-idle? {:idle-ticks 120}))))))

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

(deftest test-skill-rag-negative-constraints-injection-ca-rsi-1-2
  (testing "Skill-RAG enriquece skills recuperadas com restrições negativas empíricas do ledger"
    (let [skills-dir "target/test-rag-skills"
          ledger-data {:rescue-duck [{:failed-action :attach-lead
                                      :violated-invariant :leash-attached
                                      :diagnostic "Corda arrebentou."}
                                     {:failed-action :attach-lead
                                      :violated-invariant :leash-attached
                                      :diagnostic "Corda arrebentou novamente."}
                                     {:failed-action :attach-lead
                                      :violated-invariant :leash-attached
                                      :diagnostic "Corda arrebentou pela 3a vez."}]}]
      (.mkdirs (io/file skills-dir))
      (spit (io/file skills-dir "rescue-duck.edn")
            (pr-str {:skill/id :rescue-duck
                     :skill/name "Rescue Duck"
                     :skill/trigger "resgate o pato"
                     :skill/tree [:sequence
                                  [:action :locate-duck]
                                  [:action :attach-lead {:target "Duck"}]]}))
      (try
        (let [results (composer/retrieve-relevant-skills
                       "resgate o pato"
                       skills-dir
                       {:abort-ledger ledger-data})]
          (is (seq results))
          (let [first-res (first results)]
            (is (= :rescue-duck (get-in first-res [:skill :skill/id])))
            (is (true? (:unstable? first-res)))
            (is (seq (:negative-constraints first-res)))
            (is (= :attach-lead (:failed-action (first (:negative-constraints first-res)))))
            (is (some? (:negative-prompt first-res)))
            (is (.contains (:negative-prompt first-res) "RESTRIÇÕES NEGATIVAS"))))
        (finally
          (doseq [f (.listFiles (io/file skills-dir))] (.delete f))
          (.delete (io/file skills-dir)))))))

(deftest test-static-lease-conflict-analysis-ca-rsi-3-2
  (testing "detect-lease-conflicts identifica nós paralelos disputando o mesmo canal de lease"
    ;; Conflito em :hands (dois nós simultâneos usam :hands)
    (let [conflicting-tree [:parallel
                            [:action :attach-lead {:target "Cow"}]
                            [:action :open-gate {:enclosure "corral"}]]
          analysis (schemas/detect-lease-conflicts conflicting-tree)]
      (is (true? (:conflict? analysis)))
      (is (contains? (set (:conflicting-channels analysis)) :hands)))

    ;; Sem conflito (:legs + :gaze usam canais ortogonais)
    (let [orthogonal-tree [:parallel
                           [:action :walk {:target [10 64 10]}]
                           [:action :social-gaze {:target "Pace"}]]
          analysis (schemas/detect-lease-conflicts orthogonal-tree)]
      (is (false? (:conflict? analysis)))
      (is (empty? (:conflicting-channels analysis))))

    ;; Gatekeeper do composer rejeita árvore com conflito de leases
    (let [registry (atom {:composite-skills {}})
          skills-dir "target/test-gatekeeper-skills"
          invalid-skill {:skill/id :conflicted-task
                         :skill/name "Conflicted Task"
                         :skill/tree [:parallel
                                      [:action :attach-lead {:target "Cow"}]
                                      [:action :feed-animal {:target "Cow"}]]}]
      (.mkdirs (io/file skills-dir))
      (try
        (let [res (composer/validate-and-register-skill! registry skills-dir invalid-skill)]
          (is (false? (:ok? res)))
          (is (= :LEASE_CONFLICT (:reason res)))
          (is (contains? (set (:conflicting-channels res)) :hands)))
        (finally
          (doseq [f (.listFiles (io/file skills-dir))] (.delete f))
          (.delete (io/file skills-dir)))))))

(deftest test-autonomous-curriculum-cycle-execution
  (testing "step-curriculum-cycle! orquestra o ciclo fechado quando servidor estiver calmo"
    (let [registry (atom {:composite-skills
                          {:rescue-duck
                           [:sequence
                            [:action :locate-duck]
                            [:sequence
                             [:action :equip-bait {:item "apple"}]
                             [:action :attach-lead {:target "duck"}]]]
                           :rescue-pig
                           [:sequence
                            [:action :locate-pig]
                            [:sequence
                             [:action :equip-bait {:item "apple"}]
                             [:action :attach-lead {:target "duck"}]]]}})
          idle-status {:tps 20.0 :idle-ticks 150 :hostiles-count 0}
          busy-status {:tps 14.0 :idle-ticks 10 :hostiles-count 3}]

      ;; 1. Quando o servidor está ocupado, o ciclo é ignorado
      (let [res (curriculum/step-curriculum-cycle!
                 {:skills-map (:composite-skills @registry)
                  :abort-ledger {}
                  :scene-digest {:self {:inventory {"apple" 2}} :entities {:animals-summary {}}}
                  :skill-registry registry
                  :server-status busy-status})]
        (is (= :skipped (:status res)))
        (is (= :server-busy (:reason res))))

      ;; 2. Quando o servidor está calmo e há oportunidade de compressão (Dreamer)
      (let [res (curriculum/step-curriculum-cycle!
                 {:skills-map (:composite-skills @registry)
                  :abort-ledger {}
                  :scene-digest {:self {:inventory {}} :entities {:animals-summary {}}}
                  :skill-registry registry
                  :server-status idle-status})]
        (is (= :executed (:status res)))
        (is (= :dream-consolidation (get-in res [:hypothesis :hypothesis/type])))
        (is (= :compressed-successfully (get-in res [:result :rsi/status])))))))

(deftest test-bt-engine-macro-expansion-and-execution
  (testing "Engine expande nós [:action :macro-...] recursivamente via :composite-skills"
    (let [log (atom [])
          ctx {:actions {:walk (fn [_] (swap! log conj :walk) {:status :success})
                         :eat  (fn [_] (swap! log conj :eat) {:status :success})}
               :composite-skills {:macro-routine [:sequence
                                                  [:action :walk]
                                                  [:action :eat]]}}
          tree [:sequence
                [:action :macro-routine]]
          res (bt/tick-tree tree ctx)]
      (is (= :success (:status res)))
      (is (= [:walk :eat] @log)))))

(deftest test-bt-engine-semantic-fallback-recovery
  (testing "Fallback tenta rota alternativa quando o primeiro ramo falha"
    (let [log (atom [])
          ctx {:actions {:unstable-action (fn [_] (swap! log conj :unstable) {:status :failure})
                         :recovery-action (fn [_] (swap! log conj :recovery) {:status :success})}}
          tree [:fallback
                [:action :unstable-action]
                [:action :recovery-action]]
          res (bt/tick-tree tree ctx)]
      (is (= :success (:status res)))
      (is (= [:unstable :recovery] @log))))

  (testing "Fallback não executa contingência se o primeiro ramo for bem sucedido"
    (let [log (atom [])
          ctx {:actions {:primary-action (fn [_] (swap! log conj :primary) {:status :success})
                         :recovery-action (fn [_] (swap! log conj :recovery) {:status :success})}}
          tree [:fallback
                [:action :primary-action]
                [:action :recovery-action]]
          res (bt/tick-tree tree ctx)]
      (is (= :success (:status res)))
      (is (= [:primary] @log)))))

(deftest test-detect-lease-conflicts-fail-closed-and-macros
  (testing "Ação desconhecida em nó paralelo é tratada fail-closed gerando conflito"
    (let [tree [:parallel
                [:action :walk]
                [:action :unknown-complex-mutation]]
          res (schemas/detect-lease-conflicts tree)]
      (is (true? (:conflict? res)))
      (is (contains? (set (:conflicting-channels res)) :unknown))))

  (testing "Expande macros recursivamente para detectar conflitos de lease aninhados"
    (let [ctx {:composite-skills {:macro-hands [:sequence
                                                [:action :attach-lead]
                                                [:action :close-gate]]}}
          tree [:parallel
                [:action :open-gate]
                [:action :macro-hands]]
          res (schemas/detect-lease-conflicts tree ctx)]
      (is (true? (:conflict? res)))
      (is (contains? (set (:conflicting-channels res)) :hands)))))

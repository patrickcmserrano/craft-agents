(ns craft.agents.cognition.critic-test
  "Especificação comportamental TDD para o Environmental Critic e Reflexion Engine (CA-041)."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.cognition.critic :as critic]))

(deftest test-parse-sanctuary-snapshot
  (testing "Parsing de string raw retornada pelo In-Server Bridge SANCTUARY"
    (let [raw "OK|{NE:class_1440=2, NW:Capybara=1, SE:Capybara=1, SW:Capybara=2, SW:Deer=1}"
          parsed (critic/parse-sanctuary-snapshot raw)]
      (is (= 2 (get-in parsed [:SW "Capybara"])))
      (is (= 1 (get-in parsed [:NW "Capybara"])))
      (is (= 1 (get-in parsed [:SE "Capybara"])))
      (is (= 1 (get-in parsed [:SW "Deer"])))
      (is (= 2 (get-in parsed [:NE "class_1440"]))))))

(deftest test-evaluate-animal-containment
  (testing "Critic detecta capivara fora do Bosque e declara FALHA empírica (Zero alucinação)"
    (let [snapshot {:SW {"Capybara" 2}
                    :NW {"Capybara" 1}
                    :SE {"Capybara" 1}}
          res (critic/evaluate-animal-containment snapshot "naturalist:capybara" :pen-south-west)]
      (is (false? (:success? res)))
      (is (= :goal-failed (:verdict res)))
      (is (= 2 (:escaped-count res)))
      (is (= #{:NW :SE} (set (map :quadrant (:escaped-locations res)))))
      (is (re-find #"fora do Bosque" (:diagnostic res)))))

  (testing "Critic detecta animal no status :OUT (fora das cercas nos corredores)"
    (let [snapshot {:SW {"Deer" 1}
                    :OUT {"Deer" 1}}
          res (critic/evaluate-animal-containment snapshot "naturalist:deer" :pen-south-west)]
      (is (false? (:success? res)))
      (is (= :goal-failed (:verdict res)))
      (is (= 1 (:escaped-count res)))
      (is (= #{:OUT} (set (map :quadrant (:escaped-locations res)))))))

  (testing "Critic confirma sucesso quando todas as capivaras estão dentro do cercado SW"
    (let [snapshot {:SW {"Capybara" 3}
                    :NW {"Mammoth" 1}
                    :SE {"Elephant" 2}}
          res (critic/evaluate-animal-containment snapshot "naturalist:capybara" :pen-south-west)]
      (is (true? (:success? res)))
      (is (= :goal-achieved (:verdict res)))
      (is (= 0 (:escaped-count res)))
      (is (re-find #"todas as capivaras" (:diagnostic res)))))

  (testing "Critic valida resgate individual com sucesso empírico mesmo com outros animais soltos no mapa"
    (let [snapshot {:SW {"Deer" 1}
                    :OUT {"Deer" 1}}
          res (critic/evaluate-animal-containment snapshot "naturalist:deer" :pen-south-west
                                                 {:require-all? false :initial-contained 0})]
      (is (true? (:success? res)))
      (is (= :goal-achieved (:verdict res)))
      (is (= 1 (:contained-count res)))
      (is (re-find #"cervo resgatado e seguro no Bosque" (:diagnostic res)))))

  (testing "Critic REJEITA falso positivo quando nenhum animal novo foi adicionado ao cercado (Bug Capivara Fofinha)"
    (let [snapshot {:SW {"Capybara" 2}
                    :OUT {"Capybara" 1}}
          res (critic/evaluate-animal-containment snapshot "naturalist:capybara" :pen-south-west
                                                 {:require-all? false :initial-contained 2})]
      (is (false? (:success? res)))
      (is (= :goal-failed (:verdict res)))
      (is (= 1 (:escaped-count res)))
      (is (re-find #"fora do Bosque" (:diagnostic res))))))

(deftest test-reflexion-self-correction
  (testing "Geração de diagnóstico de reflexão a partir de falha observada pelo Critic"
    (let [failed-eval {:success? false
                       :verdict :goal-failed
                       :escaped-count 1
                       :escaped-locations [{:quadrant :SE :count 1}]
                       :diagnostic "Falha empírica: 1 capivara detectada no quadrante :SE, fora do Bosque."}
          previous-skill {:skill/id :rescue-swimming-capybara
                          :skill/version 1
                          :skill/tree [:sequence
                                       [:action :equip-bait {:item "minecraft:apple"}]
                                       [:action :walk {:target [-175.0 65.0 185.0]}]]}
          reflection (critic/generate-reflection failed-eval previous-skill)]
      (is (= :self-correction-required (:reflection/status reflection)))
      (is (= 2 (:reflection/next-version reflection)))
      (is (some #{:use-lead} (:reflection/suggested-modifications reflection)))
      (is (some #{:verify-before-gate-close} (:reflection/suggested-modifications reflection)))
      (is (string? (:reflection/voice-line reflection))))))

(deftest test-multimodal-critic-polymorphic
  (testing "Crítico de Inventário (:inventory-delta) valida deltas físicos de recursos"
    (let [before {:inventory {"minecraft:oak_log" 2}}
          after  {:inventory {"minecraft:oak_log" 6}}
          res-ok (critic/evaluate-goal-achievement :inventory-delta before after {:target/item "minecraft:oak_log" :target/count 4})
          res-fail (critic/evaluate-goal-achievement :inventory-delta before after {:target/item "minecraft:oak_log" :target/count 5})]
      (is (true? (:success? res-ok)))
      (is (= :goal-achieved (:verdict res-ok)))
      (is (= 4 (:delta res-ok)))
      (is (false? (:success? res-fail)))
      (is (= :goal-failed (:verdict res-fail)))
      (is (= 1 (:missing-count res-fail)))))

  (testing "Crítico de Navegação (:waypoint-reached) valida distância euclidiana com tolerância"
    (let [target [10.0 64.0 20.0]
          after-near {:pos [11.0 64.0 21.0]}   ;; dist ~= 1.41
          after-far  {:pos [20.0 64.0 30.0]}   ;; dist ~= 14.14
          res-ok (critic/evaluate-goal-achievement :waypoint-reached nil after-near {:target/pos target :tolerance 2.5})
          res-fail (critic/evaluate-goal-achievement :waypoint-reached nil after-far {:target/pos target :tolerance 2.5})]
      (is (true? (:success? res-ok)))
      (is (= :goal-achieved (:verdict res-ok)))
      (is (< (:distance res-ok) 2.5))
      (is (false? (:success? res-fail)))
      (is (= :goal-failed (:verdict res-fail)))
      (is (> (:distance res-fail) 2.5))))

  (testing "Crítico de Construção (:voxel-blueprint) afere conformidade volumétrica 3D"
    (let [blueprint [{:pos [0 64 0] :block "minecraft:oak_planks"}
                     {:pos [0 64 1] :block "minecraft:glass"}]
          reader-perfect (fn [p] (if (= p [0 64 0]) "minecraft:oak_planks" "minecraft:glass"))
          reader-broken  (fn [p] (if (= p [0 64 0]) "minecraft:oak_planks" "minecraft:air"))
          res-ok (critic/evaluate-goal-achievement :voxel-blueprint nil nil {:blueprint blueprint :voxel-reader reader-perfect})
          res-fail (critic/evaluate-goal-achievement :voxel-blueprint nil nil {:blueprint blueprint :voxel-reader reader-broken :min-completion 1.0})]
      (is (true? (:success? res-ok)))
      (is (= 1.0 (:completion res-ok)))
      (is (false? (:success? res-fail)))
      (is (= 0.5 (:completion res-fail)))
      (is (= 1 (count (:mismatches res-fail)))))))

(deftest test-runtime-invariants-early-abort
  (testing "Sentinela de proximidade do alvo (:max-target-distance) detecta fuga em tempo de voo"
    (let [invariants [{:invariant :max-target-distance :max-distance 8.0}]
          obs-ok   {:bot-pos [0.0 64.0 0.0] :target-pos [3.0 64.0 4.0]}  ;; dist 5.0
          obs-fail {:bot-pos [0.0 64.0 0.0] :target-pos [8.0 64.0 6.0]}  ;; dist 10.0
          res-ok   (critic/check-runtime-invariants invariants obs-ok)
          res-fail (critic/check-runtime-invariants invariants obs-fail)]
      (is (false? (:violated? res-ok)))
      (is (true? (:violated? res-fail)))
      (is (= :max-target-distance (:invariant res-fail)))
      (is (> (:distance res-fail) 8.0))))

  (testing "Sentinela de acoplamento físico com laço (:leash-attached)"
    (let [invariants [{:invariant :leash-attached}]
          res-ok   (critic/check-runtime-invariants invariants {:leashed? true})
          res-fail (critic/check-runtime-invariants invariants {:leashed? false})]
      (is (false? (:violated? res-ok)))
      (is (true? (:violated? res-fail)))
      (is (= :leash-attached (:invariant res-fail)))))

  (testing "Geração de diagnóstico causal e voice-line de Early Abort"
    (let [violation {:violated? true :invariant :max-target-distance :diagnostic "Alvo a 10 blocos"}
          step      {:op :lure-to-gate :label "Atraindo capivara até o portão"}
          abort-ref (critic/generate-early-abort-reflection violation step)]
      (is (= :early-abort-triggered (:reflection/status abort-ref)))
      (is (= :target-lost-tracking (:reflection/cause abort-ref)))
      (is (= :halt-and-relure (:reflection/suggested-action abort-ref)))
      (is (re-find #"se afastou demais" (:reflection/voice-line abort-ref))))))

(deftest test-cooperative-goal-achievement
  (testing "P3.2: Crítico Cooperativo avalia metas conjuntas simultâneas de Karlach e Picard"
    (let [sub-goals [{:goal/type :waypoint-reached
                      :goal/opts {:target/pos [-65.0 145.0 100.0] :tolerance 2.5}}
                     {:goal/type :inventory-delta
                      :goal/opts {:target/item "minecraft:apple" :target/count 1}}]
          before-state {:inventory {"minecraft:apple" 0}}
          after-ok     {:pos [-65.5 145.0 100.2]
                        :inventory {"minecraft:apple" 2}}
          after-fail   {:pos [-65.5 145.0 100.2]
                        :inventory {"minecraft:apple" 0}}
          res-ok       (critic/evaluate-goal-achievement :cooperative-goal before-state after-ok {:sub-goals sub-goals})
          res-fail     (critic/evaluate-goal-achievement :cooperative-goal before-state after-fail {:sub-goals sub-goals})]
      (is (true? (:success? res-ok)))
      (is (= :cooperative-goal-achieved (:verdict res-ok)))
      (is (re-find #"todas as 2 metas conjuntas foram satisfeitas" (:diagnostic res-ok)))

      (is (false? (:success? res-fail)))
      (is (= :cooperative-goal-failed (:verdict res-fail)))
      (is (= 1 (count (:failures res-fail)))))))

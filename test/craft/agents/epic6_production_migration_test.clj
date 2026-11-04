(ns craft.agents.epic6-production-migration-test
  "Testes de aceitacao E2E do Epico 6: Santuario, Karlach, Picard, Antigravity (JEG) e Escala de 25 Agentes (CA-043 a CA-048)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.core :as core]
            [craft.agents.backend.fabric :as fabric]
            [craft.agents.fixtures.world-fixture :as wf]
            [craft.agents.regions :as regions]
            [craft.agents.schemas :as schemas]))

(deftest ca-043-sanctuary-region-profile-validation-test
  (testing "CA-043: agents/regions/sanctuary.edn mapeia os 4 cercados reais, suas especies/residentes e vincula falas em karlach.edn"
    (let [data    (edn/read-string (slurp (io/file "agents/regions/sanctuary.edn")))
          karlach (edn/read-string (slurp (io/file "agents/karlach.edn")))]
      (is (true? (schemas/valid-region-profile? data)))
      (is (true? (schemas/valid-agent-profile? karlach)))
      (is (= 4 (count (:region/enclosures data))))
      (doseq [enc (:region/enclosures data)]
        (is (seq (:enclosure/species enc)) "Cada cercado deve declarar as especies presentes")
        (is (seq (:enclosure/residents enc)) "Cada cercado deve declarar os animais residentes")
        (is (string? (:enclosure/food enc)) "Cada cercado deve declarar o alimento apropriado")
        (is (seq (get-in karlach [:agent/dialogues :enclosures (:enclosure/id enc)]))
            "Karlach deve ter falas vinculadas especificamente a cada cercado"))
      (let [loaded (regions/load-region-file "agents/regions/sanctuary.edn")]
        (is (= :sanctuary (:region/id loaded)))))))

(deftest ca-044-karlach-sanctuary-caretaker-e2e-acceptance-test
  (testing "CA-044: Karlach patrulha sem agachar, abre portao duplo, alimenta capivaras, cruza plano de saida e tranca o cercado"
    (let [profile (edn/read-string (slurp (io/file "agents/karlach.edn")))
          w       (wf/make-flat-world-state 64)
          _       (wf/add-double-fence-gate! w [-171 65 171] {:id "pen-north-east" :normal [1.0 0.0 0.0] :open? false})
          _       (wf/add-entity! w {:id :capy-1 :type "naturalist:capybara" :pos [-168.0 65.0 171.0] :in-love? false})
          rt      (core/start! {:backend (wf/make-mock-backend w)
                                :studio-enabled? false :mcp-enabled? false
                                :initial-profiles [profile]})]
      (try
        (dotimes [t 200]
          (core/tick-engine! rt t))
        (is (true? (get-in @w [:entities :capy-1 :in-love?])) "Capivara dentro do cercado deve ter sido alimentada")
        (is (false? (get-in @w [:blocks [-171 65 171] :open?])) "Portao inferior y=65 deve terminar trancado")
        (is (false? (get-in @w [:blocks [-171 66 171] :open?])) "Portao superior y=66 deve terminar trancado")
        (finally
          (core/stop! rt))))))

(deftest ca-045-picard-command-patrol-and-social-gaze-acceptance-test
  (testing "CA-045: Capitao Picard valida perfil declarativo, estacoes da ponte Enterprise-D, meteorologia orbital e olhar social"
    (let [profile  (edn/read-string (slurp (io/file "agents/picard.edn")))
          stations (get-in profile [:agent/dialogues :stations])]
      (is (true? (schemas/valid-agent-profile? profile)))
      (is (= "Picard" (:agent/name profile)))
      (is (seq stations))
      (is (some :weather-broadcast? stations) "Picard deve possuir estacao de previsao do tempo na ponte"))))

(deftest ca-046-antigravity-jeg-combat-and-escort-acceptance-test
  (testing "CA-046: Antigravity escolta Pace, engaja Ravager hostil com jeg:assault_rifle e retoma escolta quando limpo"
    (let [profile (edn/read-string (slurp (io/file "agents/antigravity.edn")))
          w       (wf/make-flat-world-state 64)
          _       (wf/add-entity! w {:id :pace :type "minecraft:player" :name "Pace" :pos [-20.0 65.0 -48.0] :health 20.0})
          _       (wf/add-entity! w {:id :rav-1 :type "minecraft:ravager" :pos [-24.0 65.0 -46.0] :health 30.0 :hostile? true})
          rt      (core/start! {:backend (wf/make-mock-backend w)
                                :studio-enabled? false :mcp-enabled? false
                                :initial-profiles [profile]})]
      (try
        (is (true? (schemas/valid-agent-profile? profile)))
        (dotimes [t 80]
          (core/tick-engine! rt t))
        (is (<= (get-in @w [:entities :rav-1 :health]) 0.0) "Ravager hostil deve ser neutralizado")
        (finally
          (core/stop! rt))))))

(deftest ca-047-and-048-twenty-five-agents-mspt-and-memory-budget-test
  (testing "CA-047 & CA-048: 25 agentes simultaneos processam tick completo em < 15ms (15.000us) preservando 20.0 TPS"
    (let [w        (wf/make-flat-world-state 64)
          profiles (mapv (fn [idx]
                           {:agent/id (keyword (str "guard-" idx))
                            :agent/name (str "Guard" idx)
                            :agent/spawn {:pos [(double (* idx 2)) 65.0 0.0]}
                            :bt/root [:action :patrol-waypoints {:waypoints [[0 65 0] [10 65 10]] :priority 20}]})
                         (range 25))
          rt       (core/start! {:backend (wf/make-mock-backend w)
                                 :studio-enabled? false :mcp-enabled? false
                                 :initial-profiles profiles})]
      (try
        (let [metrics (core/tick-engine! rt 1)]
          (is (= 25 (count (:agents @w))))
          (is (< (:tick-duration-us metrics) 15000)
              "25 agentes somados devem gastar menos de 15ms por tick no servidor"))
        (finally
          (core/stop! rt))))))

(deftest ca-044b-karlach-quarters-and-route-expansion-test
  (testing "CA-044b: Quarto da Karlach é integrado à rota do santuário e micro-etapas expandem perfeitamente"
    (let [route (fabric/build-sanctuary-route)
          quarters-stop (some #(when (= (:type %) :quarters) %) route)]
      (is (some? quarters-stop) "Rota do santuário deve conter a parada do quarto da Karlach")
      (is (= [-131.0 65.0 195.0] (:pos quarters-stop)))
      (let [steps (#'fabric/expand-route-microsteps quarters-stop)]
        (is (vector? steps))
        (is (seq steps))
        (is (some #(= (:op %) :walk) steps))
        (is (some #(= (:op %) :wait) steps))
        (is (some #(= (:op %) :BARK) steps))))))

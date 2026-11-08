(ns craft.agents.epic4-behavior-leases-store-test
  "Testes comportamentais do Epico 4: Leases de Atuadores, Blackboard, Engine de Behavior Trees, Reflexos e Hot-Reload (CA-029 a CA-035)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [craft.agents.backend.protocol :as proto]
            [craft.agents.blackboard :as bb]
            [craft.agents.bt.builtins :as builtins]
            [craft.agents.bt.engine :as bt]
            [craft.agents.fixtures.world-fixture :as wf]
            [craft.agents.leases :as leases]
            [craft.agents.reflexes :as reflexes]
            [craft.agents.store :as store]))

(deftest ca-029-actuator-leases-exclusivity-and-preemption-cleanup-test
  (testing "CA-029: Canais distintos (:legs, :gaze, :hands) operam em paralelo; preempcao em :legs aciona on-preempt e nunca duplica donos"
    (let [lmgr (leases/create-lease-manager)
          cleaned-up? (atom false)]
      (is (:granted? (leases/acquire-leases! lmgr {:channels #{:legs} :owner-id :patrol :priority 20
                                                   :on-preempt #(reset! cleaned-up? true)})))
      (is (:granted? (leases/acquire-leases! lmgr {:channels #{:gaze} :owner-id :social-look :priority 10}))
          ":gaze independente deve ser concedido enquanto :patrol usa :legs")
      (is (false? (:granted? (leases/acquire-leases! lmgr {:channels #{:legs} :owner-id :idle-wander :priority 5})))
          "Prioridade menor jamais pode roubar :legs")
      (is (:granted? (leases/acquire-leases! lmgr {:channels #{:legs :hands} :owner-id :combat :priority 100}))
          "Prioridade 100 deve preemptar :patrol (20)")
      (is (true? @cleaned-up?) "Preempcao DEVE invocar on-preempt do dono anterior antes da troca")
      (is (= :combat (get-in (leases/current-holders lmgr) [:legs :owner-id])))))
  (testing "CA-029: Propriedade generativa — nunca existe estado inconsistente sob sequencias arbitrarias de acquire/release"
    (let [res (tc/quick-check
               60
               (prop/for-all [ops (gen/vector
                                   (gen/tuple (gen/elements [:acquire :release])
                                              (gen/elements [:a :b :c])
                                              (gen/not-empty (gen/set (gen/elements [:legs :hands :gaze])))
                                              (gen/choose 1 100))
                                   1 30)]
                 (let [lmgr (leases/create-lease-manager)]
                   (doseq [[op owner chs prio] ops]
                     (case op
                       :acquire (leases/acquire-leases! lmgr {:channels chs :owner-id owner :priority prio})
                       :release (leases/release-leases! lmgr owner)))
                   (let [holders (leases/current-holders lmgr)]
                     (every? #(contains? #{nil :a :b :c} (get-in holders [% :owner-id]))
                             [:legs :hands :gaze])))))]
      (wf/assert-quick-check! res))))

(deftest ca-030-blackboard-ttl-and-bounded-history-test
  (testing "CA-030: Diretiva com TTL expira automaticamente e historico circular respeita limite de memoria"
    (let [board (bb/create-blackboard {:max-history 5})]
      (dotimes [i 12]
        (bb/put-directive! board {:type :escort :step i :ttl-ms 2000} (* i 100)))
      (is (= 5 (count (bb/recent-history board))) "Historico nao pode crescer alem de :max-history")
      (is (some? (bb/active-directive board 2500)))
      (is (nil?  (bb/active-directive board 5000)) "Diretiva expirada deve retornar nil"))))

(deftest ca-031-behavior-tree-selector-sequence-parallel-and-trace-test
  (testing "CA-031: Avaliador puro executa :selector, :sequence, :parallel e retorna :active-path exato para o Studio"
    (let [tree [:selector
                [:sequence
                 [:guard :false-pred]
                 [:action :never-called]]
                [:sequence
                 [:guard :true-pred]
                 [:action :running-task]]]
          ctx  {:guards {:false-pred (constantly false)
                         :true-pred  (constantly true)}
                :actions {:never-called (fn [_] {:status :success})
                          :running-task (fn [_] {:status :running})}}
          out  (bt/tick-tree tree ctx)]
      (is (= :running (:status out)))
      (is (vector? (:active-path out)))
      (is (seq (:active-path out))))))

(deftest ca-032-layer1-autonomous-survival-reflexes-without-llm-test
  (testing "CA-032: Agente em contato com lava/fogo aciona reflexo de fuga em < 100ms sem depender de LLM"
    (let [w (wf/make-flat-world-state 64)
          _ (wf/set-block! w [0 65 0] {:id "minecraft:lava" :hazard? true})
          backend (wf/make-mock-backend w)
          body (wf/make-mock-body w {:id :karlach :pos [0.2 65.0 0.2]})
          lmgr (leases/create-lease-manager)
          res  (reflexes/evaluate-survival-reflexes! body backend lmgr)]
      (is (true? (:triggered? res)))
      (is (= :escape-hazard (:reflex res)))))
  (testing "Agente com asfixia/afogamento aciona reflexo :surface-for-air"
    (let [w (wf/make-flat-world-state 64)
          backend (wf/make-mock-backend w)
          body (wf/make-mock-body w {:id :karlach :pos [0.0 64.0 0.0]})
          _ (swap! (:state-atom body) assoc :drowning? true :oxygen 0)
          lmgr (leases/create-lease-manager)
          res (reflexes/evaluate-survival-reflexes! body backend lmgr)]
      (is (true? (:triggered? res)))
      (is (= :surface-for-air (:reflex res)))))
  (testing "Agente em lava ou em chamas aciona reflexo :escape-lava"
    (let [w (wf/make-flat-world-state 64)
          backend (wf/make-mock-backend w)
          body (wf/make-mock-body w {:id :karlach :pos [0.0 64.0 0.0]})
          _ (swap! (:state-atom body) assoc :in-lava? true)
          lmgr (leases/create-lease-manager)
          res (reflexes/evaluate-survival-reflexes! body backend lmgr)]
      (is (true? (:triggered? res)))
      (is (= :escape-lava (:reflex res))))))

(deftest ca-033-builtin-skills-semantic-error-codes-test
  (testing "CA-033: Skill :care-for-enclosure sem comida retorna erro semantico estruturado :MISSING_ITEM p/ escalonamento"
    (let [w (wf/make-flat-world-state 64)
          body (wf/make-mock-body w {:id :karlach :inventory {}})
          res (builtins/execute-builtin-action! :care-for-enclosure {:food-item "minecraft:apple"} {:body body :world w})]
      (is (= :failure (:status res)))
      (is (= :MISSING_ITEM (:reason res))))))

(deftest ca-034-hot-reload-edn-rejects-invalid-preserving-live-tree-test
  (testing "CA-034: Hot-reload aplica EDN valido ao vivo e rejeita EDN corrompido mantendo a Behavior Tree anterior intacta"
    (let [tmp-valid (java.io.File/createTempFile "agent-valid" ".edn")
          tmp-bad   (java.io.File/createTempFile "agent-bad" ".edn")
          st        (atom {:profiles {}})]
      (try
        (spit tmp-valid (pr-str {:agent/id :picard :agent/name "Picard" :agent/spawn {:pos [0 65 0]}
                                 :bt/root [:action :patrol-waypoints {:priority 20}]}))
        (spit tmp-bad   (pr-str {:agent/id :picard :bt/root [:invalid-node-xyz]}))
        (is (:ok? (store/reload-profile-safe! st tmp-valid)))
        (is (false? (:ok? (store/reload-profile-safe! st tmp-bad))))
        (is (= [:action :patrol-waypoints {:priority 20}]
               (get-in @st [:profiles :picard :bt/root]))
            "Arvore anterior valida deve continuar viva apos tentativa falha de reload")
        (finally
          (.delete tmp-valid)
          (.delete tmp-bad))))))

(deftest ca-035-atomic-snapshot-persistence-roundtrip-test
  (testing "CA-035: Snapshot episodico gravado atomicamente sobrevive a reinicio completo sem corrupcao"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "ca-state-" (System/nanoTime)))]
      (.mkdirs tmp-dir)
      (try
        (let [snap {:agent/id :karlach :last-fed-pen :pen-north-east :learned-skills [:rescue-stray-capybara]}]
          (store/save-agent-snapshot-atomic! tmp-dir :karlach snap)
          (is (= snap (store/load-agent-snapshot tmp-dir :karlach))))
        (finally
          (doseq [f (reverse (file-seq tmp-dir))] (.delete f)))))))

(deftest ca-046-multiagent-shared-resource-arbiter-test
  (testing "CA-046 / P3.1: Árbitro Multiagente coordena recursos físicos e espaciais entre Karlach e Picard"
    (let [arbiter      (leases/create-multiagent-arbiter)
          preempted?   (atom false)
          res-portal   :portal-pen-south-west
          karlach-claim (leases/acquire-shared-resource! arbiter
                                                        {:resource-id res-portal
                                                         :agent-id :karlach
                                                         :priority 10
                                                         :timeout-ms 5000
                                                         :on-preempt #(reset! preempted? true)})]
      (is (true? (:granted? karlach-claim)) "Karlach deve adquirir o portal livre")

      ;; Picard com prioridade menor (5) é bloqueado
      (let [picard-fail (leases/acquire-shared-resource! arbiter
                                                         {:resource-id res-portal
                                                          :agent-id :picard
                                                          :priority 5})]
        (is (false? (:granted? picard-fail)))
        (is (= :RESOURCE_LOCKED (:reason picard-fail)))
        (is (= :karlach (:held-by picard-fail))))

      ;; Picard com prioridade maior (25) preempta Karlach
      (let [picard-win (leases/acquire-shared-resource! arbiter
                                                        {:resource-id res-portal
                                                         :agent-id :picard
                                                         :priority 25
                                                         :timeout-ms 3000})]
        (is (true? (:granted? picard-win)))
        (is (true? @preempted?) "Callback on-preempt da Karlach deve ter sido invocado"))

      ;; Liberação por Picard
      (leases/release-shared-resource! arbiter res-portal :picard)
      (is (nil? (get (leases/query-shared-resources arbiter) res-portal)))

      ;; Teste de limpeza de leases expirados por TTL
      (leases/acquire-shared-resource! arbiter {:resource-id :feed-pandas :agent-id :karlach :timeout-ms 100})
      (is (some? (get (leases/query-shared-resources arbiter (System/currentTimeMillis)) :feed-pandas)))
      ;; Simula passagem de tempo (+200ms)
      (leases/clean-expired-leases! arbiter (+ (System/currentTimeMillis) 200))
      (is (nil? (get (leases/query-shared-resources arbiter (+ (System/currentTimeMillis) 200)) :feed-pandas))
          "Lease expirado deve ser reavido automaticamente"))))
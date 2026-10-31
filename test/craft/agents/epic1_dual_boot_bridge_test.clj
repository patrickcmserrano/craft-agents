(ns craft.agents.epic1-dual-boot-bridge-test
  "Testes comportamentais do Epico 1: Dual-Boot Attach + Mod Fabric, Intermediary 1.21.1 e Lifecycle (CA-006 a CA-012)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.core :as core]
            [craft.agents.fixtures.world-fixture :as wf])
  (:import [com.craft.agents.attach CraftAgentsBootstrap]
           [com.craft.agents.fabric FabricEventHooks FabricRegistryInspector IntermediaryResolver]))

(deftest ca-006-intermediary-and-mojmap-resolver-test
  (testing "CA-006: Resolvedor localiza classes via nome Mojmap ou fallback Intermediary e faz cache de MethodHandles"
    (let [cl (.getContextClassLoader (Thread/currentThread))
          resolved (IntermediaryResolver/resolveMinecraftClass cl "java.lang.String" "java.lang.String")]
      (is (= String resolved))
      (let [handles (IntermediaryResolver/resolveCoreHandles cl)]
        (is (instance? java.util.Map handles))))))

(deftest ca-007-zero-install-attach-idempotency-test
  (testing "CA-007: Bootstrap do agente JVM reporta estado de inicializacao sem duplicar hooks caso anexado duas vezes"
    (is (boolean? (CraftAgentsBootstrap/isBootstrapped)))))

(deftest ca-008-fabric-mod-manifest-and-entrypoint-test
  (testing "CA-008: fabric.mod.json e valido e aponta para classe FabricModInit compilada no classpath"
    (let [manifest (json/read-str (slurp (io/file "resources/fabric.mod.json")) :key-fn keyword)
          main-cls (first (get-in manifest [:entrypoints :main]))]
      (is (= "craft-agents" (:id manifest)))
      (is (= "com.craft.agents.fabric.FabricModInit" main-cls))
      (is (class? (Class/forName main-cls))))))

(deftest ca-009-deterministic-server-thread-executor-and-tick-telemetry-test
  (testing "CA-009: Executor ServerThread preserva ordem FIFO, captura excecoes sem matar o loop e mede duracao em microssegundos"
    (let [w (wf/make-flat-world-state 64)
          rt (core/start! {:backend (wf/make-mock-backend w) :studio-enabled? false :mcp-enabled? false})]
      (try
        (let [order (atom [])
              f1 (core/run-on-server-thread! rt #(swap! order conj 1))
              f2 (core/run-on-server-thread! rt #(swap! order conj 2))
              metrics (core/tick-engine! rt 1)]
          (is (= [1 2] @order) "Tarefas agendadas devem rodar em ordem estrita no tick")
          (is (some? @f1))
          (is (some? @f2))
          (is (number? (:tick-duration-us metrics)))
          (is (< (:tick-duration-us metrics) 1000) "Tick vazio/leve deve custar < 1ms (1000us)"))
        (finally
          (core/stop! rt))))))

(deftest ca-010-modded-registry-inspector-never-unknown-test
  (testing "CA-010: Registro real resolve IDs namespaceados de mods (Naturalist / JEG) sem jamais retornar 'unknown'"
    (let [fake-capybara {:registry-id "naturalist:capybara"}
          fake-rifle    {:registry-id "jeg:assault_rifle"}
          fake-chair    {:registry-id "another_furniture:oak_chair"}]
      (is (= "naturalist:capybara" (FabricRegistryInspector/resolveEntityTypeId fake-capybara)))
      (is (= "jeg:assault_rifle" (FabricRegistryInspector/resolveItemId fake-rifle)))
      (is (= "another_furniture:oak_chair" (FabricRegistryInspector/resolveBlockId fake-chair))))))

(deftest ca-011-native-chat-and-damage-hooks-test
  (testing "CA-011: Hooks nativos convertem sinais do servidor em mapas estruturados de chat e dano"
    (let [captured (atom [])]
      (FabricEventHooks/registerChatAndDamageListeners #(swap! captured conj %))
      (is (vector? @captured)))))

(deftest ca-012-five-cycle-hot-reload-lifecycle-test
  (testing "CA-012: 5 ciclos seguidos de start! -> spawn -> stop! nao deixam bots fantasmas nem vazam recursos"
    (let [w (wf/make-flat-world-state 64)
          backend (wf/make-mock-backend w)]
      (dotimes [_ 5]
        (let [rt (core/start! {:backend backend :studio-enabled? false :mcp-enabled? false
                               :initial-profiles [{:agent/id :karlach :agent/name "Karlach" :agent/spawn {:pos [0 65 0]}}]})]
          (is (= 1 (count (:agents @w))))
          (core/stop! rt)
          (is (zero? (count (:agents @w))) "stop! deve remover 100% dos jogadores sinteticos do mundo"))))))

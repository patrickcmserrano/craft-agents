(ns craft.agents.epic0-foundation-test
  "Testes comportamentais do Epico 0: Fundacao, Build, Fronteira Dual-Backend, Malli e Event Bus (CA-001 a CA-005)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [craft.agents.backend.protocol :as proto]
            [craft.agents.events :as events]
            [craft.agents.fixtures.world-fixture :as wf]
            [craft.agents.schemas :as schemas])
  (:import [com.craft.agents.api CollisionType PathNodeStep PathNodeStep$StepAction PathResult PathResult$Status PortalGroup]))

(deftest ca-001-repository-and-java21-bytecode-test
  (testing "CA-001: Estrutura do projeto existe e classes Java estao compiladas com target <= Java 21 (major version <= 65)"
    (is (.exists (io/file "deps.edn")))
    (is (.exists (io/file "Makefile")))
    (is (.exists (io/file "resources/fabric.mod.json")))
    (let [cls-file (io/file "target/classes/com/craft/agents/api/VoxelAccessor.class")]
      (is (.exists cls-file) "VoxelAccessor.class deve existir em target/classes")
      (with-open [in (java.io.DataInputStream. (io/input-stream cls-file))]
        (is (= (unchecked-int 0xCAFEBABE) (.readInt in)))
        (.readUnsignedShort in) ;; minor
        (let [major (.readUnsignedShort in)]
          (is (= 65 major) "Bytecode deve ser estritamente Java 21 (major 65) p/ compatibilidade universal com Fabric 1.21+"))))))

(deftest ca-002-java-dual-backend-api-contracts-test
  (testing "CA-002: Contratos Java VoxelAccessor, PortalGroup e PathResult operam sem dependencia de classes Minecraft"
    (let [w (wf/make-flat-world-state 64)
          _ (wf/add-double-fence-gate! w [-171 65 171] {:id "pen-ne" :normal [1.0 0.0 0.0] :open? false})
          vox (wf/->voxel-accessor w)
          portal (.getPortalAt vox -171 66 171)
          step (PathNodeStep. -171 65 171 PathNodeStep$StepAction/TRAVERSE_PORTAL portal)
          result (PathResult. PathResult$Status/SUCCESS [step] 2.5 12 180)]
      (is (= CollisionType/SOLID (.getCollisionType vox 0 64 0)))
      (is (= CollisionType/EMPTY (.getCollisionType vox 0 65 0)))
      (is (= CollisionType/PORTAL (.getCollisionType vox -171 66 171)))
      (is (instance? PortalGroup portal))
      (is (= "pen-ne" (.id portal)))
      (is (true? (.canBotToggle portal)))
      (is (= PathResult$Status/SUCCESS (.status result)))
      (is (= 1 (count (.steps result)))))))

(deftest ca-003-clojure-world-and-body-protocols-test
  (testing "CA-003: Protocolos IWorldBackend e IAgentBody suportam ciclo completo spawn -> motor -> bau -> despawn em memoria"
    (let [w (wf/make-flat-world-state 64)
          _ (swap! w assoc-in [:chests [-170 65 175]] {"minecraft:apple" 10})
          backend (wf/make-mock-backend w)
          body (proto/spawn-agent! backend {:agent/id :karlach
                                            :agent/name "Karlach"
                                            :agent/spawn {:pos [-175.0 65.0 180.0]}})]
      (is (= #{:karlach} (set (keys (proto/active-agents backend)))))
      (proto/apply-motor-controls! body {:forward 1.0 :sprint? true :sneak? false :yaw 0.0 :pitch 0.0})
      (is (> (nth (:pos (proto/body-state body)) 2) 180.0) "Deve avancar no eixo Z sem estar agachada")
      (is (false? (:sneaking? (proto/body-state body))))
      (is (= 4 (proto/transfer-container-items! body [-170 65 175] :withdraw "minecraft:apple" 4)))
      (is (= 4 (get-in (proto/body-state body) [:inventory "minecraft:apple"])))
      (is (true? (proto/despawn-agent! backend :karlach)))
      (is (empty? (proto/active-agents backend))))))

(deftest ca-004-malli-schemas-and-json-schema-export-test
  (testing "CA-004: Validacao estrita de coordenadas, canais de lease, nos recursivos de BT, perfis e geracao JSON Schema MCP"
    (is (true? (schemas/valid-coord3d? [-171.5 65.0 171.0])))
    (is (false? (schemas/valid-coord3d? [-171.5 65.0])))
    (is (true? (schemas/valid-lease-channel? :legs)))
    (is (true? (schemas/valid-lease-channel? :hands)))
    (is (true? (schemas/valid-lease-channel? :gaze)))
    (is (false? (schemas/valid-lease-channel? :wings)))
    (is (true? (schemas/valid-bt-node?
                [:selector
                 [:sequence
                  [:guard :hostile-nearby? {:radius 12.0}]
                  [:action :engage-hostiles {:priority 100}]]
                 [:action :patrol-waypoints {:priority 20}]])))
    (is (false? (schemas/valid-bt-node? [:unknown-node-type {:foo 1}]))
        "Nos desconhecidos devem ser rejeitados em fail-closed")
    (let [js (schemas/->json-schema schemas/SceneDigest)]
      (is (map? js))
      (is (= "object" (:type js))))
    (let [prop-res (tc/quick-check
                    50
                    (prop/for-all [x (gen/double* {:min -30000000.0 :max 30000000.0 :NaN? false :infinite? false})
                                   y (gen/double* {:min -64.0 :max 320.0 :NaN? false :infinite? false})
                                   z (gen/double* {:min -30000000.0 :max 30000000.0 :NaN? false :infinite? false})]
                      (schemas/valid-coord3d? [x y z])))]
      (wf/assert-quick-check! prop-res))))

(deftest ca-005-non-blocking-event-bus-burst-test
  (testing "CA-005: Barramento pub/sub entrega eventos por topico, isola excecoes de handlers e nao bloqueia em rajada de 10.000 eventos"
    (let [bus (events/create-bus {:buffer-size 1024})
          received (atom [])
          bad-tok (events/subscribe! bus :player-chat (fn [_] (throw (RuntimeException. "handler quebrado"))))
          ok-tok  (events/subscribe! bus :player-chat (fn [ev] (swap! received conj (:message ev))))
          t0 (System/nanoTime)]
      (try
        (dotimes [i 10000]
          (events/publish! bus {:topic :tick :tick i}))
        (let [elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
          (is (< elapsed-ms 25.0) "Publicar 10k eventos nunca deve bloquear a ServerThread"))
        (events/publish! bus {:topic :player-chat :speaker "Pace" :message "Karlach vem aqui"})
        (Thread/sleep 40)
        (is (= ["Karlach vem aqui"] @received) "Excecao em um subscriber nao pode impedir entrega aos demais")
        (events/unsubscribe! bus bad-tok)
        (events/unsubscribe! bus ok-tok)
        (finally
          (events/shutdown-bus! bus))))))

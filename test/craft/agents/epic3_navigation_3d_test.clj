(ns craft.agents.epic3-navigation-3d-test
  "Testes comportamentais do Epico 3: PathSolver3D Zero-Allocation, Portais Duplos e traverse-portal Transacional (CA-021 a CA-028)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [craft.agents.backend.protocol :as proto]
            [craft.agents.fixtures.world-fixture :as wf]
            [craft.agents.leases :as leases]
            [craft.agents.nav :as nav]
            [craft.agents.nav-follower :as follower]
            [craft.agents.nav-portals :as portals]
            [craft.agents.regions :as regions])
  (:import [com.craft.agents.api CollisionType PathNodeStep$StepAction PathResult$Status]
           [com.craft.agents.fabric ChunkSnapshotVoxelAccessor]
           [com.craft.agents.nav LongHashSet MovementCosts PackedPos PathSolver3D PortalDetector PrimitiveMinHeap]))

(deftest ca-021-packed-pos-heap-and-longset-generative-test
  (testing "CA-021: PackedPos preserva exatamente (x, y, z) em todo o dominio do Minecraft (incluindo coordenadas negativas)"
    (let [res (tc/quick-check
               200
               (prop/for-all [x (gen/choose -29999984 29999984)
                              y (gen/choose -64 320)
                              z (gen/choose -29999984 29999984)]
                 (let [p (PackedPos/pack x y z)]
                   (and (= x (PackedPos/unpackX p))
                        (= y (PackedPos/unpackY p))
                        (= z (PackedPos/unpackZ p))))))]
      (wf/assert-quick-check! res)))
  (testing "CA-021: PrimitiveMinHeap extrai elementos em ordem monotona crescente de fScore e suporta updateOrPush (decrease-key)"
    (let [heap (PrimitiveMinHeap. 32)
          p1 (PackedPos/pack 10 65 10)
          p2 (PackedPos/pack 20 65 20)
          p3 (PackedPos/pack 30 65 30)]
      (.push heap p1 15.0)
      (.push heap p2 8.0)
      (.push heap p3 22.0)
      (.updateOrPush heap p3 3.5)
      (is (= p3 (.popMin heap)))
      (is (= p2 (.popMin heap)))
      (is (= p1 (.popMin heap)))
      (is (.isEmpty heap))))
  (testing "CA-021: LongHashSet adiciona e consulta chaves 64-bit sem falsos negativos"
    (let [s (LongHashSet. 64)]
      (is (true? (.add s -9999999999999)))
      (is (false? (.add s -9999999999999)))
      (is (true? (.contains s -9999999999999)))
      (is (= 1 (.size s))))))

(deftest ca-022-off-thread-chunk-snapshot-concurrency-test
  (testing "CA-022: Snapshot imutavel suporta leitura simultanea por 8 worker threads sem erro de concorrencia"
    (let [snap (ChunkSnapshotVoxelAccessor/captureRadius {:mock-world true} -175 180 4)
          futs (mapv (fn [idx]
                       (future (.getCollisionType snap (+ -175 idx) 65 180)))
                     (range 8))]
      (is (= 8 (count (mapv deref futs)))))))

(deftest ca-023-double-stacked-gate-portal-detector-test
  (testing "CA-023: PortalDetector agrupa portao duplo (y=65 e y=66) como PortalGroup transitavel e rejeita porta de ferro trancada"
    (let [w (wf/make-flat-world-state 64)
          _ (wf/add-double-fence-gate! w [-171 65 171] {:id "sanctuary-ne" :open? false})
          _ (wf/set-block! w [-160 65 171] {:id "minecraft:iron_door" :collision CollisionType/SOLID :open? false})
          _ (wf/set-block! w [-160 66 171] {:id "minecraft:iron_door" :collision CollisionType/SOLID :open? false})
          vox (wf/->voxel-accessor w)
          double-gate (PortalDetector/detectAt vox -171 65 171)
          iron-door   (PortalDetector/detectAt vox -160 65 171)]
      (is (some? double-gate) "Deve reconhecer portao duplo empilhado onde o Mineflayer falhava")
      (is (= 2 (alength (.packedBlockPositions double-gate))))
      (is (true? (.canBotToggle double-gate)))
      (is (or (nil? iron-door) (false? (.canBotToggle iron-door)))
          "Porta de ferro sem acionador nao pode ser considerada destrancavel pelo bot"))))

(deftest ca-024-pathsolver3d-mazes-lava-and-double-gates-test
  (testing "CA-024: PathSolver3D desvia de fosso de lava, sobe escadas e atravessa cercado fechado por portao duplo"
    (let [w (wf/make-flat-world-state 64)
          ;; Cerca fechada ao redor de [-168 65 171] exceto pelo portao duplo em [-171 65 171]
          _ (doseq [z (range 169 174)
                    y [65 66]]
              (when (not= z 171)
                (wf/set-block! w [-171 y z] {:id "minecraft:oak_fence" :collision CollisionType/SOLID :height 1.5})))
          _ (wf/add-double-fence-gate! w [-171 65 171] {:id "pen-ne" :normal [1.0 0.0 0.0] :open? false})
          ;; Lava mortal em [-173 64 171] obrigando contornar antes de alinhar com o portao
          _ (wf/set-block! w [-173 64 171] {:id "minecraft:lava" :collision CollisionType/LIQUID :hazard? true})
          vox (wf/->voxel-accessor w)
          solver (PathSolver3D.)
          res (.solveToRadius solver vox -175 65 171 -169 65 171 1.0 (MovementCosts/defaults) 5000)]
      (is (= PathResult$Status/SUCCESS (.status res)))
      (is (not-any? #(and (= -173 (.x %)) (= 171 (.z %))) (.steps res))
          "Rota jamais deve pisar sobre lava")
      (is (some #(= PathNodeStep$StepAction/TRAVERSE_PORTAL (.action %)) (.steps res))
          "Rota deve incluir passo explicito TRAVERSE_PORTAL para o portao duplo"))))

(deftest ca-025-string-pulling-and-async-pool-test
  (testing "CA-025: String-pulling simplifica nos colineares redundantes e plan-path-async executa fora da thread chamadora"
    (let [w (wf/make-flat-world-state 64)
          vox (wf/->voxel-accessor w)
          caller-thread (Thread/currentThread)
          fut (nav/plan-path-async vox [0 65 0] {:to [10 65 0] :radius 0.5} {})
          res @fut]
      (is (= PathResult$Status/SUCCESS (.status res)))
      (is (not= caller-thread (:worker-thread (meta res))))
      (let [smoothed (nav/smooth-path-string-pulling vox (.steps res))]
        (is (<= (count smoothed) (count (.steps res))))))))

(deftest ca-026-continuous-steering-without-teleport-cheat-test
  (testing "CA-026: Seguidor de rota gera vetor de controle continuo sem alterar coordenadas na mao e detecta stuck > 1500ms"
    (let [body-st {:pos [-174.12 65.0 171.08] :vel [0.0 0.0 0.0] :yaw 0.0 :pitch 0.0}
          ctrl (follower/compute-steering-controls body-st {:x -172 :y 65 :z 171})]
      (is (nil? (:pos ctrl)) "Proibido teleportar sub-pixel estilo fullStop do Mineflayer")
      (is (pos? (:forward ctrl)))
      (is (true? (follower/stuck? {:last-progress-ms 1000 :target [-172 65 171]} 2600)))
      (is (false? (follower/stuck? {:last-progress-ms 1000 :target [-172 65 171]} 1800))))))

(deftest ca-027-transactional-portal-traversal-and-preemption-postcondition-test
  (testing "CA-027: Travessia normal abre y=65 e y=66, cruza o plano geometrico e tranca ambos os portoes na saida"
    (is (false? (portals/crossed-portal-plane? [-171.4 65.0 171.5] [-170.5 65.0 171.5] [1.0 0.0 0.0] 0.85)))
    (is (true?  (portals/crossed-portal-plane? [-169.4 65.0 171.5] [-170.5 65.0 171.5] [1.0 0.0 0.0] 0.85)))
    (let [w (wf/make-flat-world-state 64)
          _ (wf/add-double-fence-gate! w [-171 65 171] {:id "pen-ne" :normal [1.0 0.0 0.0] :open? false})
          body (wf/make-mock-body w {:id :karlach :pos [-172.0 65.0 171.5]})
          lmgr (leases/create-lease-manager)
          ctx  {:portal-blocks [[-171 65 171] [-171 66 171]]
                :center [-170.5 65.0 171.5]
                :normal [1.0 0.0 0.0]
                :priority 40}]
      ;; Simula preempcao de emergencia (combate prioridade 100) exatamente apos abrir os portoes!
      (portals/step-portal-traversal! (assoc ctx :phase :open) body lmgr)
      (leases/acquire-leases! lmgr {:channels #{:legs :hands} :owner-id :combat-emergency :priority 100})
      (is (false? (get-in @w [:blocks [-171 65 171] :open?]))
          "Mesmo interrompida por combate no meio da porta, a pos-condicao finally DEVE fechar y=65!")
      (is (false? (get-in @w [:blocks [-171 66 171] :open?]))
          "Mesmo interrompida por combate no meio da porta, a pos-condicao finally DEVE fechar y=66!"))))

(deftest ca-028-semantic-region-enclosure-lookup-test
  (testing "CA-028: Regioes semanticas carregam sanctuary.edn e identificam cercado exato de qualquer coordenada"
    (let [reg (regions/load-region-file "agents/regions/sanctuary.edn")]
      (is (= :pen-north-east (:enclosure/id (regions/enclosure-at reg [-166 65 171]))))
      (is (= :pen-south-west (:enclosure/id (regions/enclosure-at reg [-184 65 189]))))
      (is (nil? (regions/enclosure-at reg [-175 65 180])) "Corredor central nao pertence ao interior das jaulas"))))

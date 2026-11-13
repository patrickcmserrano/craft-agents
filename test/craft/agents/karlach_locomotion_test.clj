(ns craft.agents.karlach-locomotion-test
  "Locomocao da Karlach: A* recupera partida dentro de solido (CA-062)."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.fixtures.world-fixture :as wf])
  (:import [com.craft.agents.api CollisionType PathNodeStep$StepAction PathResult$Status]
           [com.craft.agents.nav GoalBlock GoalNear MovementCapabilities PackedPos PathSolver3D]))

(defn- stone! [w coords]
  (doseq [[x y z] coords]
    (wf/set-block! w [x y z] {:id "minecraft:stone" :collision CollisionType/SOLID})))

(defn- step-xyz [s] [(.x s) (.y s) (.z s)])

(deftest ca-062-recover-start-inside-solid-test
  (let [caps (MovementCapabilities/defaults)
        solver (PathSolver3D.)]
    (testing "CA-062: morro real — partida em (0,66,0) dentro de sólido resolve SUCCESS saindo pelo topo ou coluna vizinha"
      (let [w (wf/make-flat-world-state 64)
            _ (stone! w [[0 65 0] [0 66 0] [0 67 0]])
            vox (wf/->voxel-accessor w)
            res (.solve solver vox 0 66 0 (GoalBlock. 6 65 0) caps 5000)
            first-step (first (.steps res))
            [fx fy fz] (step-xyz first-step)]
        (is (= PathResult$Status/SUCCESS (.status res)))
        (is (or (= [0 68 0] [fx fy fz])
                (and (not= [fx fz] [0 0])
                     (.canStandOn caps vox fx (dec fy) fz)))
            "primeiro passo em (0,68,0) ou coluna vizinha em pé")
        (is (= PathNodeStep$StepAction/JUMP_UP (.action first-step))
            "prefixo mais alto que a partida original é JUMP_UP")
        (let [recovered (PathSolver3D/recoverStart vox caps 0 66 0)]
          (is (= (PackedPos/pack 0 68 0) recovered)))))
    (testing "CA-062: recoverStart com partida livre devolve a própria posição"
      (let [w (wf/make-flat-world-state 64)
            vox (wf/->voxel-accessor w)]
        (is (= (PackedPos/pack 3 65 -2) (PathSolver3D/recoverStart vox caps 3 65 -2)))))
    (testing "CA-062: partida enterrada (coluna e 8 vizinhas cheias até y+3) => NO_PATH e recoverStart = -1"
      (let [w (wf/make-flat-world-state 64)
            _ (stone! w (for [x [-1 0 1] z [-1 0 1] y (range 65 71)] [x y z]))
            vox (wf/->voxel-accessor w)
            res (.solve solver vox 0 66 0 (GoalBlock. 6 65 0) caps 5000)]
        (is (= -1 (PathSolver3D/recoverStart vox caps 0 66 0)))
        (is (= PathResult$Status/NO_PATH (.status res)))))
    (testing "CA-062: partida livre mantém a rota idêntica (10 passos WALK, sem prefixo)"
      (let [w (wf/make-flat-world-state 64)
            vox (wf/->voxel-accessor w)
            res (.solve solver vox 0 65 0 (GoalNear. 10 65 0 0.5) caps 5000)
            steps (vec (.steps res))]
        (is (= PathResult$Status/SUCCESS (.status res)))
        (is (= [1 65 0] (step-xyz (first steps))))
        (is (every? #(= PathNodeStep$StepAction/WALK (.action %)) steps))
        (is (= (mapv #(vector % 65 0) (range 1 11)) (mapv step-xyz steps)))))))

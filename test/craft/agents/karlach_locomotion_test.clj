(ns craft.agents.karlach-locomotion-test
  "Locomocao da Karlach: A* recupera partida dentro de solido (CA-062)."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.locomotion :as loco]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.fixtures.world-fixture :as wf])
  (:import [com.craft.agents.api CollisionType PathNodeStep$StepAction PathResult$Status]
           [com.craft.agents.nav GoalBlock GoalNear GroundSnap MovementCapabilities PackedPos PathSolver3D]))

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

(deftest ca-063-ground-snap-test
  (testing "CA-063: findStandableY no mundo plano (chao 64)"
    (let [vox (wf/->voxel-accessor (wf/make-flat-world-state 64))]
      (is (= 65 (GroundSnap/findStandableY vox 0 65 0 4 6)))
      (is (= 65 (GroundSnap/findStandableY vox 0 62 0 3 6)) "enterrado, sobe 3")
      (is (= 65 (GroundSnap/findStandableY vox 0 70 0 4 5)) "no ar, desce 5")))
  (testing "CA-063: morro sobe para 68"
    (let [w (wf/make-flat-world-state 64)
          _ (stone! w [[0 65 0] [0 66 0] [0 67 0]])
          vox (wf/->voxel-accessor w)]
      (is (= 68 (GroundSnap/findStandableY vox 0 66 0 4 6)))))
  (testing "CA-063: sem solucao dentro dos limites => MIN_VALUE"
    (let [vox (wf/->voxel-accessor (wf/make-flat-world-state 64))]
      (is (= Integer/MIN_VALUE (GroundSnap/findStandableY vox 0 62 0 2 6)))
      (is (= Integer/MIN_VALUE (GroundSnap/findStandableY vox 0 75 0 4 5))))))

(deftest ca-063-query-ground-y-test
  (testing "CA-063: query-ground-y traduz a resposta da ponte"
    (let [sent (atom nil)]
      (with-redefs [transport/send-bridge-cmd! (fn [c] (reset! sent c) "OK|69")]
        (is (= 69.0 (nav/query-ground-y [-596.3 67.0 -592.8])))
        (is (= "GROUND|-597|67|-593" @sent))))
    (with-redefs [transport/send-bridge-cmd! (fn [_] "ERR|NO_GROUND")]
      (is (nil? (nav/query-ground-y [0.0 65.0 0.0]))))
    (with-redefs [transport/send-bridge-cmd! (fn [_] nil)]
      (is (nil? (nav/query-ground-y [0.0 65.0 0.0]))))
    (with-redefs [transport/send-bridge-cmd! (fn [_] "OK|abc")]
      (is (nil? (nav/query-ground-y [0.0 65.0 0.0]))))))

(defn- hdist [[x _ z] {nx :x nz :z}]
  (let [dx (- (+ nx 0.5) x) dz (- (+ nz 0.5) z)]
    (Math/sqrt (+ (* dx dx) (* dz dz)))))

(deftest ca-064-advance-path-test
  (testing "descida: mantem o y ate chegar a <= 0,35 do no"
    (let [node {:x 1 :y 66 :z 0 :action :DROP_DOWN}
          r1 (loco/advance-path {:pos [0.5 69.0 0.5] :path [node] :speed 0.24})]
      (is (= 69.0 (second (:pos r1))))
      (is (> (first (:pos r1)) 0.5))
      (is (false? (:arrived? r1)))
      (loop [r r1 n 0]
        (let [d (hdist (:pos r) node)]
          (cond
            (> n 50) (is false "nao chegou")
            (:arrived? r) (is (= 66.0 (second (:pos r))))
            :else (do (is (= 69.0 (second (:pos r))) "y so muda ao chegar")
                      (is (> d 0.35))
                      (recur (loco/advance-path {:pos (:pos r) :path (:path r) :speed 0.24}) (inc n))))))))
  (testing "subida: y do no ja no primeiro tick"
    (let [r (loco/advance-path {:pos [0.5 69.0 0.5]
                                :path [{:x 1 :y 70 :z 0 :action :JUMP_UP}] :speed 0.24})]
      (is (= 70.0 (second (:pos r))))))
  (testing "sem tick ocioso: consome o no 1 e anda em direcao ao 2"
    (let [n1 {:x 0 :y 65 :z 0 :action :WALK}
          n2 {:x 3 :y 65 :z 0 :action :WALK}
          r (loco/advance-path {:pos [0.5 65.0 0.5] :path [n1 n2] :speed 0.24})]
      (is (= [n2] (:path r)))
      (is (< (Math/abs (- (first (:pos r)) 0.74)) 1e-9))
      (is (false? (:arrived? r)))
      (is (= n2 (:node r)))))
  (testing "nunca ultrapassa o no"
    (let [r (loco/advance-path {:pos [0.5 65.0 0.5]
                                :path [{:x 1 :y 65 :z 0 :action :WALK}] :speed 5.0})]
      (is (= [1.5 65.0 0.5] (:pos r)))
      (is (true? (:arrived? r)))
      (is (empty? (:path r)))))
  (testing "SWIM: y do no + 0,2"
    (let [r (loco/advance-path {:pos [0.5 63.0 0.5]
                                :path [{:x 3 :y 63 :z 0 :action :SWIM}] :speed 0.24})]
      (is (< (Math/abs (- (second (:pos r)) 63.2)) 1e-9))))
  (testing "yaw mantido sem deslocamento"
    (let [r (loco/advance-path {:pos [0.5 65.0 0.5] :path [] :speed 0.24 :yaw 42.0})]
      (is (= 42.0 (double (:yaw r))))
      (is (true? (:arrived? r))))))

(deftest ca-064-needs-replan-test
  (let [far [{:x 10 :y 65 :z 0 :action :WALK}]]
    (testing "lider subiu 4 blocos"
      (is (true? (loco/needs-replan? {:path far :pos [5.5 65.0 0.5]
                                      :target [0.0 70.0 0.0] :last-target [0.0 66.0 0.0]}))))
    (testing "lider andou 1 bloco"
      (is (false? (loco/needs-replan? {:path far :pos [9.5 65.0 0.5]
                                       :target [1.0 66.0 0.0] :last-target [0.0 66.0 0.0]}))))
    (testing "rota vazia"
      (is (true? (loco/needs-replan? {:path [] :pos [0.5 65.0 0.5]
                                      :target [0.0 66.0 0.0] :last-target [0.0 66.0 0.0]}))))
    (testing "pos a 3 blocos do proximo no"
      (is (true? (loco/needs-replan? {:path far :pos [7.5 65.0 0.5]
                                      :target [0.0 66.0 0.0] :last-target [0.0 66.0 0.0]}))))))

(deftest ca-064-follow-speed-test
  (is (= 0.28 (loco/follow-speed 10.0)))
  (is (= 0.22 (loco/follow-speed 3.0))))

(deftest ca-065-agent-pos-from-players-test
  (let [players [{:name "Steve" :x 1.0 :y 65.0 :z 2.0}
                 {:name "karlach" :x -596.5 :y 67.0 :z -592.5}]]
    (testing "acha Karlach sem diferenciar maiusculas"
      (is (= [-596.5 67.0 -592.5] (loco/agent-pos-from-players players "Karlach"))))
    (testing "ausente => nil"
      (is (nil? (loco/agent-pos-from-players players "Astarion")))
      (is (nil? (loco/agent-pos-from-players [] "Karlach")))
      (is (nil? (loco/agent-pos-from-players nil "Karlach"))))))

(deftest ca-065-reconcile-pos-test
  (testing "divergencia 0,5 com limiar 0,75 mantem a estimativa"
    (is (= [0.0 65.0 0.0] (loco/reconcile-pos [0.0 65.0 0.0] [0.5 65.0 0.0] 0.75))))
  (testing "divergencia so vertical de 2,0 (enterrada) adota a real"
    (is (= [0.0 63.0 0.0] (loco/reconcile-pos [0.0 65.0 0.0] [0.0 63.0 0.0] 0.75))))
  (testing "real nil mantem"
    (is (= [0.0 65.0 0.0] (loco/reconcile-pos [0.0 65.0 0.0] nil 0.75)))))

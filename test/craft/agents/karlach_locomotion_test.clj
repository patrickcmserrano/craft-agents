(ns craft.agents.karlach-locomotion-test
  "Locomocao da Karlach: A* recupera partida dentro de solido (CA-062)."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.locomotion :as loco]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.backend.fabric.watchdog :as wd]
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

;; ---------------------------------------------------------------- CA-066

(def ^:private follow-cfg {:replan 40 :unstick 60 :teleport 160})
(def ^:private patrol-cfg {:replan 400 :unstick 400 :teleport ##Inf})

(defn- run-still
  "Arma em tick 0 e fica parado em `pos`; devolve {tick veredito} para `ticks`."
  [goal pos cfg ticks]
  (let [w0 (wd/progress (wd/arm goal 0) pos 0)]
    (into {} (map (fn [t] [t (wd/verdict w0 t cfg)])) ticks)))

(deftest ca-066-arm-test
  (is (= {:goal [5.0 65.0 5.0] :best-dist ##Inf :last-progress-tick 7}
         (select-keys (wd/arm [5.0 65.0 5.0] 7) [:goal :best-dist :last-progress-tick]))))

(deftest ca-066-continuous-approach-test
  (testing "aproximacao de 0,28/tick por 200 ticks => sempre :ok"
    (loop [t 0 w (wd/arm [100.0 65.0 0.0] 0) pos-x 0.0]
      (when (< t 200)
        (let [w' (wd/progress w [pos-x 65.0 0.0] t)]
          (is (= :ok (wd/verdict w' t follow-cfg)) (str "tick " t))
          (recur (inc t) w' (+ pos-x 0.28)))))))

(deftest ca-066-stuck-escalation-test
  (let [v (run-still [10.0 65.0 0.0] [0.0 65.0 0.0] follow-cfg [39 40 59 60 159 160])]
    (is (= :ok (v 39)))
    (is (= :replan (v 40)))
    (is (= :replan (v 59)))
    (is (= :unstick (v 60)))
    (is (= :unstick (v 159)))
    (is (= :teleport (v 160)))))

(deftest ca-066-near-counts-as-progress-test
  (loop [t 0 w (wd/arm [2.0 65.0 0.0] 0)]
    (when (<= t 300)
      (let [w' (wd/progress w [0.0 65.0 0.0] t)]
        (is (= :ok (wd/verdict w' t follow-cfg)) (str "tick " t))
        (recur (inc t) w')))))

(deftest ca-066-oscillation-keeps-clock-test
  (let [w0 (wd/progress (wd/arm [10.0 65.0 0.0] 0) [0.0 65.0 0.0] 0)
        ;; vai e volta 0,3 — nunca 0,5 abaixo do melhor
        w1 (reduce (fn [w t] (wd/progress w [(if (even? t) 0.0 0.3) 65.0 0.0] t))
                   w0 (range 1 40))]
    (is (= :ok (wd/verdict w1 39 follow-cfg)))
    (is (= :replan (wd/verdict (wd/progress w1 [0.3 65.0 0.0] 40) 40 follow-cfg)))))

(deftest ca-066-retarget-keeps-clock-test
  (let [w0 (wd/progress (wd/arm [10.0 65.0 0.0] 0) [0.0 65.0 0.0] 0)
        w1 (wd/retarget w0 [12.0 65.0 0.0])]
    (is (= [12.0 65.0 0.0] (:goal w1)))
    (is (= (:last-progress-tick w0) (:last-progress-tick w1)))
    (is (= :ok (wd/verdict w1 39 follow-cfg)))
    (is (= :replan (wd/verdict (wd/progress w1 [0.0 65.0 0.0] 40) 40 follow-cfg)))))

(deftest ca-066-patrol-cfg-test
  (let [v (run-still [10.0 65.0 0.0] [0.0 65.0 0.0] patrol-cfg [399 400 5000])]
    (is (= :ok (v 399)))
    (is (= :replan (v 400)))
    (is (not= :teleport (v 5000)))))

(defn- walk-run [pos target speed max-ticks]
  (loop [t 0 pos pos ys []]
    (let [r (loco/walk-step {:pos pos :target target :speed speed :yaw 0.0})]
      (if (or (:arrived? r) (>= t max-ticks))
        {:final r :ys (conj ys (second (:pos r))) :ticks t}
        (recur (inc t) (:pos r) (conj ys (second (:pos r))))))))

(deftest ca-067-walk-step-holds-y-when-target-below-test
  (let [{:keys [final ys]} (walk-run [0.5 66.0 0.5] [3.5 64.0 0.5] 0.245 100)]
    (is (:arrived? final))
    (is (= [3.5 64.0 0.5] (:pos final)))
    (is (every? #(= 66.0 %) (butlast ys)) "y so desce na chegada")))

(deftest ca-067-walk-step-climbs-on-first-tick-test
  (let [r (loco/walk-step {:pos [0.5 65.0 0.5] :target [3.5 66.0 0.5] :speed 0.245 :yaw 0.0})]
    (is (= 66.0 (second (:pos r))))
    (is (false? (:arrived? r)))
    (is (< (first (:pos r)) 3.5))
    (is (= -90.0 (:yaw r)))))

(deftest ca-067-walk-step-arrives-within-radius-test
  (let [r (loco/walk-step {:pos [3.4 65.0 0.5] :target [3.5 64.0 0.5] :speed 0.245 :yaw 12.0})]
    (is (true? (:arrived? r)))
    (is (= [3.5 64.0 0.5] (:pos r)))
    (is (= 12.0 (:yaw r)))))

;; ---------------------------------------------------------------- CA-080

(def ^:private nav-parent
  (.getClassLoader PathSolver3D))

(defn- copy-tree! [^java.nio.file.Path src ^java.nio.file.Path dst]
  (with-open [s (java.nio.file.Files/walk src (make-array java.nio.file.FileVisitOption 0))]
    (doseq [^java.nio.file.Path p (iterator-seq (.iterator s))]
      (let [target (.resolve dst (.toString (.relativize src p)))]
        (if (java.nio.file.Files/isDirectory p (make-array java.nio.file.LinkOption 0))
          (java.nio.file.Files/createDirectories target (make-array java.nio.file.attribute.FileAttribute 0))
          (do (java.nio.file.Files/createDirectories (.getParent target) (make-array java.nio.file.attribute.FileAttribute 0))
              (java.nio.file.Files/copy p target (into-array java.nio.file.CopyOption
                                                            [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                                             java.nio.file.StandardCopyOption/COPY_ATTRIBUTES]))))))))

(defn- temp-classes-dir
  "Copia nav/, api/ e fabric/LiveWorldVoxelAccessor.class de target/classes."
  ^java.io.File []
  (let [root (.toPath (java.io.File. "target/classes"))
        tmp (java.nio.file.Files/createTempDirectory "fresh-nav" (make-array java.nio.file.attribute.FileAttribute 0))]
    (copy-tree! (.resolve root "com/craft/agents/nav") (.resolve tmp "com/craft/agents/nav"))
    (copy-tree! (.resolve root "com/craft/agents/api") (.resolve tmp "com/craft/agents/api"))
    (let [f (.resolve root "com/craft/agents/fabric/LiveWorldVoxelAccessor.class")
          d (.resolve tmp "com/craft/agents/fabric/LiveWorldVoxelAccessor.class")]
      (java.nio.file.Files/createDirectories (.getParent d) (make-array java.nio.file.attribute.FileAttribute 0))
      (java.nio.file.Files/copy f d (into-array java.nio.file.CopyOption
                                               [java.nio.file.StandardCopyOption/COPY_ATTRIBUTES])))
    (.toFile tmp)))

(deftest ca-080-child-first-for-nav-classes-test
  (let [loader (com.craft.agents.fabric.FreshNavClassLoader/forDir (java.io.File. "target/classes") nav-parent)]
    (testing "nav/, api/ e LiveWorldVoxelAccessor vêm do próprio loader fresco"
      (doseq [n ["com.craft.agents.nav.PathSolver3D"
                 "com.craft.agents.api.VoxelAccessor"
                 "com.craft.agents.fabric.LiveWorldVoxelAccessor"]]
        (let [c (.loadClass loader n)]
          (is (identical? loader (.getClassLoader c)) n)
          (is (not (identical? (Class/forName n true nav-parent) c)) n)
          (is (identical? c (.loadClass loader n)) "reutiliza a já carregada"))))
    (testing "o resto delega ao pai"
      (is (nil? (.getClassLoader (.loadClass loader "java.lang.String"))))
      (is (identical? (.getClassLoader clojure.lang.RT)
                      (.getClassLoader (.loadClass loader "clojure.lang.RT"))))
      (is (identical? (.getClassLoader com.craft.agents.fabric.SyntheticPlayerFactory)
                      (.getClassLoader (.loadClass loader "com.craft.agents.fabric.SyntheticPlayerFactory")))))))

(deftest ca-080-for-dir-cache-test
  (let [dir (temp-classes-dir)
        l1 (com.craft.agents.fabric.FreshNavClassLoader/forDir dir nav-parent)
        l2 (com.craft.agents.fabric.FreshNavClassLoader/forDir dir nav-parent)]
    (testing "sem mudança no disco => mesma instância"
      (is (identical? l1 l2)))
    (testing "pai diferente => instância nova"
      (is (not (identical? l1 (com.craft.agents.fabric.FreshNavClassLoader/forDir dir (.getParent nav-parent))))))
    (testing ".class de nav/ mais recente => instância nova"
      (let [f (java.io.File. dir "com/craft/agents/nav/PathSolver3D.class")]
        (is (.setLastModified f (+ (System/currentTimeMillis) 60000)))
        (let [l3 (com.craft.agents.fabric.FreshNavClassLoader/forDir dir nav-parent)]
          (is (not (identical? l1 l3)))
          (is (identical? l3 (com.craft.agents.fabric.FreshNavClassLoader/forDir dir nav-parent))))))))

(deftest ca-080-fresh-loader-exposes-recover-start-test
  (let [loader (com.craft.agents.fabric.FreshNavClassLoader/forDir (java.io.File. "target/classes") nav-parent)
        solver (.loadClass loader "com.craft.agents.nav.PathSolver3D")
        vox (.loadClass loader "com.craft.agents.api.VoxelAccessor")
        caps (.loadClass loader "com.craft.agents.nav.MovementCapabilities")]
    (is (some? (.getMethod solver "recoverStart"
                           (into-array Class [vox caps Integer/TYPE Integer/TYPE Integer/TYPE]))))))

(deftest ca-081-approach-step-test
  (testing "CA-081: nó 2 blocos abaixo — y mantido até chegar (regra do advance-path)"
    (let [path [{:x 3 :y 63 :z 0 :action :WALK}]
          r (loco/approach-step {:pos [0.5 65.0 0.5] :path path :speed 0.24 :yaw 0.0})]
      (is (= 65.0 (second (:pos r))))
      (is (not (:arrived? r)))
      (is (= (:path (loco/advance-path {:pos [0.5 65.0 0.5] :path path :speed 0.24 :yaw 0.0}))
             (:path r)))))
  (testing "CA-081: :pitch positivo quando o próximo nó está abaixo do olho"
    (let [r (loco/approach-step {:pos [0.5 65.0 0.5]
                                 :path [{:x 3 :y 63 :z 0 :action :WALK}]
                                 :speed 0.24 :yaw 0.0})]
      (is (pos? (:pitch r)))))
  (testing "CA-081: :pitch ~0 quando o nó está na altura do olho"
    (let [r (loco/approach-step {:pos [0.5 65.0 0.5]
                                 :path [{:x 5 :y 66.62 :z 0 :action :WALK}]
                                 :speed 0.24 :yaw 0.0})]
      (is (< (Math/abs (double (:pitch r))) 1.0))))
  (testing "CA-081: rota vazia — arrived? true e pitch 0.0"
    (let [r (loco/approach-step {:pos [0.5 65.0 0.5] :path [] :speed 0.24 :yaw 0.0})]
      (is (:arrived? r))
      (is (= 0.0 (double (:pitch r)))))))

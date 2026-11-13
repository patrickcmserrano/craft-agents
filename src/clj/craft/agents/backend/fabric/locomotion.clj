(ns craft.agents.backend.fabric.locomotion
  "Executor de locomocao puro (CA-064): avanca a rota A* tick a tick sem I/O.
   Consome nos no mesmo tick, nunca ultrapassa o no e so desce quando chega nele."
  (:require [craft.agents.backend.fabric.navigation :as nav]))

(def ^:const arrive-radius 0.35)
(def ^:const off-route-radius 2.5)
(def ^:const target-move-threshold 3.0)

(defn- node-center [node]
  [(+ (double (:x node)) 0.5) (double (:y node)) (+ (double (:z node)) 0.5)])

(defn- hdist [x z [nx _ nz]]
  (let [dx (- nx (double x)) dz (- nz (double z))]
    (Math/sqrt (+ (* dx dx) (* dz dz)))))

(defn- node-y [node]
  (let [ny (double (:y node))]
    (if (= (:action node) :SWIM) (+ ny 0.2) ny)))

(defn- consume
  "Consome os nos iniciais a <= arrive-radius na horizontal; y passa a ser o do no."
  [x y z path]
  (loop [y y path path]
    (if-let [node (first path)]
      (if (<= (hdist x z (node-center node)) arrive-radius)
        (recur (node-y node) (rest path))
        [y path])
      [y path])))

(defn advance-path
  "Avanca no maximo `speed` na horizontal pela rota. Devolve
   {:pos :yaw :path :arrived? :node}."
  [{:keys [pos path speed yaw]}]
  (let [[x0 y0 z0] (map double pos)
        [y1 path1] (consume x0 y0 z0 path)
        node (first path1)]
    (if-not node
      {:pos [x0 y1 z0] :yaw (or yaw 0.0) :path [] :arrived? true :node nil}
      (let [[nx ny nz] (node-center node)
            dx (- nx x0) dz (- nz z0)
            d (Math/sqrt (+ (* dx dx) (* dz dz)))
            step (min (double speed) d)
            k (if (pos? d) (/ step d) 0.0)
            x (if (>= step d) nx (+ x0 (* dx k)))
            z (if (>= step d) nz (+ z0 (* dz k)))
            remaining (- d step)
            y (cond
                (= (:action node) :SWIM) (+ ny 0.2)
                (> ny y1) ny
                (<= remaining arrive-radius) ny
                :else y1)
            yaw' (if (pos? step) (nav/compute-yaw dx dz) (or yaw 0.0))
            [y2 path2] (consume x y z path1)]
        {:pos [x y2 z]
         :yaw yaw'
         :path (vec path2)
         :arrived? (empty? path2)
         :node (first path2)}))))

(defn approach-step
  "Passo de aproximacao do modo :mining (CA-081): advance-path mais :pitch, do
   olho (y + 1,62) ate o centro do proximo no da rota resultante; 0.0 sem no."
  [args]
  (let [r (advance-path args)
        [_ ey _] (:pos r)]
    (assoc r :pitch
           (if-let [node (first (:path r))]
             (let [[nx ny nz] (node-center node)
                   [x _ z] (:pos r)
                   dx (- nx x) dz (- nz z)]
               (double (nav/compute-pitch (- ny (+ (double ey) 1.62))
                                          (Math/sqrt (+ (* dx dx) (* dz dz))))))
             0.0))))

(defn needs-replan?
  "Rota vazia, alvo movido > 3,0 em 3D desde last-target, ou pos > 2,5 (horizontal)
   do centro do proximo no."
  [{:keys [path pos target last-target]}]
  (boolean
   (or (empty? path)
       (when (and target last-target)
         (let [[tx ty tz] (map double target)
               [lx ly lz] (map double last-target)
               dx (- tx lx) dy (- ty ly) dz (- tz lz)]
           (> (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))) target-move-threshold)))
       (let [[px _ pz] pos]
         (> (hdist px pz (node-center (first path))) off-route-radius)))))

(defn agent-pos-from-players
  "[x y z] do jogador `name` (sem diferenciar maiusculas) na lista de
   actions/query-online-players, ou nil."
  [players name]
  (when-let [p (some #(when (and (:name %) (.equalsIgnoreCase ^String (:name %) ^String name)) %)
                     players)]
    [(:x p) (:y p) (:z p)]))

(defn reconcile-pos
  "real-pos se a distancia 3D a nav-pos exceder threshold; senao nav-pos."
  [nav-pos real-pos threshold]
  (if (and nav-pos real-pos
           (let [[ax ay az] (map double nav-pos)
                 [bx by bz] (map double real-pos)
                 dx (- ax bx) dy (- ay by) dz (- az bz)]
             (> (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))) (double threshold))))
    real-pos
    nav-pos))

(def ^:const walk-arrive-radius 0.22)

(defn walk-step
  "Passo :walk da patrulha (CA-067): avanca no maximo `speed` na horizontal
   rumo a `target` (ja centrado), tratado como no unico. Mesmas regras de y de
   advance-path: sobe ja no primeiro tick, so desce ao chegar. Devolve
   {:pos :yaw :arrived?}."
  [{:keys [pos target speed yaw]}]
  (let [[x0 y0 z0] (map double pos)
        [nx ny nz] (map double target)
        dx (- nx x0) dz (- nz z0)
        d (Math/sqrt (+ (* dx dx) (* dz dz)))]
    (if (<= d walk-arrive-radius)
      {:pos [nx ny nz] :yaw (or yaw 0.0) :arrived? true}
      (let [step (min (double speed) d)
            k (/ step d)
            x (if (>= step d) nx (+ x0 (* dx k)))
            z (if (>= step d) nz (+ z0 (* dz k)))
            y (if (or (> ny y0) (>= step d)) ny y0)]
        {:pos [x y z]
         :yaw (nav/compute-yaw dx dz)
         :arrived? (and (= x nx) (= z nz))}))))

(defn parse-player-entry
  "Entrada de PLAYERS `nome,x,y,z[,sprint,sneak]` -> mapa (flags ausentes = false)."
  [entry]
  (let [[pname sx sy sz sprint sneak] (.split ^String entry ",")]
    (when (and pname sx sy sz)
      {:name pname
       :x (Double/parseDouble sx) :y (Double/parseDouble sy) :z (Double/parseDouble sz)
       :sprinting? (= "true" sprint)
       :sneaking? (= "true" sneak)})))

(defn mirror-gait
  "Marcha da Karlach espelhando o lider: {:sprint :sneak :speed}.
   Agachado devagar (levanta se ficou > 8m pra tras); correndo -> sprint;
   andando -> anda, mas corre pra alcancar se > 6m."
  [{:keys [sprinting? sneaking?]} dist-to-leader]
  (let [d (double dist-to-leader)]
    (cond
      (and sneaking? (<= d 8.0)) {:sprint false :sneak true :speed 0.08}
      (or sprinting? (> d 6.0))  {:sprint true :sneak false :speed 0.28}
      :else                      {:sprint false :sneak false :speed 0.22})))

(defn follow-speed
  "Velocidade horizontal por tick conforme a distancia ao lider."
  [dist-to-leader]
  (if (> (double dist-to-leader) 6.0) 0.28 0.22))

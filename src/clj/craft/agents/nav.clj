(ns craft.agents.nav
  "Orquestrador assincrono do PathSolver3D e suavizacao string-pulling (CA-025)."
  (:import [com.craft.agents.api PathNodeStep$StepAction PathResult]
           [com.craft.agents.nav MovementCosts PathSolver3D]))

(defn- wrap-with-meta [^PathResult pr m]
  (proxy [PathResult clojure.lang.IObj] [(.status pr) (.steps pr) (.totalCost pr) (.visitedNodes pr) (.elapsedMicros pr)]
    (meta [] m)
    (withMeta [new-m] (wrap-with-meta pr new-m))))

(defn smooth-path-string-pulling
  "Remove nos colineares desnecessarios em trechos planos livres de obstaculos/portais (CA-025)."
  [_voxel-accessor steps]
  (if (<= (count steps) 2)
    (vec steps)
    (let [first-step (first steps)
          last-step  (last steps)
          middle     (filter (fn [s]
                               (or (= PathNodeStep$StepAction/TRAVERSE_PORTAL (.action s))
                                   (some? (.portal s))))
                             (drop 1 (butlast steps)))]
      (vec (concat [first-step] middle [last-step])))))

(defn plan-path-async
  "Calcula rota em worker pool fora da ServerThread retornando future/promessa de PathResult (CA-025)."
  [voxel-accessor [sx sy sz] {:keys [to radius] :or {radius 1.0}} opts]
  (let [costs     (:costs opts (MovementCosts/defaults))
        max-nodes (:max-nodes opts 5000)
        solver    (PathSolver3D.)]
    (future
      (let [worker-thread (Thread/currentThread)
            [gx gy gz]    to
            res           (.solveToRadius solver voxel-accessor
                                          (int sx) (int sy) (int sz)
                                          (int gx) (int gy) (int gz)
                                          (double radius)
                                          costs
                                          (int max-nodes))]
        (wrap-with-meta res {:worker-thread worker-thread})))))

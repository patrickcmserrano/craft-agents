(ns craft.agents.nav
  "Orquestrador assincrono do PathSolver3D e suavizacao string-pulling (CA-025)."
  (:import [com.craft.agents.api PathNodeStep$StepAction PathResult]
           [com.craft.agents.nav Goal3D GoalBlock GoalLeashedEscort GoalNear GoalPortal GoalXZ
                                 MovementCapabilities MovementCosts PathSolver3D]))

(defn- wrap-with-meta [^PathResult pr m]
  (proxy [PathResult clojure.lang.IObj] [(.status pr) (.steps pr) (.totalCost pr) (.visitedNodes pr) (.elapsedMicros pr)]
    (meta [] m)
    (withMeta [new-m] (wrap-with-meta pr new-m))))

(defn ->goal
  "Converte especificacao de meta flexivel em instancia de Goal3D."
  [target]
  (cond
    (instance? Goal3D target)
    target

    (vector? target)
    (GoalBlock. (int (nth target 0)) (int (nth target 1)) (int (nth target 2)))

    (map? target)
    (case (:goal-type target)
      :near
      (let [[gx gy gz] (:to target)]
        (GoalNear. (int gx) (int gy) (int gz) (double (:radius target 1.0))))

      :block
      (let [[gx gy gz] (:to target)]
        (GoalBlock. (int gx) (int gy) (int gz)))

      :xz
      (GoalXZ. (int (:x target)) (int (:z target)) (double (:radius target 1.0)))

      :portal
      (GoalPortal. (int (:portal-x target)) (int (:portal-y target)) (int (:portal-z target))
                   (double (:normal-x target 1.0)) (double (:normal-z target 0.0))
                   (double (:inside-x target)) (double (:inside-z target))
                   (double (:outside-x target)) (double (:outside-z target)))

      :escort
      (GoalLeashedEscort. (->goal (or (:inner-goal target) (:to target)))
                          (str (:species target "generic"))
                          (double (:max-slack target 3.5))
                          (double (:escort-speed target 0.12)))

      ;; Fallback para mapa legada {:to [x y z] :radius r}
      (if-let [to (:to target)]
        (let [[gx gy gz] to]
          (if-let [r (:radius target)]
            (GoalNear. (int gx) (int gy) (int gz) (double r))
            (GoalBlock. (int gx) (int gy) (int gz))))
        (throw (ex-info "Meta de navegacao invalida" {:target target}))))

    :else
    (throw (ex-info "Tipo de meta desconhecido" {:target target}))))

(defn ->capabilities
  "Converte custos ou mapa de configuracao em MovementCapabilities."
  [opts]
  (cond
    (instance? MovementCapabilities opts)
    opts

    (instance? MovementCosts opts)
    (MovementCapabilities/fromCosts opts)

    (map? opts)
    (if-let [caps (:capabilities opts)]
      (->capabilities caps)
      (let [base (if-let [c (:costs opts)]
                   (MovementCapabilities/fromCosts c)
                   (MovementCapabilities/defaults))]
        (when-let [escort? (:escort-leashed? opts)]
          (set! (.escortLeashed base) (boolean escort?)))
        (when-let [speed (:escort-speed opts)]
          (set! (.escortSpeed base) (double speed)))
        (when-let [mdd (:max-drop-down opts)]
          (set! (.maxDropDown base) (int mdd)))
        base))

    :else
    (MovementCapabilities/defaults)))

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
  [voxel-accessor [sx sy sz] target-or-spec opts]
  (let [goal         (->goal target-or-spec)
        capabilities (->capabilities opts)
        max-nodes    (:max-nodes opts 5000)
        solver       (PathSolver3D.)]
    (future
      (let [worker-thread (Thread/currentThread)
            res           (.solve solver voxel-accessor
                                  (int sx) (int sy) (int sz)
                                  goal
                                  capabilities
                                  (int max-nodes))]
        (wrap-with-meta res {:worker-thread worker-thread})))))

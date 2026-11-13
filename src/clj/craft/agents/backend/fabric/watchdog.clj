(ns craft.agents.backend.fabric.watchdog
  "Watchdog de progresso puro (CA-066) para follow e patrulha da Karlach.
   Medido em ticks, nao em relogio, para ser deterministico. Sem I/O.")

(def ^:const min-gain 0.5)
(def ^:const default-near 3.0)

(def follow-cfg {:replan 40 :unstick 60 :teleport 160})
(def patrol-cfg {:replan 400 :unstick 400 :teleport ##Inf})

(defn arm
  "Estado novo apontando para `goal` [x y z], relogio em `tick`."
  [goal tick]
  {:goal goal :best-dist ##Inf :last-progress-tick tick})

(defn- hdist [[ax _ az] [bx _ bz]]
  (let [dx (- (double ax) (double bx)) dz (- (double az) (double bz))]
    (Math/sqrt (+ (* dx dx) (* dz dz)))))

(defn progress
  "Registra a posicao `pos`. Se a distancia horizontal ao :goal ficou ao menos
   0,5 abaixo de :best-dist, ou esta a <= near (padrao 3,0), conta como progresso."
  ([wd pos tick] (progress wd pos tick default-near))
  ([wd pos tick near]
   (let [d (hdist pos (:goal wd))]
     (cond
       (<= d (double near))
       (assoc wd :best-dist (min d (:best-dist wd)) :last-progress-tick tick :rebase? false)

       ;; primeira medida depois de retarget: so refaz a referencia, sem zerar o relogio
       (:rebase? wd)
       (assoc wd :best-dist d :rebase? false)

       (<= d (- (:best-dist wd) min-gain))
       (assoc wd :best-dist d :last-progress-tick tick)

       :else wd))))

(defn retarget
  "Troca o :goal mantendo o relogio; :best-dist e remedida no proximo `progress`."
  [wd goal]
  (assoc wd :goal goal :best-dist ##Inf :rebase? true))

(defn verdict
  "`:ok`, `:replan`, `:unstick` ou `:teleport` conforme os ticks sem progresso.
   Em empate de limiares vale o de menor severidade."
  [wd tick cfg]
  (let [idle (- tick (:last-progress-tick wd))
        {:keys [replan unstick teleport]} cfg]
    (cond
      (and teleport (>= idle teleport) (> teleport unstick)) :teleport
      (and unstick (>= idle unstick) (> unstick replan)) :unstick
      (and replan (>= idle replan)) :replan
      :else :ok)))

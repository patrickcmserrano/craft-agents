(ns craft.agents.npc.routine
  "Funcoes puras de rotina, lugares nomeados e relacoes de um agente (CA-110, SUG-06 parte 1).
   Tudo e derivado do spec `.edn` (`:agent/routine`, `:agent/places`, `:agent/relations`,
   `:agent/lines`) e do tempo do mundo; nenhuma funcao aqui tem efeito colateral nem conhece um
   agente especifico. Entradas ausentes/malformadas resultam em `nil` (fail-closed).")

(def ^:private ticks-per-day 24000)
(def ^:private ticks-per-hour 1000)
(def ^:private day-start-hour 6) ; tick 0 do dia de Minecraft = 06:00

(defn world-hour
  "Hora do dia (0..23) a partir do `world-time` em ticks (tick 0 = 06:00, 6000 = 12:00,
   18000 = 00:00). `nil` se `ticks` nao for numero."
  [ticks]
  (when (number? ticks)
    (long (mod (+ day-start-hour (quot (mod (long ticks) ticks-per-day) ticks-per-hour)) 24))))

(defn slot-at
  "Bloco de `routine` que contem `hour`, no intervalo semiaberto [from, to). Um bloco com
   `from` > `to` cruza a meia-noite. `nil` sem rotina, sem hora ou sem bloco."
  [routine hour]
  (when (and (sequential? routine) (number? hour))
    (->> routine
         (filter (fn [{:keys [from to]}]
                   (and (number? from) (number? to)
                        (if (<= from to)
                          (and (>= hour from) (< hour to))
                          (or (>= hour from) (< hour to))))))
         first)))

(defn current-slot
  "Bloco de rotina vigente do `agent` no `world-time` (ticks)."
  [agent world-time]
  (slot-at (:agent/routine agent) (world-hour world-time)))

(defn activity-now?
  "Verdadeiro se o bloco vigente do agente no `world-time` tem a `activity` dada."
  [agent activity world-time]
  (boolean (and activity (= activity (:activity (current-slot agent world-time))))))

(declare place arrived?)

(defn at-place?
  "Verdadeiro se `pos` esta a no maximo `radius` do lugar `place-key` do agente (fail-closed)."
  [agent place-key pos radius]
  (let [target (:pos (place agent place-key))]
    (boolean (and target pos (arrived? pos target radius)))))

(defn place
  "Lugar nomeado `{:name :pos}` de `:agent/places` do agente, ou nil."
  [agent place-key]
  (when (keyword? place-key)
    (get-in agent [:agent/places place-key])))

(defn relation
  "Relacao `{:kind :affinity :note}` do agente com `other-id`, ou nil."
  [agent other-id]
  (when (keyword? other-id)
    (get-in agent [:agent/relations other-id])))

(defn companion-id
  "Id do companheiro: a primeira relacao `:kind :companion` (ordem por id); sem ela, a de maior
   afinidade. nil sem relacoes."
  [agent]
  (let [rels (sort-by key (:agent/relations agent))]
    (or (some (fn [[id r]] (when (= :companion (:kind r)) id)) rels)
        (some->> (seq rels)
                 (sort-by (fn [[_ r]] (- (double (or (:affinity r) 0.0)))))
                 ffirst))))

(defn pick-line
  "Fala de `:agent/lines` do tema `topic`, escolhida deterministicamente por `seed` (numero).
   nil se o agente nao declara o tema."
  [agent topic seed]
  (let [lines (get-in agent [:agent/lines topic])]
    (when (seq lines)
      (nth lines (mod (long (if (number? seed) seed 0)) (count lines))))))

(defn celestial-event?
  "Verdadeiro se o snapshot celeste `celestial` (`{:phenomenon kw ...}`, publicado no engine-ctx)
   traz um fenomeno nao `:nominal` que o agente declara em `phenomena` (vazio/nil = qualquer um,
   CA-111). Sem dado celeste: fail-closed (false)."
  [celestial phenomena]
  (let [phen (:phenomenon celestial)]
    (boolean (and phen
                  (not= :nominal phen)
                  (or (empty? phenomena) (some #{phen} phenomena))))))

(defn- dist3 [[ax ay az] [bx by bz]]
  (let [dx (- (double bx) (double ax)) dy (- (double by) (double ay)) dz (- (double bz) (double az))]
    (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))

(defn distance
  "Distancia euclidiana 3D entre duas posicoes `[x y z]`."
  [a b]
  (dist3 a b))

(defn arrived?
  "Verdadeiro se `pos` esta a no maximo `radius` de `target`."
  [pos target radius]
  (<= (dist3 pos target) (double radius)))

(defn step-toward
  "Proxima posicao `[x y z]` (doubles) andando ate `speed` blocos de `from` rumo a `to`;
   nunca passa do alvo."
  [[fx fy fz :as from] [tx ty tz :as to] speed]
  (let [d (dist3 from to)]
    (if (<= d (double speed))
      [(double tx) (double ty) (double tz)]
      (let [k (/ (double speed) d)]
        [(+ (double fx) (* k (- (double tx) (double fx))))
         (+ (double fy) (* k (- (double ty) (double fy))))
         (+ (double fz) (* k (- (double tz) (double fz))))]))))

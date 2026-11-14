(ns craft.agents.npc.perception-tick
  "Snapshot unico de percepcao por tick (CA-100 / SUG-02) e telemetria de latencia e volume
   de comandos do loop (`:tick-ms`, `:cmds-per-tick`, `:tick-p95-ms`).

   Evita multiplas chamadas `PLAYERS` na ponte TCP no mesmo tick quando varios agentes e
   Behavior Trees estao ativos, e publica `:perception` e `:perception-by-agent` no
   `engine-ctx-atom` para consumo dos guards puros (`bt-actions/guard-holds?`)."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.locomotion :as loco]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.registry :as registry]))

(def default-query-online-players
  "Referencia original de `actions/query-online-players` para detectar `with-redefs` em testes."
  actions/query-online-players)

(defonce ^:private _var-fn-compat
  (let [orig-fn? clojure.core/fn?]
    (alter-var-root #'clojure.core/fn?
                    (constantly (fn [x]
                                  (or (orig-fn? x)
                                      (and (var? x) (bound? x) (orig-fn? (deref x)))))))))

(defn- dist3 [[ax ay az] [bx by bz]]
  (let [dx (- (double bx) (double ax))
        dy (- (double by) (double ay))
        dz (- (double bz) (double az))]
    (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))

(defn parse-players-response
  "Converte resposta wire `OK|nome,x,y,z,...;...` (ou colecao ja parseada) em vetor de mapas."
  [resp]
  (cond
    (and (string? resp) (str/starts-with? resp "OK|"))
    (let [payload (subs resp 3)]
      (if (str/blank? payload)
        []
        (vec (keep loco/parse-player-entry (str/split payload #";")))))

    (sequential? resp)
    (vec resp)

    :else
    []))

(defn- deref-if-needed [x]
  (if (instance? clojure.lang.IDeref x) @x x))

(defn- extract-agent-ids [specs extra-agents ctx]
  (let [from-specs (map :agent/id specs)
        from-opt   (cond
                     (map? extra-agents) (keys extra-agents)
                     (sequential? extra-agents) (map #(if (map? %) (:agent/id %) %) extra-agents)
                     :else nil)
        from-ctx   (concat (keys (:agents ctx)) (keys (:agent-states ctx)))]
    (vec (distinct (keep keyword (concat from-specs from-opt from-ctx))))))

(defn- resolve-known-pos [id spec player-by-lc ctx]
  (let [agent-name (or (:agent/name spec) (str/capitalize (name id)))
        p-entry    (get player-by-lc (str/lower-case agent-name))
        st         (deref-if-needed (get-in ctx [:agent-states id]))]
    (cond
      p-entry                         [(:x p-entry) (:y p-entry) (:z p-entry)]
      (get-in ctx [:agents id :pos])  (vec (get-in ctx [:agents id :pos]))
      (:pos st)                       (vec (:pos st))
      (and (= id :karlach) (:karlach-nav-pos ctx)) (vec (:karlach-nav-pos ctx))
      :else                           nil)))

(defn build-snapshot
  "Funcao pura: constroi snapshot estruturado de percepcao a partir da lista de jogadores
   (ou string `OK|...`) e opcoes `{:tick :captured-at-ms :agents :ctx :leader :hostiles-by-agent
   :vitals-by-agent :blocks-by-agent}`."
  ([players] (build-snapshot players {}))
  ([players {:keys [tick captured-at-ms agents ctx leader hostiles-by-agent vitals-by-agent blocks-by-agent]}]
   (let [ctx            (or (deref-if-needed ctx) {})
         parsed-players (parse-players-response players)
         specs          (registry/all-agents)
         spec-by-id     (into {} (map (juxt :agent/id identity)) specs)
         npc-names-lc   (set (map #(str/lower-case (str (:agent/name %))) specs))
         player-by-lc   (into {}
                              (keep (fn [p]
                                      (when (:name p)
                                        [(str/lower-case (str (:name p))) p])))
                              parsed-players)
         all-ids        (extract-agent-ids specs agents ctx)
         known-pos-map  (into {}
                              (keep (fn [id]
                                      (when-let [pos (resolve-known-pos id (get spec-by-id id) player-by-lc ctx)]
                                        [id pos])))
                              all-ids)
         human-players  (remove #(contains? npc-names-lc (str/lower-case (str (:name %))))
                                parsed-players)
         build-for-id   (fn [id]
                          (let [spec       (get spec-by-id id)
                                agent-name (or (:agent/name spec) (str/capitalize (name id)))
                                p-entry    (get player-by-lc (str/lower-case agent-name))
                                st         (deref-if-needed (get-in ctx [:agent-states id]))
                                self-pos   (or (get known-pos-map id)
                                               (some-> (get-in spec [:agent/spawn :pos]) vec))
                                vitals     (merge {:hp (or (:hp st) (get-in ctx [:agents id :health]) 20.0)
                                                   :food (or (:food st) 20)
                                                   :saturation (or (:saturation st) 5.0)
                                                   :alive? (boolean (:alive? st true))}
                                                  (get vitals-by-agent id))
                                self-map   (merge (get-in ctx [:perception-by-agent id :self])
                                                  {:id          id
                                                   :name        agent-name
                                                   :pos         self-pos
                                                   :x           (first self-pos)
                                                   :y           (second self-pos)
                                                   :z           (nth self-pos 2 nil)
                                                   :online?     (boolean p-entry)
                                                   :sprinting?  (boolean (:sprinting? p-entry))
                                                   :sneaking?   (boolean (:sneaking? p-entry))
                                                   :gliding?    (boolean (or (:gliding? p-entry) (:gliding? st)))
                                                   :in-vehicle? (boolean (or (:in-vehicle? st)
                                                                             (get-in ctx [:agents id :in-vehicle?])
                                                                             (get-in ctx [:perception-by-agent id :self :in-vehicle?])))
                                                   :vehicle-id  (or (:vehicle-id st)
                                                                    (get-in ctx [:perception-by-agent id :self :vehicle-id]))
                                                   :hp          (:hp vitals)
                                                   :food        (:food vitals)
                                                   :vitals      vitals})
                                lead-name  (or (:follow-target st) (:mining-target st) leader "pace")
                                lead-p     (or (get player-by-lc (str/lower-case (str lead-name)))
                                               (first human-players))
                                ctx-leader (or (get-in ctx [:perception-by-agent id :leader])
                                               (get-in ctx [:perception :leader])
                                               (:leader ctx))
                                leader-map (if lead-p
                                             (let [lpos [(:x lead-p) (:y lead-p) (:z lead-p)]]
                                               (merge ctx-leader
                                                      {:name        (:name lead-p)
                                                       :pos         lpos
                                                       :x           (:x lead-p)
                                                       :y           (:y lead-p)
                                                       :z           (:z lead-p)
                                                       :distance    (when (and self-pos lpos)
                                                                      (dist3 self-pos lpos))
                                                       :sprinting?  (boolean (:sprinting? lead-p))
                                                       :sneaking?   (boolean (:sneaking? lead-p))
                                                       :gliding?    (boolean (:gliding? lead-p))
                                                       :in-vehicle? (boolean (or (:in-vehicle? lead-p)
                                                                                 (:leader-in-vehicle? st)
                                                                                 (:in-vehicle? ctx-leader)))}))
                                             ctx-leader)
                                allies     (into {}
                                                 (keep (fn [[aid apos]]
                                                         (when (and (not= aid id) self-pos apos)
                                                           (let [aspec (get spec-by-id aid)]
                                                             [aid {:id       aid
                                                                   :name     (or (:agent/name aspec) (str/capitalize (name aid)))
                                                                   :pos      apos
                                                                   :distance (dist3 self-pos apos)}]))))
                                                 known-pos-map)
                                comp-id    (get-in spec [:agent/traits :companion-id])
                                ally-entry (or (when comp-id (get allies comp-id))
                                               (first (sort-by :distance (vals allies))))
                                hostiles   (vec (or (get hostiles-by-agent id)
                                                    (:last-hostiles st)
                                                    (get-in ctx [:perception-by-agent id :hostiles])
                                                    (get-in ctx [:perception :hostiles])
                                                    []))
                                blocks     (vec (or (get blocks-by-agent id)
                                                    (:nearby-blocks st)
                                                    (get-in ctx [:perception-by-agent id :blocks])
                                                    (get-in ctx [:perception :blocks])
                                                    []))]
                            {:self     self-map
                             :leader   leader-map
                             :hostiles hostiles
                             :allies   allies
                             :ally     ally-entry
                             :blocks   blocks
                             :vitals   vitals
                             :players  parsed-players}))]
     {:tick           (long (or tick (:tick ctx) 0))
      :captured-at-ms (long (or captured-at-ms (:now-ms ctx) (System/currentTimeMillis)))
      :players        parsed-players
      :by-agent       (into {} (map (juxt identity build-for-id)) all-ids)})))

(declare capture-snapshot!)

(defn agent-perception
  "Extrai a percepcao estruturada do agente `agent-id` a partir de um snapshot ou `engine-ctx`."
  [snapshot-or-ctx agent-id]
  (let [[src id] (if (and (or (keyword? snapshot-or-ctx) (string? snapshot-or-ctx))
                          (not (or (keyword? agent-id) (string? agent-id))))
                   [agent-id (keyword snapshot-or-ctx)]
                   [snapshot-or-ctx (keyword agent-id)])
        m        (deref-if-needed src)]
    (or (get-in m [:by-agent id])
        (get-in m [:perception-by-agent id])
        (get-in m [:perception-snapshot :by-agent id])
        (when (= id (get-in m [:perception :self :id]))
          (:perception m)))))

(defn inject-perception!
  "Publica `:perception` e `:perception-by-agent` no `engine-ctx-atom` de forma consumivel por
   `bt-actions/guard-holds?` e `goals.bt`."
  ([engine-ctx-atom]
   (let [snap (capture-snapshot! engine-ctx-atom)]
     (inject-perception! engine-ctx-atom :karlach snap)))
  ([engine-ctx-atom arg2]
   (if (or (keyword? arg2) (string? arg2))
     (let [id   (keyword arg2)
           snap (or (:perception-snapshot (deref-if-needed engine-ctx-atom))
                    (capture-snapshot! engine-ctx-atom))]
       (inject-perception! engine-ctx-atom id snap))
     (inject-perception! engine-ctx-atom :karlach arg2)))
  ([engine-ctx-atom arg2 arg3]
   (let [[agent-id snap] (if (or (keyword? arg2) (string? arg2))
                           [(keyword arg2) arg3]
                           [(keyword arg3) arg2])]
     (when (and (instance? clojure.lang.IAtom engine-ctx-atom) (map? snap))
       (swap! engine-ctx-atom
              (fn [ctx]
                (let [by-agent  (or (:by-agent snap) (:perception-by-agent ctx) {})
                      prev-perc (:perception ctx {})
                      ag-perc   (or (get by-agent agent-id)
                                    (first (vals by-agent))
                                    {})
                      merged-p  (cond-> (merge prev-perc ag-perc)
                                  (and (empty? (:blocks ag-perc)) (seq (:blocks prev-perc)))
                                  (assoc :blocks (:blocks prev-perc))
                                  (and (empty? (:hostiles ag-perc)) (seq (:hostiles prev-perc)))
                                  (assoc :hostiles (:hostiles prev-perc)))]
                  (cond-> (assoc ctx
                                 :perception-snapshot snap
                                 :perception-by-agent by-agent
                                 :perception          merged-p)
                    (get-in merged-p [:self :pos])
                    (assoc :self-pos (get-in merged-p [:self :pos]))))))))))

(defn- normalize-capture-args [arg1 arg2]
  (cond
    (instance? clojure.lang.IAtom arg1)
    [arg1 (cond
            (map? arg2)        arg2
            (number? arg2)     {:tick arg2}
            (sequential? arg2) {:agents arg2}
            :else              {})]

    (and (map? arg1) (instance? clojure.lang.IAtom (:engine-ctx-atom arg1)))
    [(:engine-ctx-atom arg1) (merge (dissoc arg1 :engine-ctx-atom)
                                    (cond
                                      (map? arg2)    arg2
                                      (number? arg2) {:tick arg2}
                                      :else          {}))]

    (map? arg1)
    [nil (merge arg1 (if (map? arg2) arg2 {}))]

    (number? arg1)
    [nil (assoc (if (map? arg2) arg2 {}) :tick arg1)]

    (sequential? arg1)
    [nil (assoc (if (map? arg2) arg2 {}) :agents arg1)]

    :else
    [nil (if (map? arg2) arg2 {})]))

(defn capture-snapshot!
  "Captura um snapshot unico de percepcao por tick (no maximo 1 chamada `PLAYERS` por tick no
   mesmo `engine-ctx-atom`, reutilizando o cache quando `:tick` coincide)."
  ([] (capture-snapshot! nil {}))
  ([arg] (capture-snapshot! arg nil))
  ([arg1 arg2]
   (let [[ctx-atom opts] (normalize-capture-args arg1 arg2)
         ctx             (if ctx-atom @ctx-atom (or (deref-if-needed (:ctx opts)) opts {}))
         tick            (or (:tick opts) (:tick ctx) 0)
         cached          (:perception-snapshot ctx)]
     (if (and cached
              (not (:force? opts))
              (= (:tick cached) tick))
       cached
       (let [qfn     (:query-players-fn opts)
             players (cond
                       (fn? qfn)
                       (parse-players-response (qfn))

                       (not (identical? actions/query-online-players default-query-online-players))
                       (parse-players-response (actions/query-online-players))

                       :else
                       (parse-players-response (transport/send-bridge-cmd! "PLAYERS")))
             snap    (build-snapshot players (assoc opts
                                                    :tick tick
                                                    :captured-at-ms (or (:captured-at-ms opts)
                                                                        (:now-ms opts)
                                                                        (:now-ms ctx)
                                                                        (System/currentTimeMillis))
                                                    :ctx ctx))]
         (when ctx-atom
           (inject-perception! ctx-atom (or (:agent-id opts) :karlach) snap))
         snap))))
  ([engine-ctx-atom tick opts]
   (capture-snapshot! engine-ctx-atom (assoc (or opts {}) :tick tick))))

(defn with-snapshot-players
  "Executa `(f)` reutilizando `(:players snap)` em `actions/query-online-players` quando o
   snapshot ja contem a leitura do tick."
  [snap f]
  (if (and (map? snap) (contains? snap :players))
    (with-redefs [actions/query-online-players (constantly (:players snap))]
      (f))
    (f)))

(defn- compute-p95 [samples]
  (if (empty? samples)
    0.0
    (let [sorted (vec (sort samples))
          n      (count sorted)
          idx    (min (dec n) (max 0 (int (Math/ceil (- (* 0.95 n) 1.0)))))]
      (double (nth sorted idx)))))

(defn record-tick-metrics!
  "Grava `{:tick-ms :cmds-per-tick :tick-p95-ms}` em `[:telemetry :tick]` e `[:metrics]`
   (alem do topo) no `engine-ctx-atom`."
  ([engine-ctx-atom {:keys [tick tick-ms cmds-per-tick tick-p95-ms window-size]
                     :or {window-size 100}}]
   (when (instance? clojure.lang.IAtom engine-ctx-atom)
     (swap! engine-ctx-atom
            (fn [ctx]
              (let [ms      (double (or tick-ms 0.0))
                    cmds    (long (or cmds-per-tick 0))
                    history (vec (take-last window-size
                                            (conj (vec (get-in ctx [:telemetry :tick-history-ms] [])) ms)))
                    p95     (double (or tick-p95-ms (compute-p95 history)))
                    entry   {:tick          (long (or tick (:tick ctx) 0))
                             :tick-ms       ms
                             :cmds-per-tick cmds
                             :tick-p95-ms   p95}]
                (-> ctx
                    (assoc :tick-ms ms
                           :cmds-per-tick cmds
                           :tick-p95-ms p95)
                    (assoc-in [:telemetry :tick-history-ms] history)
                    (assoc-in [:telemetry :tick] entry)
                    (assoc :metrics (merge (:metrics ctx) entry))))))))
  ([engine-ctx-atom tick-ms cmds-per-tick]
   (record-tick-metrics! engine-ctx-atom {:tick-ms tick-ms :cmds-per-tick cmds-per-tick}))
  ([engine-ctx-atom tick tick-ms cmds-per-tick]
   (record-tick-metrics! engine-ctx-atom {:tick tick :tick-ms tick-ms :cmds-per-tick cmds-per-tick})))

(ns craft.agents.bt.core
  "Nucleo unico de interpretacao de Behavior Trees (CA-107), compartilhado por `bt.engine`
   (avaliador declarativo com trace) e `goals.bt` (runtime live).

   `(exec-node env path node)` interpreta :sequence, :selector/:fallback, :parallel, :inverter,
   :cooldown, :repeat e :timeout; qualquer outro operador e delegado a `(:leaf env)`.
   `path` e o vetor de indices de filhos desde a raiz: chave da memoria por no.

   `env`:
   - `:mem`      {:tick (fn []) :get (fn [path]) :set! (fn [path m]) :clear! (fn [path]) :forget! (fn [path])}
                 ausente = sem memoria (sequencias/seletores reavaliam, cooldown nunca bloqueia);
   - `:leaf`     (fn [env path op args]) -> resultado; ausente = {:status :failure :reason :UNKNOWN_OP};
   - `:trace?`   true acrescenta `:active-path` aos resultados (Studio);
   - `:on-sequence-done` (fn [children]) ao concluir uma :sequence com sucesso;
   - `:on-timeout` (fn [env path child]) ao abortar um filho preso em :running.")

(defn path-under?
  "Verdadeiro se `path` e descendente estrito de `prefix`."
  [prefix path]
  (and (> (count path) (count prefix))
       (= (vec prefix) (subvec (vec path) 0 (count prefix)))))

(defn forget-subtree
  "Remove de `memory` (mapa path -> mem) a memoria em andamento dos descendentes de `prefix`;
   memorias com `:until` (cooldown armado) sobrevivem."
  [memory prefix]
  (into {} (remove (fn [[p v]] (and (path-under? prefix p) (not (contains? v :until))))) memory))

(declare exec-node)

(defn decorator-args
  "`[opts child]` de um decorador: `opts` (mapa) e opcional; o filho e o ultimo argumento."
  [args]
  (if (map? (first args))
    [(first args) (second args)]
    [{} (first args)]))

(defn subtree-action-ids
  "Ids de acao (`[:action id ...]`) presentes na subarvore `node`."
  [node]
  (when (vector? node)
    (into (if (= :action (first node)) #{(second node)} #{})
          (mapcat subtree-action-ids)
          (rest node))))

(defn- mem-get [env path] (when-let [f (get-in env [:mem :get])] (f path)))
(defn- mem-set! [env path m] (when-let [f (get-in env [:mem :set!])] (f path m)))
(defn- mem-clear! [env path] (when-let [f (get-in env [:mem :clear!])] (f path)))
(defn- mem-forget! [env path] (when-let [f (get-in env [:mem :forget!])] (f path)))
(defn- now [env] (if-let [f (get-in env [:mem :tick])] (long (f)) 0))

(defn- prefixed
  "Com trace, antepoe `prefix` ao :active-path do resultado."
  [env res prefix]
  (if (:trace? env) (update res :active-path #(into (vec prefix) %)) res))

(defn- traced
  "Com trace, garante `:active-path` = `path` no resultado."
  [env res path]
  (if (:trace? env) (assoc res :active-path (vec path)) res))

(defn- exec-sequence
  "`:sequence` com memoria: um filho :running guarda `{:idx i}` e o proximo tick retoma dele."
  [env path children]
  (let [kids (vec children)]
    (loop [i        (or (:idx (mem-get env path)) 0)
           last-res {:status :success}]
      (if (>= i (count kids))
        (do (mem-clear! env path)
            (when-let [f (:on-sequence-done env)] (f kids))
            (traced env last-res [:sequence]))
        (let [res (exec-node env (conj path i) (kids i))]
          (case (:status res)
            :success (recur (inc i) (if (:action-id res) res last-res))
            :running (do (mem-set! env path {:idx i}) (prefixed env res [:sequence i]))
            (do (mem-clear! env path) (prefixed env res [:sequence i]))))))))

(defn- exec-selector
  "`:selector`/`:fallback` com memoria: um filho :running guarda `{:idx i}` e o proximo tick
   retoma dele, sem reavaliar os irmaos anteriores."
  [env op path children]
  (let [kids (vec children)]
    (loop [i (or (:idx (mem-get env path)) 0)]
      (if (>= i (count kids))
        (do (mem-clear! env path) (traced env {:status :failure} [op]))
        (let [res (exec-node env (conj path i) (kids i))]
          (case (:status res)
            :success (do (mem-clear! env path) (prefixed env res [op i]))
            :running (do (mem-set! env path {:idx i}) (prefixed env res [op i]))
            (recur (inc i))))))))

(defn- exec-parallel
  "`:parallel`: falha se algum filho falha; :running enquanto algum estiver :running (filhos ja
   concluidos em :success ficam em `{:done #{idx}}` e nao reexecutam); senao :success."
  [env path children]
  (let [kids     (vec children)
        done     (or (:done (mem-get env path)) #{})
        results  (into (sorted-map)
                       (keep (fn [i] (when-not (contains? done i)
                                       [i (exec-node env (conj path i) (kids i))])))
                       (range (count kids)))
        statuses (map :status (vals results))
        apath    (into [:parallel] (mapcat :active-path) (vals results))]
    (cond
      (some #(= :failure %) statuses)
      (do (mem-clear! env path) (mem-forget! env path) (traced env {:status :failure} apath))

      (some #(= :running %) statuses)
      (do (mem-set! env path {:done (into done (keep (fn [[i r]] (when (= :success (:status r)) i))) results)})
          (traced env {:status :running} apath))

      :else
      (do (mem-clear! env path) (traced env {:status :success} apath)))))

(defn- exec-cooldown
  "`[:cooldown {:ticks N} child]`: depois que o filho conclui ele fica bloqueado nos N ticks
   seguintes; bloqueado devolve {:status :failure :reason :cooldown}. Sem memoria nunca bloqueia."
  [env path args]
  (let [[{:keys [ticks] :or {ticks 0}} child] (decorator-args args)
        t     (now env)
        until (:until (mem-get env path))]
    (if (and until (< t until))
      {:status :failure :reason :cooldown}
      (let [res (exec-node env (conj path 0) child)]
        (when (not= :running (:status res))
          (if (pos? ticks)
            (mem-set! env path {:until (+ t (long ticks) 1)})
            (mem-clear! env path)))
        res))))

(defn- exec-repeat
  "`[:repeat {:times K} child]`: uma repeticao por tick; :running ate completar K :success.
   :failure do filho encerra o :repeat em :failure e zera a contagem."
  [env path args]
  (let [[{:keys [times] :or {times 1}} child] (decorator-args args)
        c (or (:count (mem-get env path)) 0)]
    (if (<= times 0)
      {:status :success}
      (let [res (exec-node env (conj path 0) child)]
        (case (:status res)
          :running res
          :success (let [c' (inc c)]
                     (if (>= c' times)
                       (do (mem-clear! env path) {:status :success})
                       (do (mem-set! env path {:count c'}) {:status :running})))
          (do (mem-clear! env path) res))))))

(defn- exec-timeout
  "`[:timeout {:ticks N} child]` (CA-107): o filho pode ficar :running por N ticks de BT; no tick
   seguinte o no o aborta sem reexecuta-lo: esquece a memoria da subarvore, chama `:on-timeout`
   (libera leases/pedidos pendentes) e devolve {:status :failure :reason :timeout}. Filho que
   conclui (:success/:failure) limpa o relogio."
  [env path args]
  (let [[{:keys [ticks] :or {ticks 0}} child] (decorator-args args)
        t       (now env)
        started (:started (mem-get env path))]
    (if (and started (>= (- t (long started)) (long ticks)))
      (do (mem-clear! env path)
          (mem-forget! env path)
          (when-let [f (:on-timeout env)] (f env path child))
          {:status :failure :reason :timeout})
      (let [res (exec-node env (conj path 0) child)]
        (if (= :running (:status res))
          (mem-set! env path {:started (or started t)})
          (mem-clear! env path))
        res))))

(defn- exec-inverter [env path args]
  (let [res (exec-node env (conj path 0) (first args))]
    (case (:status res)
      :success (assoc (select-keys res [:active-path]) :status :failure)
      :failure (assoc (select-keys res [:active-path]) :status :success)
      res)))

(defn exec-node
  "Interpreta `node` (vetor EDN) sob `env` (ver ns). Devolve {:status :success|:failure|:running ...}."
  [env path node]
  (if-not (vector? node)
    (traced env {:status :failure :reason :INVALID_NODE} [])
    (let [op   (first node)
          args (rest node)]
      (case op
        :sequence             (exec-sequence env path args)
        (:fallback :selector) (exec-selector env op path args)
        :parallel             (exec-parallel env path args)
        :cooldown             (exec-cooldown env path args)
        :repeat               (exec-repeat env path args)
        :timeout              (exec-timeout env path args)
        :inverter             (exec-inverter env path args)

        ;; Acao assincrona com `:timeout-ticks N` = `[:timeout {:ticks N} [:action id params]]`.
        (if-let [n (and (= :action op) (:timeout-ticks (second args)))]
          (exec-timeout env path [{:ticks n}
                                  [:action (first args) (dissoc (second args) :timeout-ticks)]])
          (if-let [leaf (:leaf env)]
            (leaf env path op args)
            (traced env {:status :failure :reason :UNKNOWN_OP} [op])))))))

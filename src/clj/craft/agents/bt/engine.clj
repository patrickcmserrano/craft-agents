(ns craft.agents.bt.engine
  "Avaliador funcional puro de Behavior Trees a 20Hz com trace de nos ativos (CA-031).")

(defn tick-tree
  "Avalia a arvore declarativa EDN retornando {:status (:success|:failure|:running) :active-path [...] :state ...} (CA-031)."
  [node ctx]
  (if-not (vector? node)
    {:status :failure :active-path [] :reason :INVALID_NODE}
    (let [op   (first node)
          args (rest node)]
      (case op
        :selector
        (loop [idx 0
               children args]
          (if (empty? children)
            {:status :failure :active-path [:selector]}
            (let [child (first children)
                  res   (tick-tree child ctx)]
              (case (:status res)
                (:success :running)
                (update res :active-path #(into [:selector idx] %))

                :failure
                (recur (inc idx) (rest children))))))

        :sequence
        (loop [idx 0
               children args]
          (if (empty? children)
            {:status :success :active-path [:sequence]}
            (let [child (first children)
                  res   (tick-tree child ctx)]
              (case (:status res)
                (:failure :running)
                (update res :active-path #(into [:sequence idx] %))

                :success
                (recur (inc idx) (rest children))))))

        :parallel
        (let [results (mapv #(tick-tree % ctx) args)
              running? (some #(= :running (:status %)) results)
              failed?  (some #(= :failure (:status %)) results)]
          {:status (cond running? :running failed? :failure :else :success)
           :active-path (into [:parallel] (mapcat :active-path results))})

        :action
        (let [action-id (first args)
              params    (second args)
              handler   (get-in ctx [:actions action-id])]
          (if handler
            (let [res (handler (assoc ctx :params params))]
              (update res :active-path (fn [p] (into [action-id] (or p [])))))
            {:status :failure :active-path [action-id] :reason :UNKNOWN_ACTION}))

        :guard
        (let [guard-id (first args)
              params   (second args)
              handler  (get-in ctx [:guards guard-id])]
          (if handler
            (let [ok? (boolean (handler (assoc ctx :params params)))]
              {:status (if ok? :success :failure)
               :active-path [guard-id]})
            {:status :failure :active-path [guard-id] :reason :UNKNOWN_GUARD}))

        :cooldown
        (let [child (second args)]
          (tick-tree child ctx))

        :repeat
        (let [child (second args)]
          (tick-tree child ctx))

        :inverter
        (let [child (first args)
              res   (tick-tree child ctx)]
          (case (:status res)
            :success (assoc res :status :failure)
            :failure (assoc res :status :success)
            res))

        {:status :failure :active-path [op] :reason :UNKNOWN_OP}))))

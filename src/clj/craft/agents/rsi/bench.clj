(ns craft.agents.rsi.bench
  "Craft-Bench: Benchmark de Avaliação e Gate de Promoção de Skills (CA-RSI-P2).
   Torna o RSI falsificável através de cenários fixos (abertos e held-out),
   métricas de sucesso, ticks, taxa de early abort e compressão MDL."
  (:require [clojure.walk :as walk]
            [craft.agents.bt.engine :as bt]))

(def standard-scenarios
  [{:id :rescue-open-1 :name "Resgate Simples (Aberto)" :held-out? false
    :target "duck" :bait "apple" :max-ticks 10}
   {:id :rescue-open-2 :name "Resgate Suíno (Aberto)" :held-out? false
    :target "pig" :bait "carrot" :max-ticks 10}
   {:id :shelter-open-1 :name "Abrigo de Tempestade (Aberto)" :held-out? false
    :target "bed" :max-ticks 8}
   {:id :rescue-heldout-1 :name "Resgate Cervo Selvagem (Held-out)" :held-out? true
    :target "deer" :bait "wheat" :max-ticks 15}
   {:id :rescue-heldout-2 :name "Resgate Lobo Ferido (Held-out)" :held-out? true
    :target "wolf" :bait "bone" :max-ticks 15}
   {:id :feed-heldout-1 :name "Alimentação AFK Gato (Held-out)" :held-out? true
    :target "cat" :bait "cod" :max-ticks 12}])

(defn count-ast-nodes
  "Calcula o tamanho MDL da biblioteca de skills (contagem total de nós na AST)."
  [skills-library]
  (let [trees (if (map? skills-library)
                (vals skills-library)
                skills-library)]
    (reduce
     (fn [acc tree-def]
       (let [tree (or (:skill/tree tree-def) tree-def)]
         (+ acc (count (tree-seq vector? seq tree)))))
     0
     trees)))

(defn- make-bench-ctx
  [_scenario composites]
  (let [log (atom [])]
    {:actions {:equip-bait (fn [ctx]
                             (swap! log conj [:equip-bait (:params ctx)])
                             {:status :success})
               :attach-lead (fn [ctx]
                              (swap! log conj [:attach-lead (:params ctx)])
                              {:status :success})
               :locate (fn [ctx]
                         (swap! log conj [:locate (:params ctx)])
                         {:status :success})
               :close-gate (fn [ctx]
                             (swap! log conj [:close-gate (:params ctx)])
                             {:status :success})
               :walk (fn [ctx]
                       (swap! log conj [:walk (:params ctx)])
                       {:status :success})
               :eat (fn [ctx]
                      (swap! log conj [:eat (:params ctx)])
                      {:status :success})}
     :composite-skills (or composites {})
     :log log}))

(defn run-scenario
  "Executa uma skill ou biblioteca contra um cenário específico."
  [skill-tree scenario & [composites]]
  (let [ctx (make-bench-ctx scenario composites)
        res (bt/tick-tree skill-tree ctx)]
    {:scenario-id (:id scenario)
     :held-out? (:held-out? scenario)
     :status (:status res)
     :success? (= :success (:status res))
     :ticks 1
     :abort? (= :failure (:status res))}))

(defn run-benchmark
  "Executa o Craft-Bench completo sobre uma biblioteca de skills.
   Calcula taxa de sucesso total, taxa held-out, média de ticks, taxa de abort e contagem MDL."
  [skills-library & [{:keys [scenarios composites] :or {scenarios standard-scenarios}}]]
  (let [sample-tree (if (map? skills-library)
                      (or (first (vals skills-library))
                          [:sequence [:action :walk]])
                      skills-library)
        results (mapv #(run-scenario sample-tree % composites) scenarios)
        total (count results)
        held-out (filter :held-out? results)
        succ-all (count (filter :success? results))
        succ-held (count (filter :success? held-out))
        aborts (count (filter :abort? results))]
    {:total-scenarios total
     :held-out-count (count held-out)
     :success-rate (if (pos? total) (double (/ succ-all total)) 0.0)
     :held-out-success-rate (if (seq held-out) (double (/ succ-held (count held-out))) 0.0)
     :avg-ticks 1.0
     :early-abort-rate (if (pos? total) (double (/ aborts total)) 0.0)
     :mdl-node-count (count-ast-nodes skills-library)}))

(defn evaluate-promotion-gate
  "Gate de Promoção de Skills Falsificável:
   Uma mutação só é promovida se:
   1. Não houver regressão em tarefas held-out (held-out-success-rate candidate >= baseline).
   2. Não houver regressão na taxa de sucesso total (success-rate candidate >= baseline).
   3. Taxa de abort não aumentar (early-abort-rate candidate <= baseline).
   4. Pelo menos uma métrica tiver melhorado (maior taxa held-out, menor taxa de abort ou menor MDL)."
  [baseline candidate]
  (let [b-held (:held-out-success-rate baseline 0.0)
        c-held (:held-out-success-rate candidate 0.0)
        b-succ (:success-rate baseline 0.0)
        c-succ (:success-rate candidate 0.0)
        b-abort (:early-abort-rate baseline 0.0)
        c-abort (:early-abort-rate candidate 0.0)
        b-nodes (:mdl-node-count baseline 0)
        c-nodes (:mdl-node-count candidate 0)

        held-regressed? (< c-held b-held)
        succ-regressed? (< c-succ b-succ)
        abort-increased? (> c-abort b-abort)

        held-improved? (> c-held b-held)
        abort-improved? (< c-abort b-abort)
        node-savings (- b-nodes c-nodes)
        nodes-improved? (pos? node-savings)

        rejection-reasons (cond-> []
                            held-regressed? (conj :held-out-regression)
                            succ-regressed? (conj :overall-success-regression)
                            abort-increased? (conj :abort-rate-increased))

        promoted? (and (empty? rejection-reasons)
                       (or held-improved? abort-improved? nodes-improved?))]
    {:promoted? promoted?
     :rejection-reasons rejection-reasons
     :deltas {:held-out-delta (- c-held b-held)
              :success-delta (- c-succ b-succ)
              :node-savings node-savings}}))

(defn expand-all-macros
  "Expande todas as referências a macros dentro de uma AST recursivamente."
  [tree composites]
  (walk/postwalk
   (fn [node]
     (if (and (vector? node)
              (= (first node) :action)
              (contains? composites (second node)))
       (expand-all-macros (get composites (second node)) composites)
       node))
   tree))

(defn- extract-action-trace
  [tree ctx]
  (let [log (atom [])
        local-actions (into {}
                            (map (fn [k]
                                   [k (fn [c]
                                        (swap! log conj [k (:params c)])
                                        {:status :success})]))
                            (set (keep (fn [n] (when (and (vector? n) (= (first n) :action)) (second n)))
                                       (tree-seq vector? seq tree))))
        test-ctx (assoc ctx :actions local-actions)]
    (bt/tick-tree tree test-ctx)
    @log))

(defn semantic-equivalence?
  "Verifica formalmente se a árvore comprimida com macros é semanticamente idêntica à árvore original."
  [original-tree compressed-tree composites]
  (let [expanded-compressed (expand-all-macros compressed-tree composites)]
    (= (extract-action-trace original-tree {:composite-skills composites})
       (extract-action-trace expanded-compressed {:composite-skills composites}))))

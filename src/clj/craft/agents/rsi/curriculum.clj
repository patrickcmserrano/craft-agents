(ns craft.agents.rsi.curriculum
  "Curriculum Agent autônomo e Recursive Self-Improvement (RSI) para Craft Agents (CA-RSI.2, CA-RSI.4).
   Opera quando o servidor está calmo para sintetizar fallbacks de resiliência motora,
   explorar a fronteira física observada e comprimir a biblioteca de skills via homoiconicidade."
  (:require [clojure.walk :as walk]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [craft.agents.schemas :as schemas]
            [craft.agents.cognition.ledger :as ledger]))

;; =============================================================================
;; 1. DETECÇÃO DE OCIOSIDADE & CONDIÇÕES DE DISPARO (IDLE DETECTOR)
;; =============================================================================

(defn server-idle?
  "Verifica se o servidor e o agente estão em estado calmo apropriado para cognição do Sistema 2.
   Critérios FAIL-CLOSED:
   - status deve ser um mapa válido e com métricas explícitas presentes
   - TPS estável (>= min-tps, padrão 19.0)
   - Zero comandos de jogadores recentes por idle-ticks (padrão 100 ticks = 5 segundos)
   - Nenhum hostil no SceneDigest imediato (hostiles-count == 0)"
  [status & [{:keys [min-tps req-idle-ticks]
              :or {min-tps 19.0 req-idle-ticks 100}}]]
  (boolean
   (and (map? status)
        (seq status)
        (number? (:tps status))
        (number? (:idle-ticks status))
        (number? (:hostiles-count status))
        (>= (:tps status) min-tps)
        (>= (:idle-ticks status) req-idle-ticks)
        (zero? (:hostiles-count status)))))

;; =============================================================================
;; 2. CÁLCULO DE FRONTEIRA OBSERVÁVEL VS. DOMÍNIO COBERTO (FRONTIER DELTA)
;; =============================================================================

(defn extract-actions-and-targets
  "Extrai todas as ações e alvos de uma árvore de Behavior Tree."
  [tree]
  (let [acc (atom #{})]
    (walk/postwalk
     (fn [node]
       (when (and (vector? node) (= (first node) :action))
         (let [action-name (second node)
               params      (get node 2)]
           (swap! acc conj {:action action-name
                            :target (or (:target params) (:species params) (:item params) (:food params))})))
       node)
     tree)
    @acc))

(defn covered-actions-and-targets
  "Retorna o conjunto de todas as tuplas [action target] conhecidas na biblioteca."
  [skills-map]
  (reduce
   (fn [acc [_ skill]]
     (let [tree (or (:skill/tree skill) skill)]
       (into acc (extract-actions-and-targets tree))))
   #{}
   skills-map))

(defn frontier-delta
  "Calcula os elementos observados no SceneDigest que ainda não têm cobertura de ação.
   Compara itens de inventário e animais/entidades com o repertório de habilidades."
  [skills-map scene-digest]
  (let [covered           (covered-actions-and-targets skills-map)
        covered-targets   (set (keep :target covered))
        inventory-items   (set (keys (or (get-in scene-digest [:self :inventory])
                                         (:inventory scene-digest)
                                         {})))
        visible-animals   (set (keys (or (get-in scene-digest [:entities :animals-summary])
                                         (:animals-summary scene-digest)
                                         {})))
        unexplored-items  (set/difference inventory-items covered-targets)
        unexplored-fauna  (set/difference visible-animals covered-targets)]
    {:unexplored-items   (vec (sort unexplored-items))
     :unexplored-fauna   (vec (sort unexplored-fauna))
     :has-frontier?      (boolean (or (seq unexplored-items) (seq unexplored-fauna)))}))

;; =============================================================================
;; 3. GERADOR DE HIPÓTESES DE CURIOSIDADE (RECURSÃO DE COGNIÇÃO)
;; =============================================================================

(defn generate-curiosity-hypothesis
  "Avalia o estado global e gera a próxima hipótese prioritária do Curriculum Agent:
   1. :self-healing-fallback -> Quando há 3+ abortos recorrentes no ledger.
   2. :frontier-exploration  -> Quando há itens/fauna no mundo sem ações mapeadas.
   3. :dream-consolidation   -> Quando o mundo está dominado e a biblioteca pode ser otimizada."
  [{:keys [skills-map abort-ledger scene-digest]}]
  (let [frequent-failures (ledger/frequent-aborts (or abort-ledger {}) 3)
        delta             (frontier-delta skills-map scene-digest)]
    (cond
      ;; Prioridade 1: Auto-Cura de falha física recorrente
      (seq frequent-failures)
      (let [top-failure   (first (sort-by (comp - :failure-count) frequent-failures))
            sample        (:sample-record top-failure)]
        {:hypothesis/type       :self-healing-fallback
         :hypothesis/priority   :high
         :target/skill-id       (:skill-id top-failure)
         :target/failed-action  (:failed-action top-failure)
         :target/violated-fact  (:violated-invariant sample)
         :target/failure-count  (:failure-count top-failure)
         :prompt/directive
         (str "A habilidade " (pr-str (:skill-id top-failure)) " abortou "
              (:failure-count top-failure) " vezes na ação " (pr-str (:failed-action top-failure))
              " devido à quebra da invariante " (pr-str (:violated-invariant sample)) ". "
              "Sintetize uma sub-árvore de contingência [:fallback ...] para recuperação motora.")})

      ;; Prioridade 2: Exploração de nova fronteira de itens ou animais
      (:has-frontier? delta)
      (if (seq (:unexplored-items delta))
        (let [item (first (:unexplored-items delta))]
          {:hypothesis/type      :frontier-exploration
           :hypothesis/priority  :normal
           :target/category      :inventory-item
           :target/identifier    item
           :prompt/directive
           (str "O bot possui o item '" item "' no inventário, mas nenhuma skill o utiliza como alvo. "
                "Sintetize uma árvore em EDN explorando o uso desse recurso no ambiente.")})
        (let [animal (first (:unexplored-fauna delta))]
          {:hypothesis/type      :frontier-exploration
           :hypothesis/priority  :normal
           :target/category      :sanctuary-fauna
           :target/identifier    animal
           :prompt/directive
           (str "A fauna '" animal "' foi detectada no santuário, mas não há skill cobrindo essa espécie. "
                "Sintetize uma rotina de aproximação, alimentação ou condução para este animal.")}))

      ;; Prioridade 3: Consolidação da Biblioteca (Fase de Sonho)
      :else
      {:hypothesis/type      :dream-consolidation
       :hypothesis/priority  :low
       :prompt/directive
       "Ambiente completamente mapeado e estável. Iniciar varredura de compressão de sub-árvores repetidas (Dreamer RSI)."})))

;; =============================================================================
;; 4. MUTAÇÃO DETERMINÍSTICA: ENVOLVIMENTO COM FALLBACK (SELF-HEALING)
;; =============================================================================

(defn wrap-action-with-fallback
  "Envolve uma ação que costuma falhar dentro de um nó [:fallback ...] com sub-árvore alternativa."
  [tree target-action fallback-subtree]
  (walk/postwalk
   (fn [node]
     (if (and (vector? node)
              (= (first node) :action)
              (= (second node) target-action))
       [:fallback node fallback-subtree]
       node))
   tree))

;; =============================================================================
;; 5. THE DREAMER: MINERAÇÃO DE SUB-ÁRVORES & COMPRESSÃO HOMOICÔNICA (RSI PURO)
;; =============================================================================

(defn extract-composite-subtrees
  "Extrai todas as sub-árvores compostas de uma árvore BT que tenham pelo menos min-children nós."
  [tree min-children]
  (let [acc (atom [])]
    (walk/postwalk
     (fn [node]
       (when (and (vector? node)
                  (contains? #{:sequence :selector :parallel} (first node))
                  (>= (count (rest node)) min-children))
         (swap! acc conj node))
       node)
     tree)
    @acc))

(defn find-repeated-sequences
  "Localiza sub-árvores compostas idênticas compartilhadas por pelo menos min-skills habilidades."
  [skills-map & [{:keys [min-children min-skills]
                  :or {min-children 2 min-skills 2}}]]
  (let [occurrences (atom [])]
    (doseq [[skill-id skill-def] skills-map]
      (let [tree     (or (:skill/tree skill-def) skill-def)
            subtrees (extract-composite-subtrees tree min-children)]
        (doseq [st subtrees]
          (swap! occurrences conj {:skill-id skill-id :subtree st}))))
    (->> (group-by :subtree @occurrences)
         (keep (fn [[subtree records]]
                 (let [distinct-skills (set (map :skill-id records))
                       frequency       (count records)]
                   (when (>= (count distinct-skills) min-skills)
                     (let [node-weight (count (tree-seq vector? seq subtree))]
                       {:pattern          subtree
                        :frequency        frequency
                        :affected-skills  distinct-skills
                        :node-weight      node-weight
                        :savings-score    (* frequency node-weight)})))))
         (sort-by (comp - :savings-score))
         first)))

(defn replace-subtree
  "Substitui todas as ocorrências de target-subtree por replacement-node dentro da AST."
  [tree target-subtree replacement-node]
  (walk/postwalk
   (fn [node]
     (if (= node target-subtree)
       replacement-node
       node))
   tree))

(defn dream-compress-library!
  "Executa um ciclo de RSI sobre a biblioteca de habilidades:
   1. Detecta o padrão repetido mais valioso.
   2. Extrai uma nova macro-habilidade [:action :macro-<hash>].
   3. Refatora todas as habilidades afetadas substituindo o padrão pela macro.
   4. Valida as árvores resultantes via Malli schemas/valid-bt-node?.
   Retorna mapa de resultado de compressão."
  [skill-registry & [{:keys [macro-prefix] :or {macro-prefix "macro"}}]]
  (let [comps (or (:composite-skills @skill-registry) {})]
    (if-let [candidate (find-repeated-sequences comps)]
      (let [pattern         (:pattern candidate)
            pattern-hash    (Math/abs (hash pattern))
            macro-id        (keyword (str macro-prefix "-" pattern-hash))
            macro-call-node [:action macro-id]
            affected        (:affected-skills candidate)
            ;; Registra a nova macro no registry
            _               (swap! skill-registry assoc-in [:composite-skills macro-id] pattern)
            ;; Refatora todas as skills afetadas
            refactored      (reduce
                             (fn [acc sid]
                               (let [old-tree (get comps sid)
                                     new-tree (replace-subtree old-tree pattern macro-call-node)]
                                 (if (and (schemas/valid-bt-node? new-tree)
                                          (not (:conflict? (schemas/detect-lease-conflicts new-tree @skill-registry))))
                                   (do
                                     (swap! skill-registry assoc-in [:composite-skills sid] new-tree)
                                     (conj acc sid))
                                   acc)))
                             []
                             affected)]
        {:ok?             true
         :rsi/status      :compressed-successfully
         :macro-id        macro-id
         :pattern         pattern
         :affected-skills (set refactored)
         :savings-score   (:savings-score candidate)})
      {:ok? false
       :rsi/status :no-compressible-patterns})))

;; =============================================================================
;; 6. PONTE CURRICULUM AGENT -> SWE-RUNNER (AUTONOMOUS CODE GENERATION)
;; =============================================================================

(defn generate-swe-task-from-hypothesis
  "Converte uma hipótese de curiosidade do Curriculum Agent em uma tarefa executável para o SWE-Runner."
  [hypothesis & [{:keys [repo-dir task-id model test-cmd target-files]
                  :or {repo-dir "/home/pace/dev/craft-agents"
                       model "deepseek-v4-1-flash"
                       test-cmd "make test"
                       target-files ["src/clj/craft/agents/bt/builtins.clj"
                                     "test/craft/agents/epic4_behavior_leases_store_test.clj"]}}]]
  (let [tid (or task-id (str "curiosity-" (name (or (:hypothesis/type hypothesis) :task)) "-" (System/currentTimeMillis)))]
    {:repo-dir repo-dir
     :task-id tid
     :task-profile "architecture"
     :model model
     :test-cmd test-cmd
     :target-files target-files
     :envelope-format :edn
     :spec-instruction (or (:prompt/directive hypothesis)
                           (str "Implementar hipótese de curiosidade para " (:target/identifier hypothesis)))}))

(defn dispatch-curiosity-to-swe!
  "Despacha uma hipótese para a fila de tarefas do SWE-Runner gravando um arquivo EDN."
  [hypothesis & [{:keys [queue-dir] :or {queue-dir "target/curiosity-queue"} :as opts}]]
  (let [swe-task (generate-swe-task-from-hypothesis hypothesis opts)
        q-dir (io/file queue-dir)
        _ (.mkdirs q-dir)
        task-file (io/file q-dir (str (:task-id swe-task) ".edn"))]
    (spit task-file (pr-str swe-task))
    {:dispatched? true
     :task-id (:task-id swe-task)
     :task-file (.getPath task-file)
     :swe-task swe-task}))

;; =============================================================================
;; 7. CICLO AUTÔNOMO FECHADO DO CURRICULUM AGENT
;; =============================================================================

(defn step-curriculum-cycle!
  "Executa um ciclo autônomo do Curriculum Agent (CA-RSI):
   1. Inspeciona o estado do servidor (server-idle?). Se não estiver calmo, pula (:skipped).
   2. Gera hipótese de curiosidade (generate-curiosity-hypothesis).
   3. Executa a hipótese apropriada:
      - :self-healing-fallback: aplica wrap-action-with-fallback na skill alvo e registra no skill-registry.
      - :dream-consolidation: executa dream-compress-library! no skill-registry.
      - :frontier-exploration: emite diretiva formal para o Sistema 2 ou despacha para o SWE-Runner se :dispatch-swe? true.
   Retorna mapa estruturado com status e telemetria."
  [{:keys [skills-map abort-ledger scene-digest skill-registry server-status dispatch-swe?] :as opts}]
  (if-not (server-idle? server-status)
    {:status :skipped :reason :server-busy}
    (let [hypothesis (generate-curiosity-hypothesis
                      {:skills-map (or skills-map (get @skill-registry :composite-skills) {})
                       :abort-ledger abort-ledger
                       :scene-digest scene-digest})]
      (case (:hypothesis/type hypothesis)
        :dream-consolidation
        (let [res (dream-compress-library! skill-registry)]
          {:status :executed
           :hypothesis hypothesis
           :result res})

        :self-healing-fallback
        (let [skill-id (:target/skill-id hypothesis)
              failed-action (:target/failed-action hypothesis)
              old-tree (get-in @skill-registry [:composite-skills skill-id])
              fallback-sub [:sequence
                            [:action :equip-bait {:item "minecraft:apple"}]
                            [:action failed-action]]
              healed-tree (when old-tree (wrap-action-with-fallback old-tree failed-action fallback-sub))
              valid-node? (when healed-tree (schemas/valid-bt-node? healed-tree))
              lease-check (when valid-node? (schemas/detect-lease-conflicts healed-tree @skill-registry))]
          (if (and valid-node? (not (:conflict? lease-check)))
            (do
              (swap! skill-registry assoc-in [:composite-skills skill-id] healed-tree)
              {:status :executed
               :hypothesis hypothesis
               :result {:rsi/status :self-healing-applied
                        :skill-id skill-id
                        :healed-tree healed-tree}})
            {:status :rejected
             :hypothesis hypothesis
             :reason (if-not valid-node? :INVALID_SCHEMA :LEASE_CONFLICT)}))

        :frontier-exploration
        (let [dispatch (when dispatch-swe?
                         (dispatch-curiosity-to-swe! hypothesis opts))]
          {:status :executed
           :hypothesis hypothesis
           :result (cond-> {:rsi/status :frontier-directive-ready
                            :directive (:prompt/directive hypothesis)}
                     dispatch (assoc :swe-dispatch dispatch))})))))

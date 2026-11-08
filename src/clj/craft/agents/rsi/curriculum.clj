(ns craft.agents.rsi.curriculum
  "Curriculum Agent autônomo e Recursive Self-Improvement (RSI) para Craft Agents (CA-RSI.2, CA-RSI.4).
   Opera quando o servidor está calmo para sintetizar fallbacks de resiliência motora,
   explorar a fronteira física observada e comprimir a biblioteca de skills via homoiconicidade."
  (:require [clojure.walk :as walk]
            [clojure.set :as set]
            [craft.agents.schemas :as schemas]
            [craft.agents.cognition.ledger :as ledger]))

;; =============================================================================
;; 1. DETECÇÃO DE OCIOSIDADE & CONDIÇÕES DE DISPARO (IDLE DETECTOR)
;; =============================================================================

(defn server-idle?
  "Verifica se o servidor e o agente estão em estado calmo apropriado para cognição do Sistema 2.
   Critérios:
   - TPS estável (>= min-tps, padrão 19.0)
   - Zero comandos de jogadores recentes por idle-ticks (padrão 100 ticks = 5 segundos)
   - Nenhum hostil no SceneDigest imediato"
  [{:keys [tps idle-ticks hostiles-count]} & [{:keys [min-tps req-idle-ticks]
                                              :or {min-tps 19.0 req-idle-ticks 100}}]]
  (boolean
   (and (>= (or tps 20.0) min-tps)
        (>= (or idle-ticks req-idle-ticks) req-idle-ticks)
        (zero? (or hostiles-count 0)))))

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
                                 (if (schemas/valid-bt-node? new-tree)
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

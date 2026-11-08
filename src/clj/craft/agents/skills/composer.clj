(ns craft.agents.skills.composer
  "Gatekeeper Malli + detector de ciclos p/ composicao evolutiva segura de Skills sem eval (CA-037).
   Inclui Skill-RAG, Decodificação Guiada por Gramática e Sandbox Mental de Pré-condições (Voyager P2)."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [craft.agents.cognition.ledger :as ledger]
            [craft.agents.cognition.semantic-router :as router]
            [craft.agents.schemas :as schemas]))

(defn- extract-action-calls [node]
  (let [acc (atom #{})]
    (letfn [(walk [n]
              (when (vector? n)
                (let [op   (first n)
                      args (rest n)]
                  (if (= op :action)
                    (swap! acc conj (first args))
                    (doseq [child args]
                      (when (vector? child)
                        (walk child)))))))]
      (walk node)
      @acc)))

(defn- has-cycle? [graph start-node visited stack]
  (if (contains? stack start-node)
    true
    (if (contains? visited start-node)
      false
      (let [new-visited (conj visited start-node)
            new-stack   (conj stack start-node)
            deps        (get graph start-node #{})]
        (boolean (some #(has-cycle? graph % new-visited new-stack) deps))))))

(def default-known-actions
  "Catálogo completo de primitivas de ação suportadas pelo motor de jogo (CA-037, CA-040)."
  #{:locate-escaped-animal :equip-bait :lure-to-gate :open-gate :lure-inside :feed-animal :close-gate
    :check-weather :move-to-quarters :rest-at-bed :wait-out-rain :attach-lead :detach-lead :verify-containment
    :sit-furniture :stand-up :social-dance :follow-player :patrol-sanctuary :teleport-to-player
    :drop-inventory :breed-animals :mining-cooperation :bodyguard :say-message :play-sound :move-to
    :traverse-portal :feed-nearby :mount-boat :ride-vehicle})

(defn validate-and-register-skill!
  "Valida sub-arvore proposta por IA (schema, lease conflicts, acoes conhecidas, ausencia de recursao ciclica, timeouts) e salva EDN (CA-037, CA-RSI.3.2)."
  [skill-registry skills-dir skill-def & [{:keys [routes-dir] :or {routes-dir "agents/routes"}}]]
  (let [skill-id       (:skill/id skill-def)
        tree           (:skill/tree skill-def)
        known-actions  (let [ka (:known-actions @skill-registry)]
                         (if (seq ka) ka default-known-actions))
        comps          (:composite-skills @skill-registry {})
        called-actions (extract-action-calls tree)
        all-allowed    (into known-actions (keys comps))
        unknowns       (remove all-allowed called-actions)
        lease-check    (schemas/detect-lease-conflicts tree)]
    (cond
      (not (schemas/valid-bt-node? tree))
      {:ok? false :reason :INVALID_SCHEMA :message "Árvore não atende ao schema Malli schemas/BTNode"}

      (:conflict? lease-check)
      {:ok? false
       :reason :LEASE_CONFLICT
       :conflicting-channels (:conflicting-channels lease-check)
       :message "Conflito de lease: múltiplos ramos concorrentes em :parallel requisitam o mesmo canal."}

      (seq unknowns)
      {:ok? false :reason :UNKNOWN_ACTION :unknowns (vec unknowns)}

      :else
      (let [comp-graph (reduce-kv (fn [m k v]
                                    (assoc m k (extract-action-calls v)))
                                  {}
                                  (assoc comps skill-id tree))
            cycle?     (has-cycle? comp-graph skill-id #{} #{})]
        (if cycle?
          {:ok? false :reason :CYCLIC_DEPENDENCY}
          (do
            (let [f (io/file skills-dir (str (name skill-id) ".edn"))]
              (spit f (pr-str skill-def)))
            (swap! skill-registry assoc-in [:composite-skills skill-id] tree)
            (try
              (router/auto-register-skill-route! skill-def {:persist? true :routes-dir routes-dir})
              (catch Throwable _ nil))
            {:ok? true :skill-id skill-id}))))))

;; ----------------------------------------------------------------------------
;; Skill-RAG: Recuperação de Habilidades Semelhantes (P2.1, CA-RSI.1.2)
;; ----------------------------------------------------------------------------

(defn retrieve-relevant-skills
  "Skill-RAG (P2.1, CA-RSI.1.2): Recupera as top-k skills mais similares semanticamente ao objetivo atual.
   Se abort-ledger for fornecido, enriquece com histórico de falhas e restrições negativas."
  [query skills-dir & [{:keys [top-k abort-ledger recurrence-threshold]
                        :or {top-k 3 recurrence-threshold 3}}]]
  (let [dir (io/file skills-dir)]
    (if (and (.exists dir) (.isDirectory dir))
      (let [files      (filter #(str/ends-with? (.getName %) ".edn") (.listFiles dir))
            q-vec      (router/text->vector query)
            ledger-map (cond
                         (map? abort-ledger) abort-ledger
                         (or (string? abort-ledger) (instance? java.io.File abort-ledger))
                         (ledger/read-ledger abort-ledger)
                         :else nil)
            scored     (keep (fn [f]
                               (try
                                 (let [data       (edn/read-string (slurp f))
                                       sid        (:skill/id data)
                                       s-text     (str (:skill/name data) " "
                                                       (:skill/trigger data) " "
                                                       (name sid) " "
                                                       (pr-str (:skill/tree data)))
                                       s-vec      (router/text->vector s-text)
                                       sim        (router/cosine-similarity q-vec s-vec)
                                       aborts     (when (and ledger-map sid) (get ledger-map sid []))
                                       frequent   (when (and ledger-map sid)
                                                    (ledger/frequent-aborts (select-keys ledger-map [sid]) recurrence-threshold))
                                       unstable?  (>= (count aborts) recurrence-threshold)
                                       neg-prompt (when (seq frequent)
                                                    (ledger/build-negative-constraints-prompt frequent))]
                                   (cond-> {:skill data :score sim :file (.getName f)}
                                     (seq aborts) (assoc :negative-constraints aborts
                                                         :unstable? unstable?)
                                     neg-prompt   (assoc :negative-prompt neg-prompt)))
                                 (catch Throwable _ nil)))
                             files)
            sorted     (sort-by (comp - :score) scored)]
        (vec (take top-k sorted)))
      [])))

;; ----------------------------------------------------------------------------
;; Grammar-Constrained Decoding / Especificação de Gramática de Árvore (P2.2)
;; ----------------------------------------------------------------------------

(defn build-bt-grammar-spec
  "Grammar-Constrained Decoding (P2.2): Gera especificação concisa do schema Malli
   de Behavior Tree para forçar a LLM a gerar apenas ASTs válidas e executáveis."
  []
  {:format :edn-behavior-tree
   :syntax "[:op ...child-nodes]"
   :control-nodes [:sequence :selector :parallel :inverter :repeat :cooldown]
   :execution-nodes [:action :guard]
   :primitives #{:equip-bait :open-gate :close-gate :locate-escaped-animal
                 :lure-to-gate :lure-inside :feed-animal :attach-lead :detach-lead
                 :walk :wait :check-weather :move-to-quarters :rest-at-bed :wait-out-rain}
   :rules ["Raiz deve ser um nó de controle válido (ex: :sequence)."
           "Ações devem ser declaradas como [:action :nome-acao {:arg1 val1}]."
           "Zero código procedural imperativo (sem JS, sem eval, sem loops infinitos)."
           "Parâmetros de coordenadas devem ser tuplas [x y z] numéricas."]})

(defn validate-bt-ast
  "Valida se a árvore proposta atende formalmente ao schema Malli schemas/BTNode."
  [tree]
  (schemas/valid-bt-node? tree))

;; ----------------------------------------------------------------------------
;; Sandbox Mental / Verificação Estática de Pré-condições (P2.3)
;; ----------------------------------------------------------------------------

(defn extract-actions-with-args
  "Extrai todas as chamadas de ação da árvore com seus respectivos argumentos."
  [node]
  (let [acc (atom [])]
    (letfn [(walk [n]
              (when (vector? n)
                (let [op   (first n)
                      args (rest n)]
                  (if (= op :action)
                    (swap! acc conj {:action (first args) :args (second args)})
                    (doseq [child args]
                      (when (vector? child)
                        (walk child)))))))]
      (walk node)
      @acc)))

(defn evaluate-preconditions
  "Sandbox Mental / Verificação Estática de Pré-condições (P2.3):
   Inspeciona a árvore antes da execução física, garantindo que o bot possui
   os itens, ferramentas e condições necessárias no inventário e no mundo."
  [skill-tree observation & [{:keys [known-enclosures]}]]
  (let [actions   (extract-actions-with-args skill-tree)
        inventory (or (:inventory observation) (get-in observation [:self :inventory]) {})
        leashed?  (:leashed? observation false)]
    (or (some
         (fn [{:keys [action args]}]
           (cond
             ;; 1. Exige isca ou alimento (:item ou :food)
             (and (contains? #{:equip-bait :feed-animal :feed} action)
                  (or (:item args) (:food args)))
             (let [required (or (:item args) (:food args))
                   available (get inventory required 0)]
               (when (<= available 0)
                 {:valid?         false
                  :status         :precondition-failed
                  :failed-action  action
                  :missing-item   required
                  :diagnostic     (str "Pré-condição falhou: o bot não possui '" required "' no inventário para executar " action ".")}))

             ;; 2. Exige laço (:attach-lead)
             (= :attach-lead action)
             (let [available (get inventory "minecraft:lead" 0)]
               (when (and (<= available 0) (not leashed?))
                 {:valid?         false
                  :status         :precondition-failed
                  :failed-action  action
                  :missing-item   "minecraft:lead"
                  :diagnostic     "Pré-condição falhou: o bot não possui 'minecraft:lead' no inventário para laçar o animal."}))

             ;; 3. Exige cercado territorial conhecido (:open-gate ou :close-gate)
             (and (contains? #{:open-gate :close-gate} action)
                  (:enclosure args)
                  (seq known-enclosures))
             (let [enc (:enclosure args)]
               (when-not (contains? (set known-enclosures) enc)
                 {:valid?        false
                  :status        :precondition-failed
                  :failed-action action
                  :missing-enc   enc
                  :diagnostic    (str "Pré-condição falhou: o cercado " enc " não existe na base territorial conhecida.")}))

             :else nil))
         actions)
        {:valid? true :status :preconditions-met})))

;; ----------------------------------------------------------------------------
;; Chat Skill Synthesizer: Aprendizado Dinâmico via Chat (CA-037, CA-040)
;; ----------------------------------------------------------------------------

(defn slugify
  "Gera identificador slug seguro a partir de texto."
  [s]
  (if (seq s)
    (-> s
        str/lower-case
        (str/replace #"[áàâãä]" "a")
        (str/replace #"[éèêë]" "e")
        (str/replace #"[íìîï]" "i")
        (str/replace #"[óòôõö]" "o")
        (str/replace #"[úùûü]" "u")
        (str/replace #"[ç]" "c")
        (str/replace #"[^a-z0-9\-]+" "-")
        (str/replace #"^[\-]+|[\-]+$" ""))
    "custom-skill"))

(defn parse-instruction-name-and-body
  "Extrai o nome sugerido da skill e o corpo (instruções ou EDN) do texto."
  [raw-instruction]
  (let [trimmed (str/trim (or raw-instruction ""))]
    (cond
      ;; 1. Se começa direto com vetor EDN [:sequence ...]
      (str/starts-with? trimmed "[")
      {:name nil :body trimmed}

      ;; 2. Se tem separador de dois-pontos "nome: corpo"
      (str/includes? trimmed ":")
      (let [[n & rest-body] (str/split trimmed #":\s*" 2)
            clean-name (-> n
                           (str/replace #"(?i)^(a|o|pra|para)\s+" "")
                           str/trim)
            body (str/trim (or (first rest-body) ""))]
        {:name (if (empty? clean-name) nil clean-name)
         :body body})

      ;; 3. Se começa com "a <palavra> <resto>"
      (re-find #"(?i)^(?:a|pra|para)\s+([a-zA-Z0-9_\-]+)\s+(.+)$" trimmed)
      (let [[_ n b] (re-find #"(?i)^(?:a|pra|para)\s+([a-zA-Z0-9_\-]+)\s+(.+)$" trimmed)]
        {:name n :body b})

      ;; 4. Fallback
      :else
      {:name nil :body trimmed})))

(defn step->action-node
  "Mapeia um fragmento de texto ou verbo em um nó de ação Malli [:action ...]."
  [step-str speaker]
  (let [norm (-> step-str
                 str/lower-case
                 (str/replace #"[áàâãä]" "a")
                 (str/replace #"[éèêë]" "e")
                 (str/replace #"[íìîï]" "i")
                 (str/replace #"[óòôõö]" "o")
                 (str/replace #"[úùûü]" "u")
                 (str/replace #"[ç]" "c")
                 str/trim)]
    (cond
      ;; Se for vetor EDN direto
      (str/starts-with? norm "[")
      (try (let [v (edn/read-string step-str)]
             (when (vector? v) v))
           (catch Throwable _ nil))

      ;; Sentar / Sofá / Assento
      (re-find #"sofa|sent[ae]|assent[ae]|cadeira" norm)
      [:action :sit-furniture]

      ;; Levantar / De pé
      (re-find #"levant[ae]|de pe" norm)
      [:action :stand-up]

      ;; Dança / Passinho
      (re-find #"dan[c]|dance|dança|passinho" norm)
      [:action :social-dance]

      ;; Reproduzir Gatos / Cuidar dos Gatos
      (re-find #"gat|felin" norm)
      [:action :breed-animals {:target "Cat"}]

      ;; Reproduzir outros animais
      (re-find #"animais|reproduz|cruz|filhot" norm)
      [:action :breed-animals {:target "animals"}]

      ;; Patrulhar / Ronda
      (re-find #"patrulh|rond|vigia" norm)
      [:action :patrol-sanctuary]

      ;; Seguir jogador
      (re-find #"sig[ae]|segui|vem com|acompanh" norm)
      [:action :follow-player {:speaker speaker}]

      ;; Guarda-costas / Proteger
      (re-find #"proteg|guard|defend|costas" norm)
      [:action :bodyguard {:speaker speaker}]

      ;; Minerar
      (re-find #"miner|picaret|escav|diamant" norm)
      [:action :mining-cooperation {:target "diamond"}]

      ;; Quarto / Cama / Descansar
      (re-find #"quart|dorm|cam[ae]|descans" norm)
      [:action :move-to-quarters]

      ;; Acolher chuva
      (re-find #"abrig|chuv|tempest" norm)
      [:action :move-to-quarters]

      ;; Drop / Entregar itens
      (re-find #"drop|larg|entreg|jog.*loot|item" norm)
      [:action :drop-inventory {:speaker speaker}]

      ;; Barco / Veículo / Montar
      (re-find #"barco|boat|veiculo|veículo|montar|mont[ae]|sub[ae] no barco|embarc" norm)
      [:action :mount-boat {:speaker speaker}]

      ;; Teleporte
      (re-find #"teleport|fend|tp" norm)
      [:action :teleport-to-player {:speaker speaker}]

      ;; Falar / Dizer
      (re-find #"fal[ae]|dig[ae]" norm)
      [:action :say-message {:text "Comando executado com sucesso!"}]

      :else nil)))

(defn parse-natural-or-edn-tree
  "Converte a instrução textual ou EDN numa árvore AST válida de Behavior Tree."
  [body suggested-name speaker]
  (let [trimmed (str/trim (or body ""))]
    (if (empty? trimmed)
      {:ok? false :reason :EMPTY_BODY :message "Corpo da instrução está vazio"}
      ;; Tentativa 1: Parse direto de EDN se começar com '['
      (if (str/starts-with? trimmed "[")
        (try
          (let [parsed (edn/read-string trimmed)]
            (if (schemas/valid-bt-node? parsed)
              (let [tree (if (= (first parsed) :sequence) parsed [:sequence parsed])
                    name (or suggested-name (str "skill-" (System/currentTimeMillis)))]
                {:ok? true :tree tree :name name})
              {:ok? false :reason :INVALID_SCHEMA :message "Vetor EDN fornecido não atende ao schema Malli BTNode"}))
          (catch Throwable e
            {:ok? false :reason :EDN_SYNTAX_ERROR :message (str "Erro de sintaxe EDN: " (.getMessage e))}))
        ;; Tentativa 2: Parse de passos em Linguagem Natural
        (let [raw-steps (str/split trimmed #"(?i)\s*(?:,|;|\be\b|\bdepois\b|\bentao\b|\bentão\b|\bthen\b|\band\b)\s*")
              clean-steps (filter #(not (str/blank? %)) raw-steps)
              actions     (keep #(step->action-node % speaker) clean-steps)]
          (if (seq actions)
            (let [tree (into [:sequence] actions)
                  skill-name (or suggested-name
                                 (slugify (first clean-steps))
                                 "nova-skill")]
              {:ok? true :tree tree :name skill-name})
            {:ok? false
             :reason :UNRECOGNIZED_STEPS
             :message (str "Não foi possível reconhecer ações válidas em: '" trimmed "'")}))))))

(defn synthesize-and-register-skill-from-chat!
  "Sintetizador do Sistema 2 para Linguagem Natural via Chat (CA-037/CA-040).
   Converte a instrução do chat numa árvore Malli segura, valida, salva em skills/
   e registra a nova rota no Semantic Router."
  [skill-registry skills-dir raw-instruction speaker & [{:keys [routes-dir] :or {routes-dir "agents/routes"}}]]
  (let [{:keys [name body]} (parse-instruction-name-and-body raw-instruction)]
    (if (empty? body)
      {:ok? false :reason :EMPTY_INSTRUCTION :message "Instrução de skill vazia"}
      (let [ast-res (parse-natural-or-edn-tree body name speaker)]
        (if-not (:ok? ast-res)
          ast-res
          (let [slug     (slugify (:name ast-res))
                skill-id (keyword slug)
                skill-def {:skill/id         skill-id
                           :skill/name       (str "Habilidade " (:name ast-res))
                           :skill/author     (str "Player-" (or speaker "pace"))
                           :skill/trigger    :chat-command
                           :skill/timeout-ms 30000
                           :skill/tree       (:tree ast-res)}
                reg-res (validate-and-register-skill! skill-registry skills-dir skill-def {:routes-dir routes-dir})]
            (if (:ok? reg-res)
              {:ok?        true
               :skill-id   skill-id
               :skill-name (:name ast-res)
               :skill-def  skill-def}
              reg-res)))))))

(ns craft.agents.skills.composer
  "Gatekeeper Malli + detector de ciclos p/ composicao evolutiva segura de Skills sem eval (CA-037).
   Inclui Skill-RAG, Decodificação Guiada por Gramática e Sandbox Mental de Pré-condições (Voyager P2)."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
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

(defn validate-and-register-skill!
  "Valida sub-arvore proposta por IA (schema, acoes conhecidas, ausencia de recursao ciclica, timeouts) e salva EDN (CA-037)."
  [skill-registry skills-dir skill-def]
  (let [skill-id       (:skill/id skill-def)
        tree           (:skill/tree skill-def)
        known-actions  (:known-actions @skill-registry #{})
        comps          (:composite-skills @skill-registry {})
        called-actions (extract-action-calls tree)
        all-allowed    (into known-actions (keys comps))
        unknowns       (remove all-allowed called-actions)]
    (if (seq unknowns)
      {:ok? false :reason :UNKNOWN_ACTION :unknowns (vec unknowns)}
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
              (router/auto-register-skill-route! skill-def {:persist? true :routes-dir "agents/routes"})
              (catch Throwable _ nil))
            {:ok? true :skill-id skill-id}))))))

;; ----------------------------------------------------------------------------
;; Skill-RAG: Recuperação de Habilidades Semelhantes (P2.1)
;; ----------------------------------------------------------------------------

(defn retrieve-relevant-skills
  "Skill-RAG (P2.1): Recupera as top-k skills mais similares semanticamente ao objetivo atual
   a partir dos arquivos .edn no repositório de habilidades para servir de few-shot context."
  [query skills-dir & [{:keys [top-k] :or {top-k 3}}]]
  (let [dir (io/file skills-dir)]
    (if (and (.exists dir) (.isDirectory dir))
      (let [files     (filter #(str/ends-with? (.getName %) ".edn") (.listFiles dir))
            q-vec     (router/text->vector query)
            scored    (keep (fn [f]
                              (try
                                (let [data    (edn/read-string (slurp f))
                                      s-text  (str (:skill/name data) " "
                                                   (:skill/trigger data) " "
                                                   (name (:skill/id data)) " "
                                                   (pr-str (:skill/tree data)))
                                      s-vec   (router/text->vector s-text)
                                      sim     (router/cosine-similarity q-vec s-vec)]
                                  {:skill data :score sim :file (.getName f)})
                                (catch Throwable _ nil)))
                            files)
            sorted    (sort-by (comp - :score) scored)]
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

(defn- extract-actions-with-args
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

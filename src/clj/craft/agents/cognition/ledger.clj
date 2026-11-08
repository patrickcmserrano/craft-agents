(ns craft.agents.cognition.ledger
  "Ledger de Memória Negativa para registro e recuperação de Early Aborts físicos (CA-RSI.1).
   Evita alucinação cíclica transformando violações empíricas do Environmental Critic
   em restrições formais para o sintetizador do Sistema 2."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-ledger-path "state/abort-ledger.edn")

(defn- ensure-parent-dir! [^java.io.File file]
  (when-let [parent (.getParentFile file)]
    (.mkdirs parent)))

(defn read-ledger
  "Lê o ledger de abortos a partir de um arquivo EDN ou caminho string. Retorna mapa ou nil se inexistente."
  ([] (read-ledger default-ledger-path))
  ([path-or-file]
   (let [f (io/file path-or-file)]
     (if (and (.exists f) (.isFile f))
       (try
         (let [content (slurp f)]
           (if (str/blank? content)
             {}
             (or (edn/read-string content) {})))
         (catch Throwable _
           {}))
       {}))))

(defn record-abort
  "Função pura: registra uma ocorrência de Early Abort no mapa de ledger.
   ledger-map: {skill-id [abort-record ...]}"
  [ledger-map {:keys [skill-id failed-action node-path violated-invariant world-snapshot diagnostic timestamp]
               :or {timestamp (System/currentTimeMillis)}}]
  (let [record {:timestamp          timestamp
                :skill-id           skill-id
                :failed-action      failed-action
                :node-path          (or node-path [])
                :violated-invariant violated-invariant
                :world-snapshot     (or world-snapshot {})
                :diagnostic         diagnostic}]
    (update ledger-map (or skill-id :unknown-skill) (fnil conj []) record)))

(defn record-abort!
  "Registra com efeito colateral no arquivo persistente EDN e atualiza átomo em memória."
  ([abort-data] (record-abort! default-ledger-path abort-data))
  ([path-or-file abort-data]
   (let [f (io/file path-or-file)]
     (ensure-parent-dir! f)
     (let [current  (read-ledger f)
           updated  (record-abort current abort-data)]
       (spit f (pr-str updated))
       updated))))

(defn clear-ledger!
  "Limpa o ledger de persistência (útil para testes ou ciclos de consolidação)."
  ([] (clear-ledger! default-ledger-path))
  ([path-or-file]
   (let [f (io/file path-or-file)]
     (when (.exists f)
       (spit f (pr-str {})))
     {})))

(defn frequent-aborts
  "Filtra skills e ações que acumularam abortos recorrentes (>= min-threshold vezes)."
  ([ledger-map] (frequent-aborts ledger-map 3))
  ([ledger-map min-threshold]
   (reduce-kv
    (fn [acc skill-id records]
      (let [by-action (group-by :failed-action records)]
        (reduce-kv
         (fn [inner-acc action action-records]
           (if (>= (count action-records) min-threshold)
             (conj inner-acc {:skill-id           skill-id
                              :failed-action      action
                              :failure-count      (count action-records)
                              :sample-record      (last action-records)
                              :all-records        action-records})
             inner-acc))
         acc
         by-action)))
    []
    ledger-map)))

(defn build-negative-constraints-prompt
  "Gera instrução negativa concisa para o prompt do LLM a partir de abortos frequentes."
  [frequent-failures]
  (if (empty? frequent-failures)
    ""
    (str "RESTRIÇÕES NEGATIVAS EMPÍRICAS (Invariantes físicas violadas no passado):\n"
         (str/join "\n"
                   (map (fn [{:keys [skill-id failed-action failure-count sample-record]}]
                          (str "- Na skill " (pr-str skill-id) ", a ação " (pr-str failed-action)
                               " falhou " failure-count "x (invariante violada: "
                               (pr-str (:violated-invariant sample-record))
                               "). NÃO repetir a mesma chamada sem mecanismo de [:fallback ...] ou salvaguarda."))
                        frequent-failures)))))

(ns craft.agents.npc.addressing
  "Enderecamento de comandos de chat por nome de QUALQUER agente do registro (CA-090).

   Funcoes puras: `addressed-agents` diz quem foi chamado na mensagem e
   `strip-addressing` remove o(s) nome(s) do inicio, deixando so o comando.
   Os agentes vem do registro (`npc.registry`) ou do argumento `:agents`/`agents`,
   o que permite testar sem tocar o disco."
  (:require [clojure.string :as str]
            [craft.agents.npc.registry :as registry])
  (:import (java.text Normalizer Normalizer$Form)
           (java.util.regex Pattern)))

(def ^:private group-words ["todos" "pessoal"])

(defn- fold
  "Minusculas e sem acentos (preserva o comprimento nos casos usuais)."
  [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
      (str/replace #"\p{M}+" "")
      str/lower-case))

(defn- word-pattern
  "Regex de palavra inteira (nao colada em letra/digito) para `word`."
  [word]
  (re-pattern (str "(?<![\\p{L}\\p{N}])" (Pattern/quote (fold word)) "(?![\\p{L}\\p{N}])")))

(defn- group-address? [folded]
  (boolean (re-find (re-pattern (str "^[\\s!/]*(?:" (str/join "|" group-words) ")(?![\\p{L}\\p{N}])"))
                    folded)))

(defn- by-position [agents folded]
  (->> agents
       (keep (fn [a]
               (when-let [m (re-matcher (word-pattern (:agent/name a)) folded)]
                 (when (.find m) [(.start m) (:agent/id a)]))))
       (sort-by first)
       (mapv second)))

(defn addressed-agents
  "Ids dos agentes chamados em `msg`, na ordem em que aparecem. `todos`/`pessoal` no
   INICIO da mensagem chamam todos os agentes; com `:capability` so os que a declaram
   em `:agent/capabilities`. Sem nome -> `[]`."
  ([msg] (addressed-agents msg nil))
  ([msg {:keys [agents capability]}]
   (let [agents (or agents (registry/all-agents))
         folded (fold msg)]
     (if (group-address? folded)
       (->> agents
            (filter #(or (nil? capability)
                         (contains? (:agent/capabilities %) capability)))
            (mapv :agent/id))
       (by-position agents folded)))))

(defn strip-addressing
  "Remove do inicio de `text` os nomes de agentes (ou todos/pessoal), com prefixo
   `!`/`/` e separadores, devolvendo o comando limpo."
  ([text] (strip-addressing text nil))
  ([text agents]
   (let [names (concat group-words (map :agent/name (or agents (registry/all-agents))))
         alt   (str/join "|" (map #(Pattern/quote %) names))
         word  (str "(?:" alt ")(?![\\p{L}\\p{N}])")
         re    (re-pattern (str "(?i)^[\\s!/]*" word "[,:;\\s]*(?:e\\s+(?=[!/]?" word "))?"))]
     (loop [s (str text)]
       (let [s' (str/replace-first s re "")]
         (if (= s s') (str/trim s) (recur s')))))))

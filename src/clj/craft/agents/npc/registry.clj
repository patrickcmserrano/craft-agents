(ns craft.agents.npc.registry
  "Registro de agentes (NPCs) carregados de `agents/*.edn` e validados por Malli (CA-086).

   Apenas os `.edn` do nivel superior do diretorio sao agentes; subdiretorios
   (`regions/`, `routes/`, `skills/`) guardam outros tipos de dado e sao ignorados.

   `antigravity.edn` E um agente: tem `:agent/id`, `:agent/name`, `:agent/spawn`, loadout
   e `:bt/root` proprios (companheiro tatico de escolta, mesmo contrato de Karlach/Picard),
   portanto entra no registro e em `agent-names`.

   Um `.edn` invalido nao derruba os demais: `load-agents` devolve
   `{:agents {id spec} :errors [{:file :message :explain}]}`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [craft.agents.schemas :as schemas]
            [malli.core :as m]
            [malli.error :as me]))

(def default-dir (or (System/getenv "CRAFT_AGENTS_DIR") "agents"))

(def Voice
  [:map
   [:color :string]
   [:sound :string]
   [:radius number?]])

(def Lines
  "Falas por evento/topico: `{:topico [\"fala\" ...]}`, cada vetor nao vazio (CA-109)."
  [:map-of :keyword [:vector {:min 1} [:string {:min 1}]]])

(def Relation
  [:map
   [:kind :keyword]
   [:affinity {:optional true} number?]
   [:note {:optional true} :string]])

(def RoutineSlot
  "Faixa de horas do dia [from, to) com uma atividade e, opcionalmente, um lugar de `:places`."
  [:map
   [:from number?]
   [:to number?]
   [:activity :keyword]
   [:place {:optional true} :keyword]])

(def Place
  [:map
   [:name :string]
   [:pos [:tuple number? number? number?]]])

(def AgentSpec
  [:map
   [:agent/id :keyword]
   [:agent/name :string]
   [:agent/spawn [:map
                  [:dimension :string]
                  [:pos [:tuple number? number? number?]]]]
   [:agent/role {:optional true} :string]
   [:agent/loadout {:optional true} [:map-of :keyword :string]]
   [:agent/quarters {:optional true} :map]
   [:agent/dialogues {:optional true} :map]
   [:agent/traits {:optional true} :map]
   [:agent/lines {:optional true} Lines]
   [:agent/relations {:optional true} [:map-of :keyword Relation]]
   [:agent/routine {:optional true} [:vector RoutineSlot]]
   [:agent/places {:optional true} [:map-of :keyword Place]]
   [:bt/root {:optional true} schemas/BTNode]
   [:agent/voice {:optional true} Voice]
   [:agent/combat-policy {:optional true} [:enum :fight :flee :none]]
   [:agent/capabilities {:optional true} [:set :keyword]]
   [:agent/skin {:optional true} :string]
   [:agent/effects {:optional true} [:vector [:map
                                              [:effect :string]
                                              [:amplifier {:optional true} :int]]]]
   [:agent/spawn-loadout {:optional true} [:map-of :keyword :string]]
   [:agent/boot-spawn? {:optional true} :boolean]])

(defn- read-spec [^java.io.File f]
  (try
    {:spec (edn/read-string (slurp f))}
    (catch Exception e
      {:error {:file (.getPath f)
               :message (str "EDN ilegivel: " (ex-message e))}})))

(defn- tree-action-ids
  "Ids de todos os nos `[:action id ...]` de uma arvore de BT (guards/conditions nao contam)."
  [node]
  (when (vector? node)
    (if (= :action (first node))
      [(second node)]
      (mapcat tree-action-ids (rest node)))))

(defn unknown-actions
  "Conjunto das `:action` referenciadas em `:bt/root` de `spec` que nao estao em `known` (CA-108)."
  [spec known]
  (into #{} (remove (set known)) (tree-action-ids (:bt/root spec))))

(defn- validate-file [^java.io.File f known-actions]
  (let [{:keys [spec error]} (read-spec f)]
    (cond
      error {:error error}
      (and (m/validate AgentSpec spec) known-actions (seq (unknown-actions spec known-actions)))
      {:error {:file (.getPath f)
               :message (str "Agente invalido em " (.getName f) ": acao fora do registro de acoes: "
                             (pr-str (sort (unknown-actions spec known-actions))))}}
      (m/validate AgentSpec spec) {:spec spec}
      :else (let [explain (m/explain AgentSpec spec)]
              {:error {:file (.getPath f)
                       :message (str "Agente invalido em " (.getName f) ": "
                                     (binding [*print-namespace-maps* false]
                                       (pr-str (me/humanize explain))))
                       :explain explain}}))))

(defn load-agents
  "Carrega os `*.edn` de `dir` (default \"agents\"), sem descer em subdiretorios. Com
   `{:known-actions #{...}}` (CA-108) rejeita o agente cujo `:bt/root` referencia `:action` fora
   do registro de acoes (ex.: `bt-actions/known-action-ids`); sem a opcao nada muda."
  ([] (load-agents default-dir))
  ([dir] (load-agents dir nil))
  ([dir {:keys [known-actions]}]
   (let [files (->> (.listFiles (io/file dir))
                    (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".edn")))
                    (sort-by #(.getName ^java.io.File %)))
         results (map #(validate-file % known-actions) files)]
     {:agents (into (sorted-map) (map (juxt :agent/id identity)) (keep :spec results))
      :errors (vec (keep :error results))})))

(defonce ^:private registry (atom nil))

(defn reload!
  "Recarrega o registro a partir de `dir` e devolve o resultado de `load-agents`."
  ([] (reload! default-dir))
  ([dir] (reset! registry (load-agents dir))))

(defn- current [] (or @registry (reload!)))

(defn agent-by-id [id] (get-in (current) [:agents id]))

(defn agent-by-name
  "Busca pelo nome de jogador, ignorando caixa."
  [nm]
  (when nm
    (let [k (str/lower-case (str nm))]
      (->> (vals (:agents (current)))
           (filter #(= k (str/lower-case (:agent/name %))))
           first))))

(defn all-agents
  "Specs de todos os agentes do registro, ordenados por id."
  []
  (vec (vals (:agents (current)))))

(defn agent-names [] (mapv :agent/name (vals (:agents (current)))))

(defn load-errors [] (:errors (current)))

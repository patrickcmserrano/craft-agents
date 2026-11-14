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

(defn- validate-file [^java.io.File f]
  (let [{:keys [spec error]} (read-spec f)]
    (cond
      error {:error error}
      (m/validate AgentSpec spec) {:spec spec}
      :else (let [explain (m/explain AgentSpec spec)]
              {:error {:file (.getPath f)
                       :message (str "Agente invalido em " (.getName f) ": "
                                     (binding [*print-namespace-maps* false]
                                       (pr-str (me/humanize explain))))
                       :explain explain}}))))

(defn load-agents
  "Carrega os `*.edn` de `dir` (default \"agents\"), sem descer em subdiretorios."
  ([] (load-agents default-dir))
  ([dir]
   (let [files (->> (.listFiles (io/file dir))
                    (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".edn")))
                    (sort-by #(.getName ^java.io.File %)))
         results (map validate-file files)]
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

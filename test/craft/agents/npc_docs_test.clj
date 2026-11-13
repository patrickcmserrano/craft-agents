(ns craft.agents.npc-docs-test
  "Invariantes estruturais baratos do NPC Core (CA-096): tamanho dos arquivos,
   perfis de agente validos e cobertura de `execute-goal!` para toda meta produzida pelo grounding."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.goals]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch]
            [craft.agents.cognition.grounding :as grounding]
            [craft.agents.npc.registry :as registry]))

(def max-lines 400)

(def size-exceptions
  "Arquivos autorizados a passar de `max-lines`: caminho -> tamanho maximo atual (nao podem crescer).
   Vazio: nenhum .clj de fabric/ ou npc/ passa de 400 linhas hoje; a fachada do atelier
   (backend/fabric/atelier.clj) e os arquivos nao tocados pela sprint ficam abaixo do limite."
  {})

(defn- clj-files [dir]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".clj")))
       (sort-by #(.getPath ^java.io.File %))))

(defn- line-count [^java.io.File f]
  (with-open [r (io/reader f)] (count (line-seq r))))

(deftest clj-files-stay-small
  (doseq [dir ["src/clj/craft/agents/backend/fabric" "src/clj/craft/agents/npc"]]
    (is (seq (clj-files dir)) (str "sem .clj em " dir))
    (doseq [f (clj-files dir)
            :let [path (.getPath ^java.io.File f)
                  n (line-count f)
                  limit (get size-exceptions path max-lines)]]
      (testing path
        (is (<= n limit) (str path " tem " n " linhas (limite " limit ")"))))))

(deftest size-exceptions-are-still-needed
  (doseq [[path limit] size-exceptions]
    (is (> (line-count (io/file path)) max-lines) (str path " ja cabe no limite: remova a excecao"))
    (is (<= (line-count (io/file path)) limit))))

(deftest agent-profiles-validate
  (let [{:keys [agents errors]} (registry/load-agents "agents")]
    (is (empty? errors) (pr-str (mapv :message errors)))
    (is (= #{:karlach :picard :richeh :coco :antigravity} (set (keys agents))))
    (let [edns (->> (.listFiles (io/file "agents"))
                    (filter #(str/ends-with? (.getName ^java.io.File %) ".edn")))]
      (is (= (count edns) (count agents))))))

(def ^:private intents
  "Um resultado de intencao por ramo do `case` de `ground-goal`, mais rota dinamica e fallback."
  (concat
   (for [t [:animal-emergency :spatial-attention :breed-animals :weather-shelter :stop-following
            :teleport-to-player :follow-player :bodyguard :mining-cooperation :social-dance
            :patrol-sanctuary :inventory-inspection :drop-inventory :sit-furniture :stand-up
            :teach-skill :dialogue]]
     {:intent/type t :intent/slots {} :intent/raw-text "x"})
   [{:intent/type :entrar-barco :intent/route-method :dynamic-skill-route
     :intent/slots {} :intent/raw-text "x"}]))

(deftest every-grounded-goal-type-has-a-defmethod
  (let [produced (into #{} (map #(:goal/type (grounding/ground-goal %))) intents)
        defined  (set (keys (methods dispatch/execute-goal!)))
        ;; :dialogue e a conversa livre: tratada de proposito pelo :default.
        handled-by-default #{:dialogue}]
    (is (>= (count produced) 15))
    (doseq [t (remove handled-by-default produced)]
      (is (contains? defined t) (str "falta defmethod execute-goal! para " t)))
    (is (contains? defined :default))))

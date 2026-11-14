(ns craft.agents.npc-body-guard-test
  "CA-104 — guarda estrutural global (`SUG-01` parte 2b): o literal `\"MOVE|` (prefixo do wire de
   movimento) so pode existir em `craft.agents.npc.body`. Varre todo `.clj` sob `src/clj/`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)))

(def ^:private src-root "src/clj")

(def ^:private body-file "src/clj/craft/agents/npc/body.clj")

(defn- clj-files
  "Todos os .clj sob `dir`, caminhos relativos ao repo com '/'."
  [^String dir]
  (->> (file-seq (File. dir))
       (filter #(and (.isFile ^File %) (str/ends-with? (.getName ^File %) ".clj")))
       (map #(str/replace (.getPath ^File %) File/separator "/"))
       sort))

(deftest src-tree-is-scanned-test
  (let [files (clj-files src-root)]
    (is (.exists (File. ^String body-file)) "npc.body existe")
    (is (some #{body-file} files) "o arbitro esta na varredura")
    (is (> (count files) 50) "varredura cobre a arvore inteira de src/clj")))

(deftest no-move-literal-outside-npc-body-test
  (doseq [f (clj-files src-root)
          :when (not= f body-file)]
    (testing f
      (is (not (str/includes? (slurp f) "\"MOVE|"))
          "so craft.agents.npc.body pode conter o literal \"MOVE|"))))

(deftest npc-body-owns-the-move-literal-test
  (is (str/includes? (slurp body-file) "\"MOVE|")
      "o guarda nao pode passar por falta de serializador: npc.body serializa o wire"))

(ns craft.agents.store
  "Hot-reload EDN watcher validado por Malli e persistencia atomica de memoria/skills (CA-034, CA-035)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [craft.agents.schemas :as schemas])
  (:import [java.nio.file Files StandardCopyOption]))

(defn reload-profile-safe!
  "Carrega perfil EDN: se valido no Malli atualiza agente ao vivo; se invalido rejeita preservando arvore anterior (CA-034)."
  [store edn-file]
  (try
    (let [data (edn/read-string (slurp (io/file edn-file)))]
      (if (schemas/valid-agent-profile? data)
        (do
          (swap! store assoc-in [:profiles (:agent/id data)] data)
          {:ok? true :profile data})
        {:ok? false :reason :INVALID_SCHEMA :errors (schemas/explain-validation schemas/AgentProfile data)}))
    (catch Throwable ex
      {:ok? false :reason :PARSE_ERROR :error (.getMessage ex)})))

(defn save-agent-snapshot-atomic!
  "Grava estado e memoria episodica em state/<id>.edn usando escrita atomica temp+move (CA-035)."
  [state-dir agent-id snapshot-map]
  (let [dir (io/file state-dir)]
    (.mkdirs dir)
    (let [target-file (io/file dir (str (name agent-id) ".edn"))
          temp-file   (java.io.File/createTempFile (str (name agent-id) "-snap") ".tmp" dir)]
      (spit temp-file (pr-str snapshot-map))
      (Files/move (.toPath temp-file)
                  (.toPath target-file)
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      target-file)))

(defn load-agent-snapshot
  "Restaura snapshot persistido de state/<id>.edn (CA-035)."
  [state-dir agent-id]
  (let [target-file (io/file state-dir (str (name agent-id) ".edn"))]
    (when (.exists target-file)
      (edn/read-string (slurp target-file)))))

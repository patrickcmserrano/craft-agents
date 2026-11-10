#!/usr/bin/env clojure
;;; Sonda de diagnostico: a classe SyntheticPlayerFactory que a porta 7890
;;; enxerga e a MESMA que a ponte 25595 usa? Compara os campos estaticos.
;;;
;;; Uso: clojure -Sdeps '{:paths ["scripts"]}' -M -m factory-probe
(ns factory-probe
  (:require [nrepl-client :as client]))

(def prelude "(clojure.core/refer 'clojure.core)")

(def q
  "
(let [C (java.lang.Class/forName \"com.craft.agents.fabric.SyntheticPlayerFactory\")
      statics (filter (fn [f] (java.lang.reflect.Modifier/isStatic (.getModifiers f)))
                      (seq (.getDeclaredFields C)))
      read1  (fn [nm]
               (let [f (.getDeclaredField C nm)]
                 (.setAccessible f true)
                 (try (str (.get f nil)) (catch Throwable e (str \"<\" (.getMessage e) \">\")))))]
  (str \"loader=\" (.getName (.getClassLoader C))
       \" identity=\" (java.lang.System/identityHashCode C)
       \" BRIDGE_RUNNING=\" (read1 \"BRIDGE_RUNNING\")
       \" cachedServer=\" (read1 \"cachedServer\")
       \" cachedKnotLoader=\" (read1 \"cachedKnotLoader\")
       \" nStatics=\" (count statics)))
")

(defn -main [& _]
  (doseq [port [7890 7888]]
    (let [{:keys [out err value]} (client/eval-code "127.0.0.1" port q prelude)]
      (println (format "--- porta %d ---" port))
      (when-not (clojure.string/blank? err)
        (println "ERR:" (clojure.string/trim (first (clojure.string/split-lines err)))))
      (when-not (clojure.string/blank? out) (println "OUT:" (clojure.string/trim out)))
      (when (some? value) (println "VAL:" (clojure.string/trim (str value))))))
  (shutdown-agents))

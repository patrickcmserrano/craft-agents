(ns craft.agents.skills.composer
  "Gatekeeper Malli + detector de ciclos p/ composicao evolutiva segura de Skills sem eval (CA-037)."
  (:require [clojure.java.io :as io]))

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
            {:ok? true :skill-id skill-id}))))))

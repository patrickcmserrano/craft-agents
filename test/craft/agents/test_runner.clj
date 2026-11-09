(ns craft.agents.test-runner
  "Executor TDD oficial do craft-agents organizado pelos Epicos 0 a 7 (CA-001 a CA-052)."
  (:require [clojure.test :as t]
            [craft.agents.epic0-foundation-test]
            [craft.agents.epic1-dual-boot-bridge-test]
            [craft.agents.epic2-synthetic-player-test]
            [craft.agents.epic3-navigation-3d-test]
            [craft.agents.epic4-behavior-leases-store-test]
            [craft.agents.epic5-cognition-mcp-studio-test]
            [craft.agents.epic6-production-migration-test]
            [craft.agents.epic7-headless-swarm-v02-test]
            [craft.agents.cognition.intent-grounding-test]
            [craft.agents.cognition.critic-test]
            [craft.agents.epic-survival-homeostasis-test]
            [craft.agents.epic-rsi-curriculum-test]
            [craft.agents.rsi.bench-test]
            [craft.agents.atelier-test]
            [craft.agents.astrophysics-test]))

(def epic-suites
  [{:epic 0 :title "Epico 0 — Fundacao, Build e Contratos (CA-001..CA-005)"      :ns 'craft.agents.epic0-foundation-test}
   {:epic 1 :title "Epico 1 — Dual-Boot e Ponte Intermediary (CA-006..CA-012)"   :ns 'craft.agents.epic1-dual-boot-bridge-test}
   {:epic 2 :title "Epico 2 — ServerPlayerEntity In-Memory (CA-013..CA-020)"     :ns 'craft.agents.epic2-synthetic-player-test}
   {:epic 3 :title "Epico 3 — PathSolver3D e Portais Duplos (CA-021..CA-028)"    :ns 'craft.agents.epic3-navigation-3d-test}
   {:epic 4 :title "Epico 4 — Behavior Trees, Leases e Store (CA-029..CA-035)"   :ns 'craft.agents.epic4-behavior-leases-store-test}
   {:epic 5 :title "Epico 5 — Digest 3D, MCP e Agent Studio (CA-036..CA-042)"    :ns 'craft.agents.epic5-cognition-mcp-studio-test}
   {:epic 6 :title "Epico 6 — Migracao Real Karlach/Picard/AGY (CA-043..CA-048)" :ns 'craft.agents.epic6-production-migration-test}
   {:epic 7 :title "Epico 7 — v0.2 Headless Swarm TCP (CA-049..CA-052)"          :ns 'craft.agents.epic7-headless-swarm-v02-test}
   {:epic 8 :title "Epico 8 — Intent Router & Semantic Grounding (CA-040)"        :ns 'craft.agents.cognition.intent-grounding-test}
   {:epic 9 :title "Epico 9 — Environmental Critic & Reflexion Loop (CA-041)"     :ns 'craft.agents.cognition.critic-test}
   {:epic 10 :title "Epico I (AGI) — Homeostase, Fisiologia e Autopreservacao (CA-E1.1..E1.4)" :ns 'craft.agents.epic-survival-homeostasis-test}
   {:epic 11 :title "Epico II (RSI) — Curriculum Agent & Recursive Self-Improvement (CA-RSI.1..RSI.4)" :ns 'craft.agents.epic-rsi-curriculum-test}
   {:epic 12 :title "Epico III (Bench) — Craft-Bench & Gate de Promocao Falsificavel (CA-RSI-P2)" :ns 'craft.agents.rsi.bench-test}
   {:epic 13 :title "Epico IV (Atelier) — Witch Hat Atelier: Coco e Richeh (CA-WHA)" :ns 'craft.agents.atelier-test}
   {:epic 14 :title "Epico V (Astrophysics) — Efemerides, HR Starfield & Consciencia Celeste (CA-050..CA-052)" :ns 'craft.agents.astrophysics-test}])

(defn- parse-args [args]
  (loop [rem args opts {:summary-only? false :epic nil}]
    (cond
      (empty? rem) opts
      (= "--summary-only" (first rem)) (recur (rest rem) (assoc opts :summary-only? true))
      (= "--epic" (first rem)) (recur (drop 2 rem) (assoc opts :epic (parse-long (second rem))))
      :else (recur (rest rem) opts))))

(defn- extract-pending-id [^Throwable ex]
  (when ex
    (or (some-> (ex-data ex) :tdd/pending name)
        (re-find #"CA-\d+" (or (.getMessage ex) ""))
        (when-let [cause (.getCause ex)]
          (extract-pending-id cause)))))

(defn- run-suite-quietly [suite-ns]
  (let [state (atom {:vars 0 :green-vars 0 :red-pending-vars 0 :broken-vars 0
                     :current-status :green :pending-ids #{}})]
    (binding [t/*test-out* (java.io.StringWriter.)
              t/report (fn [m]
                         (case (:type m)
                           :begin-test-var
                           (swap! state #(-> % (update :vars inc) (assoc :current-status :green)))

                           :fail
                           (let [cid (extract-pending-id (when (instance? Throwable (:actual m)) (:actual m)))]
                             (swap! state (fn [s]
                                            (cond-> s
                                              cid (update :pending-ids conj cid)
                                              (not= :pending (:current-status s))
                                              (assoc :current-status (if cid :pending :broken))))))

                           :error
                           (let [cid (extract-pending-id (:actual m))]
                             (swap! state (fn [s]
                                            (cond-> s
                                              cid (update :pending-ids conj cid)
                                              (= :green (:current-status s))
                                              (assoc :current-status (if cid :pending :broken))))))

                           :end-test-var
                           (swap! state (fn [s]
                                          (case (:current-status s)
                                            :green   (update s :green-vars inc)
                                            :pending (update s :red-pending-vars inc)
                                            :broken  (update s :broken-vars inc))))
                           nil))]
      (t/test-ns suite-ns))
    @state))

(defn -main [& args]
  (let [{:keys [summary-only? epic]} (parse-args args)
        selected (if (some? epic)
                   (filter #(= epic (:epic %)) epic-suites)
                   epic-suites)]
    (println "\n========================================================================================")
    (println "🧪 CRAFT-AGENTS — SUITE DE ESPECIFICACAO COMPORTAMENTAL TDD (CA-001 .. CA-052)")
    (println "========================================================================================")
    (if summary-only?
      (let [rows       (mapv (fn [s] (assoc s :res (run-suite-quietly (:ns s)))) selected)
            tot-vars   (reduce + (map #(get-in % [:res :vars]) rows))
            tot-green  (reduce + (map #(get-in % [:res :green-vars]) rows))
            tot-pend   (reduce + (map #(get-in % [:res :red-pending-vars]) rows))
            tot-broken (reduce + (map #(get-in % [:res :broken-vars]) rows))
            all-ids    (sort (reduce into #{} (map #(get-in % [:res :pending-ids]) rows)))]
        (doseq [{:keys [title res]} rows]
          (println (format "  %-56s | vars: %2d | GREEN: %2d | RED: %2d | FAIL: %2d"
                           title (:vars res) (:green-vars res) (:red-pending-vars res) (:broken-vars res))))
        (println "----------------------------------------------------------------------------------------")
        (println (format "Total Specs: %d | ✅ GREEN: %d | 🔴 RED (Aguardando Implementacao): %d | ❌ Regressoes: %d"
                         tot-vars tot-green tot-pend tot-broken))
        (println (format "Tasks Pendentes Detectadas (%d): %s"
                         (count all-ids)
                         (if (seq all-ids) (str (first all-ids) " .. " (last all-ids)) "nenhuma")))
        (println "----------------------------------------------------------------------------------------")
        (System/exit (if (and (zero? tot-pend) (zero? tot-broken)) 0 1)))
      (let [res (apply t/run-tests (map :ns selected))]
        (println "\n----------------------------------------------------------------------------------------")
        (println (format "Total Test Vars: %d | Assercoes OK: %d | Falhas: %d | Erros/RED-Pending: %d"
                         (:test res 0) (:pass res 0) (:fail res 0) (:error res 0)))
        (println "----------------------------------------------------------------------------------------")
        (System/exit (if (and (zero? (:fail res 0)) (zero? (:error res 0))) 0 1))))))

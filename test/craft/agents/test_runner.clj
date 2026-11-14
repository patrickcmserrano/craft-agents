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
            [craft.agents.sentry-test]
            [craft.agents.astrophysics-test]
            [craft.agents.karlach-rain-shelter-test]
            [craft.agents.karlach-locomotion-test]
            [craft.agents.karlach-strike-cooldown-test]
            [craft.agents.karlach-gait-mirror-test]
            [craft.agents.karlach-elytra-follow-test]
            [craft.agents.karlach-combat-escort-test]
            [craft.agents.karlach-teleport-test]
            [craft.agents.engine-characterization-test]
            [craft.agents.goals-characterization-test]
            [craft.agents.npc.registry-test]
            [craft.agents.npc-speech-test]
            [craft.agents.npc-spawn-test]
            [craft.agents.npc-state-test]
            [craft.agents.npc-addressing-test]
            [craft.agents.npc-goals-test]
            [craft.agents.npc-locomotion-test]
            [craft.agents.npc-survival-test]
            [craft.agents.npc-bt-test]
            [craft.agents.npc-bridge-world-test]
            [craft.agents.npc-docs-test]
            [craft.agents.npc-perception-tick-test]
            [craft.agents.npc-body-test]
            [craft.agents.npc-body-guard-test]
            [craft.agents.npc-body-integration-test]
            [craft.agents.npc-routine-test]))

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
   {:epic 16 :title "Epico VII (Sentry) — Deduplicacao de Chat por Identidade de Evento" :ns 'craft.agents.sentry-test}
   {:epic 14 :title "Epico V (Astrophysics) — Efemerides, HR Starfield & Consciencia Celeste (CA-050..CA-052)" :ns 'craft.agents.astrophysics-test}
   {:epic 15 :title "Epico VI (Clima) — Abrigo Autonomo da Karlach contra Chuva (CA-060)" :ns 'craft.agents.karlach-rain-shelter-test}
   {:epic 17 :title "Epico VIII (Locomocao) — Karlach: A* recupera partida, walk-step, watchdog e classes frescas (CA-062..CA-080)" :ns 'craft.agents.karlach-locomotion-test}
   {:epic 18 :title "Epico IX (Combate) — Karlach: cooldown de golpe melee" :ns 'craft.agents.karlach-strike-cooldown-test}
   {:epic 19 :title "Epico X (Marcha) — Karlach espelha sprint/agachar do lider" :ns 'craft.agents.karlach-gait-mirror-test}
   {:epic 20 :title "Epico XI (Elytra) — Karlach segue o lider em voo" :ns 'craft.agents.karlach-elytra-follow-test}
   {:epic 21 :title "Epico XII (Escolta) — Karlach luta sem abandonar o lider, grito com cooldown" :ns 'craft.agents.karlach-combat-escort-test}
   {:epic 22 :title "Karlach teleporte via MOVE (sem loop de /tp)" :ns 'craft.agents.karlach-teleport-test}
   {:epic 23 :title "CA-084 — Caracterizacao do engine.clj e sub-namespaces" :ns 'craft.agents.engine-characterization-test}
   {:epic 24 :title "CA-085 — Caracterizacao do goals.clj e sub-namespaces" :ns 'craft.agents.goals-characterization-test}
   {:epic 25 :title "CA-086 — Registro de agentes com schema Malli" :ns 'craft.agents.npc.registry-test}
   {:epic 26 :title "CA-087 — Fala unificada (npc.speech)" :ns 'craft.agents.npc-speech-test}
   {:epic 27 :title "CA-088 — Spawn e loadout guiados pelo registro" :ns 'craft.agents.npc-spawn-test}
   {:epic 28 :title "CA-089 — Estado por agente e Studio generico" :ns 'craft.agents.npc-state-test}
   {:epic 29 :title "CA-090 — Enderecamento por nome de qualquer agente" :ns 'craft.agents.npc-addressing-test}
   {:epic 30 :title "CA-091 — Despacho de metas por agente" :ns 'craft.agents.npc-goals-test}
   {:epic 31 :title "CA-092 — Locomocao compartilhada (follow por agente)" :ns 'craft.agents.npc-locomotion-test}
   {:epic 32 :title "CA-093 — Sobrevivencia compartilhada (abrigo, fome, combate por politica)" :ns 'craft.agents.npc-survival-test}
   {:epic 33 :title "CA-094 — Behavior Tree para todo agente com :bt/root" :ns 'craft.agents.npc-bt-test}
   {:epic 34 :title "CA-095 — Mundo do agente por nome nos ops da ponte" :ns 'craft.agents.npc-bridge-world-test}
   {:epic 35 :title "CA-096 — Invariantes estruturais do NPC Core (tamanho, perfis, cobertura de metas)" :ns 'craft.agents.npc-docs-test}
   {:epic 36 :title "CA-100 — Snapshot unico de percepcao por tick e metricas de tick" :ns 'craft.agents.npc-perception-tick-test}
   {:epic 37 :title "CA-102 — Arbitro unico de atuadores corporais (npc.body)" :ns 'craft.agents.npc-body-test}
   {:epic 38 :title "CA-104 — Guarda global contra \"MOVE| fora de npc.body e BT sob arbitragem unica" :ns 'craft.agents.npc-body-guard-test}
   {:epic 39 :title "CA-105 — Integracao multi-agente da sprint body-01 (corpo, percepcao, estado, chat)" :ns 'craft.agents.npc-body-integration-test}
   {:epic 40 :title "CA-110 — Primitivas genericas de rotina, lugares e interacao social" :ns 'craft.agents.npc-routine-test}])

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

(ns craft.agents.backend.fabric.engine
  "Loop de 20 TPS ao vivo da Karlach e Picard no servidor Fabric e integracao com o Agent Studio.
   CA-084: fachada + orquestrador. A logica vive em craft.agents.backend.fabric.engine.*:
     combat, shelter, watchdog  : decisoes e efeitos de combate, abrigo de chuva e watchdog
     picard-tick, atelier-tick  : ticks do Picard (A) e do Atelie (B)
     karlach-tick               : tick da Karlach (C), com follow, mining, patrol e patrol-ops
     chat                       : threads de log/chat e despachante de feiticos
     state                      : estado inicial dos atoms"
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :refer [ensure-live-bridge-and-agents! feed-hungry-agents!]]
            [craft.agents.backend.fabric.atelier :as atelier]
            [craft.agents.backend.fabric.engine.atelier-tick :refer [tick-atelier!]]
            [craft.agents.backend.fabric.engine.bt-tick :as bt-tick]
            [craft.agents.backend.fabric.engine.chat :as chat]
            [craft.agents.backend.fabric.engine.combat :as combat]
            [craft.agents.backend.fabric.engine.karlach-tick :refer [tick-karlach!]]
            [craft.agents.backend.fabric.engine.picard-tick :as picard-tick :refer [tick-picard!]]
            [craft.agents.backend.fabric.engine.shelter :as shelter]
            [craft.agents.backend.fabric.engine.state :as state]
            [craft.agents.backend.fabric.engine.watchdog :as ewd]
            [craft.agents.backend.fabric.picard :refer [refresh-real-world-weather-async!]]
            [craft.agents.backend.fabric.sanctuary :refer [sanctuary-route]]
            [craft.agents.backend.fabric.sentry :refer [handle-player-command!]]
            [craft.agents.backend.fabric.telemetry :refer [global-command-dispatcher]]
            [craft.agents.backend.fabric.transport :refer [bridge-alive? send-bridge-cmd!]]
            [craft.agents.npc.state :as npc-state]))

;; Reexportacoes: a API publica de antes da quebra em sub-namespaces (CA-084).
(def strike-cooldown-ticks combat/strike-cooldown-ticks)
(def strike-ready? combat/strike-ready?)
(def battle-cry-cooldown-ticks combat/battle-cry-cooldown-ticks)
(def battle-cry-ready? combat/battle-cry-ready?)
(def evaluate-combat-preemption combat/evaluate-combat-preemption)
(def combat-decision combat/combat-decision)
(def rain-shelter-decision shelter/rain-shelter-decision)
(def weather-transition shelter/weather-transition)
(def shelter-route-steps shelter/shelter-route-steps)
(def sit-karlach-at-quarters! shelter/sit-karlach-at-quarters!)
(def stand-karlach! shelter/stand-karlach!)
(def teleport-karlach-to-leader! ewd/teleport-karlach-to-leader!)
(def teleport-agent-to-leader! ewd/teleport-agent-to-leader!)
(def follow-watchdog-step! ewd/follow-watchdog-step!)
(def patrol-watchdog-fired! ewd/patrol-watchdog-fired!)
(def picard-walk-tick picard-tick/picard-walk-tick)

(defn- run-tick!
  "Um tick de 50ms: chat (5Hz), manutencao periodica e os ticks A (Picard), B (Atelie) e C (Karlach)."
  [{:keys [tick engine-ctx-atom nav-state] :as env}]
  (when (zero? (mod tick 4))
    (chat/poll-bridge-chat! env))
  ;; Verifica a ponte e o clima real periodicamente (10 s)
  (when (zero? (mod tick 200))
    (refresh-real-world-weather-async!)
    (when-not (bridge-alive?)
      (ensure-live-bridge-and-agents!))
    (shelter/check-weather! engine-ctx-atom nav-state))
  ;; Vigilancia de fome: sem isto um agente chega a hp=1/food=0 e morre
  ;; (medido: Picard em 2026-11-10). Alimenta os quatro a cada ~10 s.
  (when (zero? (mod tick 100))
    (feed-hungry-agents!))
  (tick-picard! env)
  (tick-atelier! env)
  (bt-tick/tick-bt-agents! env)
  (tick-karlach! env))

(defn- run-loop!
  "Loop de 20Hz Server-Native (BT) da Karlach e do Picard; erros nao derrubam a thread."
  [{:keys [running?] :as env}]
  (loop [tick 1]
    (when @running?
      (let [t0 (System/currentTimeMillis)]
        (try
          (run-tick! (assoc env :tick tick))
          (catch Throwable t
            (println "[craft-agents-20hz-ERROR]" (.getMessage t))
            (.printStackTrace t)))
        (Thread/sleep (long (max 5 (- 50 (- (System/currentTimeMillis) t0)))))
        (recur (inc tick))))))

(defn start-live-karlach-loop!
  "Inicia o loop de 20Hz da Karlach e do Capitão Picard no servidor Fabric e atualiza o engine-ctx do Agent Studio."
  [engine-ctx-atom]
  (ensure-live-bridge-and-agents!)
  (refresh-real-world-weather-async!)
  (let [env {:engine-ctx-atom engine-ctx-atom
             :nav-state    (npc-state/agent-atom engine-ctx-atom :karlach (state/initial-nav-state))
             :picard-state (atom (state/initial-picard-state))
             :coco-state   (atelier/create-coco-state)
             :richeh-state (atelier/create-richeh-state)
             :running?     (atom true)}
        {:keys [nav-state picard-state coco-state richeh-state running?]} env]
    ;; Dispatcher global para comandos via Web UI e testes
    (reset! global-command-dispatcher
            (fn [spk m]
              (send-bridge-cmd! (str "CMD|tellraw @a [\"\",{\"text\":\"<" spk "> \",\"color\":\"white\"},{\"text\":\"" (str/replace m "\"" "\\\"") "\"}]"))
              (handle-player-command! spk m engine-ctx-atom nav-state picard-state sanctuary-route coco-state richeh-state)))
    (chat/start-chat-listener! env)
    (chat/start-item-poll! env)
    (doto (Thread. ^Runnable #(run-loop! env) "craft-agents-20hz-engine")
      (.setDaemon true)
      (.start))
    {:stop-fn #(reset! running? false)
     :nav-state nav-state
     :picard-state picard-state
     :coco-state coco-state
     :richeh-state richeh-state}))

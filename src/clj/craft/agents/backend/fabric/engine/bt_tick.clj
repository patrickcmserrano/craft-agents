(ns craft.agents.backend.fabric.engine.bt-tick
  "Tick de Behavior Tree por agente (CA-094): todo agente do registro que declara `:bt/root`
   ganha um passo de arvore a 5Hz. Agentes sem `:bt/root` mantem o tick atual; Karlach, Picard, Richeh e
   Coco tem tick dedicado (`self-driven`) e nao sao ticados aqui para nao haver dois motoristas."
  (:require [craft.agents.backend.fabric.goals :as goals]
            [craft.agents.backend.fabric.sanctuary :refer [sanctuary-route]]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.state :as npc-state]))

(def bt-period-ticks
  "Um passo de BT a cada N ticks de 50ms (5Hz)."
  4)

(def self-driven
  "Agentes com `:bt/root` cujo tick dedicado ja os conduz (tick-karlach!, tick-picard!, passos da Richeh e da Coco no atelie)."
  #{:karlach :picard :richeh :coco})

(defn tick-agent!
  "Um passo da `:bt/root` de `agent` (spec do registro). `env`: :engine-ctx-atom e, opcional,
   :sanctuary-route. Devolve o resultado do no raiz."
  [{:keys [engine-ctx-atom] :as env} agent]
  (let [st (npc-state/agent-atom engine-ctx-atom (:agent/id agent))]
    (goals/execute-tree-node! (:bt/root agent) engine-ctx-atom st
                              (or (:sanctuary-route env) sanctuary-route) nil agent)))

(defn tick-bt-agents!
  "Roda um passo de BT por agente com `:bt/root` (menos os `self-driven`) a cada
   `bt-period-ticks`. Erro de um agente nao derruba os outros nem o loop."
  [{:keys [tick] :as env}]
  (when (zero? (mod tick bt-period-ticks))
    (doseq [agent (registry/all-agents)
            :when (and (:bt/root agent) (not (contains? self-driven (:agent/id agent))))]
      (try (tick-agent! env agent)
           (catch Throwable t
             (println "[bt-ERROR]" (:agent/id agent) (.getMessage t)))))
    nil))

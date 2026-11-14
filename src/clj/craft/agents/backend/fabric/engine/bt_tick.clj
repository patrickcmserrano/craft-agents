(ns craft.agents.backend.fabric.engine.bt-tick
  "Tick de Behavior Tree por agente (CA-094): todo agente do registro que declara `:bt/root`
   ganha um passo de arvore a 5Hz. Agentes sem `:bt/root` mantem o tick atual. Karlach, Picard, Richeh e
   Coco tem tambem tick dedicado: os dois motoristas convivem sob `craft.agents.npc.body` (CA-104)."
  (:require [craft.agents.backend.fabric.goals :as goals]
            [craft.agents.backend.fabric.sanctuary :refer [sanctuary-route]]
            [craft.agents.npc.perception-tick :as perception-tick]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.state :as npc-state]))

(def bt-period-ticks
  "Um passo de BT a cada N ticks de 50ms (5Hz)."
  4)

(def self-driven
  "Agentes com `:bt/root` excluidos do tick de BT. CA-104: vazio — todo agente roda a BT, e o conflito
   corpo/cabeca (ex.: o giro de cabeca da Coco) e resolvido pelo arbitro `npc.body` (BT no canal
   :gaze, ticks dedicados no :legs; 1 MOVE por agente no `flush!` do tick)."
  #{})

(defn tick-agent!
  "Um passo da `:bt/root` de `agent` (spec do registro). `env`: :engine-ctx-atom e, opcional,
   :sanctuary-route. Devolve o resultado do no raiz."
  [{:keys [engine-ctx-atom tick] :as env} agent]
  (let [agent-id (:agent/id agent)
        st       (npc-state/agent-atom engine-ctx-atom agent-id)
        snap     (when engine-ctx-atom
                   (perception-tick/capture-snapshot! engine-ctx-atom
                                                      {:tick (or tick (:tick @engine-ctx-atom) 0)
                                                       :agent-id agent-id}))]
    (when (and engine-ctx-atom snap)
      (perception-tick/inject-perception! engine-ctx-atom agent-id snap))
    (perception-tick/with-snapshot-players snap
      #(goals/execute-tree-node! (:bt/root agent) engine-ctx-atom st
                                 (or (:sanctuary-route env) sanctuary-route) nil agent))))

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

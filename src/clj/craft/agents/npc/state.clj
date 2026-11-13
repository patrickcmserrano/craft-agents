(ns craft.agents.npc.state
  "Estado por agente (CA-089): mapa `agent-id -> atom` dentro do engine-ctx.

   `(agent-atom ctx id)` cria sob demanda o atomo do agente com os defaults
   `{:pos <spawn do registro> :mode :idle :path [] :watchdog nil}`. Um estado
   inicial opcional e mesclado sobre os defaults, apenas na criacao. O mapa vive
   em `(:agent-states @ctx)`; `ctx` e o atom do engine-ctx."
  (:require [craft.agents.npc.registry :as registry]))

(defn default-state
  "Estado default de um agente: posicao do spawn (nil se o agente nao esta no registro)."
  [id]
  {:pos (get-in (registry/agent-by-id id) [:agent/spawn :pos])
   :mode :idle
   :path []
   :watchdog nil})

(defn agent-atom
  "Atomo de estado do agente `id`, criado sob demanda e guardado em `ctx`."
  ([ctx id] (agent-atom ctx id nil))
  ([ctx id init]
   (or (get-in @ctx [:agent-states id])
       (let [fresh (atom (merge (default-state id) init))]
         (-> (swap! ctx update :agent-states
                    (fn [m] (if (contains? m id) m (assoc (or m {}) id fresh))))
             (get-in [:agent-states id]))))))

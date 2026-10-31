(ns craft.agents.cognition
  "Ponte cognitiva assincrona Event-Driven: acorda LLM sob gatilhos sem congelar o loop de 20Hz (CA-039)."
  (:require [craft.agents.blackboard :as bb]))

(defn dispatch-cognitive-trigger-async!
  "Envia SceneDigest assincronamente ao provedor LLM/MCP e aplica diretivas retornadas no Blackboard (CA-039)."
  [engine-ctx agent-id trigger llm-client-fn]
  (let [board (get-in engine-ctx [:agents agent-id :blackboard])]
    (future
      (let [digest {:trigger trigger :agent-id agent-id}
            calls  (llm-client-fn digest)]
        (doseq [call calls]
          (when (= "set_directive" (or (:tool call) (:name call)))
            (let [args (or (:arguments call) (:args call))]
              (when (and board args)
                (bb/put-directive! board args (System/currentTimeMillis))))))
        calls))))

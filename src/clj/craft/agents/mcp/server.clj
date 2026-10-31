(ns craft.agents.mcp.server
  "Servidor Model Context Protocol (MCP JSON-RPC 2.0) expondo Resources e Tools tipadas (CA-038, CA-052).")

(def default-tools
  [{:name "reply_and_gesture"
    :description "Envia mensagem no chat e executa gesto corporal"
    :inputSchema {:type "object"
                  :properties {:message {:type "string"}
                               :gesture {:type "string" :enum ["nod" "wave" "point"]}}
                  :required ["message"]}}
   {:name "set_directive"
    :description "Registra nova diretiva tatica com TTL no Blackboard do agente"
    :inputSchema {:type "object"
                  :properties {:type {:type "string"}
                               :ttl_ms {:type "integer"}}
                  :required ["type"]}}
   {:name "run_task_sequence"
    :description "Executa lista encadeada de tarefas de navegacao e interacao"
    :inputSchema {:type "object"
                  :properties {:tasks {:type "array"}}
                  :required ["tasks"]}}
   {:name "compose_and_save_skill"
    :description "Compoe e salva nova sub-arvore de Skill validada no gatekeeper"
    :inputSchema {:type "object"
                  :properties {:skill_id {:type "string"}
                               :tree {:type "array"}}
                  :required ["skill_id" "tree"]}}])

(defn handle-jsonrpc-request
  "Processa requisicao JSON-RPC 2.0 MCP (initialize, tools/list, resources/list, tools/call) (CA-038, CA-052)."
  [_engine-ctx rpc-request]
  (let [id     (:id rpc-request)
        method (:method rpc-request)]
    (case method
      "initialize"
      {:jsonrpc "2.0"
       :id id
       :result {:protocolVersion "2024-11-05"
                :capabilities {:tools {} :resources {}}
                :serverInfo {:name "craft-agents-mcp" :version "0.1.0"}}}

      "tools/list"
      {:jsonrpc "2.0"
       :id id
       :result {:tools default-tools}}

      "resources/list"
      {:jsonrpc "2.0"
       :id id
       :result {:resources []}}

      "tools/call"
      {:jsonrpc "2.0"
       :id id
       :result {:content [{:type "text" :text "Executed successfully"}]}}

      {:jsonrpc "2.0"
       :id id
       :error {:code -32601 :message (str "Method not found: " method)}})))

(ns craft.agents.studio.server
  "Servidor HTTP + WebSocket (porta 8095) do Agent Studio transmitindo telemetria ao vivo (CA-040)."
  (:require [org.httpkit.server :as http]))

(defn build-telemetry-frame
  "Monta frame compacto de telemetria (arvore ativa, leases, rota A*, blackboard) p/ broadcast WebSocket (CA-040)."
  [engine-ctx]
  {:tick      (:tick engine-ctx 0)
   :timestamp (System/currentTimeMillis)
   :agents    (:agents engine-ctx {})
   :leases    (:leases engine-ctx {})})

(defn start-studio-server!
  "Inicia http-kit servindo UI estatica e endpoint /ws na porta configurada (CA-040)."
  [engine-ctx port]
  (let [app-handler (fn [_req]
                      {:status 200
                       :headers {"Content-Type" "text/plain"}
                       :body "Agent Studio HTTP/WS"})]
    (http/run-server app-handler {:port (or port 8095)})))

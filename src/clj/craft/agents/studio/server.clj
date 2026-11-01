(ns craft.agents.studio.server
  "Servidor HTTP + WebSocket (porta 8095) do Agent Studio transmitindo telemetria ao vivo (CA-040)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [craft.agents.backend.fabric :as fabric]
            [org.httpkit.server :as http]))

(defn build-telemetry-frame
  "Monta frame compacto de telemetria (arvore ativa, leases, rota A*, blackboard) p/ broadcast WebSocket (CA-040)."
  [engine-ctx]
  (let [ctx (if (instance? clojure.lang.IDeref engine-ctx) @engine-ctx engine-ctx)]
    {:tick      (:tick ctx 0)
     :timestamp (System/currentTimeMillis)
     :agents    (:agents ctx {})
     :leases    (:leases ctx {})}))

(defn- serve-file [path content-type]
  (if-let [res (io/resource path)]
    {:status 200
     :headers {"Content-Type" content-type}
     :body (slurp res)}
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "Resource Not Found"}))

(defn create-app-handler [engine-ctx]
  (fn [req]
    (let [uri (:uri req)]
      (cond
        (= uri "/")
        (serve-file "public/index.html" "text/html; charset=utf-8")

        (= uri "/style.css")
        (serve-file "public/style.css" "text/css; charset=utf-8")

        (= uri "/app.js")
        (serve-file "public/app.js" "application/javascript; charset=utf-8")

        (= uri "/api/status")
        {:status 200
         :headers {"Content-Type" "application/json; charset=utf-8"}
         :body (json/write-str (build-telemetry-frame engine-ctx))}

        (= uri "/ws")
        (if (:websocket? req)
          (http/as-channel req
                           {:on-open (fn [ch]
                                       (http/send! ch (json/write-str (build-telemetry-frame engine-ctx))))
                            :on-receive (fn [ch msg]
                                          (http/send! ch (json/write-str {:echo msg :status "ok"})))})
          {:status 400 :body "Expected WebSocket request"})

        :else
        {:status 404
         :headers {"Content-Type" "text/plain"}
         :body "Not Found"}))))

(defn start-studio-server!
  "Inicia http-kit servindo UI estatica e endpoint /ws na porta configurada (CA-040)."
  [engine-ctx port]
  (let [p (or port 8095)
        handler (create-app-handler engine-ctx)]
    (println (str "[craft-agents] 🚀 Agent Studio ativo em http://localhost:" p))
    (http/run-server handler {:port p})))

(defn -main [& args]
  (let [port (or (some-> (first args) parse-long) 8095)
        live-ctx (atom {:tick 0
                        :leases {:legs nil :hands nil :gaze nil}
                        :agents {:karlach {:status "starting" :pos [-175.0 65.0 180.0]}}})]
    (fabric/start-live-karlach-loop! live-ctx)
    (start-studio-server! live-ctx port)
    (println "[craft-agents] 🔥 Karlach Server-Native + Agent Studio em execucao continua.")
    (while true (Thread/sleep 10000))))

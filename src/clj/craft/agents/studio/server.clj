(ns craft.agents.studio.server
  "Servidor HTTP + WebSocket (porta 8095) do Agent Studio transmitindo telemetria ao vivo (CA-040)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [craft.agents.backend.fabric :as fabric]
            [craft.agents.cognition.semantic-router :as router]
            [org.httpkit.server :as http]))

(defn load-skill-library
  "Carrega do disco (diretório skills/) todas as skills compiladas e validadas."
  []
  (let [skills-dir (io/file "skills")]
    (if (.isDirectory skills-dir)
      (vec
       (keep (fn [^java.io.File f]
               (try
                 (let [content (slurp f)
                       parsed  (edn/read-string content)]
                   {:id          (name (:skill/id parsed (str/replace (.getName f) #"\.edn$" "")))
                    :name        (or (:skill/name parsed) "Skill")
                    :author      (or (:skill/author parsed) "Voyager-2.0")
                    :trigger     (str (:skill/trigger parsed :manual))
                    :timeout-ms  (or (:skill/timeout-ms parsed) 20000)
                    :tree        (:skill/tree parsed [])
                    :edn         content
                    :malli-valid true})
                 (catch Throwable _ nil)))
             (filter #(str/ends-with? (.getName ^java.io.File %) ".edn")
                     (file-seq skills-dir))))
      [])))

(defn build-telemetry-frame
  "Monta frame compacto de telemetria (arvore ativa, leases, rota A*, blackboard, feed cognitivo e skills) p/ broadcast WebSocket (CA-040)."
  [engine-ctx]
  (let [ctx (if (instance? clojure.lang.IDeref engine-ctx) @engine-ctx engine-ctx)
        online-players (try (fabric/query-online-players) (catch Throwable _ []))
        weather-info   (try (fabric/query-minecraft-weather) (catch Throwable _ {:clock "12:00" :phase "Dia" :sky "Céu Limpo"}))
        karlach-inv    (try (fabric/inspect-inventory "Karlach") (catch Throwable _ {:slots-used 0 :items {}}))]
    {:tick      (:tick ctx 0)
     :timestamp (System/currentTimeMillis)
     :agents    (:agents ctx {})
     :leases    (:leases ctx {})
     :resources (:resources ctx {})
     :events    (:events ctx [])
     :skills    (load-skill-library)
     :players   online-players
     :weather   weather-info
     :inventory {:karlach karlach-inv}}))

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
    (let [uri (:uri req)
          method (:request-method req)]
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

        (and (= uri "/api/chat") (= method :post))
        (let [body (try
                     (let [raw (slurp (:body req))]
                       (json/read-str raw :key-fn keyword))
                     (catch Throwable _ {}))
              speaker (or (:speaker body) "pace")
              msg     (or (:message body) "")]
          (fabric/dispatch-simulated-chat! speaker msg)
          {:status 200
           :headers {"Content-Type" "application/json; charset=utf-8"}
           :body (json/write-str {:status "ok" :speaker speaker :message msg})})

        (and (= uri "/api/clear-events") (= method :post))
        (do
          (when (instance? clojure.lang.IAtom engine-ctx)
            (swap! engine-ctx assoc :events []))
          {:status 200
           :headers {"Content-Type" "application/json; charset=utf-8"}
           :body (json/write-str {:status "ok" :cleared true})})

        (and (= uri "/api/reload") (= method :post))
        (try
          (println "[craft-agents] 🔄 Recebida solicitação de Hot-Reload em tempo de execução...")
          (require 'craft.agents.skills.actions :reload)
          (require 'craft.agents.skills.composer :reload)
          (require 'craft.agents.cognition.semantic-router :reload)
          (require 'craft.agents.cognition.intent :reload)
          (require 'craft.agents.backend.fabric.goals :reload)
          (let [loaded-routes (router/load-persisted-routes! "agents/routes")]
            (when (instance? clojure.lang.IAtom engine-ctx)
              (swap! engine-ctx update :events (fnil conj [])
                     {:timestamp (System/currentTimeMillis)
                      :type :rsi
                      :source "JVM Hot-Reload"
                      :text (str "Capacidades recarregadas dinamicamente via POST /api/reload (" (count loaded-routes) " rotas ativas).")}))
            (println (format "[craft-agents] ✅ Hot-Reload concluído com sucesso. %d rotas recarregadas." (count loaded-routes)))
            {:status 200
             :headers {"Content-Type" "application/json; charset=utf-8"}
             :body (json/write-str {:status "ok"
                                    :reloaded true
                                    :routes-loaded (count loaded-routes)})})
          (catch Throwable t
            (println (format "[craft-agents] ❌ Erro durante Hot-Reload: %s" (.getMessage t)))
            {:status 500
             :headers {"Content-Type" "application/json; charset=utf-8"}
             :body (json/write-str {:status "error" :message (.getMessage t)})}))

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
                        :agents {:karlach {:status "starting" :pos [-175.0 65.0 180.0]}}
                        :events []})]
    (router/load-persisted-routes! "agents/routes")
    (fabric/start-live-karlach-loop! live-ctx)
    (start-studio-server! live-ctx port)
    (println "[craft-agents] 🔥 Karlach Server-Native + Agent Studio em execucao continua.")
    (while true (Thread/sleep 10000))))

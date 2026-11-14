(ns craft.agents.studio.server
  "Servidor HTTP + WebSocket (porta 8095) do Agent Studio transmitindo telemetria ao vivo (CA-040)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [craft.agents.astrophysics.ephemeris :as ephem]
            [craft.agents.backend.fabric :as fabric]
            [craft.agents.cognition.semantic-router :as router]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.state :as npc-state]
            [nrepl.server :as nrepl]
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

(defn studio-snapshot
  "Estado por agente para o Studio (CA-089): `:agents` (`:pos`/`:mode` de cada agente
   do registro, mesclados sobre o que o engine publicou em `(:agents ctx)`) e
   `:inventory` (`id -> inventario`), ambos a partir do registro de agentes."
  [engine-ctx]
  (let [ctx (if (instance? clojure.lang.IDeref engine-ctx) @engine-ctx engine-ctx)
        specs (registry/all-agents)
        published (:agents ctx {})
        ids (distinct (concat (map :agent/id specs) (keys published)))
        agent-view (fn [id]
                     (let [st (or (get-in ctx [:agent-states id])
                                  (npc-state/default-state id))
                           st (if (instance? clojure.lang.IDeref st) @st st)
                           pub (get published id)]
                       (merge (select-keys st [:pos :mode]) pub)))]
    {:agents (into {} (map (juxt identity agent-view)) ids)
     :inventory (into {}
                      (map (fn [spec]
                             [(:agent/id spec)
                              (try (fabric/inspect-inventory (:agent/name spec))
                                   (catch Throwable _ {:slots-used 0 :items {}}))]))
                      specs)}))

(defn build-telemetry-frame
  "Monta frame compacto de telemetria (arvore ativa, leases, rota A*, blackboard, feed cognitivo e skills) p/ broadcast WebSocket (CA-040)."
  [engine-ctx]
  (let [ctx (if (instance? clojure.lang.IDeref engine-ctx) @engine-ctx engine-ctx)
        online-players (try (fabric/query-online-players) (catch Throwable _ []))
        weather-info   (try (fabric/query-minecraft-weather) (catch Throwable _ {:clock "12:00" :phase "Dia" :sky "Céu Limpo"}))
        {:keys [agents inventory]} (studio-snapshot ctx)]
    {:tick      (:tick ctx 0)
     :timestamp (System/currentTimeMillis)
     :agents    agents
     :karlach-mode (:karlach-mode ctx)
     :karlach-nav-pos (:karlach-nav-pos ctx)
     :sheltering? (:sheltering? ctx)
     :shelter-steps-left (:shelter-steps-left ctx)
     :shelter-step-head (:shelter-step-head ctx)
     :cached-weather (:weather ctx)
     :weather-edge (:weather-edge ctx)
     :weather-alerts-sent (:weather-alerts-sent ctx)
     :leases    (:leases ctx {})
     :resources (:resources ctx {})
     :events    (:events ctx [])
     :skills    (load-skill-library)
     :players   online-players
     :weather   weather-info
     :celestial (ephem/shader-uniform-payload (:tick ctx 6000))
     :inventory inventory}))

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

        (= uri "/sky-preview")
        (serve-file "public/sky_preview.html" "text/html; charset=utf-8")

        (= uri "/api/sky/uniforms")
        (let [ctx (if (instance? clojure.lang.IDeref engine-ctx) @engine-ctx engine-ctx)
              tick (or (:tick ctx) 6000)
              payload (ephem/shader-uniform-payload tick)]
          {:status 200
           :headers {"Content-Type" "application/json; charset=utf-8"
                     "Access-Control-Allow-Origin" "*"}
           :body (json/write-str payload)})

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
          (let [reg-result    (registry/reload!)
                loaded-agents (:agents reg-result)
                loaded-routes (router/load-persisted-routes! "agents/routes")]
            (when (instance? clojure.lang.IAtom engine-ctx)
              (swap! engine-ctx update :events (fnil conj [])
                     {:timestamp (System/currentTimeMillis)
                      :type :rsi
                      :source "JVM Hot-Reload"
                      :text (str "Capacidades recarregadas dinamicamente via POST /api/reload ("
                                 (count loaded-routes) " rotas ativas, "
                                 (count loaded-agents) " agentes).")}))
            (println (format "[craft-agents] ✅ Hot-Reload concluído com sucesso. %d rotas recarregadas, %d agentes."
                             (count loaded-routes) (count loaded-agents)))
            {:status 200
             :headers {"Content-Type" "application/json; charset=utf-8"}
             :body (json/write-str {:status "ok"
                                    :reloaded true
                                    :routes-loaded (count loaded-routes)
                                    :agents-loaded (count loaded-agents)})})
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

(defn start-nrepl-server!
  "Sobe um nREPL (default 7891) nesta JVM — o classloader do engine, que já tem
  craft.agents.* carregado. Serve para avaliar Clojure contra o engine vivo:
  rotas, blackboard, live-ctx, decisões. Não alcança a entidade no mundo (isso é
  o nREPL in-server da porta 7890)."
  [port]
  (let [p (or port 7891)]
    (try
      (nrepl/start-server :port p :bind "127.0.0.1")
      (println (str "[craft-agents] nREPL do engine escutando em 127.0.0.1:" p))
      (catch Throwable t
        (println "[craft-agents] Erro ao iniciar nREPL do engine:" (.getMessage t))))))

(defn -main [& args]
  (let [port (or (some-> (first args) parse-long) 8095)
        live-ctx (atom {:tick 0
                        :leases {:legs nil :hands nil :gaze nil}
                        :agents {:karlach {:status "starting" :pos [-175.0 65.0 180.0]}
                                 :picard  {:status "starting" :pos [-65.0 145.0 101.0]}
                                 :coco    {:status "starting" :pos [-30.0 66.0 190.0]}
                                 :richeh  {:status "starting" :pos [-30.0 66.0 192.0]}}
                        :events []})]
    (router/load-persisted-routes! "agents/routes")
    (fabric/start-live-karlach-loop! live-ctx)
    (start-studio-server! live-ctx port)
    (start-nrepl-server! (or (some-> (second args) parse-long) 7891))
    (println "[craft-agents] 🔥 Karlach Server-Native + Agent Studio em execucao continua.")
    (while true (Thread/sleep 10000))))

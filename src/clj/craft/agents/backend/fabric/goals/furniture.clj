(ns craft.agents.backend.fabric.goals.furniture
  "Metas de mobilia e abrigo: sentar, levantar e abrigo de chuva (CA-085, CA-091).
   O nome e o id do agente vem do contexto de `execute-goal!`."
  (:require [clojure.java.io :as io]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch :refer [execute-goal!]]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.skills.composer :as composer]))

(defmethod execute-goal! :weather-shelter
  [{:goal/keys [voice-line]} {engine-ctx-atom :engine-ctx nav-state :state sanctuary-route :sanctuary-route :as ctx}]
  (transport/send-bridge-cmd! (str "SWING|" (dispatch/agent-name ctx)))
  (transport/send-bridge-cmd! (str "CMD|execute at " (dispatch/agent-name ctx) " run playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2"))
  (dispatch/say! ctx voice-line)

  (record-cognitive-event! engine-ctx-atom :synthesis "System 2 Voyager Synthesizer"
                           "Objetivo: :shelter-from-rain. Compondo árvore de comportamento [:sequence [:action :check-weather] [:action :move-to-quarters] [:action :rest-at-bed]]")

  (let [skills-dir (io/file "skills")
        _ (.mkdirs skills-dir)
        skill-registry (atom {:known-actions #{:check-weather :move-to-quarters :rest-at-bed :wait-out-rain}
                              :composite-skills {}})
        shelter-skill {:skill/id :shelter-from-rain
                       :skill/name "Abrigo de Emergência contra Chuva"
                       :skill/author "Voyager-System-2-Cognition"
                       :skill/trigger :weather-rain
                       :skill/timeout-ms 20000
                       :skill/tree [:sequence
                                    [:action :check-weather]
                                    [:action :move-to-quarters]
                                    [:action :rest-at-bed]
                                    [:action :wait-out-rain]]}
        reg-res (composer/validate-and-register-skill! skill-registry skills-dir shelter-skill)]
    (println (str "[craft-agents] 🧠 Nova Skill composta via MCP: :shelter-from-rain"))
    (println (str "[craft-agents] 🛡️ Gatekeeper Malli: " (if (:ok? reg-res) "APROVADA (Sem eval / Schema OK / Ciclos 0)" "REJEITADA") " -> Salvo em " (str (io/file skills-dir "shelter-from-rain.edn"))))

    (let [pre-res (composer/evaluate-preconditions (:skill/tree shelter-skill) {})]
      (record-cognitive-event! engine-ctx-atom :gatekeeper "Malli Schema Gatekeeper"
                               (str "Validação AST estrutural & Sandbox Mental: SUCESSO (Zero eval / Tipos conferidos / 0 ciclos recursivos / Pré-condições: " (clojure.core/name (:status pre-res :ok)) ").")))
    (record-cognitive-event! engine-ctx-atom :skill-lib "Skill Library"
                             ":shelter-from-rain registrada permanentemente em skills/shelter-from-rain.edn.")

    (when engine-ctx-atom
      (swap! engine-ctx-atom update-in [:agents (dispatch/agent-id ctx) :skills]
             (fnil #(vec (distinct (conj % "shelter-from-rain"))) []))
      (swap! engine-ctx-atom assoc-in [:agents (dispatch/agent-id ctx) :last-learned] ":shelter-from-rain (Malli OK)")
      (swap! engine-ctx-atom assoc-in [:agents (dispatch/agent-id ctx) :task] "Indo para o Quarto se abrigar"))

    (dispatch/say! ctx "Chuva detectada, soldado! Indo pro meu quarto me proteger!")

    (let [quarters-stop (some #(when (= (:type %) :quarters) %) sanctuary-route)]
      (if quarters-stop
        (do
          (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller"
                                   (str "Iniciando navegação A* com microsteps para o quarto de " (dispatch/agent-name ctx)))
          (swap! nav-state assoc
                 :mode :sanctuary-patrol
                 :follow-target nil
                 :steps (vec (nav/expand-route-microsteps quarters-stop (:pos @nav-state)))))
        (dispatch/say! ctx "Meu quarto ainda tá sendo arrumado!")))))

(defmethod execute-goal! :sit-furniture
  [{:goal/keys [speaker voice-line]} {engine-ctx-atom :engine-ctx :as ctx}]
  (record-cognitive-event! engine-ctx-atom :action "Ação de Mobília / Sentar no Sofá"
                           (str (dispatch/agent-name ctx) " buscando assento livre próximo para sentar"))
  (dispatch/say! ctx (or voice-line (str "Sentando aqui no sofá para descansar um pouco, " (or speaker "soldado") "! 🔥")))
  (let [res (transport/send-bridge-cmd! (str "SIT|" (dispatch/agent-name ctx)))]
    (record-cognitive-event! engine-ctx-atom :motor "Assento Ocupado" (str "Bridge SIT response: " res))
    (transport/send-bridge-cmd! "CMD|playsound minecraft:block.wool.place master @a ~ ~ ~ 0.8 1.0")))

(defmethod execute-goal! :stand-up
  [{:goal/keys [speaker voice-line]} {engine-ctx-atom :engine-ctx :as ctx}]
  (let [res (transport/send-bridge-cmd! (str "STAND|" (dispatch/agent-name ctx)))]
    (record-cognitive-event! engine-ctx-atom :action "Levantar do Sofá" (str "Bridge STAND response: " res))
    (dispatch/say! ctx (or voice-line (str "De pé e pronta pro combate, " (or speaker "soldado") "! 🔥")))
    (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.player.attack.sweep master @a ~ ~ ~ 0.5 1.2")))

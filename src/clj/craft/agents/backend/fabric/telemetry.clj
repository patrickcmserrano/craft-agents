(ns craft.agents.backend.fabric.telemetry
  "Telemetria cognitiva estruturada e dispatcher de comandos para o Agent Studio."
  (:import [java.time LocalTime]
           [java.time.format DateTimeFormatter]))

(defonce global-command-dispatcher (atom nil))

(def ^:private time-formatter (DateTimeFormatter/ofPattern "HH:mm:ss"))

(defn record-cognitive-event!
  "Registra evento cognitivo estruturado no atom do Studio (CA-040/CA-041)."
  [engine-ctx-atom evt-type source text]
  (when engine-ctx-atom
    (let [now-str (.format (LocalTime/now) time-formatter)
          badge-info (case evt-type
                       :stimulus    {:badge "STIMULUS" :color "#38bdf8"}
                       :router      {:badge "ROUTER" :color "#a855f7"}
                       :grounding   {:badge "GROUNDING" :color "#06b6d4"}
                       :synthesis   {:badge "SYSTEM 2" :color "#c084fc"}
                       :gatekeeper  {:badge "GATEKEEPER" :color "#4ade80"}
                       :skill-lib   {:badge "SKILL LIB" :color "#fbbf24"}
                       :motor       {:badge "SYSTEM 1" :color "#f87171"}
                       :critic      {:badge "CRITIC" :color "#22c55e"}
                       :critic-fail {:badge "CRITIC FAIL" :color "#ef4444"}
                       :reflexion   {:badge "REFLEXION" :color "#f59e0b"}
                       {:badge "EVENT" :color "#94a3b8"})
          evt (merge badge-info
                     {:timestamp (System/currentTimeMillis)
                      :time      now-str
                      :type      evt-type
                      :source    source
                      :text      text})]
      (swap! engine-ctx-atom update :events
             (fn [evts]
               (let [evts (or evts [])]
                 (vec (take-last 40 (conj evts evt)))))))))

(defn dispatch-simulated-chat!
  "Permite disparar comandos simulados a partir da UI do Studio ou testes."
  [speaker msg]
  (when-let [disp @global-command-dispatcher]
    (disp speaker msg)))

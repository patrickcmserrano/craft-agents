(ns craft.agents.backend.fabric.goals.dispatch
  "Multimethod unico de despacho de metas cognitivas da Karlach (CA-085).
   Os defmethod vivem em goals.rescue/companion/work/furniture/skills; a fachada
   craft.agents.backend.fabric.goals garante o require de todos."
  (:require [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]))

(defmulti execute-karlach-goal!
  "Despacha a meta cognitiva fundamentada (GoalSpecification) para atuacao fisica e telemetria (CA-040)."
  (fn [goal-spec _engine-ctx _nav-state _sanctuary-route]
    (:goal/type goal-spec)))

(defmethod execute-karlach-goal! :default
  [{:goal/keys [raw-text voice-line]} engine-ctx-atom _nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :synthesis "System 2 Voyager Synthesizer"
                           (str "Analisando comando natural: '" raw-text "'. Decompondo em diretriz reativa."))
  (actions/karlach-say! voice-line)
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" "Executando diretriz"))

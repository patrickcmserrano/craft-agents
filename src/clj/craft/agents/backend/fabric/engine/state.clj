(ns craft.agents.backend.fabric.engine.state
  "Estado inicial dos atoms do loop de 20Hz (Karlach e Picard)."
  (:require [craft.agents.backend.fabric.navigation :refer [expand-route-microsteps]]
            [craft.agents.backend.fabric.sanctuary :refer [sanctuary-route]]))

(def karlach-start-pos [-175.0 65.0 180.0])

(defn initial-nav-state
  "Estado da Karlach: patrulha do santuario a partir da primeira parada da rota."
  []
  {:pos karlach-start-pos
   :yaw 0.0
   :pitch 0.0
   :mode :sanctuary-patrol
   :follow-target nil
   :dance-ticks 0
   :route-idx 0
   :steps (vec (expand-route-microsteps (first sanctuary-route) karlach-start-pos))
   :wait-left 0
   :sheltering? false
   :seated? false
   :last-weather nil
   :last-bark-ms 0})

(defn initial-picard-state
  "Estado do Capitao Picard: comando da Ponte da Enterprise-D, estacao 0."
  []
  {:pos [-65.0 145.0 101.0]
   :yaw 0.0
   :pitch 0.0
   :mode :bridge-command
   :follow-target nil
   :station-idx 0
   :phase :walk
   :wait-left 0
   :last-bark-ms 0
   :last-weather-ms 0
   :status "Comando da Ponte (Enterprise-D)"})

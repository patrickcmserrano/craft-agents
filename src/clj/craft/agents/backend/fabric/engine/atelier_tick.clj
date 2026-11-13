(ns craft.agents.backend.fabric.engine.atelier-tick
  "Tick (20Hz) do Atelie: Coco e Richeh no Castelo Touros Negros."
  (:require [craft.agents.astrophysics.ephemeris :as ephem]
            [craft.agents.backend.fabric.actions :refer [query-online-players]]
            [craft.agents.backend.fabric.atelier :as atelier]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd!]]))

(defn- say-as
  "Fala de duo: roteia (who text) para a bruxa certa."
  [who text]
  (if (= who :coco)
    (atelier/coco-say! text)
    (atelier/richeh-say! text)))

(defn- auto-defense!
  "Auto-defesa das bruxas: se houver hostil no raio, Coco e Richeh conjuram as
   suas magias (cooldown de 5s dentro de cast-*-spell!)."
  [coco-state richeh-state]
  (let [now (System/currentTimeMillis)]
    (when (atelier/spell-auto-defense! coco-state "Coco" send-bridge-cmd! now)
      (when (atelier/should-say-spell? coco-state now)
        (atelier/coco-say! "Glifo de Tinta Viva! Ele não vai chegar perto! 🖋️✨")))
    (when (atelier/spell-auto-defense! richeh-state "Richeh" send-bridge-cmd! now)
      (when (atelier/should-say-spell? richeh-state now)
        (atelier/richeh-say! "Estilhaço de Cristal. Fica atrás de mim. 🔮")))))

(defn tick-atelier!
  "Um tick do Atelie. `env` precisa de :coco-state, :richeh-state e :tick."
  [{:keys [coco-state richeh-state tick]}]
  (let [online-p (try (query-online-players) (catch Throwable _ []))]
    (try
      (atelier/step-coco-tick! coco-state send-bridge-cmd! tick online-p)
      (atelier/step-richeh-tick! richeh-state coco-state send-bridge-cmd! tick online-p)
      (atelier/check-duo-synchrony! coco-state richeh-state send-bridge-cmd! say-as)
      (let [cel (ephem/compute-celestial-state (mod tick 24000))]
        (atelier/check-celestial-reaction! coco-state richeh-state cel send-bridge-cmd! say-as))
      (when (zero? (mod tick 20))
        (auto-defense! coco-state richeh-state))
      (catch Throwable t
        (println "[craft-agents-atelier-ERROR]" (.getMessage t))))))

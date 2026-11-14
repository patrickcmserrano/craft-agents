(ns craft.agents.backend.fabric.engine.atelier-tick
  "Tick (20Hz) do Atelie.
   CA-111 (SUG-06 parte 2): rotina diaria, interacao da dupla, pontos do atelie e reacoes ao ceu
   vivem na `:bt/root` dos `.edn` das bruxas (interpretada por `bt-tick`, 5Hz, emissao unica pelo
   `npc.body`). Aqui restam so tres coisas que a BT nao cobre:
     1. publicar no engine-ctx a hora do mundo (`:world-time`) e o ceu (`:celestial`) que os
        guards/acoes genericos da BT consomem;
     2. a ordem de SEGUIR um jogador (`:mode :follow` dos atoms de estado, via chat), que entra no
        `npc.body` como fonte :player-goal (prioridade acima da BT);
     3. a auto-defesa por magia (cooldown de 5s dentro de `cast-*-spell!`).
   CA-112: este namespace nao nomeia nenhuma persona; o despacho por bruxa (2 e 3) vive na fachada
   legada `atelier` (guardada por `npc_persona_structural_test`)."
  (:require [craft.agents.astrophysics.ephemeris :as ephem]
            [craft.agents.backend.fabric.actions :refer [query-online-players]]
            [craft.agents.backend.fabric.atelier :as atelier]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd!]]))

(def ^:private ticks-per-day 24000)

(defn- publish-world!
  "Hora do mundo (tick do loop, 24000 = 1 dia, o mesmo relogio das efemerides) e ceu no engine-ctx."
  [engine-ctx-atom tick]
  (when engine-ctx-atom
    (let [t (mod tick ticks-per-day)]
      (swap! engine-ctx-atom assoc
             :world-time t
             :celestial (merge {:phenomenon :nominal} (ephem/compute-celestial-state t))))))

(defn tick-atelier!
  "Um tick do Atelie. `env` precisa de :coco-state, :richeh-state e :tick (e :engine-ctx-atom)."
  [{:keys [tick engine-ctx-atom] :as env}]
  (try
    (publish-world! engine-ctx-atom tick)
    (atelier/follow-tick! env send-bridge-cmd! query-online-players)
    (when (zero? (mod tick 20))
      (atelier/auto-defense! env send-bridge-cmd!))
    (catch Throwable t
      (println "[craft-agents-atelier-ERROR]" (.getMessage t)))))

(ns craft.agents.backend.fabric.engine.atelier-tick
  "Tick (20Hz) do Atelie: Coco e Richeh no Castelo Touros Negros.
   CA-111 (SUG-06 parte 2): rotina diaria, interacao da dupla, pontos do atelie e reacoes ao ceu
   vivem na `:bt/root` de `agents/coco.edn` e `agents/richeh.edn` (interpretada por `bt-tick`, 5Hz,
   emissao unica pelo `npc.body`). Aqui restam so tres coisas que a BT nao cobre:
     1. publicar no engine-ctx a hora do mundo (`:world-time`) e o ceu (`:celestial`) que os
        guards/acoes genericos da BT consomem;
     2. a ordem de SEGUIR um jogador (`:mode :follow` dos atoms de estado, via chat), que entra no
        `npc.body` como fonte :player-goal (prioridade acima da BT);
     3. a auto-defesa por magia (cooldown de 5s dentro de `cast-*-spell!`)."
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

(defn- sync-follow-pos!
  "Antes de seguir um jogador, parte da posicao real publicada pela BT (a BT e quem anda nos demais
   modos), em vez da ultima integrada pelo driver de seguir."
  [state engine-ctx-atom agent-id]
  (when-let [pos (get-in (some-> engine-ctx-atom deref) [:agents agent-id :pos])]
    (swap! state assoc :pos (vec pos))))

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
  "Um tick do Atelie. `env` precisa de :coco-state, :richeh-state e :tick (e :engine-ctx-atom)."
  [{:keys [coco-state richeh-state tick engine-ctx-atom]}]
  (try
    (publish-world! engine-ctx-atom tick)
    (when (or (= :follow (:mode @coco-state)) (= :follow (:mode @richeh-state)))
      (let [online-p (try (query-online-players) (catch Throwable _ []))]
        (when (= :follow (:mode @coco-state))
          (sync-follow-pos! coco-state engine-ctx-atom :coco)
          (atelier/step-coco-tick! coco-state send-bridge-cmd! tick online-p))
        (when (= :follow (:mode @richeh-state))
          (sync-follow-pos! richeh-state engine-ctx-atom :richeh)
          (atelier/step-richeh-tick! richeh-state coco-state send-bridge-cmd! tick online-p))))
    (when (zero? (mod tick 20))
      (auto-defense! coco-state richeh-state))
    (catch Throwable t
      (println "[craft-agents-atelier-ERROR]" (.getMessage t)))))

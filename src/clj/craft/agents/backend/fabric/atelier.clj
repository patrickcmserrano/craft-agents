(ns craft.agents.backend.fabric.atelier
  "Comportamento de 20 TPS, navegação e interação canônica do Ateliê (Coco & Richeh) no Castelo dos Touros Negros.
   Fachada: a implementacao vive nos sub-namespaces `atelier.*`; aqui ficam
   reexportados os nomes publicos para quem consome `atelier/...`.
   CA-111: o comportamento autonomo (rotina, dupla, reacoes) e da BT dos `.edn`; de `behavior/*`
   o engine usa apenas o driver de seguir (`step-*-tick!` em `:mode :follow`) e `create-*-state`."
  (:require [craft.agents.backend.fabric.atelier.speech :as speech]
            [craft.agents.backend.fabric.atelier.registry :as registry]
            [craft.agents.backend.fabric.atelier.aim :as aim]
            [craft.agents.backend.fabric.atelier.spells :as spells]
            [craft.agents.backend.fabric.atelier.held :as held]
            [craft.agents.backend.fabric.atelier.behavior :as behavior]
            [craft.agents.backend.fabric.atelier.chat :as chat]))

(defmacro ^:private reexport
  "Reexporta vars dos sub-namespaces preservando docstring e arglists.
   Funcoes delegam pela VAR a cada chamada (nao copiam o valor): redefinir a
   funcao no sub-namespace (nREPL, re-attach) vale tambem para quem chama pela
   fachada. Valores (waypoints, registry) sao copiados."
  [& qsyms]
  `(do ~@(for [q qsyms]
           `(let [v# (var ~q)]
              (intern '~(ns-name *ns*)
                      (with-meta '~(symbol (name q)) (select-keys (meta v#) [:doc :arglists]))
                      (if (fn? @v#)
                        (fn [& args#] (apply v# args#))
                        @v#))))))

(reexport speech/load-agent-edn
          speech/coco-say!
          speech/richeh-say!
          speech/caster-say!)

(reexport registry/fireball-item-id
          registry/spell-registry)

(reexport aim/player-aim-snapshot
          aim/select-aim-target
          aim/select-aim-target-cone
          aim/find-safe-ground-y)

(reexport spells/should-say-spell?
          spells/cast-spell-with-aim!
          spells/cast-casting!
          spells/cast-coco-spell!
          spells/cast-richeh-spell!
          spells/spell-auto-defense!)

(reexport held/held-spell-item
          held/held-item-damage
          held/spell-used?
          held/fireball-used?
          held/dispatch-held-spell!)

(reexport behavior/castle-waypoints
          behavior/create-coco-state
          behavior/create-richeh-state
          behavior/step-coco-tick!
          behavior/step-richeh-tick!
          behavior/check-duo-synchrony!
          behavior/check-celestial-reaction!)

(reexport chat/casting-spell?
          chat/atelier-command?
          chat/handle-atelier-chat!)

;; =============================================================================
;; Despacho do tick do Atelie (CA-112). A persona fica nesta fachada legada; o
;; `engine/atelier_tick.clj` (varrido por `npc_persona_structural_test`) so chama estas funcoes.
;; Chamam as vars reexportadas acima, entao redefini-las (nREPL, testes) continua valendo.
;; =============================================================================

(defn- sync-follow-pos!
  "Antes de seguir um jogador, parte da posicao real publicada pela BT (a BT e quem anda nos demais
   modos), em vez da ultima integrada pelo driver de seguir."
  [state engine-ctx-atom agent-id]
  (when-let [pos (get-in (some-> engine-ctx-atom deref) [:agents agent-id :pos])]
    (swap! state assoc :pos (vec pos))))

(defn follow-tick!
  "Driver de seguir das bruxas em `:mode :follow`. `env`: :coco-state :richeh-state :tick
   :engine-ctx-atom. `online-players-fn` so e consultada se alguma bruxa esta seguindo."
  [{:keys [coco-state richeh-state tick engine-ctx-atom]} send-cmd! online-players-fn]
  (when (or (= :follow (:mode @coco-state)) (= :follow (:mode @richeh-state)))
    (let [online-p (try (online-players-fn) (catch Throwable _ []))]
      (when (= :follow (:mode @coco-state))
        (sync-follow-pos! coco-state engine-ctx-atom :coco)
        (step-coco-tick! coco-state send-cmd! tick online-p))
      (when (= :follow (:mode @richeh-state))
        (sync-follow-pos! richeh-state engine-ctx-atom :richeh)
        (step-richeh-tick! richeh-state coco-state send-cmd! tick online-p)))))

(defn auto-defense!
  "Auto-defesa das bruxas: se houver hostil no raio, Coco e Richeh conjuram as suas magias
   (cooldown de 5s dentro de cast-*-spell!)."
  [{:keys [coco-state richeh-state]} send-cmd!]
  (let [now (System/currentTimeMillis)]
    (when (spell-auto-defense! coco-state "Coco" send-cmd! now)
      (when (should-say-spell? coco-state now)
        (coco-say! "Glifo de Tinta Viva! Ele não vai chegar perto! 🖋️✨")))
    (when (spell-auto-defense! richeh-state "Richeh" send-cmd! now)
      (when (should-say-spell? richeh-state now)
        (richeh-say! "Estilhaço de Cristal. Fica atrás de mim. 🔮")))))

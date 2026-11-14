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

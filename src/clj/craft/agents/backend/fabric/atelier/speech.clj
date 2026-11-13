(ns craft.agents.backend.fabric.atelier.speech
  "Dados dos agentes e falas tellraw de Coco e Richeh (e a resposta da conjuracao)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd!]]
            [craft.agents.npc.speech :as npc-speech]))

(defn load-agent-edn [agent-id]
  (try
    (let [f (io/file (str "agents/" (name agent-id) ".edn"))]
      (when (.exists f)
        (edn/read-string (slurp f))))
    (catch Throwable _ nil)))

(defonce coco-data (delay (load-agent-edn :coco)))

(defonce richeh-data (delay (load-agent-edn :richeh)))

;; =============================================================================
;; Falas Formatadas Tellraw no Servidor
;; =============================================================================

;; O formato tellraw vive em `craft.agents.npc.speech` (CA-087).

(defn coco-say! [msg]
  (npc-speech/say! :coco msg {:color "yellow" :radius 16 :sound-radius 24
                              :volume "0.9" :pitch "1.1"})
  (send-bridge-cmd! "SWING|Coco"))

(defn richeh-say! [msg]
  (npc-speech/say! :richeh msg {:color "aqua" :radius 16 :sound-radius 24
                                :volume "0.8" :pitch "1.6"})
  (send-bridge-cmd! "SWING|Richeh"))

(defn caster-say!
  "Resposta DA CONJURACAO, falada da posicao do JOGADOR que conjurou.
   As falas das bruxas (`coco-say!`/`richeh-say!`) ancoram na posicao delas e
   so alcancam quem esta a 16 blocos — o Atelie fica a ~200 blocos da area de
   jogo, entao quem conjura nunca ouvia a resposta (o \"mandei mas nada\").
   `caster` = nome do jogador que conjurou: o tellraw nasce DELE e vai PARA ele."
  [send-cmd! caster msg]
  (doseq [cmd (npc-speech/say-cmds :coco msg {:anchor caster :npc "Coco" :color "yellow"
                                              :radius 32 :sound-radius 32
                                              :volume "0.9" :pitch "1.1"})]
    (send-cmd! cmd)))

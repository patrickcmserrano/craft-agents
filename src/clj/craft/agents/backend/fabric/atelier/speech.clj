(ns craft.agents.backend.fabric.atelier.speech
  "Dados dos agentes e falas tellraw de Coco e Richeh (e a resposta da conjuracao)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd!]]))

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

(defn- tellraw-cmd
  "Comando `execute at <anchor> run tellraw @a[distance=..<radius>]` com o
   formato `<Nome> msg` do NPC (nome em negrito, fala na mesma cor). Aspas da
   fala sao escapadas."
  [anchor radius npc color msg]
  (let [safe-msg (str/replace (or msg "") "\"" "\\\"")]
    (str "CMD|execute at " anchor " run tellraw @a[distance=.." radius "] "
         "[{\"text\":\"<\",\"color\":\"dark_gray\"},"
         "{\"text\":\"" npc "\",\"color\":\"" color "\",\"bold\":true},"
         "{\"text\":\"> \",\"color\":\"dark_gray\"},"
         "{\"text\":\"" safe-msg "\",\"color\":\"" color "\"}]")))

(defn coco-say! [msg]
  (send-bridge-cmd! (tellraw-cmd "Coco" 16 "Coco" "yellow" msg))
  (send-bridge-cmd! "CMD|execute at Coco run playsound minecraft:item.book.page_turn master @a[distance=..24] ~ ~ ~ 0.9 1.1")
  (send-bridge-cmd! "SWING|Coco"))

(defn richeh-say! [msg]
  (send-bridge-cmd! (tellraw-cmd "Richeh" 16 "Richeh" "aqua" msg))
  (send-bridge-cmd! "CMD|execute at Richeh run playsound minecraft:block.amethyst_block.hit master @a[distance=..24] ~ ~ ~ 0.8 1.6")
  (send-bridge-cmd! "SWING|Richeh"))

(defn caster-say!
  "Resposta DA CONJURACAO, falada da posicao do JOGADOR que conjurou.
   As falas das bruxas (`coco-say!`/`richeh-say!`) ancoram na posicao delas e
   so alcancam quem esta a 16 blocos — o Atelie fica a ~200 blocos da area de
   jogo, entao quem conjura nunca ouvia a resposta (o \"mandei mas nada\").
   `caster` = nome do jogador que conjurou: o tellraw nasce DELE e vai PARA ele."
  [send-cmd! caster msg]
  (send-cmd! (tellraw-cmd caster 32 "Coco" "yellow" msg))
  (send-cmd! (str "CMD|execute at " caster " run playsound minecraft:item.book.page_turn master @a[distance=..32] ~ ~ ~ 0.9 1.1")))

(ns craft.agents.npc.speech
  "Fala unificada dos NPCs (CA-087): `tellraw` + `playsound` dirigidos por `:agent/voice`.

   `say-cmds` e puro e devolve os comandos da bridge; `say!` os envia. Agente
   desconhecido => `nil`, nada e emitido.

   `opts` (opcional) sobrepoe a voz do registro — usado pelos wrappers antigos
   (`karlach-say!`, `picard-say!`, `coco-say!`...) para preservar a saida exata:
     :anchor :npc :name-color :color :radius :sound :sound-radius :volume :pitch"
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.registry :as registry]))

(defn escape-json-text
  "Escapa barra invertida e aspas para uso dentro de um texto JSON do tellraw."
  [msg]
  (-> (str (or msg ""))
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")))

(defn- qualify-sound [s]
  (if (str/includes? s ":") s (str "minecraft:" s)))

(defn say-cmds
  "Comandos da bridge para `agent-id` falar `msg`, ou `nil` se o agente nao existe."
  ([agent-id msg] (say-cmds agent-id msg nil))
  ([agent-id msg opts]
   (when-let [spec (registry/agent-by-id agent-id)]
     (let [voice   (:agent/voice spec)
           nm      (:agent/name spec)
           anchor  (or (:anchor opts) nm)
           npc     (or (:npc opts) nm)
           color   (or (:color opts) (:color voice) "white")
           ncolor  (or (:name-color opts) color)
           radius  (long (or (:radius opts) (:radius voice) 16))
           sradius (long (or (:sound-radius opts) (* 1.5 radius)))
           sound   (qualify-sound (or (:sound opts) (:sound voice) "block.note_block.chime"))
           volume  (or (:volume opts) "0.9")
           pitch   (or (:pitch opts) "1.2")]
       [(str "CMD|execute at " anchor " run tellraw @a[distance=.." radius "] "
             "[{\"text\":\"<\",\"color\":\"dark_gray\"},"
             "{\"text\":\"" npc "\",\"color\":\"" ncolor "\",\"bold\":true},"
             "{\"text\":\"> \",\"color\":\"dark_gray\"},"
             "{\"text\":\"" (escape-json-text msg) "\",\"color\":\"" color "\"}]")
        (str "CMD|execute at " anchor " run playsound " sound
             " master @a[distance=.." sradius "] ~ ~ ~ " volume " " pitch)]))))

(defn persona-line
  "Fala sorteada de `:agent/lines` do `.edn` do agente para `topic` (evento/topico), ou `nil`
   se o agente ou o topico nao existem (CA-109). `pick` (opcional) recebe a contagem e devolve
   o indice; default `rand-int`."
  ([agent-id topic] (persona-line agent-id topic rand-int))
  ([agent-id topic pick]
   (when-let [ls (seq (get-in (registry/agent-by-id agent-id) [:agent/lines topic]))]
     (nth ls (pick (count ls))))))

(defn persona-say-cmds
  "`say-cmds` da fala sorteada por `persona-line`, ou `nil` se nao ha fala."
  ([agent-id topic] (persona-say-cmds agent-id topic rand-int))
  ([agent-id topic pick]
   (when-let [line (persona-line agent-id topic pick)]
     (say-cmds agent-id line))))

(defn say!
  "Envia os comandos de `say-cmds` pela bridge. Devolve a resposta do ultimo
   envio, ou `nil` se o agente e desconhecido."
  ([agent-id msg] (say! agent-id msg nil))
  ([agent-id msg opts]
   (when-let [cmds (say-cmds agent-id msg opts)]
     (reduce (fn [_ cmd] (transport/send-bridge-cmd! cmd)) nil cmds))))

(ns craft.agents.npc-speech-test
  "CA-087 — fala unificada: `npc.speech/say!` dirigida por `:agent/voice` e
   caracterizacao byte a byte dos wrappers antigos."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.atelier.speech :as atelier-speech]
            [craft.agents.backend.fabric.picard :as picard]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.speech :as speech]))

(defn- capture
  "Executa `f` com a bridge interceptada e devolve os comandos enviados, em ordem."
  [f]
  (let [sent (atom [])]
    (with-redefs [transport/send-bridge-cmd! (fn [cmd] (swap! sent conj cmd) "OK")]
      (f))
    @sent))

(deftest say-cmds-karlach-uses-voice
  (let [cmds (speech/say-cmds :karlach "oi")]
    (is (= 2 (count cmds)))
    (is (= (str "CMD|execute at Karlach run tellraw @a[distance=..16] "
                "[{\"text\":\"<\",\"color\":\"dark_gray\"},"
                "{\"text\":\"Karlach\",\"color\":\"red\",\"bold\":true},"
                "{\"text\":\"> \",\"color\":\"dark_gray\"},"
                "{\"text\":\"oi\",\"color\":\"red\"}]")
           (first cmds)))
    (is (str/includes? (second cmds) "execute at Karlach run playsound minecraft:block.campfire.crackle"))))

(deftest say-bang-sends-the-same-commands
  (is (= (speech/say-cmds :karlach "oi")
         (capture #(speech/say! :karlach "oi")))))

(deftest say-escapes-quotes-and-backslash
  (let [tell (first (speech/say-cmds :karlach "a\"b\\c"))]
    (is (str/includes? tell "\"text\":\"a\\\"b\\\\c\""))))

;; CA-109: falas sorteadas das :lines declaradas no .edn da persona

(deftest persona-line-sorteia-das-lines-declaradas
  (registry/reload! "agents")
  (doseq [id [:coco :richeh :karlach :picard :antigravity]
          :let [lines (:agent/lines (registry/agent-by-id id))]]
    (is (seq lines) (str id " declara :lines"))
    (doseq [[topic ls] lines]
      (is (some #{(speech/persona-line id topic)} ls)
          (str id " " topic " sorteia de :lines"))))
  (testing "rng injetavel e deterministico"
    (let [ls (get-in (registry/agent-by-id :coco) [:agent/lines :near-richeh])]
      (is (= (first ls) (speech/persona-line :coco :near-richeh (fn [_] 0))))
      (is (= (last ls) (speech/persona-line :coco :near-richeh (fn [c] (dec c))))))))

(deftest persona-line-nil-para-agente-ou-topico-inexistente
  (registry/reload! "agents")
  (is (nil? (speech/persona-line :ninguem :greeting)))
  (is (nil? (speech/persona-line :coco :topico-inexistente))))

(deftest persona-line-nao-usa-nomes-hardcoded-no-codigo
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "persona" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (spit (java.io.File. d "zed.edn")
          (pr-str {:agent/id :zed :agent/name "Zed"
                   :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 64.0 0.0]}
                   :agent/lines {:saudacao ["so existe aqui"]}}))
    (registry/reload! (.getPath d))
    (try
      (is (= "so existe aqui" (speech/persona-line :zed :saudacao)))
      (finally (registry/reload! "agents")))))

(deftest persona-say-cmds-fala-a-linha-sorteada
  (registry/reload! "agents")
  (let [cmds (speech/persona-say-cmds :richeh :near-coco (fn [_] 0))
        line (first (get-in (registry/agent-by-id :richeh) [:agent/lines :near-coco]))]
    (is (= (speech/say-cmds :richeh line) cmds))
    (is (nil? (speech/persona-say-cmds :richeh :topico-inexistente)))))

(deftest say-unknown-agent-is-nil-and-silent
  (is (nil? (speech/say-cmds :ninguem "oi")))
  (let [sent (capture #(is (nil? (speech/say! :ninguem "oi"))))]
    (is (empty? sent))))

(deftest legacy-wrappers-are-byte-identical
  (testing "karlach-say!"
    (is (= ["CMD|execute at Karlach run tellraw @a[distance=..16] [{\"text\":\"<\",\"color\":\"dark_gray\"},{\"text\":\"Karlach\",\"color\":\"red\",\"bold\":true},{\"text\":\"> \",\"color\":\"dark_gray\"},{\"text\":\"diz \\\"oi\\\"\",\"color\":\"red\"}]"
            "CMD|execute at Karlach run playsound minecraft:block.campfire.crackle master @a[distance=..24] ~ ~ ~ 0.9 1.2"]
           (capture #(actions/karlach-say! "diz \"oi\"")))))
  (testing "picard-say!"
    (is (= ["CMD|execute at Picard run tellraw @a[distance=..16] [{\"text\":\"<\",\"color\":\"dark_gray\"},{\"text\":\"Picard\",\"color\":\"aqua\",\"bold\":true},{\"text\":\"> \",\"color\":\"dark_gray\"},{\"text\":\"Engage\",\"color\":\"white\"}]"
            "CMD|execute at Picard run playsound minecraft:block.beacon.ambient master @a[distance=..32] ~ ~ ~ 0.8 1.4"]
           (capture #(picard/picard-say! "Engage")))))
  (testing "coco-say!"
    (is (= ["CMD|execute at Coco run tellraw @a[distance=..16] [{\"text\":\"<\",\"color\":\"dark_gray\"},{\"text\":\"Coco\",\"color\":\"yellow\",\"bold\":true},{\"text\":\"> \",\"color\":\"dark_gray\"},{\"text\":\"ola\",\"color\":\"yellow\"}]"
            "CMD|execute at Coco run playsound minecraft:item.book.page_turn master @a[distance=..24] ~ ~ ~ 0.9 1.1"
            "SWING|Coco"]
           (capture #(atelier-speech/coco-say! "ola")))))
  (testing "richeh-say!"
    (is (= ["CMD|execute at Richeh run tellraw @a[distance=..16] [{\"text\":\"<\",\"color\":\"dark_gray\"},{\"text\":\"Richeh\",\"color\":\"aqua\",\"bold\":true},{\"text\":\"> \",\"color\":\"dark_gray\"},{\"text\":\"ola\",\"color\":\"aqua\"}]"
            "CMD|execute at Richeh run playsound minecraft:block.amethyst_block.hit master @a[distance=..24] ~ ~ ~ 0.8 1.6"
            "SWING|Richeh"]
           (capture #(atelier-speech/richeh-say! "ola")))))
  (testing "caster-say!"
    (let [sent (atom [])]
      (atelier-speech/caster-say! #(swap! sent conj %) "Patrick" "feito")
      (is (= ["CMD|execute at Patrick run tellraw @a[distance=..32] [{\"text\":\"<\",\"color\":\"dark_gray\"},{\"text\":\"Coco\",\"color\":\"yellow\",\"bold\":true},{\"text\":\"> \",\"color\":\"dark_gray\"},{\"text\":\"feito\",\"color\":\"yellow\"}]"
              "CMD|execute at Patrick run playsound minecraft:item.book.page_turn master @a[distance=..32] ~ ~ ~ 0.9 1.1"]
             @sent)))))

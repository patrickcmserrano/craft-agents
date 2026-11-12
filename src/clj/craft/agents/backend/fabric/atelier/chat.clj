(ns craft.agents.backend.fabric.atelier.chat
  "Tratamento do chat para o Atelie: reconhecimento de comandos e respostas das bruxas."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd!]]
            [craft.agents.backend.fabric.atelier.speech :refer [caster-say! coco-data richeh-data]]
            [craft.agents.backend.fabric.atelier.spells :refer [cast-casting! cast-spell-with-aim! grimoire-carried?]]))

(def ^:private casting-words
  "Palavras de CASTING puro — escritas no chat SEM nome de bruxa, disparam a
   magia a partir do proprio jogador. Nao colidem com as ordens das bruxas."
  ["fireball" "fogo" "bola de fogo" "fire"])

(defn casting-spell?
  "True se a mensagem e um CASTING puro (a palavra de magia, sem nome de bruxa).
   Compara sobre a mensagem em minusculas: as palavras de `casting-words` como
   substring. Vazia ou nil = false."
  [msg]
  (let [lower (str/lower-case (or msg ""))]
    (boolean (some #(str/includes? lower %) casting-words))))

(defn atelier-command?
  "True se a mensagem e para o ATELIE: ou um comando com o nome de uma bruxa
   (`coco`/`richeh`), ou um CASTING puro (`fireball`/`fogo`, sem nome de bruxa).
   O dispatcher usa isto para deixar passar ao `handle-atelier-chat!` — sem ele,
   um casting puro era capturado e DESCARTADO antes de chegar ao handler."
  [msg]
  (let [lower (str/lower-case (or msg ""))]
    (boolean (or (str/includes? lower "coco")
                 (str/includes? lower "richeh")
                 (casting-spell? msg)))))

;; =============================================================================
;; Manipulação de Comandos de Chat para o Ateliê
;; =============================================================================

(defn handle-atelier-chat!
  "Processa falas direcionadas a Coco e Richeh. Retorna true se consumido.
   Aceita tambem o CASTING puro do jogador (so a palavra de magia, sem nome de
   bruxa): a magia nasce DELE, na direcao da mira dele."
  [speaker msg coco-st richeh-st say-fn]
  (let [lower (str/lower-case (or msg ""))]
    (cond
      ;; 0. CASTING puro do jogador: so a palavra de magia, sem nome de bruxa.
      ;; A resposta sai da posicao DELE (`caster-say!`), nao da Coco — o Atelie
      ;; esta a ~200 blocos e as falas das bruxas nao o alcancariam.
      (casting-spell? msg)
      (do
        (if-not (grimoire-carried? send-bridge-cmd! speaker)
          (caster-say! send-bridge-cmd! speaker "Sem o teu grimório eu não conjuro nada. Traz o livro-e-pena! 📖🎒")
          (let [hit? (cast-casting! coco-st send-bridge-cmd! speaker (System/currentTimeMillis))]
            (caster-say! send-bridge-cmd! speaker
                         (if hit?
                           (rand-nth ["Glifo de Tinta Viva! Bela mordida! 🖋️✨"
                                      "A tinta obedece a quem desenha! 📖"])
                           "Mira num bicho, não no vazio! O glifo precisa de um alvo pra morder! 🖋️"))))
        true)

      ;; 1. Comandos para Coco
      (str/includes? lower "coco")
      (cond
        (or (str/includes? lower "ataca") (str/includes? lower "atacar")
            (str/includes? lower "magia") (str/includes? lower "glifo")
            (str/includes? lower "feitiço") (str/includes? lower "feiti"))
        (do
          (if-not (grimoire-carried? send-bridge-cmd! speaker)
            (say-fn :coco "Sem o teu grimório, não há magia. Traz o livro-e-pena! 📖🎒")
            (let [hit? (cast-spell-with-aim! coco-st "Coco" send-bridge-cmd! speaker (System/currentTimeMillis)
                                             ["CMD|execute at Coco run particle minecraft:enchant ~ ~1.0 ~ 0.6 0.6 0.6 0.05 25 normal"
                                              "CMD|execute at Coco run particle minecraft:witch ~ ~1.2 ~ 0.4 0.4 0.4 0.02 15 normal"
                                              "CMD|execute at Coco run particle minecraft:crit ~ ~1.0 ~ 0.3 0.3 0.3 0.1 10 normal"]
                                             "CMD|execute at Coco run playsound minecraft:item.book.page_turn master @a[distance=..24] ~ ~ ~ 1.2 0.8"
                                             ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
                                              "particle minecraft:soul_fire_flame ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"
                                              "particle minecraft:enchant ~ ~ ~ 0.8 0.8 0.8 0.1 40 normal"])]
              (if hit?
                (say-fn :coco (rand-nth ["Isso! O Glifo de Tinta Viva é teu agora! 🖋️✨"
                                         "A tinta obedece a quem desenha. Bela mordida! 📖"]))
                (say-fn :coco "Mira num bicho, não no vazio! O glifo precisa de um alvo pra morder! 🖋️"))))
          true)

        (or (str/includes? lower "segue") (str/includes? lower "vem"))
        (do
          (swap! coco-st assoc :mode :follow :follow-target speaker)
          (say-fn :coco (str "Tô indo, " speaker "! Deixa só eu guardar a pena de prata no caderno! 📖✨"))
          true)

        (or (str/includes? lower "fica") (str/includes? lower "patrulha")
            (str/includes? lower "ateliê") (str/includes? lower "castelo") (str/includes? lower "volta"))
        (do
          (swap! coco-st assoc :mode :patrol :follow-target nil)
          (say-fn :coco "Entendido! Vou continuar mapeando os fluxos e runas aqui pelo castelo! 🖋️")
          true)

        (or (str/includes? lower "céu") (str/includes? lower "ceu")
            (str/includes? lower "estrela") (str/includes? lower "eclipse")
            (str/includes? lower "planeta") (str/includes? lower "aurora"))
        (do
          (let [dialogues (get-in @coco-data [:agent/dialogues :celestial] [])
                chosen (if (seq dialogues) (rand-nth dialogues) "A geometria dos céus desafia qualquer caderno de desenho! 🌌")]
            (say-fn :coco chosen))
          true)

        :else
        (do
          (let [dialogues (concat (get-in @coco-data [:agent/dialogues :social] [])
                                  (get-in @coco-data [:agent/dialogues :castles] [])
                                  (get-in @coco-data [:agent/dialogues :celestial] []))
                chosen (if (seq dialogues) (rand-nth dialogues) "A magia nasce do desejo de ajudar os outros! ✨")]
            (say-fn :coco chosen))
          true))

      ;; 2. Comandos para Richeh
      (str/includes? lower "richeh")
      (cond
        (or (str/includes? lower "ataca") (str/includes? lower "atacar")
            (str/includes? lower "magia") (str/includes? lower "cristal")
            (str/includes? lower "estilhaça") (str/includes? lower "feitiço")
            (str/includes? lower "feiti") (str/includes? lower "lança"))
        (do
          (if-not (grimoire-carried? send-bridge-cmd! speaker)
            (say-fn :richeh "Hmph. Sem o teu grimório, não há cristal pra traçar. 📖")
            (let [hit? (cast-spell-with-aim! richeh-st "Richeh" send-bridge-cmd! speaker (System/currentTimeMillis)
                                             ["CMD|execute at Richeh run particle minecraft:end_rod ~ ~1.0 ~ 0.6 0.6 0.6 0.08 25 normal"
                                              "CMD|execute at Richeh run particle minecraft:glow_squid_ink ~ ~0.8 ~ 0.4 0.4 0.4 0.02 20 normal"
                                              "CMD|execute at Richeh run particle minecraft:block_marker minecraft:amethyst_block ~ ~1.0 ~ 0.3 0.3 0.3 0.05 12 normal"]
                                             "CMD|execute at Richeh run playsound minecraft:block.amethyst_block.break master @a[distance=..24] ~ ~ ~ 1.1 0.9"
                                             ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
                                              "particle minecraft:end_rod ~ ~ ~ 0.6 0.6 0.6 0.08 40 normal"
                                              "particle minecraft:block_marker minecraft:amethyst_block ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"])]
              (if hit?
                (say-fn :richeh (rand-nth ["Traça a matriz e estilhaça. O cristal é teu. 🔮"
                                           "A matriz tá inscrita. Fica parado e ele quebra. 💠"]))
                (say-fn :richeh "Hmph. Mira num alvo de verdade. Meus cristais não se gastam no vazio."))))
          true)

        (or (str/includes? lower "segue") (str/includes? lower "vem"))
        (do
          (swap! richeh-st assoc :mode :follow :follow-target speaker)
          (say-fn :richeh (str "Hmph... Se você insiste tanto, " speaker ". Mas não fica no meu caminho!"))
          true)

        (or (str/includes? lower "fica") (str/includes? lower "protege")
            (str/includes? lower "ateliê") (str/includes? lower "volta"))
        (do
          (swap! richeh-st assoc :mode :tether-orbit :follow-target nil)
          (say-fn :richeh "Melhor mesmo. Preciso terminar de polir as matrizes de cristal ao redor do ateliê.")
          true)

        (or (str/includes? lower "céu") (str/includes? lower "ceu")
            (str/includes? lower "estrela") (str/includes? lower "eclipse")
            (str/includes? lower "planeta") (str/includes? lower "aurora"))
        (do
          (let [dialogues (get-in @richeh-data [:agent/dialogues :celestial] [])
                chosen (if (seq dialogues) (rand-nth dialogues) "Se a refração daquelas luzes no céu alinhar com o cristal, o feitiço não dissipa nunca. 🔮")]
            (say-fn :richeh chosen))
          true)

        :else
        (do
          (let [dialogues (concat (get-in @richeh-data [:agent/dialogues :social] [])
                                  (get-in @richeh-data [:agent/dialogues :castles] [])
                                  (get-in @richeh-data [:agent/dialogues :celestial] []))
                chosen (if (seq dialogues) (rand-nth dialogues) "Eu só desenho a magia que tem o meu próprio traço.")]
            (say-fn :richeh chosen))
          true))

      :else false)))

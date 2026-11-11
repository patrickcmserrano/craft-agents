(ns craft.agents.atelier-test
  "Testes da dinâmica comportamental de Coco e Richeh no Ateliê dos Touros Negros."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [craft.agents.backend.fabric.atelier :as atelier]))

(deftest test-coco-and-richeh-initial-state
  (testing "Inicializacao de estado e waypoints de Coco e Richeh"
    (let [coco-st (atelier/create-coco-state)
          richeh-st (atelier/create-richeh-state)]
      (is (= :patrol (:mode @coco-st)))
      (is (= :tether-orbit (:mode @richeh-st)))
      (is (vector? (:pos @coco-st)))
      (is (vector? (:pos @richeh-st)))
      (is (seq atelier/castle-waypoints)))))

(deftest test-coco-movement-tick-simulation
  (testing "Tick motora da Coco avança em direção ao waypoint"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          initial-pos (:pos @coco-st)
          sent-cmds (atom [])
          mock-send! (fn [cmd] (swap! sent-cmds conj cmd))
          _ (atelier/step-coco-tick! coco-st mock-send! 1 [])]
      (is (seq @sent-cmds) "Deve emitir comandos MOVE pela bridge")
      (is (not= initial-pos (:pos @coco-st)) "Posicao deve evoluir no tick motora"))))

(deftest test-richeh-tether-orbit-tick-simulation
  (testing "Tick motora da Richeh se aproxima da Coco quando afastada"
    (let [coco-st (atelier/create-coco-state [-20.0 66.0 190.0])
          richeh-st (atelier/create-richeh-state [-32.0 66.0 190.0]) ;; 12m de distancia
          initial-pos (:pos @richeh-st)
          sent-cmds (atom [])
          mock-send! (fn [cmd] (swap! sent-cmds conj cmd))
          _ (atelier/step-richeh-tick! richeh-st coco-st mock-send! 1 [])]
      (is (seq @sent-cmds) "Deve emitir comandos MOVE pela bridge")
      (let [[rx _ _] (:pos @richeh-st)
            [init-x _ _] initial-pos]
        (is (> rx init-x) "Richeh deve ter se movido em direcao a Coco (-20.0)")))))

(deftest test-atelier-chat-handling
  (testing "Chat handling direciona ordens e falas para Coco e Richeh"
    (let [coco-st (atelier/create-coco-state)
          richeh-st (atelier/create-richeh-state)
          sent-msgs (atom [])
          mock-say! (fn [who msg] (swap! sent-msgs conj [who msg]))]
      ;; 1. Ordem para Coco seguir
      (is (true? (atelier/handle-atelier-chat! "pace" "coco vem aqui me segue" coco-st richeh-st mock-say!)))
      (is (= :follow (:mode @coco-st)))
      (is (= "pace" (:follow-target @coco-st)))

      ;; 2. Ordem para Richeh retornar
      (is (true? (atelier/handle-atelier-chat! "pace" "richeh fica patrulha" coco-st richeh-st mock-say!)))
      (is (= :tether-orbit (:mode @richeh-st))))))

(defn- hostile-response
  "Resposta no formato real do SyntheticHostileScanner: OK|<count>|<id>,<tipo>,<x>,<y>,<z>,<dist>,<hp>;..."
  []
  "OK|2|42,minecraft:zombie,-28.00,66.00,190.00,2.00,20.0;77,minecraft:skeleton,-24.00,66.00,190.00,5.50,20.0")

(defn- spell-mock!
  "Mock da bridge: registra os comandos e responde aos opcodes de combate.
   `held` e o item devolvido por INSPECT_INVENTORY (o item de fogo, ou nil sem ele).
   `raycast-resp` fixa a resposta do RAYCAST_BLOCK (o fallback da mira).
   `opts` (mapa opcional) aceita :look-resp (a resposta do op LOOK) e
   :held-nbt (o custom_data do item na mao, lido pelo op HELD_ITEM)."
  ([sent-cmds] (spell-mock! sent-cmds "minecraft:fire_charge,1"))
  ([sent-cmds held]
   (spell-mock! sent-cmds held nil))
  ([sent-cmds held raycast-resp]
   (spell-mock! sent-cmds held raycast-resp nil))
  ([sent-cmds held raycast-resp opts]
   (let [{:keys [look-resp held-nbt]} opts]
     (fn [cmd]
       (swap! sent-cmds conj cmd)
       (cond
         (str/starts-with? cmd "INSPECT_INVENTORY")
         (if held (str "OK|1|" held) "OK|0|")
         (str/starts-with? cmd "LOOK") (or look-resp "ERR|UNKNOWN_OP")
         (str/starts-with? cmd "HELD_ITEM") (or held-nbt "ERR|NO_ITEM")
         (str/starts-with? cmd "TARGET_HOSTILE") (hostile-response)
         (str/starts-with? cmd "RAYCAST_BLOCK") (or raycast-resp "ERR|NO_BLOCK_IN_SIGHT")
         (= cmd "PLAYERS") "OK|pace,0.0,65.0,0.0"
         (str/starts-with? cmd "ATTACK_HOSTILE") (str "OK|" (last (str/split cmd #"\|")) "|0.90")
         :else "OK")))))

(deftest test-coco-spell-casts-visual-and-damage
  (testing "Coco conjura glifo: varre hostis, aplica dano ao mais proximo e emite visual"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)
          _ (atelier/cast-coco-spell! coco-st mock! "pace" 1 2000000)
          cmds @sent]
      (is (some #(str/starts-with? % "TARGET_HOSTILE|pace|") cmds)
          "Deve varrer hostis ancorados no CONJURADOR (pace), nao na bruxa")
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "damage") (str/includes? % " 100 "))
                cmds)
          "Deve matar o hostil mais proximo com um golpe de dano 100 (hit kill)")
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "particle")) cmds)
          "Deve emitir assinatura visual (particle)")
      (is (some #(str/starts-with? % "SWING|Coco") cmds)
          "Deve emitir SWING durante a conjuracao"))))

(deftest test-richeh-spell-casts-visual-and-damage
  (testing "Richeh estilhaca cristal: ataca hostil mais proximo com a assinatura de ametista"
    (let [richeh-st (atelier/create-richeh-state [-30.0 66.0 192.0])
          sent (atom [])
          mock! (spell-mock! sent)
          _ (atelier/cast-richeh-spell! richeh-st mock! "pace" 1 2000000)
          cmds @sent]
      (is (some #(str/starts-with? % "TARGET_HOSTILE|pace|") cmds)
          "Deve varrer hostis ancorados no CONJURADOR (pace)")
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "damage") (str/includes? % " 100 "))
                cmds))
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "particle")) cmds))
      (is (some #(str/starts-with? % "SWING|Richeh") cmds)))))

(deftest test-coco-spell-launches-projectile-at-target
  (testing "Coco lanca um projetil real (fireball) do CONJURADOR rumo ao alvo"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)
          _ (atelier/cast-coco-spell! coco-st mock! "pace" 1 2000000)
          cmds @sent
          proj (first (filter #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                                    (str/includes? % "fireball")) cmds))]
      (is (some? proj) "Deve summonar uma fireball como projetil")
      ;; A magia NASCE DO JOGADOR: o projetil e summonado na posicao dele
      ;; (`execute at pace`), rumo ao alvo — nao na posicao da bruxa.
      (is (str/includes? proj "at pace")
          "O projetil nasce das coordenadas do conjurador (pace)")
      (is (re-find #"Motion:\[-?[0-9]" proj)
          "O projetil leva um vetor de velocidade calculado rumo ao alvo")
      (is (str/includes? proj "ExplosionPower:0") "ExplosionPower:0 para nao quebrar blocos do mundo"))))

(deftest test-richeh-spell-detonates-burst-at-target
  (testing "Richeh detona a magia nas coordenadas do alvo (nao so na propria bruxa)"
    (let [richeh-st (atelier/create-richeh-state [-30.0 66.0 192.0])
          sent (atom [])
          mock! (spell-mock! sent)
          _ (atelier/cast-richeh-spell! richeh-st mock! "pace" 1 2000000)
          cmds @sent]
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "particle")
                      (str/includes? % "-28"))
                cmds)
          "Deve haver um golpe visual ANCRADO NO ALVO (x=-28), nao so na Richeh"))))

(deftest test-coco-spell-damage-is-hitkill
  (testing "Coco aplica DANO 100 (hit kill de mobs comuns) em vez do golpe de mao"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)
          _ (atelier/cast-coco-spell! coco-st mock! "pace" 1 2000000)
          cmds @sent
          dmg (first (filter #(and (str/starts-with? % "CMD|") (str/includes? % "damage")
                                   (str/includes? % " 100 ")) cmds))]
      (is (some? dmg) "Deve aplicar dano 100 via CMD|damage (hit kill)")
      (is (not (some #(str/starts-with? % "ATTACK_HOSTILE") cmds))
          "Nao deve usar o golpe de mao (ATTACK_HOSTILE) quando mata num golpe"))))

(deftest test-spell-requires-grimoire-in-hand
  (testing "Sem o grimorio na mao, a magia recusa e NAO emite dano nem projetil"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent nil)   ; mao vazia
          hit? (atelier/cast-coco-spell! coco-st mock! "pace" 1 2000000)
          cmds @sent]
      (is (false? hit?) "Conjuracao recusada sem o grimorio")
      (is (some #(str/starts-with? % "INSPECT_INVENTORY|pace") cmds) "Deve consultar o inventario")
      (is (not (some #(and (str/starts-with? % "CMD|") (str/includes? % "damage")) cmds))
          "Nao deve aplicar dano sem o grimorio")
      (is (not (some #(str/starts-with? % "TARGET_HOSTILE") cmds))
          "Nem sequer varre hostis sem o grimorio"))))

(deftest test-spell-fires-with-grimoire-in-hand
  (testing "Com o grimorio na mao, a magia dispara normalmente"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:fire_charge,1")
          hit? (atelier/cast-coco-spell! coco-st mock! "pace" 1 2000000)]
      (is (true? hit?) "Conjura com o grimorio na mao")
      (is (some #(str/starts-with? % "INSPECT_INVENTORY|pace") @sent)))))

(deftest test-spell-cooldown-blocks-immediate-recast
  (testing "Cooldown de 5s impede reconjuracao automatica imediata"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)]
      (is (true? (atelier/cast-coco-spell! coco-st mock! "pace" 1 1000000)) "Primeira conjuracao acontece")
      (reset! sent [])
      (is (false? (atelier/cast-coco-spell! coco-st mock! "pace" 2 1000500)) "Segunda a 0.5s e bloqueada pelo cooldown")
      (is (empty? @sent) "Nenhum comando emitido durante o cooldown"))))

(deftest test-spell-bloodlust-is-per-burst
  (testing "A fala de conjuracao so sai no primeiro lancamento de cada surto"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)]
      (is (true? (atelier/cast-coco-spell! coco-st mock! "pace" 1 1000000)))
      (is (true? (:spell-said? @coco-st)) "Marca que ja falou neste surto")
      (is (false? (atelier/should-say-spell? coco-st 1001000))
          "Nao fala de novo dentro da janela do surto")
      (is (true? (atelier/should-say-spell? coco-st 1100000))
          "Passada a janela, um novo surto pode falar"))))

;; =============================================================================
;; Mira por Direcao: conjurar contra o hostil na linha do olhar do jogador
;; =============================================================================

(deftest test-aim-picks-hostile-nearest-the-look-line
  (testing "A mira por direcao escolhe o hostil mais proximo da linha de visao"
    ;; O jogador olha para LESTE (+x). O hostil A esta na linha (+x, z igual);
    ;; o hostil B esta deslocado 4m de lado. A mira tem de escolher A.
    (let [aim-line [1.0 0.0 0.0]                     ; direcao do olhar (leste)
          snap {:look-direction aim-line
                :ray-origin [0.0 65.0 0.0]
                :ray-max-dist 24.0}
          hostiles [{:id 1 :type "minecraft:zombie" :x 10.0 :y 65.0 :z 0.0 :dist 10.0 :hp 20.0}
                    {:id 2 :type "minecraft:skeleton" :x 10.0 :y 65.0 :z 4.0 :dist 10.8 :hp 20.0}]
          chosen (atelier/select-aim-target snap hostiles)]
      (is (= 1 (:id chosen)) "Deve escolher o hostil colado na linha do olhar (+x)"))))

(deftest test-aim-rejects-off-axis-hostiles
  (testing "Hostis fora do cone de mira sao ignorados; sem ninguem na mira, nil"
    (let [aim-line [1.0 0.0 0.0]
          snap {:look-direction aim-line
                :ray-origin [0.0 65.0 0.0]
                :ray-max-dist 24.0}
          hostiles [{:id 7 :type "minecraft:creeper" :x 2.0 :y 65.0 :z 12.0 :dist 12.2 :hp 20.0}]]
      (is (nil? (atelier/select-aim-target snap hostiles))
          "Um hostil a 12m de lado nao esta na mira"))))

(deftest test-aim-uses-player-raycast-direction
  (testing "A direcao do olhar vem do raycast do jogador (bloco a frente na linha)"
    ;; O raycast devolve o PRIMEIRO bloco solido na linha do olhar. A mira usa a
    ;; ORIGEM (olhos do jogador) + a direcao ate esse bloco como eixo.
    (let [sent (atom [])
          mock! (spell-mock! sent "minecraft:fire_charge,1" "OK|minecraft:stone_bricks|10|65|0|10.00")
          snap (atelier/player-aim-snapshot mock! "pace" 24.0)]
      (is (some #(str/starts-with? % "RAYCAST_BLOCK|pace|") @sent)
          "Deve consultar o raycast do jogador")
      (is (vector? (:look-direction snap)) "O snapshot traz a direcao do olhar")
      (is (> (first (:look-direction snap)) 0.9)
          "A direcao aponta para leste (+x), rumo ao bloco avistado"))))

(deftest test-spell-fired-at-aimed-hostile
  (testing "Conjurar com mira atinge o hostil da mira, nao o mais proximo por distancia"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          ;; Dois hostis: o ID 42 esta perto mas FORA da mira; o ID 99 esta na mira.
          mock! (spell-mock! sent "minecraft:fire_charge,1" "OK|minecraft:stone_bricks|-20|66|190|10.00")
          _ (atelier/cast-coco-spell! coco-st mock! "pace" 1 2000000 {:aim-target {:id 99 :type "minecraft:skeleton"
                                                                                   :x -20.0 :y 66.0 :z 190.0
                                                                                   :dist 10.0 :hp 20.0}})
          cmds @sent
          dmg (first (filter #(and (str/starts-with? % "CMD|") (str/includes? % "damage")) cmds))]
      (is (some? dmg) "Deve aplicar dano")
      (is (str/includes? dmg "-20") "O dano cai nas coordenadas do ALVO DA MIRA (x=-20), nao do mais proximo"))))

(deftest test-aim-spell-scans-around-the-caster
  (testing "A magia do jogador varre hostis em volta DELE (o conjurador), nao da bruxa"
    ;; A bruxa esta longe do jogador; o hostil esta perto DO JOGADOR. Se a
    ;; varredura ancorasse na bruxa, nao acharia nada e a magia nao sairia.
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:fire_charge,1" "OK|minecraft:stone_bricks|6|64|128|6.00")]
      (atelier/cast-spell-with-aim! st "Coco" mock! "pace" 2000000
                                    ["CMD|execute at Coco run particle minecraft:enchant ~ ~1 ~ 0.5 0.5 0.5 0.05 10 normal"]
                                    nil
                                    ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"])
      (is (some #(str/starts-with? % "TARGET_HOSTILE|pace|") @sent)
          "Deve varrer hostis ancorados no JOGADOR (pace), nao na bruxa (Coco)"))))

(deftest test-aim-spell-originates-at-the-caster
  (testing "A magia nasce do jogador: projetil e glifo ancoram na posicao DELE"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:fire_charge,1" "OK|minecraft:stone_bricks|6|64|128|6.00")]
      (atelier/cast-spell-with-aim! st "Coco" mock! "pace" 2000000
                                    ["CMD|execute at Coco run particle minecraft:enchant ~ ~1 ~ 0.5 0.5 0.5 0.05 10 normal"]
                                    nil
                                    ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"])
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                      (str/includes? % "fireball") (str/includes? % "at pace"))
                @sent)
          "O projetil de fogo nasce das coordenadas do JOGADOR (at pace)"))))

;; =============================================================================
;; Casting puro: uma palavra de magia no chat, sem nome de bruxa, nasce de ti
;; =============================================================================

(deftest test-casting-word-is-recognized
  (testing "A palavra de casting (fireball e variacoes) e reconhecida, sem nome de bruxa"
    (is (true? (atelier/casting-spell? "fireball")))
    (is (true? (atelier/casting-spell? "fogo")))
    (is (true? (atelier/casting-spell? "bola de fogo")))
    (is (true? (atelier/casting-spell? "fire")))
    (is (true? (atelier/casting-spell? "FIREBALL")) "Case-insensitive")
    (is (true? (atelier/casting-spell? "  fogo!  ")) "Tolera espacos e pontuacao")))

(deftest test-non-casting-words-are-not-casting
  (testing "Palavras que NAO sao casting nao disparam a magia"
    (is (false? (atelier/casting-spell? "coco vem aqui")))
    (is (false? (atelier/casting-spell? "bom dia")))
    (is (false? (atelier/casting-spell? "")))
    (is (false? (atelier/casting-spell? nil)))))

(deftest test-casting-fires-from-the-caster-with-aim
  (testing "O casting puro conjura a magia na mira do JOGADOR, nascendo dele"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:fire_charge,1" "OK|minecraft:stone_bricks|6|64|128|6.00")
          hit? (atelier/cast-casting! st mock! "pace" 2000000)]
      (is (true? hit?) "O casting puro dispara a magia")
      (is (some #(str/starts-with? % "TARGET_HOSTILE|pace|") @sent)
          "Varre hostis ancorado no JOGADOR")
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                      (str/includes? % "fireball") (str/includes? % "at pace"))
                @sent)
          "A fireball nasce do JOGADOR (at pace)"))))

(deftest test-casting-requires-grimoire
  (testing "Sem o grimorio, o casting puro recusa e nao emite dano"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent nil)
          hit? (atelier/cast-casting! st mock! "pace" 2000000)]
      (is (false? hit?) "Sem grimorio o casting recusa")
      (is (not (some #(and (str/starts-with? % "CMD|") (str/includes? % "damage")) @sent))
          "Nao emite dano sem o grimorio"))))

(deftest test-casting-with-word-decides-the-trigger
  (testing "A palavra de casting e a chave; ordens normais nao a disparam"
    (is (true? (atelier/casting-spell? "fogo")))
    (is (false? (atelier/casting-spell? "coco fica patrulha"))
        "Uma ordem comum nao e casting")))

(deftest test-atelier-command-includes-pure-casting
  (testing "O dispatcher deixa passar o casting puro, alem das mensagens com nome de bruxa"
    ;; O sentry so chamava o handler do atelie quando a mensagem tinha
    ;; "coco"/"richeh" — um casting puro ("fireball") era capturado e DESCARTADO.
    (is (true? (atelier/atelier-command? "fireball"))
        "O casting puro tem de chegar ao handler")
    (is (true? (atelier/atelier-command? "bola de fogo")))
    (is (true? (atelier/atelier-command? "coco ataca")) "Comando com nome de bruxa passa")
    (is (true? (atelier/atelier-command? "richeh cristal")))
    (is (false? (atelier/atelier-command? "karlach detectar"))
        "Uma ordem para outro agente nao e do atelie")))

;; =============================================================================
;; Mira cega: a magia nao pode depender de haver um BLOCO na linha do olhar
;; =============================================================================

(deftest test-aim-falls-back-to-nearest-hostile-without-block-in-sight
  (testing "Sem bloco na linha do olhar (campo aberto), a mira cai no hostil mais proximo"
    ;; O raycast devolve ERR|NO_BLOCK_IN_SIGHT quando o jogador olha para o
    ;; horizonte aberto — o caso mais comum de atirar uma bola de fogo. Antes,
    ;; isso deixava `select-aim-target` sem direcao e a magia nao saia.
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:fire_charge,1" "ERR|NO_BLOCK_IN_SIGHT")
          hit? (atelier/cast-casting! st mock! "pace" 2000000)
          cmds @sent]
      (is (true? hit?) "A magia conjura mesmo sem bloco em vista (usa o mais proximo)")
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "damage")) cmds)
          "Aplica dano ao hostil mais proximo quando nao ha bloco na mira")))

(deftest test-aim-still-prefers-the-aimed-hostile-over-the-nearest
  (testing "Com bloco em vista, a mira continua a preferir o hostil apontado"
    ;; Regressao: o fallback NAO pode atropelar a mira por direcao.
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:fire_charge,1" "OK|minecraft:stone_bricks|-20|66|190|10.00")
          hit? (atelier/cast-casting! st mock! "pace" 2000000)
          cmds @sent]
      (is (true? hit?) "A magia conjura")
      ;; A mira aponta para x=-20, onde esta o hostil apontado, nao o mais proximo (x=-28).
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "damage")
                      (str/includes? % "-20"))
                cmds)
          "O dano cai nas coordenadas do hostil APONTADO, nao do mais proximo"))))

;; =============================================================================
;; Voz do conjurador: quem conjura ouve a resposta, mesmo longe da bruxa
;; =============================================================================

(deftest test-caster-say-speaks-from-the-caster-position
  (testing "A fala de conjuracao sai da posicao do CONJURADOR, nao da bruxa"
    ;; As bruxas vivem a ~200 blocos do jogador; a fala delas usa
    ;; `execute at Coco run tellraw @a[distance=..16]` e nunca alcanca quem
    ;; conjura. A resposta ao casting tem de sair de QUEM conjurou.
    (let [sent (atom [])
          mock! (fn [cmd] (swap! sent conj cmd) "OK")]
      (atelier/caster-say! mock! "pace" "Glifo de Tinta Viva!")
      (let [tell (first (filter #(str/includes? % "tellraw") @sent))]
        (is (some? tell) "Deve emitir um tellraw de resposta")
        (is (str/includes? tell "execute at pace run tellraw")
            "O tellraw ancora no CONJURADOR (pace) — a resposta chega a ele")
        (is (not (str/includes? tell "execute at Coco"))
            "Nao pode ancorar na bruxa, que esta longe")
        (is (str/includes? tell "Glifo de Tinta Viva")
            "A mensagem vai no tellraw")))))

(deftest test-casting-reply-reaches-the-caster
  (testing "Conjurando longe da bruxa, a resposta do casting chega ao conjurador"
    ;; O sintoma reportado: "mandei mas nada" — a magia saia, mas a fala de
    ;; sucesso/falha partia da Coco (a 200 blocos) e nunca aparecia para ele.
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:fire_charge,1" "ERR|NO_BLOCK_IN_SIGHT")
          said (atom [])
          say-fn (fn [_who text] (swap! said conj text))
          ok? (atelier/handle-atelier-chat! "pace" "fireball" st (atelier/create-richeh-state) say-fn)]
      (is (true? ok?) "O casting puro foi consumido pelo handler")
      (is (seq @said) "O conjurador recebeu uma resposta (sucesso ou falha)")
      ;; E a resposta tem de sair da posicao DELE, nao da bruxa.
      (is (some #(and (str/includes? % "tellraw") (str/includes? % "at pace")) @sent)
          "A resposta do casting ancora no conjurador (at pace)")))))

;; =============================================================================
;; Item consumivel: fire_charge substitui o grimorio, e a mira vem do op LOOK
;; =============================================================================

(deftest test-fireball-item-replaces-grimoire
  (testing "O porteiro da magia passa a ser o fire_charge, nao o writable_book"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent-lg (atom [])
          mock-lg (spell-mock! sent-lg "minecraft:fire_charge,1")
          hit-lg? (atelier/cast-coco-spell! coco-st mock-lg "pace" 1 2000000)]
      (is (true? hit-lg?) "Com o fire_charge no inventario, a magia conjura")
      (is (some #(str/starts-with? % "TARGET_HOSTILE|pace|") @sent-lg)
          "E varre hostis como antes"))

    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent-livro (atom [])
          mock-livro (spell-mock! sent-livro "minecraft:writable_book,1")
          hit-livro? (atelier/cast-coco-spell! coco-st mock-livro "pace" 1 2000000)]
      (is (false? hit-livro?) "So o writable_book (o grimorio antigo) JA NAO conjura")
      (is (not (some #(str/starts-with? % "TARGET_HOSTILE") @sent-livro))
          "Sem o item de fogo, nem sequer varre hostis"))))

(deftest test-aim-snapshot-prefers-look-op
  (testing "A direcao do olhar vem do op LOOK, nao do bloco avistado pelo raycast"
    ;; O raycast do jogador pode acertar o CHAO (vetor apontando para baixo);
    ;; o op LOOK devolve a direcao REAL do olhar. Quando o LOOK existe, ele vence.
    (let [sent (atom [])
          mock! (spell-mock! sent "minecraft:fire_charge,1"
                             "OK|minecraft:deepslate|9|63|0|1.00"    ; raycast: o chao
                             {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"}) ; LOOK: leste reto
          snap (atelier/player-aim-snapshot mock! "pace" 24.0)]
      (is (some #(str/starts-with? % "LOOK|pace") @sent)
          "Deve consultar o op LOOK do jogador")
      (is (vector? (:look-direction snap)) "O snapshot traz a direcao do olhar")
      (is (> (first (:look-direction snap)) 0.9)
          "A direcao aponta para leste (+x), a direcao REAL do olhar")
      (is (< (Math/abs (double (second (:look-direction snap)))) 0.1)
          "E NAO aponta para o chao (o vetor do raycast que acertou o solo)"))))

(deftest test-aim-snapshot-falls-back-to-raycast-when-look-err
  (testing "Se o op LOOK nao existe (JVM antiga), a mira cai no raycast de sempre"
    (let [sent (atom [])
          mock! (spell-mock! sent "minecraft:fire_charge,1"
                             "OK|minecraft:stone_bricks|10|65|0|10.00"
                             {:look-resp "ERR|UNKNOWN_OP"})
          snap (atelier/player-aim-snapshot mock! "pace" 24.0)]
      (is (some #(str/starts-with? % "RAYCAST_BLOCK|pace|") @sent)
          "Sem LOOK, consulta o raycast (nao parte o caminho antigo)")
      (is (vector? (:look-direction snap)) "O snapshot continua a trazer a direcao")
      (is (> (first (:look-direction snap)) 0.9)
          "A direcao cai no bloco avistado (leste) — o fallback funciona"))))

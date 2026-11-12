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
   `opts` (mapa opcional) aceita :look-resp (a resposta do op LOOK),
   :hostile-resp (resposta do scanner) e :held-nbt (o custom_data do item
   na mao, lido pelo op HELD_ITEM)."
  ([sent-cmds] (spell-mock! sent-cmds "minecraft:blaze_rod,1"))
  ([sent-cmds held]
   (spell-mock! sent-cmds held nil))
  ([sent-cmds held raycast-resp]
   (spell-mock! sent-cmds held raycast-resp nil))
  ([sent-cmds held raycast-resp opts]
   (let [{:keys [look-resp hostile-resp held-nbt blocks]} opts]
     (fn [cmd]
       (swap! sent-cmds conj cmd)
       (cond
         (str/starts-with? cmd "INSPECT_INVENTORY")
         (if held (str "OK|1|" held) "OK|0|")
         (str/starts-with? cmd "LOOK") (or look-resp "ERR|UNKNOWN_OP")
         (str/starts-with? cmd "HELD_ITEM") (or held-nbt "ERR|NO_ITEM")
         (str/starts-with? cmd "TARGET_HOSTILE") (or hostile-resp (hostile-response))
         (str/starts-with? cmd "RAYCAST_BLOCK") (or raycast-resp "ERR|NO_BLOCK_IN_SIGHT")
         (str/starts-with? cmd "BLOCK|")
         (let [parts (str/split cmd #"\|")
               key (str/join " " (rest parts))]
           (str "OK|" (get blocks key "minecraft:air") "|state"))
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
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                      (str/includes? % "fireball"))
                cmds)
          "Deve lancar a fireball com DANO DE AREA (a explosao e o dano)")
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
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                      (str/includes? % "fireball"))
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
      (is (str/includes? proj "ExplosionPower:1")
          "ExplosionPower:1: a explosao e o visual/knockback (o dano vem do comando)"))))

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

(deftest test-coco-spell-damage-is-an-explosion-command
  (testing "Coco fere com o comando `damage` tipo explosion — a fireball fisica nao fere (MEDIDO)"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)
          _ (atelier/cast-coco-spell! coco-st mock! "pace" 1 2000000)
          cmds @sent
          proj (first (filter #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                                    (str/includes? % "fireball")) cmds))
          dmg (first (filter #(and (str/starts-with? % "CMD|") (str/includes? % "damage")) cmds))]
      (is (some? proj) "Deve lancar a fireball (visual)")
      (is (str/includes? proj "ExplosionPower:1") "A fireball leva a explosao visual")
      (is (some? dmg) "Deve aplicar o dano por comando (a fisica nao fere)")
      (is (str/includes? dmg "minecraft:explosion")
          "O tipo de dano e `explosion` (o dano da bola de fogo)")
      (is (not (some #(str/starts-with? % "ATTACK_HOSTILE") cmds))
          "Nao deve usar o golpe de mao (ATTACK_HOSTILE)"))))

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
          mock! (spell-mock! sent "minecraft:blaze_powder,1")
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

(deftest test-aim-accepts-any-hostile-in-front
  (testing "Sem cone: um hostil à frente, mesmo bem de lado, é aceite"
    ;; Ele pediu: "atire independente do mob, que acerte qualquer alvo". O cone
    ;; de 20 graus que recusava os mobs de lado saiu — basta estar à frente
    ;; (cos > 0). Antes este caso (12m de lado) dava nil.
    (let [aim-line [1.0 0.0 0.0]
          snap {:look-direction aim-line
                :ray-origin [0.0 65.0 0.0]
                :ray-max-dist 24.0}
          hostiles [{:id 7 :type "minecraft:creeper" :x 2.0 :y 65.0 :z 12.0 :dist 12.2 :hp 20.0}]]
      (is (= 7 (:id (atelier/select-aim-target snap hostiles)))
          "Um hostil à frente (cos > 0) conta, mesmo desviado da linha"))))

(deftest test-aim-rejects-hostiles-behind-the-player
  (testing "Hostil às costas (cos <= 0) é ignorado; sem ninguém à frente, nil"
    (let [aim-line [1.0 0.0 0.0]
          snap {:look-direction aim-line
                :ray-origin [0.0 65.0 0.0]
                :ray-max-dist 24.0}
          hostiles [{:id 7 :type "minecraft:creeper" :x -12.0 :y 65.0 :z 0.0 :dist 12.0 :hp 20.0}]]
      (is (nil? (atelier/select-aim-target snap hostiles))
          "Um hostil atrás do olhar não é alvo — não se acerta pelas costas"))))

(deftest test-aim-uses-player-raycast-direction
  (testing "A direcao do olhar vem do raycast do jogador (bloco a frente na linha)"
    ;; O raycast devolve o PRIMEIRO bloco solido na linha do olhar. A mira usa a
    ;; ORIGEM (olhos do jogador) + a direcao ate esse bloco como eixo.
    (let [sent (atom [])
          mock! (spell-mock! sent "minecraft:blaze_powder,1" "OK|minecraft:stone_bricks|10|65|0|10.00")
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
          mock! (spell-mock! sent "minecraft:blaze_powder,1" "OK|minecraft:stone_bricks|-20|66|190|10.00")
          _ (atelier/cast-coco-spell! coco-st mock! "pace" 1 2000000 {:aim-target {:id 99 :type "minecraft:skeleton"
                                                                                   :x -20.0 :y 66.0 :z 190.0
                                                                                   :dist 10.0 :hp 20.0}})
          cmds @sent
          proj (first (filter #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                                    (str/includes? % "fireball")) cmds))]
      (is (some? proj) "Deve lancar a fireball rumo ao alvo da mira")
      (is (str/includes? proj "at pace") "O projetil nasce do conjurador (pace)")
      (is (re-find #"Motion:\[" proj) "Leva o vetor de velocidade rumo ao alvo"))))

(deftest test-aim-spell-scans-around-the-caster
  (testing "A magia do jogador varre hostis em volta DELE (o conjurador), nao da bruxa"
    ;; A bruxa esta longe do jogador; o hostil esta perto DO JOGADOR. Se a
    ;; varredura ancorasse na bruxa, nao acharia nada e a magia nao sairia.
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:blaze_powder,1" "OK|minecraft:stone_bricks|6|64|128|6.00")]
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
          mock! (spell-mock! sent "minecraft:blaze_powder,1" "OK|minecraft:stone_bricks|6|64|128|6.00")]
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
          mock! (spell-mock! sent "minecraft:blaze_powder,1" "OK|minecraft:stone_bricks|6|64|128|6.00")
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
  (testing "Sem bloco na linha do olhar (campo aberto), a magia ainda sai"
    ;; O raycast devolve ERR|NO_BLOCK_IN_SIGHT quando o jogador olha para o
    ;; horizonte aberto — o caso mais comum de atirar uma bola de fogo. Com o op
    ;; LOOK isso ja nao importa (a direcao nao depende de bloco), e mesmo o
    ;; fallback ja nao bloqueia: a fireball voa na direcao da mira.
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:blaze_powder,1" "ERR|NO_BLOCK_IN_SIGHT")
          _ (atelier/cast-casting! st mock! "pace" 2000000)
          cmds @sent]
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                      (str/includes? % "fireball"))
                cmds)
          "A fireball sai mesmo sem bloco na mira")))

(deftest test-aim-still-prefers-the-aimed-hostile-over-the-nearest
  (testing "Com mira, o projetil aponta ao hostil APONTADO, nao ao mais proximo"
    ;; Regressao: o alvo mirado tem de continuar a ser o que decide os visuais.
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:blaze_powder,1" "OK|minecraft:stone_bricks|-20|66|190|10.00")
          hit? (atelier/cast-casting! st mock! "pace" 2000000)
          cmds @sent]
      (is (true? hit?) "A magia conjura")
      ;; O burst visual cai em x=-20, onde esta o hostil apontado, nao o mais
      ;; proximo (x=-28).
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "-20"))
                cmds)
          "O impacto visual cai nas coordenadas do hostil APONTADO"))))

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
          mock! (spell-mock! sent "minecraft:blaze_powder,1" "ERR|NO_BLOCK_IN_SIGHT")
          said (atom [])
          say-fn (fn [_who text] (swap! said conj text))
          ok? (atelier/handle-atelier-chat! "pace" "fireball" st (atelier/create-richeh-state) say-fn)]
      (is (true? ok?) "O casting puro foi consumido pelo handler")
      (is (seq @said) "O conjurador recebeu uma resposta (sucesso ou falha)")
      ;; E a resposta tem de sair da posicao DELE, nao da bruxa.
      (is (some #(and (str/includes? % "tellraw") (str/includes? % "at pace")) @sent)
          "A resposta do casting ancora no conjurador (at pace)")))))

;; =============================================================================
;; A varinha atira SEMPRE — a mira DIRIGE o tiro, nao o autoriza
;; =============================================================================
;; Ele pediu, medido: "QUERO que atire independente do mob, que acerte qualquer
;; alvo". Antes, a magia so saia com um hostil no cone de 20 graus — sem alvo,
;; clicar na varinha nao gastava a carga e nao voava nada. Agora o projetil nasce
;; sempre, rumo a direcao do olhar (`LOOK`), e o hostil mais proximo so serve para
;; decidir DANO + visuais. Sem hostil: projetil voa, sem dano.

(deftest test-wand-fires-without-any-target
  (testing "Sem hostil nenhum, o clique na varinha ainda atira (na direcao da mira)"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:blaze_powder,1" nil
                             {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"})
          _ (with-redefs [atelier/parse-all-hostiles (fn [_] [])
                          atelier/parse-hostile-response (fn [_] nil)]
              (atelier/cast-casting! coco-st mock! "pace" 2000000))
          cmds @sent
          proj (first (filter #(and (str/starts-with? % "CMD|")
                                    (str/includes? % "summon") (str/includes? % "fireball"))
                              cmds))]
      (is (some? proj) "Sem alvo, o projetil de fogo TEM de nascer")
      (is (str/includes? proj "at pace") "O projetil nasce do conjurador")
      ;; Sem alvo, a direcao e a da mira: +x (leste), zero em y e z.
      (is (re-find #"Motion:\[\s*1\.1[-0-9]" proj)
          "O projetil voa na direcao do olhar (+x), a velocidade fixa")
      (is (not (some #(and (str/starts-with? % "CMD|") (str/includes? % "damage")) cmds))
          "Sem alvo nao ha dano — mas ha projetil")
      (is (not (some #(str/starts-with? % "SWING|") cmds))
          "O golpe de mao (SWING) tambem depende de haver alvo"))))

(deftest test-wand-miss-does-not-claim-a-hit
  (testing "A missao sem alvo NAO inventa alvo: nenhum dano sem hostil"
    ;; Guarda contra a tentacao de "resolver" a mira cega escolhendo o hostil mais
    ;; proximo por distancia (o que fazia o campo de tiro acertar pelas costas).
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:blaze_rod,1" nil
                             {:look-resp "OK|0.0|-1.0|0.0|0.0|90.0"})
          _ (with-redefs [atelier/parse-all-hostiles (fn [_] [])
                          atelier/parse-hostile-response (fn [_] nil)]
              (atelier/cast-casting! st mock! "pace" 2000000))
          cmds @sent]
      (is (not (some #(and (str/starts-with? % "CMD|") (str/includes? % "damage")) cmds))
          "Sem hostil no alcance, nada de dano inventado"))))

;; =============================================================================
;; Item consumivel: a varinha (blaze_rod) substitui o grimorio; mira via op LOOK
;; =============================================================================

(deftest test-fireball-item-replaces-grimoire
  (testing "O porteiro da magia passa a ser a varinha (blaze_rod), nao o writable_book"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent-lg (atom [])
          mock-lg (spell-mock! sent-lg "minecraft:blaze_rod,1")
          hit-lg? (atelier/cast-coco-spell! coco-st mock-lg "pace" 1 2000000)]
      (is (true? hit-lg?) "Com a varinha de fogo no inventario, a magia conjura")
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
          mock! (spell-mock! sent "minecraft:blaze_rod,1"
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
          mock! (spell-mock! sent "minecraft:blaze_powder,1"
                             "OK|minecraft:stone_bricks|10|65|0|10.00"
                             {:look-resp "ERR|UNKNOWN_OP"})
          snap (atelier/player-aim-snapshot mock! "pace" 24.0)]
      (is (some #(str/starts-with? % "RAYCAST_BLOCK|pace|") @sent)
          "Sem LOOK, consulta o raycast (nao parte o caminho antigo)")
      (is (vector? (:look-direction snap)) "O snapshot continua a trazer a direcao")
      (is (> (first (:look-direction snap)) 0.9)
          "A direcao cai no bloco avistado (leste) — o fallback funciona"))))

;; =============================================================================
;; Polling do item (HELD_ITEM): o engine ve a carga subir e dispara a magia
;; =============================================================================

(deftest test-held-item-damage-reads-the-wand
  (testing "held-item-damage le o damage (carga) quando a mao tem a varinha"
    (let [calls (atom [])
          mock! (fn [cmd] (swap! calls conj cmd)
                  "OK|minecraft:blaze_rod|1|3|8")]
      (is (= 3 (atelier/held-item-damage mock! "pace"))
          "Devolve o damage (3 cargas gastas) como contador")
      (is (some #(str/starts-with? % "HELD_ITEM|pace") @calls)
          "Consulta o op HELD_ITEM do jogador")))

  (testing "held-item-damage devolve nil quando a mao NAO tem a varinha"
    (let [mock! (fn [_] "OK|minecraft:diamond_pickaxe|1|0|0")]
      (is (nil? (atelier/held-item-damage mock! "pace"))
          "Outro item na mao -> nil (o loop fica inerte)"))

    (let [mock! (fn [_] "OK|minecraft:air|0|0|0")]
      (is (nil? (atelier/held-item-damage mock! "pace")) "Mao vazia -> nil"))

    (let [mock! (fn [_] "ERR|UNKNOWN_OP")]
      (is (nil? (atelier/held-item-damage mock! "pace"))
          "Op inexistente (JVM sem restart) -> nil, sem explodir"))))

(deftest test-fireball-used-detects-a-charge-up
  (testing "fireball-used? dispara quando o damage AUMENTA (uso da varinha)"
    (is (true? (atelier/fireball-used? 2 3)) "2 -> 3: um uso"))

  (testing "fireball-used? NAO dispara quando o damage fica igual (nada mudou)"
    (is (false? (atelier/fireball-used? 3 3)) "3 -> 3: sem uso"))

  (testing "fireball-used? NAO dispara na PRIMEIRA leitura (prev nil)"
    (is (false? (atelier/fireball-used? nil 0))
        "Primeira leitura so memoriza a base — nao dispara ao entrar"))

  (testing "fireball-used? NAO dispara sem item (now nil)"
    (is (false? (atelier/fireball-used? 3 nil)) "Sem leitura -> false")))

;; =============================================================================
;; Auto-defesa das bruxas: so conjura COM hostil (sem alvo, nada sai)
;; =============================================================================

(deftest test-auto-defense-does-not-cast-without-hostile
  (testing "Sem hostil no raio, a auto-defesa NAO conjura nada (nem projetil)"
    ;; Defeito medido: `spell-auto-defense!` corria a cada 1s e o cast-spell!
    ;; enviava o projectile-cmd FORA do (when target) -> as bruxas cuspiam uma
    ;; fireball a cada 5s para o vazio, mesmo sem nenhum mob (TARGET_HOSTILE -> OK|0|).
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)]
      (with-redefs [atelier/parse-hostile-response (fn [_] nil)
                    atelier/parse-all-hostiles (fn [_] [])]
        (let [cast? (atelier/spell-auto-defense! coco-st "Coco" mock! 2000000)
              cmds @sent]
          (is (false? cast?) "Sem hostil, a auto-defesa nao conjura")
          (is (not (some #(and (str/starts-with? % "CMD|")
                               (str/includes? % "summon") (str/includes? % "fireball"))
                         cmds))
              "Sem hostil, nenhuma fireball sai (nem na direcao da mira)")))))

  (testing "COM hostil, a auto-defesa conjura e acerta-o"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)
          cast? (atelier/spell-auto-defense! coco-st "Coco" mock! 2000000)
          cmds @sent]
      (is (true? cast?) "Com hostil, a bruxa conjura a sua magia")
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                      (str/includes? % "fireball"))
                cmds)
          "Com hostil, a fireball sai"))))

;; =============================================================================
;; Despachante Unificado de Feiticos (Passo 0 + 1o Feitico: Wind Charge)
;; =============================================================================

(deftest test-spell-registry-catalog
  (testing "Catalogo de feiticos contem fireball, wind-charge, lightning-smite e frost-beam"
    (is (contains? atelier/spell-registry "minecraft:blaze_rod")
        "blaze_rod mapeado no catalogo")
    (is (= :fireball (get-in atelier/spell-registry ["minecraft:blaze_rod" :id])))
    (is (contains? atelier/spell-registry "minecraft:breeze_rod")
        "breeze_rod mapeado no catalogo")
    (is (= :wind-charge (get-in atelier/spell-registry ["minecraft:breeze_rod" :id])))
    (is (contains? atelier/spell-registry "minecraft:lightning_rod")
        "lightning_rod mapeado no catalogo")
    (is (= :lightning-smite (get-in atelier/spell-registry ["minecraft:lightning_rod" :id])))
    (is (contains? atelier/spell-registry "minecraft:trial_key")
        "trial_key mapeado no catalogo")
    (is (= :lightning-smite (get-in atelier/spell-registry ["minecraft:trial_key" :id])))
    (is (contains? atelier/spell-registry "minecraft:amethyst_shard")
        "amethyst_shard mapeado no catalogo")
    (is (= :frost-beam (get-in atelier/spell-registry ["minecraft:amethyst_shard" :id])))
    (is (contains? atelier/spell-registry "minecraft:echo_shard")
        "echo_shard mapeado no catalogo")
    (is (= :shadow-step (get-in atelier/spell-registry ["minecraft:echo_shard" :id])))))

(deftest test-held-spell-item-detection
  (testing "held-spell-item identifica o item magico e seu dano atual"
    (let [mock-breeze! (fn [cmd]
                         (if (str/starts-with? cmd "HELD_ITEM")
                           "OK|minecraft:breeze_rod|1|5|64"
                           "OK"))
          info (atelier/held-spell-item mock-breeze! "pace")]
      (is (some? info) "Detectou o item magico")
      (is (= "minecraft:breeze_rod" (:item-id info)))
      (is (= 5 (:damage info)))
      (is (= :wind-charge (get-in info [:spell :id])))))

  (testing "held-spell-item devolve nil para item mundano"
    (let [mock-stick! (fn [cmd]
                        (if (str/starts-with? cmd "HELD_ITEM")
                          "OK|minecraft:stick|1|0|0"
                          "OK"))
          info (atelier/held-spell-item mock-stick! "pace")]
      (is (nil? info) "Item comum nao e feitico"))))

(deftest test-spell-used-unified-detection
  (testing "spell-used? detecta gasto de carga no mesmo item"
    (is (true? (atelier/spell-used?
                {:item-id "minecraft:breeze_rod" :damage 0}
                {:item-id "minecraft:breeze_rod" :damage 1}))
        "0 -> 1: carga gasta")
    (is (true? (atelier/spell-used?
                {:item-id "minecraft:blaze_rod" :damage 3}
                {:item-id "minecraft:blaze_rod" :damage 4}))
        "3 -> 4: carga gasta"))

  (testing "spell-used? rejeita se o dano nao aumentou ou trocou de item"
    (is (false? (atelier/spell-used?
                 {:item-id "minecraft:breeze_rod" :damage 1}
                 {:item-id "minecraft:breeze_rod" :damage 1}))
        "Dano igual: nao disparou")
    (is (false? (atelier/spell-used?
                 {:item-id "minecraft:blaze_rod" :damage 3}
                 {:item-id "minecraft:breeze_rod" :damage 4}))
        "Itens diferentes: nao dispara")
    (is (false? (atelier/spell-used? nil {:item-id "minecraft:breeze_rod" :damage 0}))
        "Primeira leitura: nao dispara")
    (is (false? (atelier/spell-used? {:item-id "minecraft:breeze_rod" :damage 1} nil))
        "Item guardado: nao dispara")))

(deftest test-dispatch-wind-charge-spell
  (testing "dispatch-held-spell! para breeze_rod lanca breeze_wind_charge e som do breeze"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:breeze_rod,1" nil
                             {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"})
          spell-info {:item-id "minecraft:breeze_rod"
                      :damage 1
                      :max-damage 64
                      :spell (get atelier/spell-registry "minecraft:breeze_rod")}
          cast? (atelier/dispatch-held-spell! st mock! "pace" spell-info 2000000)
          cmds @sent]
      (is (true? cast?) "Wind charge disparado com sucesso")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "summon")
                      (or (str/includes? % "breeze_wind_charge")
                          (str/includes? % "wind_charge")))
                cmds)
          "Lanca entidade de wind charge na direcao do olhar")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "playsound")
                      (str/includes? % "breeze"))
                cmds)
          "Emite efeito sonoro do breeze")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "damage")
                      (str/includes? % "type=!minecraft:player"))
                cmds)
          "Aplica dano forte em area (AOE) protegendo o jogador"))))

(deftest test-dispatch-lightning-smite-spell
  (testing "dispatch-held-spell! para lightning_rod invoca lightning_bolt, som de trovao e protege o conjurador"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:lightning_rod,1" nil
                             {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"})
          spell-info {:item-id "minecraft:lightning_rod"
                      :damage 1
                      :max-damage 64
                      :spell (get atelier/spell-registry "minecraft:lightning_rod")}
          cast? (atelier/dispatch-held-spell! st mock! "pace" spell-info 2000000)
          cmds @sent]
      (is (true? cast?) "Lightning smite disparado com sucesso")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "effect give pace")
                      (str/includes? % "fire_resistance"))
                cmds)
          "Aplica protecao contra fogo/dano residual ao conjurador")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "summon")
                      (str/includes? % "lightning_bolt"))
                cmds)
          "Invoca o raio real vanilla (lightning_bolt) nas coordenadas de impacto")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "playsound")
                      (str/includes? % "entity.lightning_bolt.thunder"))
                cmds)
          "Emite efeito sonoro do trovao global")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "particle")
                      (str/includes? % "electric_spark"))
                cmds)
          "Gera particulas de faiscas eletricas")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "damage")
                      (str/includes? % "type=!minecraft:player"))
                cmds)
          "Aplica dano eletrico forte em area preservando o jogador"))))

(deftest test-dispatch-spell-selects-aimed-enderman
  (testing "uma magia escolhe o Enderman mirado mesmo quando outro hostil esta mais perto"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:trial_key,1" nil
                             {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"
                              :hostile-resp
                              "OK|2|41,minecraft:zombie,2.00,65.00,2.00,2.83,20.0;77,minecraft:enderman,8.00,66.62,0.00,8.00,40.0"})
          spell-info {:item-id "minecraft:trial_key"
                      :damage 1
                      :max-damage 64
                      :spell (get atelier/spell-registry "minecraft:trial_key")}
          cast? (atelier/dispatch-held-spell! st mock! "pace" spell-info 2000000)
          cmds @sent]
      (is (true? cast?) "Relampago disparado com sucesso")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "positioned 8.00 66.62 0.00")
                      (str/includes? % "summon minecraft:lightning_bolt"))
                cmds)
          "O impacto deve ser resolvido nas coordenadas do Enderman mirado"))))

(deftest test-dispatch-frost-beam-spell
  (testing "dispatch-held-spell! para amethyst_shard dispara raio congelante com projeteis de gelo, lentidao extrema e dano freeze"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:amethyst_shard,1" nil
                             {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"})
          spell-info {:item-id "minecraft:amethyst_shard"
                      :damage 1
                      :max-damage 64
                      :spell (get atelier/spell-registry "minecraft:amethyst_shard")}
          cast? (atelier/dispatch-held-spell! st mock! "pace" spell-info 2000000)
          cmds @sent]
      (is (true? cast?) "Frost beam disparado com sucesso")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "summon")
                      (str/includes? % "snowball"))
                cmds)
          "Lanca projeteis azuis/snowball na direcao da mira")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "damage")
                      (str/includes? % "freeze"))
                cmds)
          "Aplica dano de congelamento (freeze)")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "effect give")
                      (str/includes? % "slowness"))
                cmds)
          "Aplica lentidao extrema (slowness IV) aos inimigos atingidos")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "particle")
                      (str/includes? % "snowflake"))
                cmds)
          "Emite particulas de flocos de gelo/snowflake")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "playsound")
                      (or (str/includes? % "block.glass.break")
                          (str/includes? % "amethyst_block.break")))
                cmds)
          "Emite efeito sonoro de vidro quebrando / estilhacamento de gelo"))))

(deftest test-dispatch-fireball-firerod-spell
  (testing "dispatch-held-spell! para blaze_rod (firerod) dispara fireball e causa fogo (fire) no impacto"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:blaze_rod,1" nil
                             {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"})
          spell-info {:item-id "minecraft:blaze_rod"
                      :damage 1
                      :max-damage 64
                      :spell (get atelier/spell-registry "minecraft:blaze_rod")}
          cast? (atelier/dispatch-held-spell! st mock! "pace" spell-info 2000000)
          cmds @sent]
      (is (true? cast?) "Firerod disparado com sucesso")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "summon")
                      (str/includes? % "fireball"))
                cmds)
          "Lanca projetil fisico de bola de fogo (fireball) rumo a mira")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "setblock")
                      (str/includes? % "minecraft:fire keep"))
                cmds)
          "Causa fogo ao impacto no bloco/chao via setblock fire keep")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "damage")
                      (str/includes? % "type=!minecraft:player"))
                cmds)
          "Aplica dano de impacto em area protegendo o jogador")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "particle")
                      (str/includes? % "flame"))
                cmds)
          "Emite particulas de labaredas/flame no ponto de impacto"))))

(deftest test-dispatch-shadow-step-spell
  (testing "dispatch-held-spell! para echo_shard executa teleporte tatica instantaneo com resistance, invisibility, particulas de portal e som de enderman"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:echo_shard,1" nil
                             {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"})
          spell-info {:item-id "minecraft:echo_shard"
                      :damage 1
                      :max-damage 64
                      :spell (get atelier/spell-registry "minecraft:echo_shard")}
          cast? (atelier/dispatch-held-spell! st mock! "pace" spell-info 2000000)
          cmds @sent]
      (is (true? cast?) "Passo das sombras disparado com sucesso")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "tp pace")
                      (str/includes? % "~ ~"))
                cmds)
          "Executa teleporte do jogador mantendo a rotacao atual")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "effect give pace minecraft:resistance"))
                cmds)
          "Concede efeito de resistencia pos-teleporte")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "effect give pace minecraft:speed"))
                cmds)
          "Concede efeito de velocidade pos-teleporte para reposicionamento rapido")
      (is (not-any? #(str/includes? % "effect give pace minecraft:invisibility") cmds)
          "Nao concede invisibilidade para preservar a visibilidade da skin")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "playsound entity.enderman.teleport"))
                cmds)
          "Toca som de teleporte do enderman")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "particle minecraft:portal"))
                cmds)
          "Emite nuvem de particulas portal")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "particle minecraft:reverse_portal"))
                cmds)
          "Emite nuvem de particulas reverse_portal")))

  (testing "dispatch-held-spell! para echo_shard para antes de obstaculo solido detectado pelo raycast"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          ;; Obstaculo solido a 8 blocos a frente
          mock! (spell-mock! sent "minecraft:echo_shard,1" "OK|minecraft:stone|8.0|65.0|0.0|8.0"
                             {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"})
          spell-info {:item-id "minecraft:echo_shard"
                      :damage 1
                      :max-damage 64
                      :spell (get atelier/spell-registry "minecraft:echo_shard")}
          cast? (atelier/dispatch-held-spell! st mock! "pace" spell-info 2000000)
          cmds @sent]
      (is (true? cast?) "Passo das sombras disparado com sucesso")
      ;; Teleporte deve parar antes do bloco 8.0 (ex: 6.80 ou 7.00)
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "tp pace 6.80 65.00 0.00 ~ ~"))
                cmds)
          "Teleporta para um bloco antes do obstaculo solido")))

  (testing "dispatch-held-spell! evita entrar debaixo da terra quando o jogador olha para baixo"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          ;; Olhar para baixo (dy = -0.3): a 32 blocos daria ty = 65 - 9.2 = 55.8 (debaixo da terra)
          ;; Bloco em y=55..64 sao solidos em x=30; y=65 e ar livre
          blocks {"30 55 0" "minecraft:stone"
                  "30 56 0" "minecraft:stone"
                  "30 57 0" "minecraft:stone"
                  "30 58 0" "minecraft:stone"
                  "30 59 0" "minecraft:stone"
                  "30 60 0" "minecraft:stone"
                  "30 61 0" "minecraft:stone"
                  "30 62 0" "minecraft:stone"
                  "30 63 0" "minecraft:dirt"
                  "30 64 0" "minecraft:grass_block"
                  "30 65 0" "minecraft:air"
                  "30 66 0" "minecraft:air"}
          mock! (spell-mock! sent "minecraft:echo_shard,1" nil
                             {:look-resp "OK|1.0|-0.3|0.0|90.0|15.0"
                              :blocks blocks})
          spell-info {:item-id "minecraft:echo_shard"
                      :damage 1
                      :max-damage 64
                      :spell (get atelier/spell-registry "minecraft:echo_shard")}
          cast? (atelier/dispatch-held-spell! st mock! "pace" spell-info 2000000)
          cmds @sent]
      (is (true? cast?) "Passo das sombras disparado")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "tp pace")
                      (str/includes? % "65.00")
                      (str/includes? % "~ ~"))
                cmds)
          "Teleporte corrige a altura para o topo do solo seguro (y=65.00), nao entrando na terra")))

  (testing "dispatch-held-spell! para echo_shard permite 2 cliques seguidos sem cooldown completo"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent1 (atom [])
          mock1! (spell-mock! sent1 "minecraft:echo_shard,1" nil
                              {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"})
          spell-info {:item-id "minecraft:echo_shard"
                      :damage 1
                      :max-damage 64
                      :spell (get atelier/spell-registry "minecraft:echo_shard")}
          ;; 1º clique (t = 1000 ms)
          cast1? (atelier/dispatch-held-spell! st mock1! "pace" spell-info 1000)
          cmds1 @sent1
          ;; 2º clique quase imediato (t = 1300 ms, 300ms depois)
          sent2 (atom [])
          mock2! (spell-mock! sent2 "minecraft:echo_shard,2" nil
                              {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"})
          cast2? (atelier/dispatch-held-spell! st mock2! "pace" spell-info 1300)
          cmds2 @sent2]
      (is (true? cast1?) "1º clique disparado com sucesso")
      (is (true? cast2?) "2º clique disparado com sucesso")
      ;; No 1º clique: cooldown visual minimo (3 ticks) para liberar o 2º clique
      (is (some #(= % "ITEM_COOLDOWN|pace|minecraft:echo_shard|3") cmds1)
          "1º clique nao entra em cooldown longo (3 ticks apenas)")
      ;; No 2º clique: cooldown completo de recarga (20 ou 25 ticks)
      (is (some #(and (str/starts-with? % "ITEM_COOLDOWN|pace|minecraft:echo_shard|")
                      (not= % "ITEM_COOLDOWN|pace|minecraft:echo_shard|3"))
                cmds2)
          "2º clique entra no cooldown completo de recarga"))))

(deftest test-dispatch-tidal-surge-spell
  (testing "spell-registry registra o Orbe das Mares (heart_of_the_sea)"
    (let [spell (get atelier/spell-registry "minecraft:heart_of_the_sea")]
      (is (some? spell) "heart_of_the_sea registrado no catalogo")
      (is (= :tidal-surge (:id spell)))
      (is (= "Orbe das Marés" (:name spell)))))

  (testing "held-spell-item detecta o Orbe das Mares"
    (let [mock! (fn [cmd]
                  (if (str/starts-with? cmd "HELD_ITEM")
                    "OK|minecraft:heart_of_the_sea|1|0|64"
                    "OK"))
          info (atelier/held-spell-item mock! "pace")]
      (is (some? info))
      (is (= :tidal-surge (get-in info [:spell :id])))))

  (testing "dispatch-held-spell! para heart_of_the_sea lanca torrente aquatica, apaga fogo, molha entidades e causa dano"
    (let [st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent "minecraft:heart_of_the_sea,1" nil
                             {:look-resp "OK|1.0|0.0|0.0|90.0|0.0"})
          spell-info {:item-id "minecraft:heart_of_the_sea"
                      :damage 1
                      :max-damage 64
                      :spell (get atelier/spell-registry "minecraft:heart_of_the_sea")}
          cast? (atelier/dispatch-held-spell! st mock! "pace" spell-info 2000000)
          cmds @sent]
      (is (true? cast?) "Torrente aquatica disparada com sucesso")
      ;; 1. Projeteis aquaticos
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "summon minecraft:potion")
                      (str/includes? % "minecraft:water"))
                cmds)
          "Lanca projeteis/pocao de agua na direcao da mira")
      ;; 2. Deixa agua no chao; no Nether usa cauldron para nao evaporar
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "setblock ~ ~ ~ minecraft:water keep"))
                cmds)
          "Deixa uma fonte de agua no ponto de impacto")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "minecraft:water_cauldron[level=3] keep"))
                cmds)
          "Preserva agua no Nether dentro de um caldeirao cheio")
      ;; 3. Neutraliza lava no raio da magia
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "minecraft:obsidian replace minecraft:lava"))
                cmds)
          "Transforma lava em obsidiana para neutraliza-la no Nether")
      (is (some #(and (str/starts-with? % "CMD|execute at pace run fill")
                      (str/includes? % "minecraft:obsidian replace minecraft:lava"))
                cmds)
          "Neutraliza lava tambem ao redor do conjurador")
      ;; 4. Apaga fogo no cenario (raio de 3 blocos)
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "fill")
                      (str/includes? % "minecraft:air replace minecraft:fire"))
                cmds)
          "Apaga blocos de fogo ao redor do impacto")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "fill")
                      (str/includes? % "minecraft:air replace minecraft:soul_fire"))
                cmds)
          "Apaga blocos de fogo das almas")
      ;; 3. Molha entidades / apaga chamas
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "data modify entity @s Fire set value 0s"))
                cmds)
          "Zera chamas (Fire) das entidades atingidas")
      ;; 4. Dano de afogamento (drown) e dano extra a blazes
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "damage @s 35 minecraft:drown"))
                cmds)
          "Aplica dano de afogamento em area nos hostis")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "type=minecraft:blaze")
                      (str/includes? % "damage @s 50 minecraft:drown"))
                cmds)
          "Aplica dano critico extra em blazes")
      ;; 5. Sons e particulas aquaticas
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "playsound entity.generic.splash"))
                cmds)
          "Toca som de splash aquatica")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "particle minecraft:splash"))
                cmds)
          "Emite particulas de splash")
      (is (some #(and (str/starts-with? % "CMD|")
                      (str/includes? % "particle minecraft:bubble_pop"))
                cmds)
          "Emite particulas de bolhas estourando")
      ;; 6. Cooldown de 20 ticks
      (is (some #(= % "ITEM_COOLDOWN|pace|minecraft:heart_of_the_sea|20") cmds)
          "Aplica cooldown de 20 ticks (1s)"))))








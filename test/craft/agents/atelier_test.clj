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
   `held` e o item devolvido por INSPECT_INVENTORY (o grimorio, ou nil sem ele)."
  ([sent-cmds] (spell-mock! sent-cmds "minecraft:writable_book,1"))
  ([sent-cmds held]
   (spell-mock! sent-cmds held nil))
  ([sent-cmds held raycast-resp]
   (fn [cmd]
     (swap! sent-cmds conj cmd)
     (cond
       (str/starts-with? cmd "INSPECT_INVENTORY")
       (if held (str "OK|1|" held) "OK|0|")
       (str/starts-with? cmd "TARGET_HOSTILE") (hostile-response)
       (str/starts-with? cmd "RAYCAST_BLOCK") (or raycast-resp "ERR|NO_BLOCK_IN_SIGHT")
       (= cmd "PLAYERS") "OK|pace,0.0,65.0,0.0"
       (str/starts-with? cmd "ATTACK_HOSTILE") (str "OK|" (last (str/split cmd #"\|")) "|0.90")
       :else "OK"))))

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
          mock! (spell-mock! sent "minecraft:writable_book,1")
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
          mock! (spell-mock! sent "minecraft:writable_book,1" "OK|minecraft:stone_bricks|10|65|0|10.00")
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
          mock! (spell-mock! sent "minecraft:writable_book,1" "OK|minecraft:stone_bricks|-20|66|190|10.00")
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
          mock! (spell-mock! sent "minecraft:writable_book,1" "OK|minecraft:stone_bricks|6|64|128|6.00")]
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
          mock! (spell-mock! sent "minecraft:writable_book,1" "OK|minecraft:stone_bricks|6|64|128|6.00")]
      (atelier/cast-spell-with-aim! st "Coco" mock! "pace" 2000000
                                    ["CMD|execute at Coco run particle minecraft:enchant ~ ~1 ~ 0.5 0.5 0.5 0.05 10 normal"]
                                    nil
                                    ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"])
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                      (str/includes? % "fireball") (str/includes? % "at pace"))
                @sent)
          "O projetil de fogo nasce das coordenadas do JOGADOR (at pace)"))))

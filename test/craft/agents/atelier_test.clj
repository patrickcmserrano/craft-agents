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
  "Mock da bridge: registra os comandos e responde aos opcodes de combate."
  [sent-cmds]
  (fn [cmd]
    (swap! sent-cmds conj cmd)
    (cond
      (str/starts-with? cmd "TARGET_HOSTILE") (hostile-response)
      (str/starts-with? cmd "ATTACK_HOSTILE") (str "OK|" (last (str/split cmd #"\|")) "|0.90")
      :else "OK")))

(deftest test-coco-spell-casts-visual-and-damage
  (testing "Coco conjura glifo: varre hostis, aplica dano ao mais proximo e emite visual"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)
          _ (atelier/cast-coco-spell! coco-st mock! 1 2000000)
          cmds @sent]
      (is (some #(str/starts-with? % "TARGET_HOSTILE|Coco|") cmds)
          "Deve varrer hostis com TARGET_HOSTILE ancorado na Coco")
      (is (some #(= % "ATTACK_HOSTILE|Coco|42") cmds)
          "Deve atacar o hostil mais proximo (id 42) com dano real")
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "particle")) cmds)
          "Deve emitir assinatura visual (particle)")
      (is (some #(str/starts-with? % "SWING|Coco") cmds)
          "Deve emitir SWING durante a conjuracao"))))

(deftest test-richeh-spell-casts-visual-and-damage
  (testing "Richeh estilhaca cristal: ataca hostil mais proximo com a assinatura de ametista"
    (let [richeh-st (atelier/create-richeh-state [-30.0 66.0 192.0])
          sent (atom [])
          mock! (spell-mock! sent)
          _ (atelier/cast-richeh-spell! richeh-st mock! 1 2000000)
          cmds @sent]
      (is (some #(str/starts-with? % "TARGET_HOSTILE|Richeh|") cmds))
      (is (some #(= % "ATTACK_HOSTILE|Richeh|42") cmds))
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "particle")) cmds))
      (is (some #(str/starts-with? % "SWING|Richeh") cmds)))))

(deftest test-coco-spell-launches-projectile-at-target
  (testing "Coco lanca um projetil real (fireball) rumo ao alvo, sem ExplosionPower a quebrar blocos"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)
          _ (atelier/cast-coco-spell! coco-st mock! 1 2000000)
          cmds @sent
          proj (first (filter #(and (str/starts-with? % "CMD|") (str/includes? % "summon")
                                    (str/includes? % "fireball")) cmds))]
      (is (some? proj) "Deve summonar uma fireball como projetil")
      ;; Coco em x=-30, alvo em x=-28: o vetor de velocidade tem de apontar para
      ;; LESTE (x positivo) — a magia voa em direcao ao alvo, nao para o lado errado.
      (is (re-find #"Motion:\[1\.1" proj)
          "O vetor de velocidade deve apontar para o alvo (leste, +x)")
      (is (str/includes? proj "ExplosionPower:0") "ExplosionPower:0 para nao quebrar blocos do mundo"))))

(deftest test-richeh-spell-detonates-burst-at-target
  (testing "Richeh detona a magia nas coordenadas do alvo (nao so na propria bruxa)"
    (let [richeh-st (atelier/create-richeh-state [-30.0 66.0 192.0])
          sent (atom [])
          mock! (spell-mock! sent)
          _ (atelier/cast-richeh-spell! richeh-st mock! 1 2000000)
          cmds @sent]
      (is (some #(and (str/starts-with? % "CMD|") (str/includes? % "particle")
                      (str/includes? % "-28"))
                cmds)
          "Deve haver um golpe visual ANCRADO NO ALVO (x=-28), nao so na Richeh"))))

(deftest test-spell-cooldown-blocks-immediate-recast
  (testing "Cooldown de 5s impede reconjuracao automatica imediata"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)]
      (is (true? (atelier/cast-coco-spell! coco-st mock! 1 1000000)) "Primeira conjuracao acontece")
      (reset! sent [])
      (is (false? (atelier/cast-coco-spell! coco-st mock! 2 1000500)) "Segunda a 0.5s e bloqueada pelo cooldown")
      (is (empty? @sent) "Nenhum comando emitido durante o cooldown"))))

(deftest test-spell-bloodlust-is-per-burst
  (testing "A fala de conjuracao so sai no primeiro lancamento de cada surto"
    (let [coco-st (atelier/create-coco-state [-30.0 66.0 190.0])
          sent (atom [])
          mock! (spell-mock! sent)]
      (is (true? (atelier/cast-coco-spell! coco-st mock! 1 1000000)))
      (is (true? (:spell-said? @coco-st)) "Marca que ja falou neste surto")
      (is (false? (atelier/should-say-spell? coco-st 1001000))
          "Nao fala de novo dentro da janela do surto")
      (is (true? (atelier/should-say-spell? coco-st 1100000))
          "Passada a janela, um novo surto pode falar"))))

(ns craft.agents.epic2-synthetic-player-test
  "Testes comportamentais do Epico 2: ServerPlayerEntity Sintetico, EmbeddedChannel, Fisica, Portoes, Naturalist, JEG e Baus (CA-013 a CA-020)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [craft.agents.backend.fabric :as fabric]
            [craft.agents.backend.fabric-skins :as skins]
            [craft.agents.fixtures.world-fixture :as wf])
  (:import [com.craft.agents.fabric
            SilentEmbeddedChannel
            SyntheticCombat
            SyntheticInteraction
            SyntheticMotorController
            SyntheticPlayerFactory]))

(deftest ca-013-silent-embedded-channel-zero-bytebuf-leak-test
  (testing "CA-013: SilentEmbeddedChannel descarta pacotes de saida imediatamente sem serializar e contabiliza descartes sem vazar ByteBuf"
    (let [ch (SilentEmbeddedChannel.)]
      (dotimes [i 500]
        (is (true? (.releaseOutboundMessage ch {:packet-index i}))))
      (is (= 500 (.discardedPacketCount ch))))))

(deftest ca-014-deterministic-offline-uuid-and-synthetic-spawn-test
  (testing "CA-014: UUID offline de cada agente e deterministico entre reinicializacoes e distinto por nome"
    (let [u1 (SyntheticPlayerFactory/offlineAgentUuid "Karlach")
          u2 (SyntheticPlayerFactory/offlineAgentUuid "Karlach")
          u3 (SyntheticPlayerFactory/offlineAgentUuid "Picard")]
      (is (= u1 u2) "Mesmo nome deve gerar exatamente o mesmo UUID p/ preservar inventario/dados")
      (is (not= u1 u3)))))

(deftest ca-015-declarative-skin-profile-application-test
  (testing "CA-015: Aplicacao declarativa de textura/assinatura preenche propriedades de skin do perfil sem erro"
    (let [mock-profile (atom {:name "Karlach" :properties {}})
          res (skins/apply-skin-profile! mock-profile {:texture-value "eyJ0ZXh0dXJlcyI6..." :signature "sig123"})]
      (is (true? (:applied? res)))
      (is (= "eyJ0ZXh0dXJlcyI6..." (get-in @mock-profile [:properties "textures" :value]))))))

(deftest ca-016-server-side-20hz-motor-physics-test
  (testing "CA-016: Controlador motor aplica gravidade, corrida e pulo usando fisica do servidor sem teleporte artificial"
    (let [mock-player (atom {:x 0.0 :y 66.0 :z 0.0 :on-ground? false :sneaking? true})]
      (SyntheticMotorController/stepMotorTick mock-player 1.0 0.0 false true false 0.0 0.0)
      (is (false? (:sneaking? @mock-player)) "Deve limpar sneaking quando sneak=false")
      (is (< (:y @mock-player) 66.0) "Deve sofrer gravidade real quando no ar"))))

(deftest ca-017-double-gate-block-interaction-test
  (testing "CA-017: Interacao de bloco abre e fecha os dois blocos empilhados (y=65 e y=66) do portao duplo"
    (let [w (wf/make-flat-world-state 64)
          _ (wf/add-double-fence-gate! w [-171 65 171] {:id "pen-1" :open? false})]
      (is (true? (SyntheticInteraction/togglePortalBlock w -171 65 171 true)))
      (is (true? (SyntheticInteraction/togglePortalBlock w -171 66 171 true)))
      (is (true? (get-in @w [:blocks [-171 65 171] :open?])))
      (is (true? (get-in @w [:blocks [-171 66 171] :open?]))))))

(deftest ca-018-naturalist-and-vanilla-animal-feeding-test
  (testing "CA-018: Alimentar animal modded (naturalist:capybara) consome 1 item e ativa estado InLove"
    (let [player (atom {:inventory {"minecraft:apple" 3}})
          capybara (atom {:id :capy-1 :type "naturalist:capybara" :in-love? false :cooldown 0})]
      (is (true? (SyntheticInteraction/feedEntity player capybara "minecraft:apple")))
      (is (true? (:in-love? @capybara)))
      (is (= 2 (get-in @player [:inventory "minecraft:apple"]))))))

(deftest ca-019-jeg-modded-ranged-and-melee-combat-test
  (testing "CA-019: Disparo com arma JEG (jeg:assault_rifle / jeg:flamethrower) consome municao e causa dano sem erro de pacote"
    (let [antigravity (atom {:equipped "jeg:assault_rifle" :ammo {"jeg:rifle_ammo" 64}})
          ravager (atom {:type "minecraft:ravager" :health 100.0})]
      (is (true? (SyntheticCombat/fireRangedItem antigravity ravager "jeg:assault_rifle")))
      (is (< (:health @ravager) 100.0))
      (is (= 63 (get-in @antigravity [:ammo "jeg:rifle_ammo"]))))))

(deftest ca-020-atomic-chest-transfer-item-conservation-property-test
  (testing "CA-020: Qualquer sequencia de saques e depositos entre bot e bau preserva estritamente a soma total de itens"
    (let [prop-res
          (tc/quick-check
           60
           (prop/for-all [initial-chest (gen/choose 0 64)
                          initial-bot   (gen/choose 0 64)
                          amount        (gen/choose 1 32)
                          dir           (gen/elements [:withdraw :deposit])]
             (let [w (wf/make-flat-world-state 64)
                   _ (swap! w assoc-in [:chests [0 65 0] "minecraft:apple"] initial-chest)
                   body (wf/make-mock-body w {:id :karlach :inventory {"minecraft:apple" initial-bot}})
                   total-before (+ initial-chest initial-bot)
                   _ (fabric/atomic-chest-transfer! body [0 65 0] dir "minecraft:apple" amount)
                   chest-after (get-in @w [:chests [0 65 0] "minecraft:apple"] 0)
                   bot-after   (get-in (craft.agents.backend.protocol/body-state body) [:inventory "minecraft:apple"] 0)]
               (= total-before (+ chest-after bot-after)))))]
      (wf/assert-quick-check! prop-res))))

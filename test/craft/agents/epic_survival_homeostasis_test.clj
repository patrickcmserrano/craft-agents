(ns craft.agents.epic-survival-homeostasis-test
  "Testes comportamentais do Epico I: Homeostase, Fisiologia e Autopreservacao (CA-E1.1 a CA-E1.4)."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric :as fabric])
  (:import [com.craft.agents.fabric
            SyntheticCombat
            SyntheticCombat$HostileTarget
            SyntheticHostileDetector
            SyntheticItemCollector
            SyntheticItemCollector$DroppedItem
            SyntheticPhysiology
            SyntheticPhysiology$HealthStatus]))

(deftest ca-e1-1-physiology-and-auto-eat-test
  (testing "CA-E1.1: Fisiologia nativa (HP, food, saturation) e auto-eat in-JVM"
    (let [status (SyntheticPhysiology$HealthStatus. (float 18.5) 12 (float 3.5) true)]
      (is (= (float 18.5) (.hp status)))
      (is (= 12 (.foodLevel status)))
      (is (= (float 3.5) (.saturation status)))
      (is (true? (.isAlive status)))
      (is (= "OK|18.50|12|3.50|true" (.toProtocolString status))))

    (testing "Consumo simulado de comida restaura fome e consome item do inventario"
      (let [initial-state {"health" 14.0
                           "food" 10
                           "inventory" {"minecraft:apple" 3 "minecraft:stick" 2}}
            after-eat (SyntheticPhysiology/simulateConsumeFood initial-state "minecraft:apple")]
        (is (= 14 (get after-eat "food")) "Maca restaura 4 pontos de fome")
        (is (= 16.0 (get after-eat "health")) "Alimento regenera vida")
        (is (= 2 (get-in after-eat ["inventory" "minecraft:apple"])) "Item consumido do inventario")))

    (testing "Funcao query-health-status em Clojure faz parsing correto da resposta da bridge"
      (with-redefs [fabric/send-bridge-cmd! (fn [cmd]
                                              (if (= cmd "HEALTH_STATUS|Karlach")
                                                "OK|19.50|11|4.20|true"
                                                "ERR"))]
        (let [h (fabric/query-health-status "Karlach")]
          (is (= 19.5 (:hp h)))
          (is (= 11 (:food h)))
          (is (= 4.2 (:saturation h)))
          (is (true? (:alive? h))))))))

(deftest ca-e1-4-item-collector-dropped-items-scan-test
  (testing "CA-E1.4: Varredura de ItemEntity no chao sem packet lag"
    (let [drops [(SyntheticItemCollector$DroppedItem. 101 "minecraft:apple" 2 -170.0 65.0 172.0 2.45)
                 (SyntheticItemCollector$DroppedItem. 102 "minecraft:feather" 1 -171.0 65.0 173.0 3.80)]
          proto (SyntheticItemCollector/formatDropsProtocol drops)]
      (is (.startsWith proto "OK|2|"))
      (is (.contains proto "minecraft:apple"))
      (is (.contains proto "minecraft:feather")))

    (testing "locate-dropped-items faz parsing de drops em Clojure ordenados por proximidade"
      (with-redefs [fabric/send-bridge-cmd! (fn [cmd]
                                              (if (.startsWith cmd "LOCATE_ITEMS|Karlach|16.0|")
                                                "OK|2|101,minecraft:apple,2,-170.0,65.0,172.0,2.45;102,minecraft:feather,1,-171.0,65.0,173.0,3.80"
                                                "OK|0|"))]
        (let [items (fabric/locate-dropped-items "Karlach" {:radius 16.0})]
          (is (= 2 (count items)))
          (is (= "minecraft:apple" (:item (first items))))
          (is (= 2.45 (:distance (first items))))
          (is (= 2 (:count (first items)))))))))

(deftest ca-e1-3-tactical-combat-hostiles-and-cooldown-test
  (testing "CA-E1.3: Deteccao de hostis, medicao de cooldown e ataque sincronizado"
    (let [hostiles [(SyntheticCombat$HostileTarget. 201 "minecraft:spider" -168.0 65.0 171.0 3.20 (float 16.0))
                    (SyntheticCombat$HostileTarget. 202 "minecraft:zombie" -165.0 65.0 170.0 6.50 (float 20.0))]
          proto (SyntheticHostileDetector/formatHostilesProtocol hostiles)]
      (is (.startsWith proto "OK|2|"))
      (is (.contains proto "minecraft:spider"))
      (is (.contains proto "minecraft:zombie")))

    (testing "scan-nearby-hostiles faz parsing do alvo mais proximo em Clojure"
      (with-redefs [fabric/send-bridge-cmd! (fn [cmd]
                                              (if (.startsWith cmd "TARGET_HOSTILE|Karlach|16.0")
                                                "OK|1|201,minecraft:spider,-168.0,65.0,171.0,3.20,16.0"
                                                "OK|0|"))]
        (let [mobs (fabric/scan-nearby-hostiles "Karlach" 16.0)]
          (is (= 1 (count mobs)))
          (is (= "minecraft:spider" (:type (first mobs))))
          (is (= 3.2 (:distance (first mobs))))
          (is (= 201 (:id (first mobs)))))))

    (testing "attack-hostile-target! envia comando com cooldown carregado"
      (with-redefs [fabric/send-bridge-cmd! (fn [cmd]
                                              (if (= cmd "ATTACK_HOSTILE|Karlach|201")
                                                "OK|201|0.95"
                                                "ERR"))]
        (let [res (fabric/attack-hostile-target! "Karlach" 201)]
          (is (true? (:success? res)))
          (is (= 201 (:target-id res)))
          (is (= 0.95 (:cooldown res))))))))

(ns craft.agents.npc-spawn-test
  "CA-088 — Spawn e loadout guiados pelo registro `agents/*.edn`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.npc.registry :as reg]
            [craft.agents.npc.spawn :as spawn]))

(def skin {:value "V" :signature "S"})

(defn- agent-spec [id]
  (reg/reload! "agents")
  (reg/agent-by-id id))

(deftest karlach-igual-ao-comportamento-anterior
  (is (= ["SPAWN|Karlach|-175.0|65.0|180.0|V|S"
          "CMD|gamemode survival Karlach"
          "CMD|effect give Karlach minecraft:resistance infinite 4 true"
          "CMD|effect give Karlach minecraft:fire_resistance infinite 1 true"
          "CMD|item replace entity Karlach hotbar.0 with minecraft:netherite_pickaxe"
          "CMD|item replace entity Karlach weapon.mainhand with minecraft:netherite_pickaxe"]
         (spawn/spawn-cmds (agent-spec :karlach) skin))))

(deftest picard-igual-ao-comportamento-anterior
  (is (= ["SPAWN|Picard|-65.0|145.0|101.0|V|S"
          "CMD|gamemode survival Picard"
          "CMD|effect give Picard minecraft:resistance infinite 4 true"
          "CMD|item replace entity Picard armor.chest with minecraft:leather_chestplate[dyed_color=10031872]"
          "CMD|item replace entity Picard armor.legs with minecraft:netherite_leggings"
          "CMD|item replace entity Picard armor.feet with minecraft:netherite_boots"
          "CMD|item replace entity Picard weapon.mainhand with minecraft:spyglass"
          "CMD|item replace entity Picard weapon.offhand with minecraft:compass"]
         (spawn/spawn-cmds (agent-spec :picard) skin))))

(deftest coco-e-richeh-igual-ao-comportamento-anterior
  (is (= ["SPAWN|Coco|-30.0|66.0|190.0|V|S"
          "CMD|gamemode survival Coco"
          "CMD|effect give Coco minecraft:resistance infinite 4 true"
          "CMD|item replace entity Coco weapon.mainhand with minecraft:feather"
          "CMD|item replace entity Coco weapon.offhand with minecraft:writable_book"]
         (spawn/spawn-cmds (agent-spec :coco) skin)))
  (is (= ["SPAWN|Richeh|-30.0|66.0|192.0|V|S"
          "CMD|gamemode survival Richeh"
          "CMD|effect give Richeh minecraft:resistance infinite 4 true"
          "CMD|item replace entity Richeh weapon.mainhand with minecraft:amethyst_shard"
          "CMD|item replace entity Richeh weapon.offhand with minecraft:glow_ink_sac"]
         (spawn/spawn-cmds (agent-spec :richeh) skin))))

(deftest spec-sintetica-cobre-slots-e-efeitos-opcionais
  (let [spec {:agent/id :x :agent/name "Xis"
              :agent/spawn {:dimension "minecraft:overworld" :pos [1.0 2.0 3.0]}
              :agent/loadout {:offhand "minecraft:shield" :hotbar.3 "minecraft:bread"
                              :mainhand "minecraft:stick"}}]
    (testing "sem :agent/effects nao ha comandos de efeito; skin ausente fica vazia"
      (is (= ["SPAWN|Xis|1.0|2.0|3.0||"
              "CMD|gamemode survival Xis"
              "CMD|item replace entity Xis hotbar.3 with minecraft:bread"
              "CMD|item replace entity Xis weapon.mainhand with minecraft:stick"
              "CMD|item replace entity Xis weapon.offhand with minecraft:shield"]
             (spawn/spawn-cmds spec))))
    (testing "sem loadout so SPAWN e gamemode"
      (is (= ["SPAWN|Xis|1.0|2.0|3.0|V|S" "CMD|gamemode survival Xis"]
             (spawn/spawn-cmds (dissoc spec :agent/loadout) skin))))))

(deftest registro-declara-efeitos-e-skin-file
  (is (= "karlach_meta.json" (spawn/skin-file (agent-spec :karlach))))
  (is (nil? (spawn/skin-file (agent-spec :antigravity))))
  (is (some? (:agent/effects (agent-spec :karlach)))))

(deftest spawn-agents-itera-o-registro
  (reg/reload! "agents")
  (let [sent (atom [])
        names (spawn/spawn-agents! #(do (swap! sent conj %) "OK") (constantly skin))]
    (testing "antigravity (spawn feito em outro lugar) fica de fora"
      (is (= #{"Karlach" "Picard" "Coco" "Richeh"} (set names))))
    (is (every? string? @sent))
    (is (= 4 (count (filter #(str/starts-with? % "SPAWN|") @sent))))
    (is (not-any? #(str/includes? % "Antigravity") @sent))))

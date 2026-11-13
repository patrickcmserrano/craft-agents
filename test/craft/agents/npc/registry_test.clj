(ns craft.agents.npc.registry-test
  "CA-086 — Registro de agentes com schema Malli (agents/*.edn)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [craft.agents.npc.registry :as reg]))

(defn- tmp-dir []
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "agents-reg" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit d)
    d))

(deftest carrega-todos-os-agentes-validos
  (let [{:keys [agents errors]} (reg/load-agents "agents")]
    (is (empty? errors))
    (is (= #{:antigravity :coco :karlach :picard :richeh} (set (keys agents))))
    (doseq [[id spec] agents]
      (is (m/validate reg/AgentSpec spec) (str id " deve validar pelo AgentSpec")))))

(deftest ignora-subdiretorios
  (let [{:keys [agents errors]} (reg/load-agents "agents")]
    (is (empty? errors))
    (is (not-any? #(contains? #{:sanctuary :shelter-from-rain :detectar} %) (keys agents)))))

(deftest agent-by-name-aceita-qualquer-caixa
  (reg/reload! "agents")
  (is (= :karlach (:agent/id (reg/agent-by-name "karlach"))))
  (is (= :karlach (:agent/id (reg/agent-by-name "Karlach"))))
  (is (= :karlach (:agent/id (reg/agent-by-name "KARLACH"))))
  (is (nil? (reg/agent-by-name "ninguem"))))

(deftest agent-by-id-e-agent-names
  (reg/reload! "agents")
  (is (= "Coco" (:agent/name (reg/agent-by-id :coco))))
  (is (nil? (reg/agent-by-id :nao-existe)))
  (is (= #{"Antigravity" "Coco" "Karlach" "Picard" "Richeh"} (set (reg/agent-names)))))

(deftest campos-novos-nos-edn-existentes
  (reg/reload! "agents")
  (let [k (reg/agent-by-id :karlach)]
    (is (= :fight (:agent/combat-policy k)))
    (is (= "block.campfire.crackle" (get-in k [:agent/voice :sound])))
    (is (string? (get-in k [:agent/voice :color]))))
  (is (= :flee (:agent/combat-policy (reg/agent-by-id :coco))))
  (is (= :flee (:agent/combat-policy (reg/agent-by-id :richeh))))
  (doseq [id [:karlach :coco :richeh :picard]]
    (is (map? (:agent/voice (reg/agent-by-id id))) (str id " tem voz"))
    (is (string? (:agent/skin (reg/agent-by-id id))) (str id " tem skin"))))

(deftest schema-valida-campos-novos
  (let [base {:agent/id :x :agent/name "X"
              :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 64.0 0.0]}}]
    (is (m/validate reg/AgentSpec base))
    (is (m/validate reg/AgentSpec
                    (assoc base
                           :agent/voice {:color "red" :sound "block.x" :radius 12.0}
                           :agent/combat-policy :none
                           :agent/capabilities #{:escort :social}
                           :agent/skin "karlach")))
    (is (not (m/validate reg/AgentSpec (assoc base :agent/combat-policy :berserk))))
    (is (not (m/validate reg/AgentSpec (dissoc base :agent/spawn))))))

(deftest edn-invalido-gera-erro-sem-derrubar-os-outros
  (let [d (tmp-dir)]
    (spit (io/file d "bom.edn")
          (pr-str {:agent/id :bom :agent/name "Bom"
                   :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 64.0 0.0]}}))
    (spit (io/file d "ruim.edn") (pr-str {:agent/name "Ruim"}))
    (let [{:keys [agents errors]} (reg/load-agents (.getPath d))]
      (is (= [:bom] (keys agents)))
      (is (= 1 (count errors)))
      (let [{:keys [file message]} (first errors)]
        (is (str/includes? file "ruim.edn"))
        (is (str/includes? message ":agent/id"))))))

(deftest reload-troca-o-registro
  (let [d (tmp-dir)]
    (spit (io/file d "a.edn")
          (pr-str {:agent/id :a :agent/name "Alfa"
                   :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 64.0 0.0]}}))
    (reg/reload! (.getPath d))
    (is (= ["Alfa"] (vec (reg/agent-names))))
    (is (some? (reg/agent-by-name "alfa")))
    (reg/reload! "agents")
    (is (nil? (reg/agent-by-name "alfa")))))

(ns craft.agents.npc-bridge-world-test
  "CA-095 — Mundo do agente por nome nos ops da ponte (PATH/GROUND/BLOCK/TOGGLE_PORTAL).
   O campo opcional `agent=<Nome>` no fim do comando escolhe o jogador; sem ele o padrao e Karlach."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :as transport]))

(defn- agent-name [parts]
  (let [cls (Class/forName "com.craft.agents.nav.BridgeAgentArg")
        m   (.getMethod cls "agentName" (into-array Class [(Class/forName "[Ljava.lang.String;")]))]
    (.invoke m nil (object-array [(into-array String parts)]))))

(defn- is-agent-field? [s]
  (let [cls (Class/forName "com.craft.agents.nav.BridgeAgentArg")
        m   (.getMethod cls "isAgentField" (into-array Class [String]))]
    (.invoke m nil (object-array [s]))))

(deftest extrai-agente-do-fim-do-comando
  (is (= "Picard" (agent-name (.split "PATH|1,2,3|4,5,6|1.2|agent=Picard" "\\|"))))
  (is (= "Richeh" (agent-name (.split "GROUND|1|2|3|agent=Richeh" "\\|"))))
  (is (= "Picard" (agent-name (.split "TOGGLE_PORTAL|1|2|3|true|agent=Picard" "\\|")))))

(deftest sem-campo-o-padrao-e-karlach
  (is (= "Karlach" (agent-name (.split "PATH|1,2,3|4,5,6|1.2" "\\|"))))
  (is (= "Karlach" (agent-name (.split "BLOCK|1|2|3" "\\|"))))
  (is (= "Karlach" (agent-name (.split "TOGGLE_PORTAL|1|2|3|false" "\\|"))))
  (testing "campo vazio tambem cai no padrao"
    (is (= "Karlach" (agent-name (.split "GROUND|1|2|3|agent=" "\\|"))))))

(deftest reconhece-campo-de-agente
  (is (true? (is-agent-field? "agent=Picard")))
  (is (false? (is-agent-field? "1.2")))
  (is (false? (is-agent-field? "true"))))

(deftest raw-request-path3d-anexa-agent-name
  (let [sent (atom nil)]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (reset! sent c) "ERR|NO_PATH")]
      (nav/raw-request-path3d [1.0 2.0 3.0] [4.0 5.0 6.0] 1.2 :agent-name "Picard")
      (is (= "PATH|1.0,2.0,3.0|4.0,5.0,6.0|1.2|agent=Picard" @sent))
      (nav/raw-request-path3d [1.0 2.0 3.0] [4.0 5.0 6.0] 1.2)
      (is (= "PATH|1.0,2.0,3.0|4.0,5.0,6.0|1.2" @sent)))))

(deftest query-ground-y-anexa-agent-name
  (let [sent (atom nil)]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (reset! sent c) "OK|64.0")]
      (is (= 64.0 (nav/query-ground-y [1.5 2.5 3.5] :agent-name "Picard")))
      (is (= "GROUND|1|2|3|agent=Picard" @sent))
      (nav/query-ground-y [1.5 2.5 3.5])
      (is (= "GROUND|1|2|3" @sent)))))

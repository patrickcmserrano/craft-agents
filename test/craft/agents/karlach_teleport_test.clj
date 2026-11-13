(ns craft.agents.karlach-teleport-test
  "Karlach e um ServerPlayerEntity sintetico: /tp loga 'Teleported' mas nao a move
   (sem ack de teleporte do cliente). Teleporte tem que ir pelo op MOVE da bridge,
   senao o follow (>48 m) dispara /tp em loop infinito."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.engine :as engine]
            [craft.agents.backend.fabric.transport :as transport]))

(defn- capture [f]
  (let [sent (atom [])]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! sent conj c) "OK")]
      (f))
    @sent))

(deftest teleport-agent-uses-bridge-move-test
  (let [sent (capture #(actions/teleport-agent-to! "Karlach" [167.5 184.0 -105.25]))]
    (is (= "MOVE|Karlach|167.500|184.000|-105.250|0.0|0.0|false|false" (first sent)))
    (is (not-any? #(str/starts-with? % "CMD|tp ") sent))))

(deftest teleport-karlach-to-leader-uses-move-test
  (let [nav (atom {:pos [-150.0 107.0 -89.0] :follow-path [[1 2 3]]})
        sent (capture #(engine/teleport-karlach-to-leader! nav "pace" [167.0 184.0 -105.0]))]
    (testing "move real pela bridge, nunca /tp"
      (is (some #(str/starts-with? % "MOVE|Karlach|167.000|184.000|-105.000|") sent))
      (is (not-any? #(str/starts-with? % "CMD|tp ") sent)))
    (testing "ancora nav-state no lider"
      (is (= [167.0 184.0 -105.0] (:pos @nav)))
      (is (= [] (:follow-path @nav))))))

(deftest no-vanilla-tp-of-karlach-in-sources-test
  (doseq [f ["src/clj/craft/agents/backend/fabric/engine.clj"
             "src/clj/craft/agents/backend/fabric/goals.clj"]]
    (is (not (str/includes? (slurp (io/file f)) "CMD|tp Karlach"))
        (str f " ainda usa /tp vanilla na Karlach"))))

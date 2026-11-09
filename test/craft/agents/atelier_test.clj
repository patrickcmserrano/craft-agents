(ns craft.agents.atelier-test
  "Testes da dinâmica comportamental de Coco e Richeh no Ateliê dos Touros Negros."
  (:require [clojure.test :refer [deftest is testing]]
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

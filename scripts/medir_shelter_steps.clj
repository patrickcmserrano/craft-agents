#!/usr/bin/env clojure
;; Sonda reexecutavel: o que o shelter-route-steps devolve, de facto, hoje.
;; Mede os ops e os :wait da ida ao quarto, com e sem from-pos, para o teste
;; CA-060o poder descrever o comportamento REAL (o CA-061 encurtou o trajeto).
;;
;; Uso: clojure -M:dev scripts/medir_shelter_steps.clj

(ns medir-shelter-steps
  (:require [craft.agents.backend.fabric :as fabric]))

(defn- resumo [label steps]
  (let [ops   (map :op steps)
        waits (filter #(= (:op %) :wait) steps)
        waits-lab (map :label waits)]
    (println "==" label "==")
    (println "  n-steps:" (count steps))
    (println "  ops:" (vec (distinct ops)))
    (println "  n-waits:" (count waits))
    (println "  waits:" (vec waits-lab))
    (println)))

(defn -main [& _]
  (let [route    (fabric/build-sanctuary-route)
        quarters (some #(when (= (:type %) :quarters) %) route)]
    (println "quarters-stop:" (select-keys quarters [:type :name :pos]))
    (println)
    (resumo "COM from-pos [-179.5 65.0 171.5]  (o que o teste CA-060o usa)"
            (fabric/shelter-route-steps quarters [-179.5 65.0 171.5]))
    (resumo "SEM from-pos (caminho por :waypoints)"
            (fabric/shelter-route-steps quarters nil))
    (println "NOTA: com from-pos, expand-route-microsteps gera os :wait de portao")
    (println "e tranca alem do :wait de Descanso — o trajeto corta no Descansando.")))

(apply -main *command-line-args*)

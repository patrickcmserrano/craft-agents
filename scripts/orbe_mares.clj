#!/usr/bin/env clojure
;;; orbe_mares.clj — entrega o ORBE DAS MARÉS (Torrente Aquática / Extinção)
;;; ao jogador por /give, pela ponte in-server (25595). O item é um heart_of_the_sea
;;; marcado com custom_data {varinha_magica:1b} e durabilidade 64 (as cargas).
;;; O clique direito dispara uma torrente de água na direção da mira, extingue
;;; todo fogo no raio de 3 blocos, zera chamas (Fire:0s) de jogadores/entidades molhadas,
;;; causa dano de afogamento em área (35 drown) e dano crítico extra em blazes (50 drown).
;;;
;;; Uso:
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m orbe-mares          # dá ao pace
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m orbe-mares --target Lizzie
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m orbe-mares --n 2
(ns orbe-mares
  (:require [clojure.string :as str]
            [entity-state :as es]))

(def ^:private ^:const host "127.0.0.1")
(def ^:private ^:const port 25595)

(defn- item-nbt []
  (str "minecraft:heart_of_the_sea["
       "custom_data={varinha_magica:1b},"
       "food={nutrition:0,saturation:0,can_always_eat:true,eat_seconds:1000000},"
       "max_damage=64,"
       "damage=0,"
       "max_stack_size=1"
       "]"))

(defn- parse-args [args]
  (loop [m {:target "pace" :n 1} [a & more] args]
    (cond
      (nil? a) m
      (= a "--target") (recur (assoc m :target (first more)) (rest more))
      (= a "--n")      (recur (assoc m :n (first more)) (rest more))
      :else (recur m more))))

(defn- enviar! [cmd]
  (let [r (es/send-cmd (str "CMD|" cmd) host port 5.0)]
    (println (format "  %-60s -> %s" cmd (pr-str r)))
    r))

(defn -main [& args]
  (let [{:keys [target n]} (parse-args args)
        cmd (str "give " target " " (item-nbt) " " n)]
    (println (format "== Orbe das Marés para %s (x%s) ==" target n))
    (enviar! cmd)
    (println)
    (println "Confira a mão com:")
    (println (format "  clojure -Sdeps '{:paths [\"scripts\"]}' -M -e '(require (quote [entity-state :as es])) (println (es/send-cmd \"HELD_ITEM|%s\"))'" target))
    (shutdown-agents)))

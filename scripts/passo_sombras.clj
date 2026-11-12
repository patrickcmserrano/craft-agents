#!/usr/bin/env clojure
;;; passo_sombras.clj — entrega o PASSO DAS SOMBRAS (Ender Dash / Teleporte Tatico)
;;; ao jogador por /give, pela ponte in-server (25595). O item e um echo_shard marcado
;;; com custom_data {varinha_magica:1b} e durabilidade 64 (as cargas).
;;; O clique direito calcula o ponto a N blocos na direcao do LOOK (ou antes de obstaculo
;;; solido detectado pelo raycast), executa teleporte instantaneo mantendo a rotacao da
;;; camera, concede 2s de resistencia e invisibilidade, e emite som de enderman e nuvens
;;; densas de portal e reverse_portal.
;;;
;;; Uso:
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m passo-sombras          # da ao pace
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m passo-sombras --target Lizzie
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m passo-sombras --n 2
(ns passo-sombras
  (:require [clojure.string :as str]
            [entity-state :as es]))

(def ^:private ^:const host "127.0.0.1")
(def ^:private ^:const port 25595)

(defn- item-nbt []
  (str "minecraft:echo_shard["
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
    (println (format "== Passo das Sombras para %s (x%s) ==" target n))
    (enviar! cmd)
    (println)
    (println "Confira a mao com:")
    (println (format "  clojure -Sdeps '{:paths [\"scripts\"]}' -M -e '(require (quote [entity-state :as es])) (println (es/send-cmd \"HELD_ITEM|%s\"))'" target))
    (shutdown-agents)))

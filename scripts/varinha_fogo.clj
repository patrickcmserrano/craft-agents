#!/usr/bin/env clojure
;;; varinha_fogo.clj — entrega a VARINHA DE FOGO ao jogador por /give, pela
;;; ponte in-server (25595). O item e um blaze_rod marcado com custom_data
;;; {varinha_fogo:1b} e durabilidade 8 (a barra = as cargas); cada uso soma 1 ao
;;; damage e no 8o uso a varinha e removida por clear condicional. O data pack
;;; fireball_item gasta a carga no clique e o engine (polling do op HELD_ITEM)
;;; dispara a magia na mira.
;;;
;;; A base e blaze_rod, NAO blaze_powder: MEDIDO em 2026-11-11 que o clique do
;;; jogador num blaze_powder nao produzia NENHUMA linha no journal — o
;;; blaze_powder nao tem acao de uso, o cliente nao envia o pacote "use item" e
;;; o advancement using_item nunca dispara. O blaze_rod tem animacao de uso.
;;;
;;; Uso:
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m varinha-fogo          # da a varinha ao pace
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m varinha-fogo --target Lizzie
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m varinha-fogo --n 2
(ns varinha-fogo
  (:require [clojure.string :as str]
            [entity-state :as es]))

(def ^:private ^:const host "127.0.0.1")
(def ^:private ^:const port 25595)

(defn- item-nbt
  "O componente do item: varinha de fogo com durabilidade 8 (= as cargas).
   ARMADILHAS do /give no 1.21.1, ambas MEDIDAS no log do servidor:
   - `max_damage` exige `max_stack_size=1`, senao o servidor recusa com
     'Item cannot be both damageable and stackable' (o blaze_rod e stackable);
   - `custom_name`/`lore` recusam a forma de string crua ('Not a string') e a de
     objeto JSON ('MalformedJsonException') por causa das camadas de escaping
     (shell -> ponte -> comando). Ficam de fora por agora — o essencial (barra de
     cargas + marca) nao depende deles. O nome visivel vem da data pack/lore."
  []
  (str "minecraft:blaze_rod["
       "custom_data={varinha_fogo:1b},"
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
    (println (format "== varinha de fogo para %s (x%s) ==" target n))
    (enviar! cmd)
    (println)
    (println "Confira a mao com:")
    (println (format "  clojure -Sdeps '{:paths [\"scripts\"]}' -M -e '(require (quote [entity-state :as es])) (println (es/send-cmd \"HELD_ITEM|%s\"))'" target))
    (shutdown-agents)))

;; Sem (apply -main ...) — rodar com `-M -m varinha-fogo` ja invoca o -main.

#!/usr/bin/env clojure
;;; Varredura de VOLUME de blocos pela ponte in-server (25595) e remocao por
;;; /fill, para apagar estruturas construidas (ex.: as "naves Enterprise", que
;;; NAO sao entidades — sao blocos, e por isso exigem /fill e nao DESPAWN).
;;;
;;; Nasceu de um erro: a primeira remocao usou uma caixa inferida da GRADE de
;;; um unico plano (y=123) e deixou resto para fora da caixa. A grade de um
;;; plano nao mede o volume. Aqui a varredura e do volume inteiro, incluindo
;;; blocos FORA da caixa, e a remocao so e declarada pronta quando o volume
;;; largo volta a ser ar.
;;;
;;; O que a ponte expoe (e so isso — o que nao existe nao se inventa):
;;;   BLOCK|x|y|z   -> OK|<id>|<BlockState toString>   (leitura por coordenada)
;;;   CMD|<comando> -> OK                              (despacho com op)
;;; O OK do CMD NAO prova execucao; a prova e reler os blocos.
;;;
;;; Uso:
;;;   # inventario de um volume (agrupa por material, com bounds por material)
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m ship-volume scan -195 118 52 -158 126 84
;;;
;;;   # varredura LARGA em busca de resto (procura fora da caixa antiga)
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m ship-volume scan -215 110 35 -140 136 100
;;;
;;;   # apagar a caixa medida e CONFIRMAR (revarre a mesma caixa)
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m ship-volume clear -195 118 52 -158 126 84
;;;
;;;   # apagar sem confirmar ao vivo (nao recomendado)
;;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m ship-volume clear -195 118 52 -158 126 84 --no-verify
(ns ship-volume
  (:require [clojure.string :as str]
            [entity-state :as es]))

(def ^:private ^:const host "127.0.0.1")
(def ^:private ^:const port 25595)
(def ^:private ^:const air #{"" "air" "cave_air" "void_air"})

(defn- air? [b] (contains? air (str/replace (str b) "minecraft:" "")))

(defn- block-id
  "Le o id do bloco na coordenada pela ponte. nil se a leitura falhar."
  [host port timeout x y z]
  (let [resp (es/send-cmd (str "BLOCK|" x "|" y "|" z) host port timeout)]
    (when (and resp (str/starts-with? resp "OK|"))
      (let [parts (str/split resp #"\|")]
        (when (>= (count parts) 2)
          (str/replace (nth parts 1) "minecraft:" ""))))))

(defn- cells-in-box
  "Todas as (x y z) do paralelepipedo inclusivo, em ordem y-major."
  [x1 y1 z1 x2 y2 z2]
  (let [xs (range (min x1 x2) (inc (max x1 x2)))
        ys (range (min y1 y2) (inc (max y1 y2)))
        zs (range (min z1 z2) (inc (max z1 z2)))]
    (for [y ys x xs z zs] [x y z])))

(defn- scan-box
  "Varre a caixa e devolve {:total n :hits [[x y z id] ...]} para blocos nao-ar."
  [host port timeout [x1 y1 z1 x2 y2 z2]]
  (let [cells (cells-in-box x1 y1 z1 x2 y2 z2)]
    (reduce (fn [acc [x y z]]
              (let [b (block-id host port timeout x y z)]
                (if (air? b)
                  (update acc :total inc)
                  (-> acc
                      (update :total inc)
                      (update :hits conj [x y z b])))))
            {:total 0 :hits []}
            cells)))

(defn- summarize [hits]
  (let [by-mat (group-by #(nth % 3) hits)]
    (into (sorted-map)
          (map (fn [[mat pts]]
                 (let [xs (map first pts) ys (map second pts) zs (map #(nth % 2) pts)]
                   [mat {:count (count pts)
                         :x [(apply min xs) (apply max xs)]
                         :y [(apply min ys) (apply max ys)]
                         :z [(apply min zs) (apply max zs)]
                         :sample (take 6 pts)}])))
          by-mat)))

(defn- print-scan [label hits total]
  (println (format "%s: %d celulas varridas, %d NAO-ar" label total (count hits)))
  (doseq [[mat {:keys [count x y z sample]}] (summarize hits)]
    (println (format "  %-28s %5d  x%d..%d y%d..%d z%d..%d  ex=%s"
                     mat count (first x) (second x) (first y) (second y)
                     (first z) (second z) (pr-str (vec sample))))))

;; ---------------------------------------------------------------- comandos

(defn cmd-scan [args]
  (let [[x1 y1 z1 x2 y2 z2] (map #(Long/parseLong %) args)
        {:keys [total hits]} (scan-box host port 2.5
                                       [x1 y1 z1 x2 y2 z2])]
    (print-scan (format "SCAN (%d,%d,%d)..(%d,%d,%d)" x1 y1 z1 x2 y2 z2) hits total)))

(defn cmd-clear [args]
  (let [no-verify? (some #{"--no-verify"} args)
        nums (remove #(str/starts-with? % "--") args)
        [x1 y1 z1 x2 y2 z2] (map #(Long/parseLong %) nums)
        box [x1 y1 z1 x2 y2 z2]]
    (let [{:keys [hits] :as before} (scan-box host port 2.5 box)]
      (print-scan "ANTES" hits (:total before))
      (println (format "FILL|/fill %d %d %d %d %d %d air replace" x1 y1 z1 x2 y2 z2))
      (println "  ->" (es/send-cmd (str "CMD|/fill " x1 " " y1 " " z1 " " x2 " " y2 " " z2 " air replace")
                                   host port 20))
      (when-not no-verify?
        (Thread/sleep 2000)
        (let [{:keys [hits] :as after} (scan-box host port 2.5 box)]
          (print-scan "DEPOIS (mesma caixa)" hits (:total after))
          (println (if (empty? hits) "RESULTADO: caixa limpa" "RESULTADO: AINDA HA BLOCOS NA CAIXA")))))))

(defn -main [& args]
  (let [[mode & more] args]
    (case mode
      "scan"  (cmd-scan more)
      "clear" (cmd-clear more)
      (do (println "uso: scan|clear x1 y1 z1 x2 y2 z2 [--no-verify]")
          (System/exit 2))))
  (shutdown-agents))

#!/usr/bin/env clojure
;; Sonda: o alvo mais proximo esta DENTRO do cone de mira do pace?
;;
;; Junta as duas leituras da ponte 25595 que select-aim-target usa:
;;   PLAYERS         -> origem (pes do jogador)
;;   RAYCAST_BLOCK   -> direcao (primeiro bloco solido na linha do olhar)
;;   TARGET_HOSTILE  -> alvos (id,tipo,x,y,z,dist,hp; o 1o e o mais proximo)
;; e calcula o ANGULO entre a linha do olhar e o vetor ao hostil, contra o
;; cone de 20 graus (aim-cone-deg). Angulo > 20 = a magia NAO sai por desenho.
;;
;; Uso:
;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m diagnostico_mira pace 20
;;
;; Nota: os y de entidade vem como pe; somamos ~1.0 ao hostil e ~1.62 ao
;; jogador (altura dos olhos) para o centro. Numeros sao do relatorio, nao
;; inventados: se faltar leitura, a sonda diz INDETERMINADO.

(ns diagnostico-mira
  (:require [clojure.string :as str]
            [entity-state :as es]))

(def ^:private host "127.0.0.1")
(def ^:private port 25595)

(defn- cmd [c] (es/send-cmd c host port 2.5))

(defn- campos [s] (str/split (str s) #"\|"))

(defn- players []
  (let [r (cmd "PLAYERS")]
    ;; PLAYERS devolve "OK|nome,x,y,z;nome,..." — prefixo "OK|" (3 chars).
    ;; (O CHAT_POLL usa "OK;;" com 4 — nao confundir os dois prefixos.)
    (into {} (for [linha (str/split (subs (str r) 3) #";")
                   :let [c (str/split linha #",")]
                   :when (= 4 (count c))]
               [(first c) (mapv #(Double/parseDouble %) (rest c))]))))

(defn- hostil [nome raio]
  (let [r (cmd (str "TARGET_HOSTILE|" nome "|" raio))
        c (campos r)]
    (when (and (= "OK" (first c)) (>= (count c) 2) (not= "0" (second c)))
      (let [p (str/split (nth c 2) #",")]
        {:id (nth p 0) :tipo (nth p 1)
         :pos (mapv #(Double/parseDouble %) (subvec p 2 5))
         :dist (Double/parseDouble (nth p 5))
         :hp (nth p 6)}))))

(defn- raycast [nome dist]
  (let [c (campos (cmd (str "RAYCAST_BLOCK|" nome "|" dist)))]
    (when (and (= "OK" (first c)) (>= (count c) 6))
      {:bloco (second c) :pos (mapv #(Double/parseDouble %) (subvec c 2 5))})))

(defn- sub [a b] (mapv - a b))
(defn- dot [a b] (reduce + (map * a b)))
(defn- mag [a] (Math/sqrt (dot a a)))
(defn- angle-deg [a b]
  (let [m (* (mag a) (mag b))]
    (if (< m 1e-9) nil
        (/ (* 180.0 (Math/acos (max -1.0 (min 1.0 (/ (dot a b) m))))) Math/PI))))

(defn -main [& args]
  (let [nome (or (first args) "pace")
        raio (Double/parseDouble (or (second args) "20"))
        cone 20.0
        ps   (players)
        pes  (get ps nome)
        rc   (raycast nome 60.0)
        h    (hostil nome raio)]
    (println "== diagnostico de mira:" nome "(raio" raio ") ==")
    (println "pes       :" (pr-str pes))
    (println "raycast   :" (pr-str rc))
    (println "hostil    :" (pr-str h))
    (println)
    (if (and pes rc h)
      (let [olhos    [(first pes) (+ (second pes) 1.62) (nth pes 2)]
            dir      (sub (:pos rc) olhos)
            v        (sub (mapv + (:pos h) [0.0 1.0 0.0]) olhos)
            ang      (angle-deg dir v)]
        (println "olhos     :" (mapv #(Double/parseDouble (format "%.2f" %)) olhos))
        (println "dir olhar :" (mapv #(Double/parseDouble (format "%.2f" %)) dir))
        (println "vetor alvo:" (mapv #(Double/parseDouble (format "%.2f" %)) v))
        (println "ANGULO    :" (format "%.2f" (double ang)) "graus  (cone" cone ")")
        (println)
        (println (if (<= (double ang) cone)
                   "RESULTADO: DENTRO do cone — a magia DEVERIA sair. Se nao saiu, e defeito."
                   "RESULTADO: FORA do cone — a magia NAO sai por desenho (mire mais perto do alvo).")))
      (println "RESULTADO: INDETERMINADO — falta leitura (sem hostil, sem raycast ou jogador ausente)."))))

;; Sem (apply -main ...): correr com `-M -m diagnostico-mira` ja invoca o -main.

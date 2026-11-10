#!/usr/bin/env clojure
;;; Le o estado VIVO de uma entidade do servidor pela ponte in-server (25595).
;;;
;;; Versao Clojure do scripts/entity_state.py.
;;;
;;; POR QUE NAO A PORTA 7890 (nREPL in-server): ela roda no KnotClassLoader e
;;; alcanca `net.minecraft.*`, mas NAO ve a instancia viva do servidor. Medido em
;;; 2026-11-10: as statics de `SyntheticPlayerFactory` (BRIDGE_RUNNING,
;;; `cachedServer`) leem false/null mesmo com a ponte respondendo PLAYERS com
;;; posicoes ao vivo — e `MinecraftServer` nao tem campo estatico com a
;;; instancia do servidor. O caminho que MEDE o estado vivo e a ponte.
;;;
;;; O que a ponte expoe por entidade:
;;;   PLAYERS                -> OK|nome,x,y,z;nome,x,y,z;...
;;;   HEALTH_STATUS|nome      -> OK|hp|food|saturation|isAlive
;;; Posicao e saude cobrem "anda" vs "esta presa" (o discriminador do debug da
;;; Karlach); pose/motion/water NAO existem como op da ponte, entao nao se
;;; inventa — o que nao e medivel pela ponte nao entra aqui.
;;;
;;; Uso:
;;;     clojure -Sdeps '{:paths ["scripts"]}' -M -m entity-state Karlach
;;;     clojure -Sdeps '{:paths ["scripts"]}' -M -m entity-state --all --n 6 --interval 1.5
;;;     clojure -Sdeps '{:paths ["scripts"]}' -M -m entity-state Karlach --port 25595
(ns entity-state
  (:require [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter PrintWriter]
           [java.net Socket InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def ^:private ^:const default-host "127.0.0.1")
(def ^:private ^:const default-port 25595)

(defn- bridge-send
  "Envia uma linha de comando a ponte e devolve a linha de resposta (nil no EOF)."
  [^String host ^long port line timeout]
  (with-open [sock (Socket.)]
    (.connect sock (InetSocketAddress. host (int port)) (int (* 1000 timeout)))
    (.setSoTimeout sock (int (* 1000 timeout)))
    (let [in  (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))
          out (PrintWriter. (OutputStreamWriter. (.getOutputStream sock) StandardCharsets/UTF_8) true)]
      (.println out (str line))
      (.readLine in))))

(defn- parse-players
  "`OK|nome,x,y,z;...` -> {\"nome\" {:x .. :y .. :z ..}}. Vazio se nao houver OK."
  [resp]
  (when (and resp (str/starts-with? resp "OK|"))
    (into {}
          (keep (fn [entry]
                  (let [[nm x y z] (str/split entry #",")]
                    (when (and nm x y z)
                      [nm {:x (Double/parseDouble x)
                           :y (Double/parseDouble y)
                           :z (Double/parseDouble z)}]))))
          (remove str/blank? (str/split (subs resp 3) #";")))))

(defn- read-players [host port timeout]
  (parse-players (bridge-send host port "PLAYERS" timeout)))

(defn- read-health [host port nm timeout]
  (let [resp (bridge-send host port (str "HEALTH_STATUS|" nm) timeout)]
    (when (and resp (str/starts-with? resp "OK|"))
      (let [[hp food sat alive] (str/split (subs resp 3) #"\|")]
        {:hp hp :food food :saturation sat :alive alive}))))

(defn- state-line [host port nm timeout]
  (let [p ((read-players host port timeout) nm)
        h (read-health host port nm timeout)]
    (cond
      (nil? p) "ENTITY_NOT_FOUND"
      :else (str (format "pos=[%.3f,%.3f,%.3f]" (:x p) (:y p) (:z p))
                 (when h (format "  hp=%s food=%s sat=%s alive=%s"
                                 (:hp h) (:food h) (:saturation h) (:alive h)))))))

(defn probe
  "Le o estado de `nm` pela ponte. Devolve uma string de estado (nunca lanca)."
  ([nm] (probe nm default-host default-port 2.5))
  ([nm host port timeout]
   (try
     (state-line host port nm timeout)
     (catch Throwable e (str "ERR " (.getMessage e))))))

(defn- parse-args [args]
  (loop [m {:host default-host :port default-port :timeout 2.5
            :n 1 :interval 1.0 :all false :entity "Karlach"}
         [a & more] args]
    (cond
      (nil? a) m
      (= a "--host") (recur (assoc m :host (first more)) (rest more))
      (= a "--port") (recur (assoc m :port (Long/parseLong (first more))) (rest more))
      (= a "--timeout") (recur (assoc m :timeout (Double/parseDouble (first more))) (rest more))
      (= a "--n") (recur (assoc m :n (Long/parseLong (first more))) (rest more))
      (= a "--interval") (recur (assoc m :interval (Double/parseDouble (first more))) (rest more))
      (= a "--all") (recur (assoc m :all true) more)
      :else (recur (assoc m :entity a) more))))

(defn -main [& args]
  (let [{:keys [host port timeout n interval all entity]} (parse-args args)
        names (if all ["Karlach" "Picard" "Coco" "Richeh"] [entity])]
    (loop [i 0 prev {}]
      (when (< i n)
        (let [prev' (reduce
                     (fn [acc nm]
                       (let [line (probe nm host port timeout)
                             changed (and (contains? prev nm)
                                          (not= line (get prev nm)))]
                         (println (format "[%d] %-8s %s%s" i nm line
                                          (if changed "   <-- MUDOU" "")))
                         (assoc acc nm line)))
                     prev names)]
          (when (< (inc i) n)
            (Thread/sleep (long (* 1000 interval))))
          (recur (inc i) prev'))))
    (shutdown-agents)))

(defn read-entity
  "API publica: mapa {:name :pos {:x :y :z} :health {...}} ou {:error ...}."
  ([nm] (read-entity nm default-host default-port 2.5))
  ([nm host port timeout]
   (try
     (let [p ((read-players host port timeout) nm)]
       (if p
         {:name nm :pos p :health (read-health host port nm timeout)}
         {:name nm :error "ENTITY_NOT_FOUND"}))
     (catch Throwable e {:name nm :error (.getMessage e)}))))

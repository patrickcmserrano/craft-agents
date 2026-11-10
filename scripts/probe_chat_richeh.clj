#!/usr/bin/env clojure
;;; probe_chat_richeh.clj — sonda do caminho de fala do atelie.
;;;
;;; Instrumenta atelier/handle-atelier-chat! para registrar, no journal do
;;; engine, QUAL agente o handler escolhe responder e QUAL texto — sem alterar
;;; o comportamento. Serve para separar "o handler nao escolheu a Richeh" de
;;; "escolheu e o say-fn nao emitiu".
;;;
;;; Uso (carrega a sonda no engine vivo):
;;;   clojure -M:scripts -m nrepl-client --port 7891 --file scripts/probe_chat_richeh.clj
(ns probe-chat-richeh
  (:require [craft.agents.backend.fabric.atelier :as atelier]))

(defonce original @(var atelier/handle-atelier-chat!))

(defn instalar! []
  (alter-var-root
   (var atelier/handle-atelier-chat!)
   (fn [_]
     (fn [speaker msg coco-st richeh-st say-fn]
       (let [wrapped (fn [who text]
                       (println (str "PROBE-SAY-FN: who=" who
                                     " coco-state=" (some? coco-st)
                                     " richeh-state=" (some? richeh-st)
                                     " msg=" (pr-str msg)
                                     " text=" (pr-str (subs (str text) 0 (min 60 (count (str text)))))))
                       (say-fn who text))]
         (println (str "PROBE-HANDLE: speaker=" speaker " msg=" (pr-str msg)))
         (original speaker msg coco-st richeh-st wrapped)))))
  (println "PROBE-CHAT-RICHEH: instalada"))

(defn remover! []
  (alter-var-root (var atelier/handle-atelier-chat!) (fn [_] original))
  (println "PROBE-CHAT-RICHEH: removida"))

(if (= "off" (first *command-line-args*))
  (remover!)
  (instalar!))

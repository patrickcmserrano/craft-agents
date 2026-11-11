#!/usr/bin/env clojure
;; Verificacao AO VIVO da identidade de evento do chat, pela ponte 25595.
;;
;; Prova que a JVM do servidor (hoje, com o Java do contador) enfileira o
;; EVENT-ID como 3o campo: CHAT_INJECT entra e CHAT_POLL sai com "speaker|texto|evt-N".
;; Se sair so "speaker|texto" (2 campos), a JVM viva corre a classe ANTIGA e o
;; fix do dedup esta inerte ate um restart do servidor.
;;
;; Uso:
;;   clojure -Sdeps '{:paths ["scripts"]}' -M -m verificar_event_id_ao_vivo
;;
;; Nota: o CHAT_INJECT e um evento NOVO (ganha id proprio); o teste compara o
;; numero de campos, nao o valor. O OK do CHAT_INJECT nao prova nada — a prova
;; e o CHAT_POLL trazer o 3o campo.

(ns verificar-event-id-ao-vivo
  (:require [clojure.string :as str]
            [entity-state :as es]))

(def ^:private host "127.0.0.1")
(def ^:private port 25595)

(defn- poll []
  (es/send-cmd "CHAT_POLL" host port 3.0))

(defn- injeta [texto]
  (es/send-cmd (str "CHAT_INJECT|sonda-verificacao|" texto) host port 3.0))

(defn- campos [item]
  (str/split item #"\|"))

(defn -main [& _]
  (let [marca   (str "eventid-check-" (System/currentTimeMillis))
        inj     (injeta marca)
        resp    (poll)]
    (println "== verificacao ao vivo do event-id (ponte 25595) ==")
    (println "1) CHAT_INJECT ->" (pr-str inj))
    (println "2) CHAT_POLL   ->" (pr-str resp))
    (println)
    (let [item (some #(when (str/includes? % marca) (str/trim %))
                     (str/split (str resp) #";;"))]
      (println
       (if item
         (let [cs (campos item)
               n  (count cs)]
           (str "3) item da nossa mensagem: " (pr-str item) "\n"
                "   campos: " n " " (pr-str cs) "\n\n"
                (cond
                  (>= n 3)
                  (str "RESULTADO: OK — a JVM viva enfileira o EVENT-ID (3o campo = "
                       (pr-str (nth cs 2)) "). O fix do dedup esta ATIVO ao vivo.")

                  (= n 2)
                  (str "RESULTADO: INERTE — a ponte so devolve 2 campos (speaker|texto).\n"
                       "   A JVM do servidor corre a classe ANTIGA do SyntheticPlayerFactory;\n"
                       "   reiniciar o SERVIDOR (nao so o craft-agents) para carregar o contador.")

                  :else
                  (str "RESULTADO: INDETERMINADO — " n " campo(s)."))))
         (str "RESULTADO: nao achei a mensagem no poll (talvez o chat poll ja tenha\n"
              "   consumido, ou o craft-agents drenou a fila antes). Repetir."))))))

;; Sem (apply -main ...) aqui: rodar com `-M -m verificar-event-id-ao-vivo`
;; ja invoca o -main. Chamar as duas coisas corre a verificacao 2x.

#!/usr/bin/env clojure
;; Sonda reexecutavel de debug do CASTING PURO no chat.
;; Le o journal do unit craft-agents e mede, por mensagem, quantas vezes o
;; handler do atelie foi chamado e se o retorno prova alguma coisa.
;;
;; Uso:
;;   clojure -M scripts/mc_chat_debug.clj                 ; janela padrao: 1h
;;   clojure -M scripts/mc_chat_debug.clj "2026-11-11 00:00:00"
;;
;; Notas de instrumento:
;;   - `handle-atelier-chat!` devolve `true` mesmo quando a magia NAO saiu
;;     (so diz "consumido"). Logo `retornou: true` NAO e prova de lancamento.
;;   - As linhas `>>> PROBE` / `>>> ATELIER-HANDLER` NAO existem em src/ —
;;     vem de codigo reanexado na JVM viva. Presenca delas prova que a JVM
;;     corre codigo que o repo nao tem.

(ns mc-chat-debug
  (:require [clojure.string :as str]
            [clojure.java.shell :refer [sh]]))

(defn journal [since]
  (let [{:keys [out]} (sh "journalctl" "--user" "-u" "craft-agents"
                          "--since" since "--no-pager")]
    (str/split-lines (or out ""))))

(defn parse-line [line]
  (cond
    (re-find #"Chat capturado do jogo: <([^>]+)>\s+(.+)$" line)
    (let [[_ spk msg] (re-find #"Chat capturado do jogo: <([^>]+)>\s+(.+)$" line)]
      {:kind :capturado :spk spk :msg (str/trim msg)})

    (re-find #">>> PROBE: handler chamado com" line)
    {:kind :probe}

    (re-find #">>> ATELIER-HANDLER chamado" line)
    (let [[_ spk msg] (re-find #"speaker= \"([^\"]*)\"\s+msg= \"([^\"]*)\"" line)]
      {:kind :handler-in :spk spk :msg msg})

    (re-find #">>> ATELIER-HANDLER retornou" line)
    (let [[_ v] (re-find #"retornou:\s*(\S+)" line)]
      {:kind :handler-out :value v})

    :else {:kind :other}))

(defn -main [& args]
  (let [since (or (first args) "1 hour ago")
        lines (journal since)
        parsed (mapv parse-line lines)
        cap (filter #(= :capturado (:kind %)) parsed)
        hin (filter #(= :handler-in (:kind %)) parsed)
        hout (filter #(= :handler-out (:kind %)) parsed)]
    (println "== janela:" since " ==")
    (println "linhas no journal:" (count lines))
    (println "Chat capturado:      " (count cap))
    (println "handler chamado:     " (count hin))
    (println "handler retornou:    " (count hout))
    (println)
    (println "-- capturas por mensagem --")
    (doseq [[[spk msg] n] (sort-by val > (frequencies (map (juxt :spk :msg) cap)))]
      (println (format "  %-6s %-20s capturado %d" spk (pr-str msg) n)))
    (println)
    (println "-- razao handler/captura (2.0 = duplicado, 0.0 = engolido) --")
    (let [by-msg (frequencies (map (juxt :spk :msg) hin))]
      (doseq [[[spk msg] n] (sort-by val > (frequencies (map (juxt :spk :msg) cap)))]
        (let [h (get by-msg [spk msg] 0)]
          (println (format "  %-6s %-20s cap=%-3d handler=%-3d razao=%.2f"
                           spk (pr-str msg) n h (double (/ h n)))))))
    (println)
    (println "-- aviso: 'retornou: true' NAO prova lancamento (o handler devolve true")
    (println "   mesmo quando a magia nao saiu; so diz que a mensagem foi consumida).")))

(apply -main *command-line-args*)

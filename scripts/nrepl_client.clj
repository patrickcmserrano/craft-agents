#!/usr/bin/env clojure
;;; Cliente nREPL minimo para o craft-agents (portas 7891 engine / 7890 in-server).
;;;
;;; Versao Clojure do scripts/nrepl.py: em vez de embarcar um bencode proprio,
;;; usa o `nrepl.bencode` que o proprio servidor usa — o parser e o mesmo dos
;;; dois lados do fio.
;;;
;;; Medido em 2026-11-10 (nREPL 1.3.0):
;;; - **NAO envie `id`.** Esta versao TRAVA a resposta quando a mensagem tem `id`
;;;   (medido: `{"op":"clone","id":"c"}` -> timeout com zero bytes; o MESMO
;;;   payload sem `id` responde na hora). Casamos as respostas pela `session`.
;;; - clone ANTES do eval e obrigatorio; mensagem para sessao inexistente e
;;;   descartada em silencio pelo nREPL.
;;; - o ns `user` da sessao NAO tem clojure.core referenciado: qualifique
;;;   (clojure.core/println) ou faca o require.
;;; - o erro vem no campo `err` com status `eval-error`; um cliente que so olha
;;;   value/done sai antes de ler e devolve vazio.
;;;
;;; Uso:
;;;     clojure -M:scripts -m nrepl-client --port 7891 '(+ 1 2)'
;;;     clojure -M:scripts -m nrepl-client --port 7890 --file sonda.clj
;;;     clojure -M:scripts -m nrepl-client '(+ 1 2)'      ; porta default 7891
;;;
;;; Sem o alias, use a forma direta:
;;;     clojure -Sdeps '{:paths ["scripts"]}' -M -m nrepl-client 7891 '(+ 1 2)'
(ns nrepl-client
  (:require [clojure.string :as str]
            [nrepl.bencode :as bencode])
  (:import [java.net Socket InetSocketAddress]
           [java.io ByteArrayOutputStream InputStream OutputStream
            PushbackInputStream]))

(def ^:private ^:const default-host "127.0.0.1")
(def ^:private ^:const default-port 7891)

(defn- send-msg
  "Escreve uma mensagem bencode no stream.

  O framing do nREPL e o proprio bencode (strings sao netstrings DENTRO dele) —
  NAO se envolve o payload num write-netstring. O nrepl.transport faz exatamente
  `(bencode/write-bencode buffer msg)` + `(socket/write out bytes)`; envelopar
  de novo faz o servidor nao decodificar e fechar a conexao (broken pipe).

  NAO inclua :id — o nREPL 1.3.0 trava a resposta quando ele esta presente."
  [^OutputStream out msg]
  (let [body (ByteArrayOutputStream.)]
    (bencode/write-bencode body msg)
    (.write out ^bytes (.toByteArray body))
    (.flush out)))

(defn- read-msg
  "Le uma mensagem bencode. Devolve nil no EOF."
  [^PushbackInputStream in]
  (try
    (bencode/read-bencode in)
    (catch java.io.EOFException _ nil)))

(defn- s [v]
  (cond (bytes? v) (String. ^bytes v "UTF-8")
        (nil? v) nil
        :else (str v)))

;; O nrepl.bencode devolve as MENSAGENS com chaves STRING (nao keyword) e os
;; valores como byte[]. Traduzir na entrada evita o bug classico: usar
;; (:session msg) num mapa de chaves string devolve sempre nil, e o read-until
;; nunca casa — o cliente fica pendurado esperando uma resposta que ja chegou.
(defn- norm-msg [msg]
  (when msg
    (into {} (map (fn [[k v]]
                    [(keyword (s k))
                     (if (vector? v) (mapv s v) (s v))])
                  msg))))

(defn- read-until
  "Le mensagens ate `done`/`eval-error` na resposta da sessao `where`.
  Devolve {:out :err :value}."
  [^PushbackInputStream in where]
  (loop [out (StringBuilder.) err (StringBuilder.) value nil]
    (if-let [msg (norm-msg (read-msg in))]
      (if (not= where (:session msg))
        (recur out err value)
        (let [out (if-let [o (:out msg)] (.append out ^String o) out)
              err (if-let [e (:err msg)] (.append err ^String e) err)
              value (if (contains? msg :value) (:value msg) value)
              status (set (:status msg))]
          (if (or (contains? status "done") (contains? status "eval-error"))
            {:out (str out) :err (str err) :value value}
            (recur out err value))))
      {:out (str out) :err (str err) :value value})))

(defn- clone-session! [^PushbackInputStream in ^OutputStream out]
  (send-msg out {:op "clone"})
  (loop []
    (when-let [msg (norm-msg (read-msg in))]
      (if-let [sid (:new-session msg)]
        sid
        (recur)))))

(defn eval-code
  "Clona uma sessao e avalia `code` nela. `prelude` (opcional) e avaliado antes
  — util para `(require ...)`, ja que o ns `user` nao tem clojure.core referido."
  ([host port code] (eval-code host port code nil))
  ([host port code prelude]
   (with-open [sock (Socket.)]
     (.connect sock (InetSocketAddress. ^String host (int port)) 10000)
     (let [in (PushbackInputStream. (.getInputStream sock))
           out (.getOutputStream sock)
           session (clone-session! in out)]
       (when prelude
         (send-msg out {:op "eval" :session session :code prelude})
         (read-until in session))
       (send-msg out {:op "eval" :session session :code code})
       (read-until in session)))))

(defn- parse-args [args]
  (loop [m {:host default-host :port default-port :code []}
         [a & more] args]
    (cond
      (nil? a) m
      (= a "--host") (recur (assoc m :host (first more)) (rest more))
      (= a "--port") (recur (assoc m :port (Long/parseLong (first more))) (rest more))
      (= a "--file") (recur (assoc m :file (first more)) (rest more))
      :else (recur (update m :code conj a) more))))

(defn -main [& args]
  (let [{:keys [host port file code]} (parse-args args)
        src (if file (slurp file) (str/join " " code))]
    (when (str/blank? src)
      (println "uso: clojure -M -m nrepl-client [--host H] [--port P] [--file f.clj] '<codigo>'")
      (System/exit 1))
    (let [{:keys [out err value]} (eval-code host port src)]
      (when-not (str/blank? err)
        (println "--- ERR ---")
        (println (str/trimr err)))
      (when-not (str/blank? out)
        (println (str/trimr out)))
      (when (some? value)
        (println (str "=> " value)))
      (System/exit 0))))

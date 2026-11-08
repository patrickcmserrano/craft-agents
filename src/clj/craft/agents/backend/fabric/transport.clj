(ns craft.agents.backend.fabric.transport
  "Camada de transporte de rede TCP para comunicacao de baixa latencia com o Fabric Bridge (127.0.0.1:25595)."
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter PrintWriter]
           [java.net Socket]
           [java.nio.charset StandardCharsets]))

(defonce ^:private bridge-conn-atom (atom nil))

(defn close-bridge-conn! []
  (when-let [{:keys [^Socket sock]} @bridge-conn-atom]
    (try (.close sock) (catch Throwable _ nil)))
  (reset! bridge-conn-atom nil))

(defn uformat
  "Formata strings usando java.util.Locale/ROOT para garantir ponto decimal (.)
   e evitar bugs criticos de parsing no protocolo da ponte em Linux pt_BR."
  ^String [^String fmt & args]
  (String/format java.util.Locale/ROOT fmt (to-array args)))

(defn raw-send-bridge-cmd!
  "Envia linha de comando ao In-Server Bridge em 127.0.0.1:25595 com reconexao automatica."
  [line]
  (locking bridge-conn-atom
    (loop [attempt 0]
      (if (>= attempt 2)
        "ERR|DISCONNECTED"
        (let [res (try
                    (when (or (nil? @bridge-conn-atom)
                              (.isClosed ^Socket (:sock @bridge-conn-atom)))
                      (let [s (Socket. "127.0.0.1" 25595)]
                        (.setTcpNoDelay s true)
                        (.setSoTimeout s 2500)
                        (reset! bridge-conn-atom
                                {:sock s
                                 :in   (BufferedReader. (InputStreamReader. (.getInputStream s) StandardCharsets/UTF_8))
                                 :out  (PrintWriter. (OutputStreamWriter. (.getOutputStream s) StandardCharsets/UTF_8) true)})))
                    (let [{:keys [^BufferedReader in ^PrintWriter out]} @bridge-conn-atom]
                      (.println out (str line))
                      (when (.checkError out)
                        (throw (java.io.IOException. "Falha de escrita no socket")))
                      (if-let [l (.readLine in)]
                        l
                        (do
                          (close-bridge-conn!)
                          nil)))
                    (catch Throwable _
                      (close-bridge-conn!)
                      nil))]
          (if (some? res)
            res
            (do
              (close-bridge-conn!)
              (recur (inc attempt)))))))))

(defn send-bridge-cmd!
  "Ponto de entrada unificado para envio de comandos via socket.
   Delega dinamicamente se craft.agents.backend.fabric/send-bridge-cmd! tiver sido redefinido (ex: em suites de teste)."
  [line]
  (if-let [v (resolve 'craft.agents.backend.fabric/send-bridge-cmd!)]
    (let [f (var-get v)]
      (if (and f (not (identical? f send-bridge-cmd!)))
        (f line)
        (raw-send-bridge-cmd! line)))
    (raw-send-bridge-cmd! line)))

(defn bridge-alive? []
  (= "PONG" (send-bridge-cmd! "PING")))

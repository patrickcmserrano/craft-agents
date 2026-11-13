(ns craft.agents.backend.fabric.engine.chat
  "Threads de log/chat do engine: tailer de latest.log (fallback do hook),
   polling de CHAT_POLL da bridge e despachante de feiticos por item na mao."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [craft.agents.backend.fabric.atelier :as atelier]
            [craft.agents.backend.fabric.sanctuary :refer [sanctuary-route]]
            [craft.agents.backend.fabric.sentry :refer [handle-player-command!]]
            [craft.agents.backend.fabric.transport :refer [bridge-alive? send-bridge-cmd!]])
  (:import [java.io RandomAccessFile]
           [java.nio.charset StandardCharsets]))

(def ^:private bot-speakers #{"Karlach" "Picard" "Antigravity"})

(defn- read-new-lines
  "Linhas novas de `f` desde `last-p`. Devolve [nova-posicao linhas]; arquivo
   truncado reinicia em 0."
  [^java.io.File f last-p]
  (if (.exists f)
    (let [cur-p (.length f)]
      (cond
        (> cur-p last-p)
        (let [lines (try
                      (with-open [raf (RandomAccessFile. f "r")]
                        (.seek raf last-p)
                        (loop [acc []]
                          (if-let [l (.readLine raf)]
                            (recur (conj acc (String. (.getBytes l StandardCharsets/ISO_8859_1) StandardCharsets/UTF_8)))
                            acc)))
                      (catch Throwable _ []))]
          [cur-p lines])
        (< cur-p last-p) [0 []]
        :else [last-p []]))
    [0 []]))

(defn- daemon-thread! [name f]
  (doto (Thread. ^Runnable f ^String name)
    (.setDaemon true)
    (.start)))

(defn start-chat-listener!
  "Thread que monitora client & server latest.log em paralelo (latencia de 100ms)
   e despacha `<jogador> msg` como comando. So age se a bridge estiver fora do ar."
  [{:keys [engine-ctx-atom nav-state picard-state coco-state richeh-state running?]}]
  (daemon-thread!
   "craft-agents-chat-listener"
   (fn []
     (let [server-log (io/file "/home/pace/dev/minecraft/server/logs/latest.log")
           client-log (io/file "/home/pace/.minecraft/logs/latest.log")
           seen-msgs  (atom {})]
       (loop [s-pos (if (.exists server-log) (.length server-log) 0)
              c-pos (if (.exists client-log) (.length client-log) 0)]
         (when @running?
           (Thread/sleep 100)
           (let [[new-s-pos s-lines] (read-new-lines server-log s-pos)
                 [new-c-pos c-lines] (read-new-lines client-log c-pos)
                 all-lines (concat s-lines c-lines)
                 now (System/currentTimeMillis)]
             ;; Evita reprocessar a mesma mensagem enviada nos ultimos 10s
             (swap! seen-msgs (fn [m] (into {} (filter (fn [[_ ts]] (< (- now ts) 10000)) m))))
             (doseq [line all-lines]
               (when-let [[_ speaker msg] (re-find #"<([^>]+)>\s+(.+)$" line)]
                 (let [clean-spk (str/trim speaker)
                       clean-msg (str/trim msg)
                       dedup-key (str clean-spk ":" clean-msg)]
                   (when (and (not (bot-speakers clean-spk))
                              (not (contains? @seen-msgs dedup-key))
                              ;; CHAT_POLL is authoritative while the
                              ;; bridge is healthy; use disk only as a
                              ;; fallback when the hook is unavailable.
                              (not (bridge-alive?)))
                     (swap! seen-msgs assoc dedup-key now)
                     (println (str "[craft-agents-chat] 📨 Chat capturado do jogo: <" clean-spk "> " clean-msg))
                     ;; Rota CEGA: o log de disco nao traz a identidade do
                     ;; evento (so remetente e texto). Passa nil e cai no
                     ;; dedup por conteudo — e a rota de FALLBACK do hook.
                     (handle-player-command! clean-spk clean-msg engine-ctx-atom nav-state picard-state sanctuary-route coco-state richeh-state nil)))))
             (recur new-s-pos new-c-pos))))))))

(defn start-item-poll!
  "Despachante unificado de feiticos: polling do item na mao (op HELD_ITEM) a 4Hz.
   Cada item magico (blaze_rod, breeze_rod, etc.) tem durabilidade = cargas; o data pack
   soma 1 ao damage por uso (clicar). Quando o damage do mesmo item SOBE, o jogador
   usou a varinha/cajado: despachamos a magia associada na mira dele (LOOK)."
  [{:keys [coco-state running?]}]
  (daemon-thread!
   "craft-agents-item-poll"
   (fn []
     (let [prev-item (atom {})]
       (loop []
         (when @running?
           (try
             (let [players-resp (send-bridge-cmd! "PLAYERS")
                   names (when (and players-resp (str/starts-with? players-resp "OK|"))
                           (->> (str/split (subs players-resp 3) #";")
                                (remove str/blank?)
                                (map #(first (str/split % #",")))
                                (set)))]
               (doseq [nm names]
                 (when-let [info (atelier/held-spell-item send-bridge-cmd! nm)]
                   (let [prev (get @prev-item nm)]
                     (when (atelier/spell-used? prev info)
                       (println (format "[craft-agents-spell] %s usado por %s (carga %s->%s): disparando %s"
                                        (:item-id info) nm (:damage prev) (:damage info) (get-in info [:spell :id])))
                       (try
                         (atelier/dispatch-held-spell! coco-state send-bridge-cmd! nm info (System/currentTimeMillis))
                         (catch Throwable t
                           (println "[craft-agents-spell-ERROR] disparar:" (.getMessage t)))))
                     (swap! prev-item assoc nm info)))))
             (catch Throwable t
               (println "[craft-agents-spell-ERROR]" (.getMessage t))))
           (Thread/sleep 250)
           (recur)))))))

(defn poll-bridge-chat!
  "CHAT_POLL da bridge (5Hz): cada item `speaker|text|event-id` vira comando. O id
   nasce no hook do servidor; `limit 3` preserva pipes dentro do texto."
  [{:keys [engine-ctx-atom nav-state picard-state coco-state richeh-state]}]
  (let [resp (send-bridge-cmd! "CHAT_POLL")]
    (when (and resp (str/starts-with? resp "OK;;"))
      (doseq [item (str/split (subs resp 4) #";;")]
        (when-not (str/blank? item)
          (let [[speaker msg event-id] (str/split item #"\|" 3)]
            (when (and speaker msg (not (bot-speakers speaker)))
              (handle-player-command! speaker msg engine-ctx-atom nav-state picard-state sanctuary-route coco-state richeh-state event-id))))))))

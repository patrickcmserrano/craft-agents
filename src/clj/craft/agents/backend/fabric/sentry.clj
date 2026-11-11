(ns craft.agents.backend.fabric.sentry
  "Escolta Ativa (deteccao proativa de animais fugitivos) e manipulacao de comandos de jogadores via chat."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.atelier :as atelier]
            [craft.agents.backend.fabric.goals :as goals]
            [craft.agents.backend.fabric.picard :as picard]
            [craft.agents.backend.fabric.sanctuary :as sanctuary]
            [craft.agents.backend.fabric.telemetry :as telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport :refer [uformat]]
            [craft.agents.cognition.critic :as critic]
            [craft.agents.cognition.grounding :as grounding]
            [craft.agents.cognition.intent :as intent]))

(defonce ^:private global-seen-chat (atom {}))

(def ^:private chat-dedup-window-ms
  "Janela de deduplicacao. Ela existe para casar as DUAS rotas de leitura do chat
   (log-tailer de disco a 100ms e CHAT_POLL in-memory a ~800ms) e nao re-executar a
   MESMA mensagem. Janela curta de proposito: o jogador precisa poder repetir o
   mesmo comando (ex.: reconjurar `fireball`) e uma janela longa o tratava como
   duplicata e o descartava em silencio."
  1500)

(defn chat-duplicate?
  "Verifica se a mensagem recente do speaker ja foi processada ha menos de
   `chat-dedup-window-ms`. Previne duplicacao cruzada entre o log-tailer de disco
   e o CHAT_POLL in-memory do bridge."
  [speaker msg]
  (let [now    (System/currentTimeMillis)
        key    (str (str/trim (or speaker "")) ":" (str/trim (or msg "")))
        cutoff (- now chat-dedup-window-ms)]
    (swap! global-seen-chat (fn [m] (into {} (filter (fn [[_ ts]] (> ts cutoff)) m))))
    (if (get @global-seen-chat key)
      true
      (do
        (swap! global-seen-chat assoc key now)
        false))))

(defonce ^:private proactive-sentry-cooldowns (atom {}))
(defonce ^:private fugitive-candidate-counts (atom {}))
(defonce ^:private last-sentry-scan-ms (atom 0))

(def sanctuary-monitored-species
  #{"Capybara" "Deer" "Elephant" "Giraffe" "Zebra" "Mammoth" "Fox" "Armadillo" "Duck" "Tortoise" "Hedgehog" "Panda" "Camel" "Sniffer" "Cat"})

(defn parse-sanctuary-escaped-animals
  "Analisa a telemetria do SANCTUARY e retorna a lista de animais fugitivos (fora de qualquer cerca ou baia)."
  [raw-sanctuary-resp]
  (when (and raw-sanctuary-resp (str/starts-with? raw-sanctuary-resp "OK|"))
    (let [parts (str/split raw-sanctuary-resp #"\|")
          details (when (>= (count parts) 3) (nth parts 2))]
      (when (seq details)
        (->> (str/split details #";")
             (keep (fn [item]
                     (let [f (str/split item #",")]
                       (when (>= (count f) 6)
                         (let [species (nth f 0)
                               cname   (nth f 1)
                               pos     [(Double/parseDouble (nth f 2))
                                        (Double/parseDouble (nth f 3))
                                        (Double/parseDouble (nth f 4))]
                               leashed (Boolean/parseBoolean (nth f 5))]
                           (when (and (contains? sanctuary-monitored-species species)
                                      (not leashed)
                                      (nil? (sanctuary/current-enclosure-id pos))
                                      (sanctuary/in-escaped-zone? pos))
                             {:species species
                              :name    (if (str/blank? cname) species cname)
                              :pos     pos
                              :leashed? leashed}))))))
             vec)))))

(defn proactive-sentry-scan!
  "Escolta Ativa da Karlach: monitora autonomamente se animais do santuário escaparam.
   Ao identificar um fugitivo, interrompe a patrulha e executa o protocolo de resgate e contenção."
  [engine-ctx-atom nav-state sanctuary-route]
  (let [now (System/currentTimeMillis)]
    ;; Varredura a cada 2.5s (2500ms)
    (when (> (- now @last-sentry-scan-ms) 2500)
      (reset! last-sentry-scan-ms now)
      (let [raw-snap (transport/send-bridge-cmd! "SANCTUARY")
            escaped  (parse-sanctuary-escaped-animals raw-snap)]
        (when (seq escaped)
          (let [bot-pos   (:pos @nav-state [-175.0 65.0 180.0])
                cutoff    (- now 120000) ;; Cooldown ampliado de 2 minutos por animal resgatado
                _         (swap! proactive-sentry-cooldowns (fn [m] (into {} (filter (fn [[_ ts]] (> ts cutoff)) m))))
                escaped-names (set (map :name escaped))
                ;; Histerese: incrementa contador de detecções e limpa quem não está mais solto
                _         (swap! fugitive-candidate-counts
                                 (fn [m]
                                   (let [filtered (select-keys m escaped-names)]
                                     (reduce (fn [acc n] (update acc n (fnil inc 0))) filtered escaped-names))))
                ;; Animal só é considerado para captura se confirmado em pelo menos 2 varreduras (5s)
                confirmed (filter (fn [e] (>= (get @fugitive-candidate-counts (:name e) 0) 2)) escaped)
                available (remove (fn [e] (get @proactive-sentry-cooldowns (:name e))) confirmed)]
            (when-let [target (first (sort-by (fn [e]
                                                (let [[ex _ ez] (:pos e)
                                                      [bx _ bz] bot-pos
                                                      dx (- ex bx)
                                                      dz (- ez bz)]
                                                  (+ (* dx dx) (* dz dz))))
                                              available))]
              (let [target-name    (:name target)
                    target-species (:species target)
                    target-pos     (:pos target)
                    species-key    (critic/normalize-species (or target-species target-name))
                    reg-species    (case (str/lower-case (or species-key ""))
                                     "capybara" "naturalist:capybara"
                                     "deer" "naturalist:deer"
                                     "panda" "minecraft:panda"
                                     "camel" "minecraft:camel"
                                     "sniffer" "minecraft:sniffer"
                                     "elephant" "naturalist:elephant"
                                     "giraffe" "naturalist:giraffe"
                                     "zebra" "naturalist:zebra"
                                     "ostrich" "naturalist:ostrich"
                                     "fox" "minecraft:fox"
                                     "mammoth" "naturalist:mammoth"
                                     "tortoise" "naturalist:tortoise"
                                     "duck" "naturalist:duck"
                                     "hedgehog" "naturalist:hedgehog"
                                     "armadillo" "minecraft:armadillo"
                                     "cat" "minecraft:cat"
                                     "naturalist:capybara")]
                (swap! proactive-sentry-cooldowns assoc target-name now)
                (record-cognitive-event! engine-ctx-atom :perception "Escolta Ativa — Detecção de Fugitivo"
                                         (str "Animal detectado fora do cercado: " target-name " (" target-species ") em " target-pos ". Iniciando resgate autônomo!"))
                (actions/karlach-say! (str "Opa! Avistei o " target-name " solto fora do cercado! Meu motor infernal tá aquecido, vou resgatar agora mesmo! 🔥 🐾"))
                (let [intent-res {:intent/type :animal-emergency
                                  :intent/confidence 0.99
                                  :intent/slots {:target/entity target-name
                                                 :target/species reg-species
                                                 :target/speaker "Escolta Ativa"}
                                  :intent/raw-text (str "resgata " target-name)}
                      goal-spec  (grounding/ground-goal intent-res)]
                  (goals/execute-karlach-goal! goal-spec engine-ctx-atom nav-state sanctuary-route)
                  true)))))))))

(defn handle-player-command!
  "Processa comandos de chat (recebidos via bridge CHAT_POLL, log tailer ou Studio UI)
   e orquestra as reações dos agentes Karlach, Picard, Coco e Richeh."
  ([speaker msg engine-ctx-atom nav-state picard-state sanctuary-route]
   (handle-player-command! speaker msg engine-ctx-atom nav-state picard-state sanctuary-route nil nil))
  ([speaker msg engine-ctx-atom nav-state picard-state sanctuary-route coco-state richeh-state]
   (when-not (chat-duplicate? speaker msg)
     (let [lower (str/lower-case (str/trim msg))]
       ;; 0. Comandos para o Ateliê (Coco & Richeh) OU casting puro do jogador
       (when (and (atelier/atelier-command? msg)
                  coco-state richeh-state)
         (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
         (atelier/handle-atelier-chat! speaker msg coco-state richeh-state
                                      (fn [who text]
                                        (if (= who :coco)
                                          (atelier/coco-say! text)
                                          (atelier/richeh-say! text)))))

       ;; 1. Comandos para Karlach (Pipeline Cognitivo Desacoplado: Router -> Grounding -> Multimethod)
      (when (or (str/includes? lower "karlach")
                (str/starts-with? lower "!karlach"))
        (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
        (let [t0         (System/currentTimeMillis)
              intent-res (intent/classify-intent-semantic msg speaker)
              dt         (- (System/currentTimeMillis) t0)
              goal-spec  (grounding/ground-goal intent-res)]
          (record-cognitive-event! engine-ctx-atom :router "System 1 Intent Router"
                                   (str "Intenção: " (:intent/type intent-res)
                                        " | Confiança: " (int (* 100 (:intent/confidence intent-res 0.0))) "%"
                                        " | Latência: " dt "ms"
                                        " | Slots: " (:intent/slots intent-res)))
          (goals/execute-karlach-goal! goal-spec engine-ctx-atom nav-state sanctuary-route)))

      ;; 2. Previsão do Tempo e Comandos para o Capitão Picard
      (cond
        (or (str/includes? lower "previsao")
            (str/includes? lower "previsão")
            (str/includes? lower "meteorologia")
            (and (or (str/includes? lower "picard")
                     (str/includes? lower "capitao")
                     (str/includes? lower "capitão")
                     (str/includes? lower "qual")
                     (str/includes? lower "como ta")
                     (str/includes? lower "como está"))
                 (or (str/includes? lower "tempo")
                     (str/includes? lower "clima")
                     (str/includes? lower "chuva"))))
        (do
          (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
          (record-cognitive-event! engine-ctx-atom :motor "Picard Telemetry" "Consultando sensores meteorológicos orbitais e terrestres")
          (transport/send-bridge-cmd! "SWING|Picard")
          (picard/picard-broadcast-weather!)
          (swap! picard-state assoc :last-weather-ms (System/currentTimeMillis)))

        (and (or (str/includes? lower "picard")
                 (str/includes? lower "capitao")
                 (str/includes? lower "capitão"))
             (or (str/includes? lower "limpa")
                 (str/includes? lower "abrir o sol")
                 (str/includes? lower "parar chuva")))
        (do
          (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
          (record-cognitive-event! engine-ctx-atom :motor "Picard Weather Matrix" "Comando de dispersão climática executado: weather clear")
          (transport/send-bridge-cmd! "CMD|weather clear")
          (picard/picard-say! "Make it so! Acionando matriz de controle climático orbital: Céu limpo estabelecido, Número Um."))

        (and (or (str/includes? lower "picard")
                 (str/includes? lower "engage")
                 (str/includes? lower "engajar"))
             (or (str/includes? lower "engajar")
                 (str/includes? lower "engage")
                 (str/includes? lower "dobra")))
        (do
          (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
          (record-cognitive-event! engine-ctx-atom :motor "Picard Helm" "Acionando Dobra 9")
          (transport/send-bridge-cmd! "SWING|Picard")
          (transport/send-bridge-cmd! "CMD|playsound minecraft:block.beacon.power_select master @a -65 145 100 1.0 1.2")
          (picard/picard-say! "Make it so! Dobra 9 acionada, leme à frente!"))

        (and (or (str/includes? lower "picard")
                 (str/includes? lower "earl grey"))
             (or (str/includes? lower "cha")
                 (str/includes? lower "chá")
                 (str/includes? lower "tea")
                 (str/includes? lower "earl grey")))
        (do
          (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
          (picard/picard-say! "Tea. Earl Grey. Hot. Nada como um bom chá para clarear os pensamentos na ponte, Número Um."))

        (and (or (str/includes? lower "picard")
                 (str/includes? lower "capitao")
                 (str/includes? lower "capitão"))
             (or (str/includes? lower "vem")
                 (str/includes? lower "seguir")
                 (str/includes? lower "acompanhe")))
        (do
          (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
          (record-cognitive-event! engine-ctx-atom :motor "Picard Away Team" (str "Acompanhando " speaker))
          (swap! picard-state assoc :mode :follow :follow-target speaker)
          (picard/picard-say! (str "Com prazer, " speaker ". Acompanhando você em missão de superfície.")))

        (and (or (str/includes? lower "picard")
                 (str/includes? lower "capitao")
                 (str/includes? lower "capitão"))
             (or (str/includes? lower "ponte")
                 (str/includes? lower "voltar")
                 (str/includes? lower "posto")))
        (do
          (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
          (record-cognitive-event! engine-ctx-atom :motor "Picard Bridge" "Retornando à ponte")
          (swap! picard-state assoc :mode :bridge-command :follow-target nil :pos [-65.0 145.0 101.0] :phase :walk)
          (transport/send-bridge-cmd! "MOVE|Picard|-65.0|145.0|101.0|0.0|0.0|false|false")
          (picard/picard-say! "Entendido. Retornando por teletransporte ao meu posto na ponte da Enterprise-D.")))))))

(defonce ^:private log-file-pos (atom 0))
(def ^:private server-log-path "/home/pace/dev/minecraft/server/logs/latest.log")

(defn poll-server-log-chat!
  "Le novas linhas do latest.log do Minecraft server em tempo real (fallback robusto de chat)."
  [engine-ctx-atom nav-state picard-state sanctuary-route]
  (try
    (let [f (java.io.File. server-log-path)]
      (when (.exists f)
        (let [len (.length f)]
          (when (< len @log-file-pos)
            (reset! log-file-pos len))
          (if (zero? @log-file-pos)
            (reset! log-file-pos len)
            (when (> len @log-file-pos)
              (with-open [raf (java.io.RandomAccessFile. f "r")]
                (.seek raf @log-file-pos)
                (loop []
                  (when-let [raw-line (.readLine raf)]
                    (let [line (String. (.getBytes raw-line "ISO-8859-1") "UTF-8")]
                      (when-let [[_ speaker text] (re-find #"\[\d{2}:\d{2}:\d{2}\] \[Server thread/INFO\]: <([^>]+)> (.*)" line)]
                        (when-not (#{"Karlach" "Picard" "Antigravity"} speaker)
                          (handle-player-command! speaker text engine-ctx-atom nav-state picard-state sanctuary-route))))
                    (recur)))
                (reset! log-file-pos (.getFilePointer raf))))))))
    (catch Throwable _ nil)))

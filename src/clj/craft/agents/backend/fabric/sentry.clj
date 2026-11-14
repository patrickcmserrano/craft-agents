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
            [craft.agents.cognition.intent :as intent]
            [craft.agents.npc.addressing :as addressing]
            [craft.agents.npc.body :as body]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.state :as npc-state]))

(defonce ^:private global-seen-chat (atom {}))

(def ^:private chat-dedup-window-ms
  "Janela de deduplicacao do FALLBACK por conteudo (só quando a rota nao traz
   identidade de evento). Ver `chat-duplicate?`: com event-id nao ha janela — a
   identidade do evento e que decide. Esta janela só serve as rotas cegas (ex.:
   tailer de disco) para casar a MESMA mensagem entre elas. Curta de proposito:
   o jogador precisa poder repetir o mesmo comando."
  1500)

(defn- content-key [speaker msg]
  (str (str/trim (or speaker "")) ":" (str/trim (or msg ""))))

(defn chat-duplicate?
  "Deduplica uma mensagem de chat.

   Recebe um MAPA de evento — `{:speaker s :text t :event-id id}` — e nao mais a
   tupla (speaker, msg). A diferenca e a razao de existir desta funcao:

   - COM `:event-id`: a identidade do EVENTO decide. O MESMO event-id (a mesma
     mensagem vista por duas rotas) e duplicata; event-ids distintos com o MESMO
     texto sao eventos distintos e disparam sempre (o jogador reescreveu
     `fireball` de proposito). Sem janela temporal — nao ha o que colapsar.
   - SEM `:event-id`: rota cega (fallback). Cai no dedup por CONTEUDO dentro de
     `chat-dedup-window-ms`, como antes."
  [event]
  (let [speaker  (:speaker event)
        msg      (:text event)
        event-id (:event-id event)
        now      (System/currentTimeMillis)
        cutoff   (- now chat-dedup-window-ms)
        key      (if (some? event-id)
                   (str "id:" event-id)
                   (str "c:" (content-key speaker msg)))]
    (swap! global-seen-chat (fn [m] (into {} (filter (fn [[_ ts]] (> ts cutoff)) m))))
    (if (get @global-seen-chat key)
      true
      (do
        ;; Keep a short content marker even for identified events. The log
        ;; fallback does not carry the event id and must recognize that event.
        (swap! global-seen-chat
               (fn [m]
                 (cond-> (assoc m key now)
                   (some? event-id)
                   (assoc (str "c:" (content-key speaker msg)) now))))
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

(defn agent-goal-ctx
  "Resolve o agente alvo via `npc.addressing` / `npc.registry` e monta o mapa de contexto
   compatível com `execute-goal!`, contendo `:agent-id`, `:agent`, `:agent-name`,
   `:nav-state` e `:state` (`npc.state/agent-atom` quando não for Karlach/Picard com átomo
   legado, ou mapa compatível)."
  [agent-id engine-ctx-atom nav-state picard-state coco-state richeh-state sanctuary-route]
  (let [spec (or (registry/agent-by-id agent-id)
                 (when (= :karlach agent-id) (goals/karlach-agent)))
        st   (case agent-id
               :karlach (or nav-state (when engine-ctx-atom (npc-state/agent-atom engine-ctx-atom :karlach)) (atom {:pos [-175.0 65.0 180.0]}))
               :picard  (or picard-state (when engine-ctx-atom (npc-state/agent-atom engine-ctx-atom :picard)) (atom {:pos [-65.0 145.0 101.0]}))
               :coco    (or coco-state (when engine-ctx-atom (npc-state/agent-atom engine-ctx-atom :coco)) (atom {:pos [-160.0 65.0 190.0]}))
               :richeh  (or richeh-state (when engine-ctx-atom (npc-state/agent-atom engine-ctx-atom :richeh)) (atom {:pos [-160.0 65.0 192.0]}))
               (if engine-ctx-atom (npc-state/agent-atom engine-ctx-atom agent-id) (atom {})))]
    {:agent-id agent-id
     :agent spec
     :agent-name (or (:agent/name spec) (name agent-id))
     :state st
     :nav-state st
     :engine-ctx engine-ctx-atom
     :sanctuary-route sanctuary-route}))

(def agent-ctx
  "Alias para `agent-goal-ctx`."
  agent-goal-ctx)

(defn handle-player-command!
  "Processa comandos de chat (recebidos via bridge CHAT_POLL, log tailer ou Studio UI)
   e orquestra as reações dos agentes Karlach, Picard, Coco e Richeh.

   `event-id` (opcional) é a identidade do evento de chat, quando a rota a
   conhece (o hook Fabric do servidor). Sem ela, a mensagem cai no dedup por
   conteúdo — ver `chat-duplicate?`."
  ([speaker msg engine-ctx-atom nav-state picard-state sanctuary-route]
   (handle-player-command! speaker msg engine-ctx-atom nav-state picard-state sanctuary-route nil nil nil))
  ([speaker msg engine-ctx-atom nav-state picard-state sanctuary-route coco-state richeh-state]
   (handle-player-command! speaker msg engine-ctx-atom nav-state picard-state sanctuary-route coco-state richeh-state nil))
  ([speaker msg engine-ctx-atom nav-state picard-state sanctuary-route coco-state richeh-state event-id]
   (when-not (chat-duplicate? {:speaker speaker :text msg :event-id event-id})
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

       ;; 1. Comandos para o pipeline cognitivo de metas por agente
       ;; CA-099: qualquer agente do registro endereçado monta seu ctx e executa via execute-goal!
       (let [addressed (addressing/addressed-agents msg)]
         (doseq [agent-id addressed]
           (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
           (let [t0         (System/currentTimeMillis)
                 intent-res (assoc (intent/classify-intent-semantic msg speaker)
                                   :agent/id agent-id)
                 dt         (- (System/currentTimeMillis) t0)
                 goal-spec  (grounding/ground-goal intent-res)
                 ctx        (agent-goal-ctx agent-id engine-ctx-atom nav-state picard-state
                                            coco-state richeh-state sanctuary-route)]
             (record-cognitive-event! engine-ctx-atom :router "System 1 Intent Router"
                                      (str "Agente: " (name agent-id)
                                           " | Intenção: " (:intent/type intent-res)
                                           " | Confiança: " (int (* 100 (:intent/confidence intent-res 0.0))) "%"
                                           " | Latência: " dt "ms"
                                           " | Slots: " (:intent/slots intent-res)))
             (goals/execute-goal! goal-spec ctx))))

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

         (and (not (some #{:picard} (addressing/addressed-agents msg)))
              (or (str/includes? lower "picard")
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
           (body/move! {:agent :picard :agent-name "Picard" :x -65.0 :y 145.0 :z 101.0
                        :yaw 0.0 :pitch 0.0 :priority :player-goal})
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

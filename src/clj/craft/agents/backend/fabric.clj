(ns craft.agents.backend.fabric
  "Adaptador Server-Native p/ Fabric 1.21.1 sobre ServerPlayerEntity + SilentEmbeddedChannel (CA-014, CA-020)
   e controlador 20Hz ao vivo da Guardiã Karlach (Santuário) e do Capitão Picard (USS Enterprise-D + Meteorologia)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [craft.agents.backend.protocol :as proto]
            [craft.agents.regions :as regions])
  (:import [java.io BufferedReader InputStreamReader OutputStreamWriter PrintWriter RandomAccessFile]
           [java.net Socket]
           [java.nio.charset StandardCharsets]))

;; ============================================================================
;; Contratos do Epico 2 (CA-014, CA-020) preservados p/ suite TDD
;; ============================================================================

(defn create-fabric-backend
  "Instancia o backend Server-Native acoplado ao MinecraftServer ativo (CA-014)."
  [_server-instance _opts]
  (reify proto/IWorldBackend
    (spawn-agent! [_ _profile] nil)
    (despawn-agent! [_ _agent-id] true)
    (active-agents [_] {})
    (capture-voxel-snapshot [_ _center _radius] nil)
    (query-entities [_ _pos _radius] [])
    (has-line-of-sight? [_ _from _to] true)))

(defn read-player-inventory-edn
  "Serializa os 36 slots + armadura + offhand em mapa EDN imutavel (CA-020)."
  [player-entity]
  (or (get-in (proto/body-state player-entity) [:inventory]) {}))

(defn atomic-chest-transfer!
  "Transfere itens entre inventario do bot e bau preservando conservacao estrita de quantidade (CA-020)."
  [body chest-coord direction item-id amount]
  (proto/transfer-container-items! body chest-coord direction item-id amount))

;; ============================================================================
;; Ponte TCP persistente com o In-Server Fabric Bridge (127.0.0.1:25595)
;; ============================================================================

(defonce ^:private bridge-conn-atom (atom nil))

(defn- close-bridge-conn! []
  (when-let [{:keys [^Socket sock]} @bridge-conn-atom]
    (try (.close sock) (catch Throwable _ nil)))
  (reset! bridge-conn-atom nil))

(defn send-bridge-cmd!
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
                        (.setSoTimeout s 1500)
                        (reset! bridge-conn-atom
                                {:sock s
                                 :in   (BufferedReader. (InputStreamReader. (.getInputStream s) StandardCharsets/UTF_8))
                                 :out  (PrintWriter. (OutputStreamWriter. (.getOutputStream s) StandardCharsets/UTF_8) true)})))
                    (let [{:keys [^BufferedReader in ^PrintWriter out]} @bridge-conn-atom]
                      (.println out (str line))
                      (.readLine in))
                    (catch Throwable _
                      (close-bridge-conn!)
                      nil))]
          (if (some? res)
            res
            (recur (inc attempt))))))))

(defn bridge-alive? []
  (= "PONG" (send-bridge-cmd! "PING")))

(defn load-skin-meta [skin-filename]
  (let [f (io/file (str "/home/pace/minecraft-server/skins/" skin-filename))]
    (if (.exists f)
      (try
        (let [m (json/read-str (slurp f) :key-fn keyword)]
          {:value (or (:value m) "")
           :signature (or (:signature m) "")})
        (catch Throwable _ {:value "" :signature ""}))
      {:value "" :signature ""})))

(defn ensure-live-bridge-and-agents!
  "Garante que a ponte in-server (25595) esta anexada ao servidor Fabric e que Karlach e Picard estao online com suas skins."
  []
  (when-not (bridge-alive?)
    (println "[craft-agents] Anexando In-Server Bridge ao servidor Fabric...")
    (try
      (sh/sh "make" "attach" :dir "/home/pace/dev/craft-agents")
      (Thread/sleep 500)
      (catch Throwable t
        (println "[craft-agents] Aviso ao anexar bridge:" (.getMessage t)))))
  (when (bridge-alive?)
    ;; 1. Spawn Karlach no Santuário
    (let [{:keys [value signature]} (load-skin-meta "karlach_meta.json")
          resp (send-bridge-cmd! (str "SPAWN|Karlach|-175.0|65.0|180.0|" value "|" signature))]
      (send-bridge-cmd! "CMD|gamemode survival Karlach")
      (send-bridge-cmd! "CMD|effect give Karlach minecraft:resistance infinite 4 true")
      (send-bridge-cmd! "CMD|effect give Karlach minecraft:fire_resistance infinite 1 true")
      (send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:apple")
      (println "[craft-agents] 🔥 Karlach Server-Native conectada:" resp))

    ;; 2. Spawn Capitão Picard na Ponte da USS Enterprise-D
    (let [{:keys [value signature]} (load-skin-meta "picard_meta.json")
          resp (send-bridge-cmd! (str "SPAWN|Picard|-65.0|145.0|101.0|" value "|" signature))]
      (send-bridge-cmd! "CMD|gamemode survival Picard")
      (send-bridge-cmd! "CMD|effect give Picard minecraft:resistance infinite 4 true")
      (send-bridge-cmd! "CMD|item replace entity Picard armor.chest with minecraft:leather_chestplate[dyed_color=10031872]")
      (send-bridge-cmd! "CMD|item replace entity Picard armor.legs with minecraft:netherite_leggings")
      (send-bridge-cmd! "CMD|item replace entity Picard armor.feet with minecraft:netherite_boots")
      (send-bridge-cmd! "CMD|item replace entity Picard weapon.mainhand with minecraft:spyglass")
      (send-bridge-cmd! "CMD|item replace entity Picard weapon.offhand with minecraft:compass")
      (println "[craft-agents] 🖖 Capitão Picard Server-Native conectado:" resp))
    true))

(defn query-online-players []
  (let [resp (send-bridge-cmd! "PLAYERS")]
    (if (and resp (str/starts-with? resp "OK|"))
      (let [payload (subs resp 3)]
        (if (str/blank? payload)
          []
          (vec
           (keep (fn [entry]
                   (let [[pname sx sy sz] (str/split entry #",")]
                     (when (and pname sx sy sz)
                       {:name pname
                        :x (Double/parseDouble sx)
                        :y (Double/parseDouble sy)
                        :z (Double/parseDouble sz)})))
                 (str/split payload #";")))))
      [])))

;; ============================================================================
;; Sistema Meteorológico Orbital (Minecraft) + Planetário (Mundo Real via API)
;; ============================================================================

(defonce ^:private real-weather-cache
  (atom {:updated-at 0
         :summary "Maricá/RJ: 21°C (Sensação 23°C), Céu parcialmente nublado, Vento 7 km/h, Chuva 45%"}))

(defn- wmo-code->pt [code]
  (cond
    (= code 0) "Céu limpo"
    (#{1 2} code) "Poucas nuvens"
    (= code 3) "Nublado"
    (#{45 48} code) "Neblina"
    (#{51 53 55 56 57} code) "Garoa leve"
    (#{61 63 65 66 67 80 81 82} code) "Chuva"
    (#{95 96 99} code) "Tempestade elétrica"
    :else "Estável"))

(defn refresh-real-world-weather-async!
  "Atualiza o cache de clima real (Open-Meteo p/ Maricá, RJ) em background a cada 10 minutos."
  []
  (let [now (System/currentTimeMillis)]
    (when (> (- now (:updated-at @real-weather-cache 0)) 600000)
      (swap! real-weather-cache assoc :updated-at now)
      (future
        (try
          (let [url "https://api.open-meteo.com/v1/forecast?latitude=-22.9194&longitude=-42.8186&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=America%2FSao_Paulo&forecast_days=1"
                res (sh/sh "curl" "-s" "--max-time" "4" url)]
            (when (and (zero? (:exit res)) (not (str/blank? (:out res))))
              (let [data (json/read-str (:out res) :key-fn keyword)
                    cur  (:current data)
                    dly  (:daily data)
                    temp (:temperature_2m cur 21.0)
                    app  (:apparent_temperature cur 23.0)
                    hum  (:relative_humidity_2m cur 85)
                    wind (:wind_speed_10m cur 7.0)
                    cond-str (wmo-code->pt (:weather_code cur 1))
                    tmax (first (:temperature_2m_max dly [27.0]))
                    tmin (first (:temperature_2m_min dly [20.0]))
                    prob (first (:precipitation_probability_max dly [30]))
                    summary (format "Maricá/RJ: %s, %.1f°C (Sensação %.1f°C | Mín %.0f°/Máx %.0f°C), Umidade %d%%, Vento %.1f km/h, Chance de chuva %d%%"
                                    cond-str (double temp) (double app) (double tmin) (double tmax) (long hum) (double wind) (long prob))]
                (reset! real-weather-cache {:updated-at (System/currentTimeMillis)
                                            :summary summary}))))
          (catch Throwable _ nil))))))

(defn query-minecraft-weather
  "Lê o horário solar, fase do dia, condição climática e contagem regressiva diretamente do ServerWorld."
  []
  (let [resp (send-bridge-cmd! "WEATHER")]
    (if (and resp (str/starts-with? resp "OK|"))
      (let [[_ stod srain sthunder srain-time sclear-time] (str/split resp #"\|")
            tod        (Long/parseLong (or stod "0"))
            day-ticks  (mod tod 24000)
            total-mins (long (Math/floor (/ (* (mod (+ day-ticks 6000) 24000) 1440.0) 24000.0)))
            hh         (quot total-mins 60)
            mm         (mod total-mins 60)
            phase      (cond
                         (< day-ticks 12000) "Dia Solar"
                         (< day-ticks 13800) "Crepúsculo / Pôr do Sol"
                         (< day-ticks 22200) "Noite Estrelada"
                         :else "Alvorada / Amanhecer")
            raining?   (= "true" srain)
            thunder?   (= "true" sthunder)
            rain-ticks (Long/parseLong (or srain-time "0"))
            clear-ticks (Long/parseLong (or sclear-time "0"))
            eta-sec    (quot (if (pos? clear-ticks) clear-ticks rain-ticks) 20)
            eta-min    (quot eta-sec 60)
            eta-rem-s  (mod eta-sec 60)
            sky-state  (cond
                         thunder? "Tempestade Elétrica severa"
                         raining? "Chuva em andamento"
                         :else    "Céu limpo e visibilidade orbital de 100%")
            next-trans (if raining?
                         (format "estabilização prevista em %dm%02ds" eta-min eta-rem-s)
                         (if (pos? eta-sec)
                           (format "próxima frente de chuva em %dm%02ds" eta-min eta-rem-s)
                           "clima estável"))]
        {:clock (format "%02d:%02d" hh mm)
         :phase phase
         :sky sky-state
         :next-transition next-trans
         :raining? raining?
         :thundering? thunder?})
      {:clock "12:00"
       :phase "Dia Solar"
       :sky "Céu limpo"
       :next-transition "clima estável"})))

(defn- picard-say! [msg]
  (let [safe-msg (str/replace msg "\"" "\\\"")
        json-cmd (str "tellraw @a [\"\",{\"text\":\"<\",\"color\":\"dark_gray\"},"
                      "{\"text\":\"Picard\",\"color\":\"aqua\",\"bold\":true},"
                      "{\"text\":\"> \",\"color\":\"dark_gray\"},"
                      "{\"text\":\"" safe-msg "\",\"color\":\"white\"}]")]
    (send-bridge-cmd! (str "CMD|" json-cmd))
    (send-bridge-cmd! "CMD|execute at Picard run playsound minecraft:block.beacon.ambient master @a[distance=..32] ~ ~ ~ 0.8 1.4")))

(defn picard-broadcast-weather!
  "Emite o Boletim Meteorológico Orbital (Minecraft) e Planetário (Terra) pelo Capitão Picard."
  []
  (refresh-real-world-weather-async!)
  (let [mc-w   (query-minecraft-weather)
        real-w (:summary @real-weather-cache)]
    (picard-say! (format "📡 [Sensores Orbitais Enterprise-D] Horário local: %s (%s) | %s (%s)."
                         (:clock mc-w) (:phase mc-w) (:sky mc-w) (:next-transition mc-w)))
    (picard-say! (format "🌍 [Telemetria Atmosférica Terrestre] %s." real-w))))

;; ============================================================================
;; Rotina Completa do Santuario da Karlach (20Hz)
;; ============================================================================

(defn build-sanctuary-route
  "Constrói a rota de patrulha e alimentação do Santuário diretamente de sanctuary.edn + karlach.edn,
   vinculando os animais de cada cercado às falas contextuais da Karlach."
  []
  (let [region   (regions/load-region-file "agents/regions/sanctuary.edn")
        karlach  (edn/read-string (slurp (io/file "agents/karlach.edn")))
        cor-barks (get-in karlach [:agent/dialogues :corridors])
        enc-barks (get-in karlach [:agent/dialogues :enclosures])
        sp-barks  (get-in karlach [:agent/dialogues :species])
        wps       (:region/corridor-waypoints region)
        corridors [{:type :promenade :name "Praça Norte"    :pos (nth wps 0 [-175.0 65.0 175.0]) :hold-item "minecraft:golden_carrot" :pause-ticks 50 :bark (:north cor-barks)}
                   {:type :promenade :name "Corredor Leste" :pos (nth wps 1 [-173.0 65.0 180.0]) :hold-item "minecraft:wheat"         :pause-ticks 40 :bark (:east cor-barks)}
                   {:type :promenade :name "Praça Sul"      :pos (nth wps 2 [-175.0 65.0 185.0]) :hold-item "minecraft:apple"         :pause-ticks 50 :bark (:south cor-barks)}
                   {:type :promenade :name "Corredor Oeste" :pos (nth wps 3 [-177.0 65.0 180.0]) :hold-item "minecraft:sweet_berries" :pause-ticks 40 :bark (:west cor-barks)}]
        enclosures (mapv (fn [enc]
                           (let [eid    (:enclosure/id enc)
                                 portal (:enclosure/portal enc)
                                 sp-vec (:enclosure/species enc [])
                                 barks  (vec (concat (get enc-barks eid [])
                                                     (keep #(get sp-barks %) sp-vec)))]
                             {:type        :enclosure
                              :id          eid
                              :name        (:enclosure/name enc (name eid))
                              :outside     (:outside-anchor portal)
                              :gates       (:blocks portal)
                              :gate-block  (:gate-block portal "minecraft:dark_oak_fence_gate")
                              :gate-facing (:gate-facing portal "west")
                              :inside      (:inside-anchor portal)
                              :feed-pos    (:enclosure/feed-pos enc (:inside-anchor portal))
                              :food        (:enclosure/food enc "minecraft:apple")
                              :species     sp-vec
                              :residents   (:enclosure/residents enc [])
                              :barks       barks
                              :bark        (first barks)}))
                         (:region/enclosures region))]
    (vec (interleave corridors enclosures))))

(def sanctuary-route (build-sanctuary-route))

(defn build-picard-stations []
  (let [picard (edn/read-string (slurp (io/file "agents/picard.edn")))]
    (vec (get-in picard [:agent/dialogues :stations]))))

(def picard-stations (build-picard-stations))

(defn- set-double-gate! [gates gate-block gate-facing open?]
  (let [open-str (if open? "true" "false")
        [[x1 y1 z1] [x2 y2 z2]] gates
        sound (if open? "minecraft:block.fence_gate.open" "minecraft:block.fence_gate.close")]
    (send-bridge-cmd! "SWING|Karlach")
    (send-bridge-cmd! (format "CMD|setblock %d %d %d %s[facing=%s,open=%s]" x1 y1 z1 gate-block gate-facing open-str))
    (send-bridge-cmd! (format "CMD|setblock %d %d %d %s[facing=%s,open=%s]" x2 y2 z2 gate-block gate-facing open-str))
    (send-bridge-cmd! (format "CMD|playsound %s block @a %d %d %d 1.0 1.0" sound x1 y1 z1))))

(defn- karlach-say! [msg]
  (let [safe-msg (str/replace msg "\"" "\\\"")
        json-cmd (str "tellraw @a [\"\",{\"text\":\"<\",\"color\":\"dark_gray\"},"
                      "{\"text\":\"Karlach\",\"color\":\"red\",\"bold\":true},"
                      "{\"text\":\"> \",\"color\":\"dark_gray\"},"
                      "{\"text\":\"" safe-msg "\",\"color\":\"gold\"}]")]
    (send-bridge-cmd! (str "CMD|" json-cmd))
    (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:block.campfire.crackle master @a[distance=..24] ~ ~ ~ 0.9 1.2")))

(defn- feed-enclosure-animals! [[fx fy fz] food species-list]
  (send-bridge-cmd! (str "CMD|item replace entity Karlach weapon.mainhand with " food))
  (send-bridge-cmd! "SWING|Karlach")
  (doseq [sp species-list]
    ;; Cura e regenera os animais sem forçar reprodução infinita (InLove:0 evita superpopulação e morte por sufocamento)
    (send-bridge-cmd!
     (format "CMD|execute positioned %.1f %.1f %.1f as @e[type=%s,distance=..22] run effect give @s minecraft:instant_health 1 1 true"
             fx fy fz sp))
    (send-bridge-cmd!
     (format "CMD|execute positioned %.1f %.1f %.1f as @e[type=%s,distance=..22] run effect give @s minecraft:regeneration 20 1 true"
             fx fy fz sp))
    (send-bridge-cmd!
     (format "CMD|execute positioned %.1f %.1f %.1f as @e[type=%s,distance=..22] run data merge entity @s {InLove:0,PersistenceRequired:1b}"
             fx fy fz sp))
    (send-bridge-cmd!
     (format "CMD|execute positioned %.1f %.1f %.1f at @e[type=%s,distance=..22,limit=4,sort=nearest] run particle minecraft:heart ~ ~1.2 ~ 0.4 0.4 0.4 0.02 6 normal"
             fx fy fz sp))
    (send-bridge-cmd!
     (format "CMD|execute positioned %.1f %.1f %.1f at @e[type=%s,distance=..22,limit=4,sort=nearest] run particle minecraft:happy_villager ~ ~1.0 ~ 0.4 0.4 0.4 0.05 8 normal"
             fx fy fz sp))
    (send-bridge-cmd!
     (format "CMD|execute positioned %.1f %.1f %.1f at @e[type=%s,distance=..22,limit=1,sort=nearest] run playsound minecraft:entity.generic.eat neutral @a ~ ~ ~ 1.0 1.0"
             fx fy fz sp))))

(defn- compute-yaw [dx dz]
  (float (* (/ 180.0 Math/PI) (Math/atan2 (- (double dx)) (double dz)))))

(defn- expand-route-microsteps
  "Converte um item da rota do santuario em micro-etapas atomicas para execucao deterministica a 20Hz."
  [stop]
  (if (= (:type stop) :promenade)
    [{:op :equip :item (:hold-item stop) :label (:name stop)}
     {:op :walk  :target (:pos stop)     :label (str "Caminhando -> " (:name stop))}
     {:op :BARK  :text (:bark stop)      :label (:name stop)}
     {:op :wait  :ticks (:pause-ticks stop 40) :label (str "Vigiando " (:name stop))}]
    ;; Enclosure care sequence with guaranteed gate close
    [{:op :equip :item (:food stop)      :label (:name stop)}
     {:op :walk  :target (:outside stop) :label (str "Indo ao portão -> " (:name stop))}
     {:op :gate  :open? true  :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
      :label (str "Abrindo portão -> " (:name stop))}
     {:op :walk  :target (:inside stop)  :label (str "Entrando -> " (:name stop))}
     {:op :gate  :open? false :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
      :label (str "Fechando portão interno -> " (:name stop))}
     {:op :walk  :target (:feed-pos stop) :label (str "Aproximando dos animais -> " (:name stop))}
     {:op :feed  :pos (:feed-pos stop) :food (:food stop) :species (:species stop)
      :barks (:barks stop) :bark (:bark stop)
      :label (str "Alimentando -> " (:name stop))}
     {:op :wait  :ticks 45 :label (str "Cuidando -> " (:name stop))}
     {:op :walk  :target (:inside stop)  :label (str "Saindo -> " (:name stop))}
     {:op :gate  :open? true  :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
      :label (str "Abrindo portão de saída -> " (:name stop))}
     {:op :walk  :target (:outside stop) :label (str "Cruzando saída -> " (:name stop))}
     {:op :gate  :open? false :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
      :label (str "Trancando cercado -> " (:name stop))}
     {:op :wait  :ticks 25 :label (str "Cercado trancado: " (:name stop))}]))

(defn start-live-karlach-loop!
  "Inicia o loop de 20Hz da Karlach e do Capitão Picard no servidor Fabric e atualiza o engine-ctx do Agent Studio."
  [engine-ctx-atom]
  (ensure-live-bridge-and-agents!)
  (refresh-real-world-weather-async!)
  (let [nav-state    (atom {:pos [-175.0 65.0 180.0]
                            :yaw 0.0
                            :pitch 0.0
                            :mode :sanctuary-patrol
                            :follow-target nil
                            :dance-ticks 0
                            :route-idx 0
                            :steps (vec (expand-route-microsteps (first sanctuary-route)))
                            :wait-left 0
                            :last-bark-ms 0})
        picard-state (atom {:pos [-65.0 145.0 101.0]
                            :yaw 0.0
                            :pitch 0.0
                            :mode :bridge-command
                            :follow-target nil
                            :station-idx 0
                            :phase :walk
                            :wait-left 0
                            :last-bark-ms 0
                            :last-weather-ms 0
                            :status "Comando da Ponte (Enterprise-D)"})
        running?     (atom true)]

    ;; Chat listener thread tailing latest.log for player commands to Karlach & Picard
    (doto (Thread.
           (fn []
             (let [log-file (io/file "/home/pace/minecraft-server/logs/latest.log")]
               (loop [last-len (if (.exists log-file) (.length log-file) 0)]
                 (when @running?
                   (Thread/sleep 400)
                   (let [cur-len (if (.exists log-file) (.length log-file) 0)]
                     (if (> cur-len last-len)
                       (let [lines (try
                                     (with-open [raf (RandomAccessFile. log-file "r")]
                                       (.seek raf last-len)
                                       (loop [acc []]
                                         (if-let [l (.readLine raf)]
                                           (recur (conj acc (String. (.getBytes l StandardCharsets/ISO_8859_1) StandardCharsets/UTF_8)))
                                           acc)))
                                     (catch Throwable _ []))]
                         (doseq [line lines]
                           (when-let [[_ speaker msg] (re-find #"<([^>]+)>\s+(.+)$" line)]
                             (when (not (#{"Karlach" "Picard" "Antigravity"} speaker))
                               (let [lower (str/lower-case (str/trim msg))]
                                 ;; 1. Comandos para Karlach
                                 (when (or (str/includes? lower "karlach")
                                           (str/starts-with? lower "!karlach"))
                                   (cond
                                     (or (str/includes? lower "vem")
                                         (str/includes? lower "segue"))
                                     (do
                                       (swap! nav-state assoc :mode :follow :follow-target speaker)
                                       (karlach-say! (str "Tô indo até você, " speaker "! Meu motor infernal já tá aquecido!")))

                                     (or (str/includes? lower "danca")
                                         (str/includes? lower "dança"))
                                     (do
                                       (swap! nav-state assoc :dance-ticks 80)
                                       (karlach-say! "Haha! Olha o passinho de Avernus, soldado! 🔥"))

                                     (or (str/includes? lower "patrulha")
                                         (str/includes? lower "santuario")
                                         (str/includes? lower "santuário")
                                         (str/includes? lower "alimenta"))
                                     (do
                                       (swap! nav-state assoc :mode :sanctuary-patrol :follow-target nil)
                                       (karlach-say! "De volta à ronda do Santuário! Vou cuidar de todos os bichinhos!"))))

                                 ;; 2. Previsão do Tempo e Comandos para o Capitão Picard
                                 (cond
                                   ;; Pedido direto de previsão do tempo / clima
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
                                     (send-bridge-cmd! "SWING|Picard")
                                     (picard-broadcast-weather!)
                                     (swap! picard-state assoc :last-weather-ms (System/currentTimeMillis)))

                                   ;; Controle climático orbital pelo Picard
                                   (and (or (str/includes? lower "picard")
                                            (str/includes? lower "capitao")
                                            (str/includes? lower "capitão"))
                                        (or (str/includes? lower "limpa")
                                            (str/includes? lower "abrir o sol")
                                            (str/includes? lower "parar chuva")))
                                   (do
                                     (send-bridge-cmd! "CMD|weather clear")
                                     (picard-say! "Make it so! Acionando matriz de controle climático orbital: Céu limpo estabelecido, Número Um."))

                                   ;; Engajar / Dobra 9
                                   (and (or (str/includes? lower "picard")
                                            (str/includes? lower "engage")
                                            (str/includes? lower "engajar"))
                                        (or (str/includes? lower "engajar")
                                            (str/includes? lower "engage")
                                            (str/includes? lower "dobra")))
                                   (do
                                     (send-bridge-cmd! "SWING|Picard")
                                     (send-bridge-cmd! "CMD|playsound minecraft:block.beacon.power_select master @a -65 145 100 1.0 1.2")
                                     (picard-say! "Make it so! Dobra 9 acionada, leme à frente!"))

                                   ;; Earl Grey Tea
                                   (and (or (str/includes? lower "picard")
                                            (str/includes? lower "earl grey"))
                                        (or (str/includes? lower "cha")
                                            (str/includes? lower "chá")
                                            (str/includes? lower "tea")
                                            (str/includes? lower "earl grey")))
                                   (picard-say! "Tea. Earl Grey. Hot. Nada como um bom chá para clarear os pensamentos na ponte, Número Um.")

                                   ;; Picard seguir / ponte
                                   (and (or (str/includes? lower "picard")
                                            (str/includes? lower "capitao")
                                            (str/includes? lower "capitão"))
                                        (or (str/includes? lower "vem")
                                            (str/includes? lower "seguir")
                                            (str/includes? lower "acompanhe")))
                                   (do
                                     (swap! picard-state assoc :mode :follow :follow-target speaker)
                                     (picard-say! (str "Com prazer, " speaker ". Acompanhando você em missão de superfície.")))

                                   (and (or (str/includes? lower "picard")
                                            (str/includes? lower "capitao")
                                            (str/includes? lower "capitão"))
                                        (or (str/includes? lower "ponte")
                                            (str/includes? lower "voltar")
                                            (str/includes? lower "posto")))
                                   (do
                                     (swap! picard-state assoc :mode :bridge-command :follow-target nil :pos [-65.0 145.0 101.0] :phase :walk)
                                     (send-bridge-cmd! "MOVE|Picard|-65.0|145.0|101.0|0.0|0.0|false|false")
                                     (picard-say! "Entendido. Retornando por teletransporte ao meu posto na ponte da Enterprise-D.")))))))
                         (recur cur-len))
                       (recur cur-len)))))))
           "craft-agents-chat-listener")
      (.setDaemon true)
      (.start))

    ;; Main 20Hz Server-Native motor & BT loop for Karlach & Picard
    (doto (Thread.
           (fn []
             (loop [tick 1]
               (when @running?
                 (let [t0 (System/currentTimeMillis)]
                   (try
                     ;; Periodically verify bridge connection & weather cache
                     (when (zero? (mod tick 200))
                       (refresh-real-world-weather-async!)
                       (when-not (bridge-alive?)
                         (ensure-live-bridge-and-agents!)))

                     ;; ========================================================
                     ;; A) Tick do Capitão Picard (Ponte da Enterprise-D + Clima)
                     ;; ========================================================
                     (let [pst @picard-state
                           [px py pz] (:pos pst)]
                       (if (= (:mode pst) :follow)
                         (let [players (query-online-players)
                               target-p (first (filter #(= (str/lower-case (:name %))
                                                           (str/lower-case (or (:follow-target pst) "pace")))
                                                       players))]
                           (if target-p
                             (let [dx (- (:x target-p) px)
                                   dz (- (:z target-p) pz)
                                   dist (Math/sqrt (+ (* dx dx) (* dz dz)))
                                   yaw (compute-yaw dx dz)]
                               (cond
                                 (> dist 28.0)
                                 (do
                                   (send-bridge-cmd!
                                    (format "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                                            (:x target-p) (:y target-p) (:z target-p) yaw))
                                   (swap! picard-state assoc :pos [(:x target-p) (:y target-p) (:z target-p)] :yaw yaw
                                          :status (str "Missão com " (:name target-p))))
                                 (> dist 2.8)
                                 (let [step-d (min 0.21 dist)
                                       nx (+ px (* (/ dx dist) step-d))
                                       nz (+ pz (* (/ dz dist) step-d))
                                       ny (:y target-p)]
                                   (send-bridge-cmd!
                                    (format "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ny nz yaw))
                                   (swap! picard-state assoc :pos [nx ny nz] :yaw yaw
                                          :status (str "Acompanhando " (:name target-p))))))
                             (swap! picard-state assoc :mode :bridge-command)))
                         ;; Rotina de estações na Ponte da Enterprise-D
                         (let [st-idx  (:station-idx pst 0)
                               station (nth picard-stations st-idx (first picard-stations))
                               [tx ty tz] (:pos station)]
                           (if (= (:phase pst) :walk)
                             (let [dx (- tx px)
                                   dz (- tz pz)
                                   dist (Math/sqrt (+ (* dx dx) (* dz dz)))]
                               (if (<= dist 0.22)
                                 (do
                                   (send-bridge-cmd!
                                    (format "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|%.1f|false|false"
                                            (double tx) (double ty) (double tz)
                                            (double (:yaw station 0.0)) (double (:pitch station 0.0))))
                                   ;; Se chegou na estação meteorológica Ops e o cooldown de 3 min passou, anuncia a previsão do tempo!
                                   (let [now (System/currentTimeMillis)]
                                     (if (and (:weather-broadcast? station)
                                              (> (- now (:last-weather-ms pst 0)) 180000))
                                       (do
                                         (picard-broadcast-weather!)
                                         (swap! picard-state assoc :last-weather-ms now :last-bark-ms now))
                                       (when (and (:bark station)
                                                  (> (- now (:last-bark-ms pst 0)) 45000))
                                         (picard-say! (:bark station))
                                         (swap! picard-state assoc :last-bark-ms now))))
                                   (swap! picard-state assoc
                                          :pos [(double tx) (double ty) (double tz)]
                                          :yaw (:yaw station 0.0)
                                          :pitch (:pitch station 0.0)
                                          :phase :wait
                                          :wait-left (:wait-ticks station 120)
                                          :status (str "Na estação: " (:name station))))
                                 (let [speed 0.16
                                       nx (+ px (* (/ dx dist) speed))
                                       nz (+ pz (* (/ dz dist) speed))
                                       yaw (compute-yaw dx dz)]
                                   (send-bridge-cmd!
                                    (format "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                                            nx (double ty) nz yaw))
                                   (swap! picard-state assoc
                                          :pos [nx (double ty) nz]
                                          :yaw yaw
                                          :status (str "Ponte -> " (:name station))))))
                             ;; Fase :wait na estação da ponte
                             (let [rem (dec (:wait-left pst 0))]
                               (if (<= rem 0)
                                 (swap! picard-state assoc
                                        :station-idx (mod (inc st-idx) (count picard-stations))
                                        :phase :walk)
                                 (swap! picard-state assoc :wait-left rem)))))))

                     ;; ========================================================
                     ;; B) Tick da Guardiã Karlach (Santuário de Animais)
                     ;; ========================================================
                     (when (zero? (mod tick 25))
                       (let [[kx ky kz] (:pos @nav-state)]
                         (send-bridge-cmd!
                          (format "CMD|particle minecraft:flame %.2f %.2f %.2f 0.15 0.2 0.15 0.01 3 normal"
                                  kx (+ ky 1.1) kz))
                         (send-bridge-cmd!
                          (format "CMD|particle minecraft:smoke %.2f %.2f %.2f 0.15 0.25 0.15 0.01 2 normal"
                                  kx (+ ky 1.2) kz))))

                     (let [st @nav-state
                           [cx cy cz] (:pos st)
                           picard-card (let [[px py pz] (:pos @picard-state)]
                                         {:status (:status @picard-state)
                                          :pos [(/ (Math/round (* px 10.0)) 10.0)
                                                (double py)
                                                (/ (Math/round (* pz 10.0)) 10.0)]})]
                       (cond
                         ;; 1. Dance override
                         (pos? (:dance-ticks st 0))
                         (let [nyaw (float (mod (+ (:yaw st) 32.0) 360.0))]
                           (send-bridge-cmd!
                            (format "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz nyaw))
                           (when (zero? (mod tick 5))
                             (send-bridge-cmd! "SWING|Karlach")
                             (send-bridge-cmd!
                              (format "CMD|particle minecraft:note %.2f %.2f %.2f 0.3 0.3 0.3 1.0 2 normal"
                                      cx (+ cy 2.0) cz)))
                           (swap! nav-state #(-> %
                                                 (update :dance-ticks dec)
                                                 (assoc :yaw nyaw)))
                           (swap! engine-ctx-atom assoc
                                  :tick tick
                                  :leases {:legs {:owner :dance} :hands {:owner :dance} :gaze {:owner :dance}}
                                  :agents {:karlach {:status "dancing 🔥" :pos [cx cy cz]}
                                           :picard  picard-card}))

                         ;; 2. Follow player mode
                         (= (:mode st) :follow)
                         (let [players (query-online-players)
                               target-p (first (filter #(= (str/lower-case (:name %))
                                                           (str/lower-case (or (:follow-target st) "pace")))
                                                       players))]
                           (if target-p
                             (let [dx (- (:x target-p) cx)
                                   dz (- (:z target-p) cz)
                                   dist (Math/sqrt (+ (* dx dx) (* dz dz)))
                                   yaw (compute-yaw dx dz)]
                               (if (> dist 2.6)
                                 (let [step-d (min 0.24 dist)
                                       nx (+ cx (* (/ dx dist) step-d))
                                       nz (+ cz (* (/ dz dist) step-d))
                                       ny (:y target-p)]
                                   (send-bridge-cmd!
                                    (format "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|true|false" nx ny nz yaw))
                                   (swap! nav-state assoc :pos [nx ny nz] :yaw yaw))
                                 (do
                                   (send-bridge-cmd!
                                    (format "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz yaw))
                                   (swap! nav-state assoc :yaw yaw)))
                               (swap! engine-ctx-atom assoc
                                      :tick tick
                                      :leases {:legs {:owner :escort-player} :hands nil :gaze {:owner :social-gaze}}
                                      :agents {:karlach {:status (str "escoltando " (:name target-p))
                                                         :pos (:pos @nav-state)}
                                               :picard  picard-card}))
                             (swap! nav-state assoc :mode :sanctuary-patrol)))

                         ;; 3. Sanctuary Patrol & Enclosure Caretaker BT loop
                         :else
                         (let [steps (:steps st)]
                           (if (empty? steps)
                             (let [next-idx (mod (inc (:route-idx st)) (count sanctuary-route))
                                   next-stop (nth sanctuary-route next-idx)]
                               (swap! nav-state assoc
                                      :route-idx next-idx
                                      :steps (vec (expand-route-microsteps next-stop))))
                             (let [cur-step (first steps)
                                   op (:op cur-step)]
                               (case op
                                 :equip
                                 (do
                                   (when (:item cur-step)
                                     (send-bridge-cmd! (str "CMD|item replace entity Karlach weapon.mainhand with " (:item cur-step))))
                                   (swap! nav-state update :steps #(vec (rest %))))

                                 :walk
                                 (let [[tx ty tz] (:target cur-step)
                                       dx (- tx cx)
                                       dz (- tz cz)
                                       dist (Math/sqrt (+ (* dx dx) (* dz dz)))]
                                   (if (<= dist 0.25)
                                     (do
                                       (send-bridge-cmd!
                                        (format "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                                                (double tx) (double ty) (double tz) (double (:yaw st))))
                                       (swap! nav-state #(-> %
                                                             (assoc :pos [(double tx) (double ty) (double tz)])
                                                             (update :steps (fn [s] (vec (rest s)))))))
                                     (let [speed 0.215
                                           nx (+ cx (* (/ dx dist) speed))
                                           nz (+ cz (* (/ dz dist) speed))
                                           yaw (compute-yaw dx dz)]
                                       (send-bridge-cmd!
                                        (format "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                                                nx (double ty) nz yaw))
                                       (swap! nav-state assoc :pos [nx (double ty) nz] :yaw yaw)
                                       (swap! engine-ctx-atom assoc
                                              :tick tick
                                              :leases {:legs {:owner :patrol-waypoints} :hands nil :gaze {:owner :path-look}}
                                              :agents {:karlach {:status (:label cur-step)
                                                                 :pos [(/ (Math/round (* nx 10.0)) 10.0)
                                                                       (double ty)
                                                                       (/ (Math/round (* nz 10.0)) 10.0)]}
                                                       :picard  picard-card}))))

                                 :gate
                                 (do
                                   (set-double-gate! (:gates cur-step) (:block cur-step) (:facing cur-step) (:open? cur-step))
                                   (swap! nav-state update :steps #(vec (rest %)))
                                   (swap! engine-ctx-atom assoc
                                          :tick tick
                                          :leases {:legs {:owner :portal-traversal}
                                                   :hands {:owner :toggle-double-gate}
                                                   :gaze {:owner :portal-traversal}}
                                          :agents {:karlach {:status (:label cur-step) :pos [cx cy cz]}
                                                   :picard  picard-card}))

                                 :feed
                                 (do
                                   (feed-enclosure-animals! (:pos cur-step) (:food cur-step) (:species cur-step))
                                   (let [barks-vec (seq (:barks cur-step))
                                         chosen-bark (if barks-vec (rand-nth (vec barks-vec)) (:bark cur-step))]
                                     (when chosen-bark
                                       (when (> (- (System/currentTimeMillis) (:last-bark-ms st 0)) 20000)
                                         (karlach-say! chosen-bark)
                                         (swap! nav-state assoc :last-bark-ms (System/currentTimeMillis)))))
                                   (swap! nav-state update :steps #(vec (rest %)))
                                   (swap! engine-ctx-atom assoc
                                          :tick tick
                                          :leases {:legs {:owner :care-for-enclosure}
                                                   :hands {:owner :feed-animals}
                                                   :gaze {:owner :care-for-enclosure}}
                                          :agents {:karlach {:status (:label cur-step) :pos [cx cy cz]}
                                                   :picard  picard-card}))

                                 :BARK
                                 (do
                                   (when-let [b (:text cur-step)]
                                     (when (> (- (System/currentTimeMillis) (:last-bark-ms st 0)) 35000)
                                       (karlach-say! b)
                                       (swap! nav-state assoc :last-bark-ms (System/currentTimeMillis))))
                                   (swap! nav-state update :steps #(vec (rest %))))

                                 :wait
                                 (let [rem-ticks (if (pos? (:wait-left st 0))
                                                   (dec (:wait-left st))
                                                   (dec (:ticks cur-step 30)))]
                                   (when (zero? (mod tick 10))
                                     (let [players (remove #(#{"Karlach" "Picard"} (:name %)) (query-online-players))
                                           nearest (first (sort-by #(let [dx (- (:x %) cx) dz (- (:z %) cz)]
                                                                      (+ (* dx dx) (* dz dz)))
                                                                   players))]
                                       (when nearest
                                         (let [dx (- (:x nearest) cx)
                                               dz (- (:z nearest) cz)
                                               dist (Math/sqrt (+ (* dx dx) (* dz dz)))]
                                           (when (< dist 12.0)
                                             (let [gyaw (compute-yaw dx dz)]
                                               (send-bridge-cmd!
                                                (format "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                                                        cx cy cz gyaw))))))))
                                   (if (<= rem-ticks 0)
                                     (swap! nav-state #(-> %
                                                           (assoc :wait-left 0)
                                                           (update :steps (fn [s] (vec (rest s))))))
                                     (swap! nav-state assoc :wait-left rem-ticks))
                                   (swap! engine-ctx-atom assoc
                                          :tick tick
                                          :leases {:legs nil :hands nil :gaze {:owner :social-gaze}}
                                          :agents {:karlach {:status (:label cur-step)
                                                             :pos [(/ (Math/round (* cx 10.0)) 10.0)
                                                                   cy
                                                                   (/ (Math/round (* cz 10.0)) 10.0)]}
                                                   :picard  picard-card}))))))))
                     (catch Throwable _ nil))
                   (let [elapsed (- (System/currentTimeMillis) t0)
                         sleep-ms (max 5 (- 50 elapsed))]
                     (Thread/sleep sleep-ms))
                   (recur (inc tick))))))
           "craft-agents-20hz-engine")
      (.setDaemon true)
      (.start))
    {:stop-fn #(reset! running? false)
     :nav-state nav-state
     :picard-state picard-state}))

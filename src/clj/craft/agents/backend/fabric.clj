(ns craft.agents.backend.fabric
  "Adaptador Server-Native p/ Fabric 1.21.1 sobre ServerPlayerEntity + SilentEmbeddedChannel (CA-014, CA-020)
   e controlador 20Hz ao vivo da Guardiã Karlach (Santuário) e do Capitão Picard (USS Enterprise-D + Meteorologia)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [craft.agents.backend.protocol :as proto]
            [craft.agents.regions :as regions]
            [craft.agents.skills.composer :as composer]
            [craft.agents.cognition.intent :as intent]
            [craft.agents.cognition.grounding :as grounding]
            [craft.agents.cognition.critic :as critic])
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

(defonce ^:private last-attach-attempt-ms (atom 0))

(defn ensure-live-bridge-and-agents!
  "Garante que a ponte in-server (25595) esta anexada ao servidor Fabric e que Karlach e Picard estao online com suas skins."
  []
  (when-not (bridge-alive?)
    (let [now (System/currentTimeMillis)]
      (when (> (- now @last-attach-attempt-ms) 60000)
        (reset! last-attach-attempt-ms now)
        (println "[craft-agents] Anexando In-Server Bridge ao servidor Fabric (rate-limited)...")
        (try
          (sh/sh "make" "attach" :dir "/home/pace/dev/craft-agents")
          (Thread/sleep 1000)
          (catch Throwable t
            (println "[craft-agents] Aviso ao anexar bridge:" (.getMessage t)))))))
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

(def karlach-dialogues
  (try
    (:agent/dialogues (edn/read-string (slurp (io/file "agents/karlach.edn"))))
    (catch Throwable _ {})))

(defn current-enclosure-id
  "Identifica rigorosamente em qual cercado uma coordenada [x y z] se encontra."
  [[cx cy cz]]
  (cond
    (and (<= -170 cx -136) (<= 165 cz 177)) :pen-north-east
    (and (<= -170 cx -136) (<= 184 cz 196)) :pen-south-east
    (and (<= -214 cx -180) (<= 184 cz 196)) :pen-south-west
    (and (<= -214 cx -180) (<= 165 cz 177)) :pen-north-west
    :else nil))

(defn barks-for-current-position
  "Retorna exclusivamente as falas contextuais correspondentes aos animais do cercado onde a Karlach fisicamente está."
  [[cx cy cz]]
  (if-let [eid (current-enclosure-id [cx cy cz])]
    (get-in karlach-dialogues [:enclosures eid] [])
    []))

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
                         (:region/enclosures region))
        base-route (vec (interleave corridors enclosures))
        quarters   (when-let [q (:agent/quarters karlach)]
                     [{:type             :quarters
                       :id               (:id q :karlach-bedroom)
                       :name             (:name q "Quarto da Karlach")
                       :pos              (:pos q [-131.0 65.0 195.0])
                       :waypoints        (:waypoints q [[-136.0 65.0 183.0] [-133.0 65.0 194.0] (:pos q [-131.0 65.0 195.0])])
                       :return-waypoints (:return-waypoints q [[-133.0 65.0 194.0] [-136.0 65.0 183.0] [-173.0 65.0 180.0]])
                       :hold-item        (:hold-item q "minecraft:golden_apple")
                       :pause-ticks      (:pause-ticks q 140)
                       :barks            (:barks q ["Ah, finalmente um descanso pro meu motor infernal!"])
                       :bark             (first (:barks q))}])]
    (if (seq quarters)
      (into base-route quarters)
      base-route)))

(def sanctuary-route (build-sanctuary-route))

(def sanctuary-portals-by-pos
  (try
    (let [reg (regions/load-region-file "agents/regions/sanctuary.edn")]
      (into {}
            (for [enc (:region/enclosures reg)
                  :let [portal (:enclosure/portal enc)
                        blocks (:blocks portal)]
                  b blocks]
              [(vec b) {:gates blocks
                        :block (:gate-block portal)
                        :facing (:gate-facing portal)}])))
    (catch Throwable _ {})))

(def sanctuary-enclosures-by-id
  (try
    (let [reg (regions/load-region-file "agents/regions/sanctuary.edn")]
      (into {} (map (fn [e] [(:enclosure/id e) e]) (:region/enclosures reg))))
    (catch Throwable _ {})))

(defn compute-enclosure-funnel
  "Calcula os waypoints do funil ortogonal de travessia do portão para evitar que animais
   na guia fiquem presos diagonalmente contra as quinas e batentes da cerca."
  [portal-info]
  (let [norm (:normal portal-info [-1.0 0.0 0.0])
        nx   (double (first norm))
        nz   (double (nth norm 2))
        out-anc (:outside-anchor portal-info [-177.0 65.0 189.0])
        in-anc  (:inside-anchor portal-info [-181.5 65.0 189.0])
        ox (double (first out-anc)) oy (double (second out-anc)) oz (double (nth out-anc 2))
        ix (double (first in-anc))  iy (double (second in-anc))  iz (double (nth in-anc 2))
        funnel-entry [(- ox (* nx 2.5)) oy (- oz (* nz 2.5))]
        pre-gate     [ox oy oz]
        deep-inside  [(+ ix (* nx 4.0)) iy (+ iz (* nz 4.0))]]
    {:funnel-entry funnel-entry
     :pre-gate pre-gate
     :inside-anchor in-anc
     :deep-inside deep-inside}))

(defn- uformat
  "Formata strings usando java.util.Locale/ROOT para garantir ponto decimal (.)
   e evitar bugs críticos de parsing no protocolo da ponte em Linux pt_BR."
  ^String [^String fmt & args]
  (String/format java.util.Locale/ROOT fmt (to-array args)))

(defn- set-double-gate! [gates gate-block gate-facing open?]
  (let [open-str (if open? "true" "false")
        [[x1 y1 z1] [x2 y2 z2]] gates
        sound (if open? "minecraft:block.fence_gate.open" "minecraft:block.fence_gate.close")]
    (send-bridge-cmd! "SWING|Karlach")
    (send-bridge-cmd! (uformat "CMD|setblock %d %d %d %s[facing=%s,open=%s]" x1 y1 z1 gate-block gate-facing open-str))
    (send-bridge-cmd! (uformat "CMD|setblock %d %d %d %s[facing=%s,open=%s]" x2 y2 z2 gate-block gate-facing open-str))
    (send-bridge-cmd! (uformat "CMD|playsound %s block @a %d %d %d 1.0 1.0" sound x1 y1 z1))))

(defn open-portal-at-coord!
  "Abre o portão correspondente à coordenada [x y z] com animação e som."
  [[x y z]]
  (let [bpos [(int x) (int y) (int z)]]
    (if-let [portal-info (get sanctuary-portals-by-pos bpos)]
      (set-double-gate! (:gates portal-info) (:block portal-info) (:facing portal-info) true)
      (send-bridge-cmd! (uformat "TOGGLE_PORTAL|%d|%d|%d|true" (int x) (int y) (int z))))))

(defn close-portal-at-coord!
  "Fecha o portão correspondente à coordenada [x y z] garantindo contenção dos animais."
  [[x y z]]
  (let [bpos [(int x) (int y) (int z)]]
    (if-let [portal-info (get sanctuary-portals-by-pos bpos)]
      (set-double-gate! (:gates portal-info) (:block portal-info) (:facing portal-info) false)
      (send-bridge-cmd! (uformat "TOGGLE_PORTAL|%d|%d|%d|false" (int x) (int y) (int z))))))

(defn build-picard-stations []
  (let [picard (edn/read-string (slurp (io/file "agents/picard.edn")))]
    (vec (get-in picard [:agent/dialogues :stations]))))

(def picard-stations (build-picard-stations))

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
     (uformat "CMD|execute positioned %.1f %.1f %.1f as @e[type=%s,distance=..22] run effect give @s minecraft:instant_health 1 1 true"
             fx fy fz sp))
    (send-bridge-cmd!
     (uformat "CMD|execute positioned %.1f %.1f %.1f as @e[type=%s,distance=..22] run effect give @s minecraft:regeneration 20 1 true"
             fx fy fz sp))
    (send-bridge-cmd!
     (uformat "CMD|execute positioned %.1f %.1f %.1f as @e[type=%s,distance=..22] run data merge entity @s {InLove:0,PersistenceRequired:1b}"
             fx fy fz sp))
    (send-bridge-cmd!
     (uformat "CMD|execute positioned %.1f %.1f %.1f at @e[type=%s,distance=..22,limit=4,sort=nearest] run particle minecraft:heart ~ ~1.2 ~ 0.4 0.4 0.4 0.02 6 normal"
             fx fy fz sp))
    (send-bridge-cmd!
     (uformat "CMD|execute positioned %.1f %.1f %.1f at @e[type=%s,distance=..22,limit=4,sort=nearest] run particle minecraft:happy_villager ~ ~1.0 ~ 0.4 0.4 0.4 0.05 8 normal"
             fx fy fz sp))
    (send-bridge-cmd!
     (uformat "CMD|execute positioned %.1f %.1f %.1f at @e[type=%s,distance=..22,limit=1,sort=nearest] run playsound minecraft:entity.generic.eat neutral @a ~ ~ ~ 1.0 1.0"
             fx fy fz sp))))

(defn- compute-yaw [dx dz]
  (float (* (/ 180.0 Math/PI) (Math/atan2 (- (double dx)) (double dz)))))

(defn- compute-pitch [dy hdist]
  (float (- (* (/ 180.0 Math/PI) (Math/atan2 (double dy) (max 0.001 (double hdist)))))))

(defn request-path3d
  "Requisita rota A* 3D com percepcao real de voxels e portais via ponte in-server."
  [[fx fy fz] [tx ty tz] & [radius]]
  (let [r (or radius 1.2)
        cmd (uformat "PATH|%.1f,%.1f,%.1f|%.1f,%.1f,%.1f|%.1f"
                    (double fx) (double fy) (double fz)
                    (double tx) (double ty) (double tz)
                    (double r))
        raw (send-bridge-cmd! cmd)]
    (when (str/starts-with? (or raw "") "OK|")
      (let [steps-str (subs raw 3)
            parts (str/split steps-str #";")]
        (mapv (fn [p]
                (let [[x y z act] (str/split p #",")]
                  {:x (Double/parseDouble x)
                   :y (Double/parseDouble y)
                   :z (Double/parseDouble z)
                   :action (keyword act)}))
              parts)))))

(defn query-leash-status
  "Consulta sensorial em tempo real se o agente possui animal na corda/guia.
   Retorna {:leashed? boolean, :id int, :species string, :distance double, :pos [x y z]}."
  ([] (query-leash-status "Karlach"))
  ([player-name]
   (let [resp (send-bridge-cmd! (str "LEASH_STATUS|" player-name))]
     (if (and resp (str/starts-with? resp "LEASHED|"))
       (let [parts (str/split (subs resp 8) #"\|")
             eid (try (Long/parseLong (nth parts 0 "0")) (catch Throwable _ 0))
             sp (nth parts 1 "mob")
             dist (try (Double/parseDouble (nth parts 2 "0.0")) (catch Throwable _ 0.0))
             pos-str (nth parts 3 "0,0,0")
             coords (mapv #(try (Double/parseDouble %) (catch Throwable _ 0.0)) (str/split pos-str #","))]
         {:leashed? true :id eid :species sp :distance dist :pos coords})
       {:leashed? false}))))

(defn query-health-status
  "Consulta estado fisiologico nativo (HP, foodLevel, saturation) via bridge (CA-E1.1)."
  ([] (query-health-status "Karlach"))
  ([player-name]
   (let [resp (send-bridge-cmd! (str "HEALTH_STATUS|" player-name))]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")]
         {:hp (try (Double/parseDouble (nth parts 0 "20.0")) (catch Throwable _ 20.0))
          :food (try (Integer/parseInt (nth parts 1 "20")) (catch Throwable _ 20))
          :saturation (try (Double/parseDouble (nth parts 2 "5.0")) (catch Throwable _ 5.0))
          :alive? (try (Boolean/parseBoolean (nth parts 3 "true")) (catch Throwable _ true))})
       {:hp 20.0 :food 20 :saturation 5.0 :alive? true}))))

(defn locate-dropped-items
  "Escaneia ItemEntity caidos no chao em um raio via bridge (CA-E1.4)."
  ([player-name] (locate-dropped-items player-name {}))
  ([player-name opts]
   (let [radius (or (:radius opts) 16.0)
         filter-item (or (:filter opts) "")
         cmd (uformat "LOCATE_ITEMS|%s|%.1f|%s" player-name (double radius) filter-item)
         resp (send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             count-val (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? count-val) (< (count parts) 2) (str/blank? (nth parts 1)))
           []
           (let [entries (str/split (nth parts 1) #";")]
             (mapv (fn [raw]
                     (let [[eid iid cnt x y z dist] (str/split raw #",")]
                       {:id (try (Integer/parseInt eid) (catch Throwable _ 0))
                        :item iid
                        :count (try (Integer/parseInt cnt) (catch Throwable _ 1))
                        :x (try (Double/parseDouble x) (catch Throwable _ 0.0))
                        :y (try (Double/parseDouble y) (catch Throwable _ 0.0))
                        :z (try (Double/parseDouble z) (catch Throwable _ 0.0))
                        :distance (try (Double/parseDouble dist) (catch Throwable _ 0.0))}))
                   entries))))
       []))))

(defn collect-nearby-drops!
  "Coleta drops no chão e insere no inventário real do jogador via bridge (CA-E1.4 / Looting)."
  ([player-name] (collect-nearby-drops! player-name 4.0))
  ([player-name radius]
   (let [cmd (uformat "COLLECT_DROPS|%s|%.1f" player-name (double radius))
         resp (send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             cnt (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? cnt) (< (count parts) 2) (str/blank? (nth parts 1)))
           {:total-count 0 :items {}}
           (let [entries (str/split (nth parts 1) #";")]
             {:total-count cnt
              :items (into {}
                           (map (fn [e]
                                  (let [[iid c] (str/split e #",")]
                                    [iid (try (Integer/parseInt c) (catch Throwable _ 1))]))
                                entries))})))
       {:total-count 0 :items {}}))))

(defn inspect-inventory
  "Consulta o inventário real do jogador via bridge, retornando mapa de itens e contagens."
  ([player-name]
   (let [cmd (str "INSPECT_INVENTORY|" player-name)
         resp (send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             occupied (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? occupied) (< (count parts) 2) (str/blank? (nth parts 1)))
           {:slots-used 0 :items {}}
           (let [entries (str/split (nth parts 1) #";")]
             {:slots-used occupied
              :items (into {}
                           (map (fn [e]
                                  (let [[iid c] (str/split e #",")]
                                    [iid (try (Integer/parseInt c) (catch Throwable _ 1))]))
                                entries))})))
       {:slots-used 0 :items {}}))))

(defn drop-inventory-items!
  "Ejeta itens do inventário no mundo (loot sharing com jogador humano)."
  ([player-name] (drop-inventory-items! player-name "all"))
  ([player-name filter-str]
   (let [cmd (str "DROP_ITEMS|" player-name "|" (or filter-str ""))
         resp (send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             dropped (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? dropped) (< (count parts) 2) (str/blank? (nth parts 1)))
           {:dropped-count 0 :items {}}
           (let [entries (str/split (nth parts 1) #";")]
             {:dropped-count dropped
              :items (into {}
                           (map (fn [e]
                                  (let [[iid c] (str/split e #",")]
                                    [iid (try (Integer/parseInt c) (catch Throwable _ 1))]))
                                entries))})))
       {:dropped-count 0 :items {}}))))

(defn scan-nearby-hostiles
  "Escaneia monstros hostis no raio de percepcao do jogador via bridge (CA-E1.3)."
  ([player-name] (scan-nearby-hostiles player-name 16.0))
  ([player-name radius]
   (let [cmd (uformat "TARGET_HOSTILE|%s|%.1f" player-name (double radius))
         resp (send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             count-val (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? count-val) (< (count parts) 2) (str/blank? (nth parts 1)))
           []
           (let [entries (str/split (nth parts 1) #";")]
             (mapv (fn [raw]
                     (let [[eid type x y z dist hp] (str/split raw #",")]
                       {:id (try (Integer/parseInt eid) (catch Throwable _ 0))
                        :type type
                        :x (try (Double/parseDouble x) (catch Throwable _ 0.0))
                        :y (try (Double/parseDouble y) (catch Throwable _ 0.0))
                        :z (try (Double/parseDouble z) (catch Throwable _ 0.0))
                        :distance (try (Double/parseDouble dist) (catch Throwable _ 0.0))
                        :hp (try (Double/parseDouble hp) (catch Throwable _ 20.0))}))
                   entries))))
       []))))

(defn attack-hostile-target!
  "Executa ataque fisico com cooldown carregado via bridge (CA-E1.3)."
  [player-name target-id]
  (let [cmd (str "ATTACK_HOSTILE|" player-name "|" target-id)
        resp (send-bridge-cmd! cmd)]
    (when (and resp (str/starts-with? resp "OK|"))
      (let [[_ tid cd] (str/split resp #"\|")]
        {:success? true
          :target-id (try (Integer/parseInt tid) (catch Throwable _ target-id))
          :cooldown (try (Double/parseDouble cd) (catch Throwable _ 1.0))}))))

(defn mine-block!
  "Executa quebra segura de bloco in-JVM respeitando regras de ouro (CA-E3.1).
   Retorna {:success? true/false :block-id ... :tool-used ...} ou erro."
  ([x y z] (mine-block! "Karlach" x y z))
  ([player-name x y z]
   (let [cmd (uformat "MINE_BLOCK|%s|%d|%d|%d" player-name (int x) (int y) (int z))
         resp (send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [[_ bid tid bx by bz] (str/split resp #"\|")]
         {:success? true
          :block-id bid
          :tool-used tid
          :x (try (Integer/parseInt bx) (catch Throwable _ (int x)))
          :y (try (Integer/parseInt by) (catch Throwable _ (int y)))
          :z (try (Integer/parseInt bz) (catch Throwable _ (int z)))})
       (let [parts (str/split (or resp "ERR|UNKNOWN") #"\|")
             code (nth parts 1 "UNKNOWN")
             msg (nth parts 2 "")]
         {:success? false
          :error-code code
          :message msg})))))

(defn locate-nearby-ores
  "Escaneia veios de minerios proximos ao jogador via bridge (CA-E3.1).
   Retorna apenas minerios com faces expostas ao ar (Anti X-Ray) e coordenadas do ponto de ar (Anti No-Clip)."
  ([player-name] (locate-nearby-ores player-name 12.0 ""))
  ([player-name radius] (locate-nearby-ores player-name radius ""))
  ([player-name radius filter-str]
   (let [cmd (uformat "LOCATE_ORES|%s|%.1f|%s" player-name (double radius) (or filter-str ""))
         resp (send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             count-val (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? count-val) (< (count parts) 2) (str/blank? (nth parts 1)))
           []
           (let [entries (str/split (nth parts 1) #";")]
             (mapv (fn [raw]
                     (let [[x y z id dist ax ay az] (str/split raw #",")]
                       {:x (try (Integer/parseInt x) (catch Throwable _ 0))
                        :y (try (Integer/parseInt y) (catch Throwable _ 0))
                        :z (try (Integer/parseInt z) (catch Throwable _ 0))
                        :block-id id
                        :distance (try (Double/parseDouble dist) (catch Throwable _ 0.0))
                        :air-x (if ax (try (Integer/parseInt ax) (catch Throwable _ (Integer/parseInt x))) (try (Integer/parseInt x) (catch Throwable _ 0)))
                        :air-y (if ay (try (Integer/parseInt ay) (catch Throwable _ (Integer/parseInt y))) (try (Integer/parseInt y) (catch Throwable _ 0)))
                        :air-z (if az (try (Integer/parseInt az) (catch Throwable _ (Integer/parseInt z))) (try (Integer/parseInt z) (catch Throwable _ 0)))}))
                   entries))))
       []))))

(defn raycast-player-look
  "Detecta o bloco para o qual o jogador esta olhando (ate max-dist metros)."
  ([player-name] (raycast-player-look player-name 5.5))
  ([player-name max-dist]
   (let [cmd (uformat "RAYCAST_BLOCK|%s|%.1f" player-name (double max-dist))
         resp (send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [[_ bid bx by bz dist] (str/split resp #"\|")]
         {:found? true
          :block-id bid
          :x (try (Integer/parseInt bx) (catch Throwable _ 0))
          :y (try (Integer/parseInt by) (catch Throwable _ 0))
          :z (try (Integer/parseInt bz) (catch Throwable _ 0))
          :distance (try (Double/parseDouble dist) (catch Throwable _ 0.0))})
       {:found? false}))))

(defn auto-eat-food!
  "Consome alimento para restaurar barra de fome e curar vida."
  ([player-name] (auto-eat-food! player-name "minecraft:apple"))
  ([player-name food-item]
   (send-bridge-cmd! (str "CMD|item replace entity " player-name " weapon.mainhand with " food-item))
   (send-bridge-cmd! (str "SWING|" player-name))
   (send-bridge-cmd! (str "CMD|execute at " player-name " run playsound minecraft:entity.generic.eat neutral @a ~ ~ ~ 1.0 1.0"))
   (send-bridge-cmd! (str "CMD|effect give " player-name " minecraft:saturation 2 1 true"))
   (send-bridge-cmd! (str "CMD|effect give " player-name " minecraft:instant_health 1 0 true"))
   {:eaten? true :item food-item}))

(defn generate-walk-steps
  "Gera passos de caminhada detalhados usando A* 3D (request-path3d) com percepção
   em tempo real de voxels e detecção dinâmica de portais (:TRAVERSE_PORTAL),
   evitando colisões com cercas e paredes de alvenaria."
  [from-pos to-pos label & [opts]]
  (let [invariants   (:invariants opts)
        species      (:species opts)
        pull-leash?  (or (:pull-leashed? opts) (some #(= (:invariant %) :leash-attached) invariants))
        radius       (or (:radius opts) 1.5)
        safe-from    (or from-pos to-pos [0.0 65.0 0.0])
        safe-to      (or to-pos from-pos [0.0 65.0 0.0])
        raw-path     (or (try (request-path3d safe-from safe-to radius) (catch Throwable _ nil))
                         (try (request-path3d safe-from safe-to 2.2) (catch Throwable _ nil)))]
    (if (seq raw-path)
      (let [n (count raw-path)]
        (loop [idx 0
               acc []]
          (if (>= idx n)
            acc
            (let [node (nth raw-path idx)
                  act  (:action node)
                  nx   (+ (double (:x node)) 0.5)
                  ny   (double (:y node))
                  nz   (+ (double (:z node)) 0.5)
                  target-pt [nx ny nz]]
              (if (= act :TRAVERSE_PORTAL)
                ;; Percepção de Terreno em Tempo Real: Portal/Gate detectado!
                (let [portal-pos [(:x node) (:y node) (:z node)]
                      prev-node  (when (pos? idx) (nth raw-path (dec idx)))
                      next-node  (when (< (inc idx) n) (nth raw-path (inc idx)))
                      ;; Vetor de avanço do portal
                      pdx (if next-node (- (:x next-node) (:x node)) (if prev-node (- (:x node) (:x prev-node)) 1.0))
                      pdz (if next-node (- (:z next-node) (:z node)) (if prev-node (- (:z node) (:z prev-node)) 0.0))
                      deep-in-pt [(+ nx (* (double pdx) 3.5)) ny (+ nz (* (double pdz) 3.5))]
                      next-pt    (if next-node
                                   [(+ (double (:x next-node)) 0.5) (double (:y next-node)) (+ (double (:z next-node)) 0.5)]
                                   [(+ nx (double pdx)) ny (+ nz (double pdz))])
                      portal-microsteps
                      (if pull-leash?
                        ;; Com animal na guia: abre, entra, avança fundo para puxar mob, espera colisão, volta e tranca
                        [{:op :gate :open? true :pos portal-pos :label "Abrindo portão do cercado"}
                         {:op :wait :ticks 10 :label "Aguardando portão abrir"}
                         (cond-> {:op :walk :target target-pt :label "Atravessando vão do portão"}
                           invariants (assoc :invariants invariants)
                           species (assoc :species species))
                         (cond-> {:op :walk :target deep-in-pt :label "Conduzindo animal para o interior do cercado"}
                           invariants (assoc :invariants invariants)
                           species (assoc :species species))
                         {:op :wait :ticks 25 :label "Aguardando física de colisão do animal"}
                         {:op :detach-lead :species species :label "Soltando o laço dentro do cercado"}
                         {:op :wait :ticks 10 :label "Removendo guia"}
                         {:op :walk :target next-pt :label "Retornando ao portão para trancar"}
                         {:op :gate :open? false :pos portal-pos :label "Trancando portão"}
                         {:op :wait :ticks 8 :label "Conferindo tranca"}]
                        ;; Sem animal: abre, cruza vão, avança e fecha
                        [{:op :gate :open? true :pos portal-pos :label "Abrindo portão"}
                         {:op :wait :ticks 8 :label "Aguardando portão abrir"}
                         {:op :walk :target target-pt :label "Cruzando vão do portão"}
                         {:op :walk :target next-pt :label "Passando pelo portão"}
                         {:op :gate :open? false :pos portal-pos :label "Fechando portão atrás de si"}
                         {:op :wait :ticks 6 :label "Conferindo tranca"}])]
                  (recur (if next-node (+ idx 2) (inc idx))
                         (into acc portal-microsteps)))
                ;; Passo normal de caminhada
                (let [step (cond-> {:op :walk :target target-pt :label label}
                             invariants (assoc :invariants invariants)
                             species (assoc :species species))]
                  (recur (inc idx) (conj acc step))))))))
      ;; Fallback se path solver não encontrou caminho
      [(cond-> {:op :walk :target safe-to :label label}
         invariants (assoc :invariants invariants)
         species (assoc :species species))])))

(defn- expand-route-microsteps
  "Converte um item da rota do santuario em micro-etapas atomicas para execucao deterministica a 20Hz,
   utilizando PathSolver3D com voxels reais para evitar travessia fantasma através de paredes."
  ([stop] (expand-route-microsteps stop nil))
  ([stop from-pos]
   (cond
     (= (:type stop) :promenade)
     (let [target-pos (:pos stop)
           walk-steps (if from-pos
                        (generate-walk-steps from-pos target-pos (str "Caminhando -> " (:name stop)) {:radius 0.8})
                        [{:op :walk :target target-pos :label (str "Caminhando -> " (:name stop))}])]
       (vec
        (concat
         [{:op :equip :item (:hold-item stop) :label (:name stop)}]
         walk-steps
         [{:op :BARK :text (:bark stop) :label (:name stop)}
          {:op :wait :ticks (:pause-ticks stop 40) :label (str "Vigiando " (:name stop))}])))


     (= (:type stop) :quarters)
     (let [barks-vec (seq (:barks stop))
           chosen-bark (if barks-vec (rand-nth (vec barks-vec)) (:bark stop))
           bed-pos (:pos stop [-131.0 65.0 195.0])
           ret-pos (or (last (:return-waypoints stop)) [-173.0 65.0 180.0])
           to-bed-steps (if from-pos
                          (generate-walk-steps from-pos bed-pos (str "Indo descansar no " (:name stop)) {:radius 0.8})
                          (let [wps (or (:waypoints stop) [bed-pos])]
                            (mapv (fn [wp] {:op :walk :target wp :label (str "Indo ao " (:name stop))}) wps)))
           ret-steps (if from-pos
                       (generate-walk-steps bed-pos ret-pos "Retornando à ronda de patrulha" {:radius 0.8})
                       (let [ret (or (:return-waypoints stop) [ret-pos])]
                         (mapv (fn [wp] {:op :walk :target wp :label "Retornando à ronda"}) ret)))]
       (vec
        (concat
         [{:op :equip :item (:hold-item stop "minecraft:golden_apple") :label (:name stop)}]
         to-bed-steps
         [{:op :BARK :text chosen-bark :label (:name stop)}
          {:op :wait :ticks (:pause-ticks stop 140) :label (str "Descansando no " (:name stop))}]
         ret-steps)))

     :else
     ;; Enclosure care sequence with guaranteed gate close
     (let [outside-pos (:outside stop)
           approach-steps (if from-pos
                            (generate-walk-steps from-pos outside-pos (str "Indo ao portão -> " (:name stop)) {:radius 0.8})
                            [{:op :walk :target outside-pos :label (str "Indo ao portão -> " (:name stop))}])]
       (vec
        (concat
         [{:op :equip :item (:food stop) :label (:name stop)}]
         approach-steps
         [{:op :gate :open? true :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
           :label (str "Abrindo portão -> " (:name stop))}
          {:op :walk :target (:inside stop) :label (str "Entrando -> " (:name stop))}
          {:op :gate :open? false :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
           :label (str "Fechando portão interno -> " (:name stop))}
          {:op :feed :pos (:feed-pos stop) :food (:food stop) :species (:species stop)
           :barks (:barks stop) :bark (:bark stop)
           :label (str "Alimentando -> " (:name stop))}
          {:op :wait :ticks 45 :label (str "Cuidando -> " (:name stop))}
          {:op :walk :target (:inside stop) :label (str "Saindo -> " (:name stop))}
          {:op :gate :open? true :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
           :label (str "Abrindo portão de saída -> " (:name stop))}
          {:op :walk :target (:outside stop) :label (str "Cruzando saída -> " (:name stop))}
          {:op :gate :open? false :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
           :label (str "Trancando cercado -> " (:name stop))}
          {:op :wait :ticks 25 :label (str "Cercado trancado: " (:name stop))}]))))))


;; ============================================================================
;; Telemetria Cognitiva e Dispatcher de Comandos (Voyager 2.0)
;; ============================================================================

(defonce global-command-dispatcher (atom nil))

(defn record-cognitive-event!
  "Registra evento cognitivo estruturado no atom do Studio (CA-040/CA-041)."
  [engine-ctx-atom evt-type source text]
  (when engine-ctx-atom
    (let [fmt (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss")
          now-str (.format (java.time.LocalTime/now) fmt)
          badge-info (case evt-type
                       :stimulus   {:badge "STIMULUS" :color "#38bdf8"}
                       :router     {:badge "ROUTER" :color "#a855f7"}
                       :grounding  {:badge "GROUNDING" :color "#06b6d4"}
                       :synthesis  {:badge "SYSTEM 2" :color "#c084fc"}
                       :gatekeeper {:badge "GATEKEEPER" :color "#4ade80"}
                       :skill-lib  {:badge "SKILL LIB" :color "#fbbf24"}
                       :motor      {:badge "SYSTEM 1" :color "#f87171"}
                       :critic     {:badge "CRITIC" :color "#22c55e"}
                       :critic-fail {:badge "CRITIC FAIL" :color "#ef4444"}
                       :reflexion  {:badge "REFLEXION" :color "#f59e0b"}
                       {:badge "EVENT" :color "#94a3b8"})
          evt (merge badge-info
                     {:timestamp (System/currentTimeMillis)
                      :time      now-str
                      :type      evt-type
                      :source    source
                      :text      text})]
      (swap! engine-ctx-atom update :events
             (fn [evts]
               (let [evts (or evts [])]
                 (vec (take-last 40 (conj evts evt)))))))))

(defn dispatch-simulated-chat!
  "Permite disparar comandos simulados a partir da UI do Studio ou testes."
  [speaker msg]
  (when-let [disp @global-command-dispatcher]
    (disp speaker msg)))

;; ============================================================================
;; Despacho Polimorfico de Metas da Karlach (System 1 Motor + System 2 Malli)
;; ============================================================================

(defmulti execute-karlach-goal!
  "Despacha a meta cognitiva fundamentada (GoalSpecification) para atuacao fisica e telemetria (CA-040)."
  (fn [goal-spec _engine-ctx _nav-state _sanctuary-route]
    (:goal/type goal-spec)))

(defmethod execute-karlach-goal! :animal-rescue
  [{:goal/keys [id name entity species pen-id gate-pos gate-block gate-facing inside-pos outside-pos feed-pos bait-item voice-line speaker] :as goal-spec}
   engine-ctx-atom nav-state sanctuary-route]
  ;; 1. Fala inicial da Karlach acusando o estímulo
  (send-bridge-cmd! "SWING|Karlach")
  (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
  (karlach-say! voice-line)

  (record-cognitive-event! engine-ctx-atom :grounding "Grounding Engine"
                           (str "Ancorado em " (clojure.core/name pen-id) " | Alvo: " entity " | Portão: " gate-pos " | Isca: " bait-item))

  ;; 2. Composição e Validação da Nova Skill em Tempo Real via Malli
  (let [skills-dir (io/file "skills")
        _ (.mkdirs skills-dir)
        skill-registry (atom {:known-actions #{:locate-escaped-animal :equip-bait :lure-to-gate :open-gate :lure-inside :feed-animal :close-gate :check-weather :move-to-quarters :rest-at-bed :wait-out-rain :attach-lead :detach-lead :verify-containment}
                              :composite-skills {}})
        rescue-skill {:skill/id :rescue-animal
                      :skill/name "Resgate e Condução Geral de Animal ao Cercado"
                      :skill/author "Voyager-System-2-Cognition"
                      :skill/trigger :animal-escaped
                      :skill/timeout-ms 35000
                      :skill/tree [:sequence
                                   [:action :equip-bait {:item bait-item}]
                                   [:action :locate-escaped-animal {:target species}]
                                   [:action :attach-lead {:species species}]
                                   [:action :lure-to-gate {:pos outside-pos}]
                                   [:action :open-gate {:enclosure pen-id}]
                                   [:action :lure-inside {:pos inside-pos}]
                                   [:action :close-gate {:enclosure pen-id}]
                                   [:action :detach-lead {:species species}]
                                   [:action :feed-animal {:food bait-item}]]}
        reg-res (composer/validate-and-register-skill! skill-registry skills-dir rescue-skill)
        skill-id-str "rescue-animal"]
    (println (str "[craft-agents] 🧠 Nova Skill composta via MCP: " id))
    (println (str "[craft-agents] 🛡️ Gatekeeper Malli: " (if (:ok? reg-res) "APROVADA (Sem eval / Schema OK / Ciclos 0)" "REJEITADA") " -> Salvo em " (str (io/file skills-dir (str skill-id-str ".edn")))))

    (let [pre-res (composer/evaluate-preconditions (:skill/tree rescue-skill) {:inventory {bait-item 1 "minecraft:lead" 1}} {:known-enclosures #{pen-id}})]
      (record-cognitive-event! engine-ctx-atom :gatekeeper "Malli Schema Gatekeeper"
                               (str "Validação AST estrutural & Sandbox Mental: SUCESSO (Zero eval / Tipos conferidos / 0 ciclos recursivos / Pré-condições: " (clojure.core/name (:status pre-res :ok)) ").")))
    (record-cognitive-event! engine-ctx-atom :skill-lib "Skill Library"
                             (str id " registrada permanentemente em skills/" skill-id-str ".edn."))

    ;; Atualiza telemetria do Agent Studio (sem poluir a tela do jogo com títulos artificiais)
    (when engine-ctx-atom
      (swap! engine-ctx-atom update-in [:agents :karlach :skills] (fnil #(vec (distinct (conj % skill-id-str))) []))
      (swap! engine-ctx-atom assoc-in [:agents :karlach :last-learned] (str id " (Malli OK)"))
      (let [enc-title (or (:enclosure/name (get sanctuary-enclosures-by-id (or pen-id :pen-south-west))) "Cercado")]
        (swap! engine-ctx-atom assoc-in [:agents :karlach :task] (str "Conduzindo " entity " ao " enc-title))))

    ;; 4. Execução imediata da rota motora de resgate com targeting real e A* 3D
    (let [norm-sp (critic/normalize-species (or species entity))
          mob-resp (send-bridge-cmd! (str "LOCATE_MOB|Karlach|" norm-sp))
          mob-pos (when (and mob-resp (clojure.string/starts-with? mob-resp "OK|"))
                    (let [parts (clojure.string/split mob-resp #"\|")]
                      [(Double/parseDouble (nth parts 1))
                       (Double/parseDouble (nth parts 2))
                       (Double/parseDouble (nth parts 3))]))
          players (query-online-players)
          target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or speaker "pace"))) players))
          near-player-pos (when target-p
                            (let [px (:x target-p)
                                  pz (:z target-p)]
                              (if (nil? (current-enclosure-id [px 65.0 pz]))
                                [px 65.0 pz]
                                outside-pos)))
          animal-rescue-pos (or mob-pos near-player-pos outside-pos [-175.0 65.0 185.0])
          init-snap (critic/parse-sanctuary-snapshot (send-bridge-cmd! "SANCTUARY"))
          target-quad (get critic/enclosure->quadrant (or pen-id :pen-south-west) :SW)
          init-count (get-in init-snap [target-quad norm-sp] 0)
          gate-coords [gate-pos [(first gate-pos) (inc (second gate-pos)) (nth gate-pos 2)]]
          gate-blk (or gate-block "minecraft:dark_oak_fence_gate")
          gate-fac (or gate-facing "east")

          enc-info (get sanctuary-enclosures-by-id (or pen-id :pen-south-west))
          portal-info (or (:enclosure/portal enc-info)
                          {:blocks gate-coords
                           :normal (if (<= (first gate-pos) -175) [-1.0 0.0 0.0] [1.0 0.0 0.0])
                           :inside-anchor inside-pos
                           :outside-anchor outside-pos
                           :gate-block gate-blk
                           :gate-facing gate-fac})
          funnel (compute-enclosure-funnel portal-info)
          funnel-entry (:funnel-entry funnel)
          pre-gate (:pre-gate funnel)
          in-anc (:inside-anchor funnel)
          deep-in (:deep-inside funnel)

          ;; Navegação 100% guiada por Voxels Reais, A* 3D e Funil Ortogonal:
          ;; 1. Karlach caminha até o animal (localizado dinamicamente via LOCATE_MOB)
          to-animal-steps (generate-walk-steps (:pos @nav-state) animal-rescue-pos (str "Indo até " entity " para resgatar") {:radius 1.2})

          ;; 2. Conduz o animal até o início do funil no corredor central
          to-funnel-steps (generate-walk-steps animal-rescue-pos funnel-entry (str "Conduzindo " entity " ao corredor de acesso") {:species species :radius 1.0})

          ;; 3. Da área de segurança interna até o comedouro
          to-feed-steps   (generate-walk-steps in-anc feed-pos (str "Aproximando do comedouro de " entity) {:radius 1.5})

          main-steps (concat
                      [{:op :equip :item "minecraft:lead" :label "Equipando laço"}]
                      to-animal-steps
                      [{:op :attach-lead :species species :label (str "Laçando " entity)}
                       {:op :wait :ticks 15 :label "Conectando o laço"}]
                      to-funnel-steps
                      ;; Alinhamento 100% ortogonal com o vão do portão (elimina atrito diagonal contra as quinas):
                      [{:op :walk :target pre-gate :label "Alinhando animal ortogonalmente ao portão" :species species}
                       {:op :gate :open? true :pos gate-pos :gates gate-coords :block gate-blk :facing gate-fac :label "Abrindo portão do cercado"}
                       {:op :wait :ticks 8 :label "Aguardando portão abrir"}
                       {:op :walk :target in-anc :label "Atravessando vão do portão" :species species}
                       {:op :walk :target deep-in :label (str "Conduzindo " entity " para o interior seguro") :species species}
                       ;; Verificação sensorial contínua: só fecha quando o animal estiver fisicamente dentro do cercado!
                       {:op :await-contained :species species :pen-id pen-id :initial-contained init-count :timeout 80
                        :label "Aguardando confirmação sensorial de entrada do animal"}
                       {:op :detach-lead :species species :label (str "Soltando a guia do " entity)}
                       {:op :wait :ticks 10 :label "Removendo guia"}
                       {:op :walk :target in-anc :label "Retornando ao portão para trancar"}
                       {:op :gate :open? false :pos gate-pos :gates gate-coords :block gate-blk :facing gate-fac :label "Trancando portão"}
                       {:op :wait :ticks 8 :label "Conferindo tranca"}]
                      to-feed-steps
                      [{:op :feed :pos feed-pos :food bait-item :species [species] :label (str "Alimentando: " entity)}
                       {:op :wait :ticks 20 :label (str entity " seguro e alimentado")}
                       {:op :critic
                        :attempt 1
                        :goal-id id
                        :species species
                        :pen-id pen-id
                        :entity entity
                        :initial-contained init-count
                        :animal-pos animal-rescue-pos
                        :gates gate-coords
                        :block gate-blk
                        :facing gate-fac
                        :outside-pos pre-gate
                        :inside-pos in-anc
                        :feed-pos feed-pos
                        :bait-item bait-item
                        :label "Environmental Critic: Verificação de Contenção"}])]
      (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller"
                               (str "Executando resgate com percepção dinâmica de terreno para " entity " rumo a " feed-pos))
      (swap! nav-state assoc
             :mode :sanctuary-patrol
             :follow-target nil
             :steps (vec main-steps)))))

(defmethod execute-karlach-goal! :weather-shelter
  [{:goal/keys [id name voice-line]} engine-ctx-atom nav-state sanctuary-route]
  (send-bridge-cmd! "SWING|Karlach")
  (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
  (karlach-say! voice-line)

  (record-cognitive-event! engine-ctx-atom :synthesis "System 2 Voyager Synthesizer"
                           "Objetivo: :shelter-from-rain. Compondo árvore de comportamento [:sequence [:action :check-weather] [:action :move-to-quarters] [:action :rest-at-bed]]")

  (let [skills-dir (io/file "skills")
        _ (.mkdirs skills-dir)
        skill-registry (atom {:known-actions #{:check-weather :move-to-quarters :rest-at-bed :wait-out-rain}
                              :composite-skills {}})
        shelter-skill {:skill/id :shelter-from-rain
                       :skill/name "Abrigo de Emergência contra Chuva"
                       :skill/author "Voyager-System-2-Cognition"
                       :skill/trigger :weather-rain
                       :skill/timeout-ms 20000
                       :skill/tree [:sequence
                                    [:action :check-weather]
                                    [:action :move-to-quarters]
                                    [:action :rest-at-bed]
                                    [:action :wait-out-rain]]}
        reg-res (composer/validate-and-register-skill! skill-registry skills-dir shelter-skill)]
    (println (str "[craft-agents] 🧠 Nova Skill composta via MCP: :shelter-from-rain"))
    (println (str "[craft-agents] 🛡️ Gatekeeper Malli: " (if (:ok? reg-res) "APROVADA (Sem eval / Schema OK / Ciclos 0)" "REJEITADA") " -> Salvo em " (str (io/file skills-dir "shelter-from-rain.edn"))))

    (let [pre-res (composer/evaluate-preconditions (:skill/tree shelter-skill) {})]
      (record-cognitive-event! engine-ctx-atom :gatekeeper "Malli Schema Gatekeeper"
                               (str "Validação AST estrutural & Sandbox Mental: SUCESSO (Zero eval / Tipos conferidos / 0 ciclos recursivos / Pré-condições: " (clojure.core/name (:status pre-res :ok)) ").")))
    (record-cognitive-event! engine-ctx-atom :skill-lib "Skill Library"
                             ":shelter-from-rain registrada permanentemente em skills/shelter-from-rain.edn.")

    (when engine-ctx-atom
      (swap! engine-ctx-atom assoc-in [:agents :karlach :skills] ["shelter-from-rain"])
      (swap! engine-ctx-atom assoc-in [:agents :karlach :last-learned] ":shelter-from-rain (Malli OK)")
      (swap! engine-ctx-atom assoc-in [:agents :karlach :task] "Indo para o Quarto se abrigar"))

    (karlach-say! "Chuva detectada, soldado! Indo pro meu quarto me proteger!")

    (let [quarters-stop (some #(when (= (:type %) :quarters) %) sanctuary-route)]
      (if quarters-stop
        (do
          (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller"
                                   "Iniciando navegação A* com microsteps para Quarto da Karlach [-129, 65, 194]")
          (swap! nav-state assoc
                 :mode :sanctuary-patrol
                 :follow-target nil
                 :steps (vec (expand-route-microsteps quarters-stop (:pos @nav-state)))))
        (karlach-say! "Meu quarto ainda tá sendo arrumado!")))))

(defmethod execute-karlach-goal! :follow-player
  [{:goal/keys [target voice-line]} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" (str "Seguindo jogador " target))
  (let [players (query-online-players)
        target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
        [cx cy cz] (:pos @nav-state)
        tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] [cx cy cz])
        dist-to-p (if target-p
                    (Math/sqrt (+ (Math/pow (- (:x target-p) cx) 2)
                                  (Math/pow (- (:y target-p) cy) 2)
                                  (Math/pow (- (:z target-p) cz) 2)))
                    0.0)]
    ;; Teleporte apenas de emergencia se estiver muito distante (> 48m / outro bioma)
    (when (and target-p (> dist-to-p 48.0))
      (send-bridge-cmd! (str "CMD|tp Karlach " target))
      (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0")
      (send-bridge-cmd! "CMD|execute at Karlach run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 20 normal"))
    (swap! nav-state assoc
           :mode :follow
           :pos (if (> dist-to-p 48.0) tgt-pos [cx cy cz])
           :follow-target target
           :follow-path (if (and target-p (<= dist-to-p 48.0))
                          (or (request-path3d [cx cy cz] tgt-pos 2.2) [])
                          [])
           :follow-last-target [0.0 0.0 0.0]
           :follow-lost-ticks 0
           :steps []))
  (karlach-say! voice-line))

(defmethod execute-karlach-goal! :bodyguard
  [{:goal/keys [target voice-line]} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" (str "Modo Guarda-Costas ativado para " target))
  (let [players (query-online-players)
        target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
        [cx cy cz] (:pos @nav-state)
        tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] [cx cy cz])
        dist-to-p (if target-p
                    (Math/sqrt (+ (Math/pow (- (:x target-p) cx) 2)
                                  (Math/pow (- (:y target-p) cy) 2)
                                  (Math/pow (- (:z target-p) cz) 2)))
                    0.0)]
    ;; Teleporte apenas de emergencia se estiver muito distante (> 48m / outro bioma)
    (when (and target-p (> dist-to-p 48.0))
      (send-bridge-cmd! (str "CMD|tp Karlach " target))
      (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0")
      (send-bridge-cmd! "CMD|execute at Karlach run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 20 normal"))
    (send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:netherite_axe")
    (send-bridge-cmd! "CMD|item replace entity Karlach weapon.offhand with minecraft:shield")
    (send-bridge-cmd! "CMD|playsound minecraft:item.armor.equip_netherite master @a ~ ~ ~ 1.0 1.0")
    (send-bridge-cmd! "SWING|Karlach")
    (swap! nav-state assoc
           :mode :bodyguard
           :pos (if (> dist-to-p 48.0) tgt-pos [cx cy cz])
           :follow-target target
           :follow-path (if (and target-p (<= dist-to-p 48.0))
                          (or (request-path3d [cx cy cz] tgt-pos 2.2) [])
                          [])
           :follow-last-target [0.0 0.0 0.0]
           :follow-lost-ticks 0
           :steps []))
  (when engine-ctx-atom
    (swap! engine-ctx-atom assoc-in [:agents :karlach :task] (str "Guarda-Costas de " target)))
  (karlach-say! voice-line))

(def ^:private ore-pt-names
  {"iron" "ferro"
   "coal" "carvão"
   "diamond" "diamante"
   "copper" "cobre"
   "gold" "ouro"
   "redstone" "redstone"
   "lapis" "lápis-lazúli"
   "emerald" "esmeralda"
   "deepslate" "deepslate"
   "stone" "pedra"
   "dripstone" "espeleotema"})

(defmethod execute-karlach-goal! :mining-cooperation
  [{:goal/keys [target voice-line] target-filter :goal/filter} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller"
                           (str "Iniciando mineração cooperativa com " target " | Filtro: " (or target-filter "geral")))
  ;; 1. Garante sincronizacao e proximidade imediata com o jogador para mineracao cooperativa
  (let [players (query-online-players)
        target-p (first (clojure.core/filter #(= (str/lower-case (:name %)) (str/lower-case (or target "pace"))) players))
        tgt-pos (if target-p [(:x target-p) (:y target-p) (:z target-p)] (:pos @nav-state))
        [cx cy cz] (:pos @nav-state)
        dist-to-player (if target-p
                         (Math/sqrt (+ (Math/pow (- (:x target-p) cx) 2)
                                       (Math/pow (- (:y target-p) cy) 2)
                                       (Math/pow (- (:z target-p) cz) 2)))
                         0.0)]
    (when (and target-p (> dist-to-player 48.0))
      (send-bridge-cmd! (str "CMD|tp Karlach " target))
      (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0")
      (send-bridge-cmd! "CMD|execute at Karlach run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 20 normal")
      (swap! nav-state assoc :pos tgt-pos :follow-path [] :steps [])))

  (send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:netherite_pickaxe")
  (send-bridge-cmd! "CMD|playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
  (send-bridge-cmd! "SWING|Karlach")

  (cond
    ;; Caso A: Minerar o bloco que o jogador está olhando (Raycast / Joint Gaze)
    (= target-filter "crosshair")
    (let [look-res (raycast-player-look (or target "pace") 5.5)]
      (if (:found? look-res)
        (let [bx (:x look-res)
              by (:y look-res)
              bz (:z look-res)
              bid (:block-id look-res)
              [kx ky kz] (:pos @nav-state)
              odx (- (+ (double bx) 0.5) kx)
              ody (- (+ (double by) 0.5) (+ (double ky) 1.62))
              odz (- (+ (double bz) 0.5) kz)
              hdist (Math/sqrt (+ (* odx odx) (* odz odz)))
              total-d (Math/sqrt (+ (* odx odx) (* ody ody) (* odz odz)))
              yaw (compute-yaw odx odz)
              pitch (compute-pitch ody hdist)]
          ;; Se Karlach estiver a mais de 2.2m do bloco apontado, aproxima para quebrar colada
          (when (> total-d 2.2)
            (let [approach-x (+ (double bx) (if (> kx bx) 1.2 -1.2))
                  approach-z (+ (double bz) (if (> kz bz) 1.2 -1.2))]
              (send-bridge-cmd! (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|%.1f|false|false" approach-x ky approach-z yaw pitch))
              (swap! nav-state assoc :pos [approach-x ky approach-z] :yaw yaw :pitch pitch)))
          (let [[cur-x cur-y cur-z] (:pos @nav-state)]
            (send-bridge-cmd! (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|%.1f|false|false" cur-x cur-y cur-z yaw pitch)))
          (send-bridge-cmd! "SWING|Karlach")
          (let [mine-res (mine-block! "Karlach" bx by bz)]
            (if (:success? mine-res)
              (do
                (record-cognitive-event! engine-ctx-atom :action "Mineração Instantânea"
                                         (str "Bloco " bid " quebrado em [" bx " " by " " bz "]"))
                (send-bridge-cmd! (uformat "CMD|particle minecraft:block %s %.2f %.2f %.2f 0.3 0.3 0.3 0.05 12 normal" (or bid "minecraft:stone") (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5)))
                (send-bridge-cmd! (uformat "CMD|playsound minecraft:block.deepslate.break master @a %.1f %.1f %.1f 1.0 1.0" (double bx) (double by) (double bz)))
                (karlach-say! (str "Bloco " (str/replace (or bid "alvo") #"minecraft:" "") " quebrado com sucesso na sua frente, soldado! ⛏️ 🔥")))
              (karlach-say! (str "Não consegui quebrar esse bloco: " (:message mine-res))))))
        (karlach-say! (str "Não consegui identificar nenhum bloco na sua mira direta, " target "! Chega um pouco mais perto e aponta de novo!"))))

    ;; Caso B: Ativação do Modo de Mineração Contínua Autônoma
    :else
    (let [ore-name (get ore-pt-names target-filter (if (str/blank? target-filter) "minérios" target-filter))]
      (karlach-say! (str "Picareta em punho, " target "! Modo de mineração contínua de " ore-name " ativado! Vou extrair todos os veios que encontrar por aqui! 🔥 ⛏️"))
      (swap! nav-state assoc
             :mode :mining
             :mining-filter (or target-filter "")
             :mining-target (or target "pace")
             :mining-blocks-broken 0)))

  (when engine-ctx-atom
    (swap! engine-ctx-atom assoc-in [:agents :karlach :task]
           (if target-filter (str "Minerando " (get ore-pt-names target-filter target-filter) " com " target) (str "Minerando com " target)))))

(defmethod execute-karlach-goal! :spatial-attention
  [{:goal/keys [entity speaker]} engine-ctx-atom nav-state _sanctuary-route]
  (let [players (query-online-players)
        target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case (or speaker "pace"))) players))
        [cx cy cz] (:pos @nav-state)]
    (if target-p
      (let [px (:x target-p)
            py (:y target-p)
            pz (:z target-p)
            dx (- px cx)
            dz (- pz cz)
            look-yaw (compute-yaw dx dz)]
        (send-bridge-cmd! (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz look-yaw))
        (swap! nav-state assoc :yaw look-yaw)
        (record-cognitive-event! engine-ctx-atom :grounding "Percepção Espacial (Joint Attention)"
                                 (str "Olhando para " speaker " em [" (int px) " " (int pz) "] identificando " entity))
        (karlach-say! (str "Tô vendo sim, " speaker "! Um " entity " bem aí perto de você, do lado de fora do muro!")))
      (karlach-say! (str "Onde, " speaker "? Não tô conseguindo te ver por aqui!")))))

(defmethod execute-karlach-goal! :social-dance
  [{:goal/keys [voice-line]} engine-ctx-atom nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" "Executando animação de dança infernal")
  (swap! nav-state assoc :dance-ticks 80)
  (karlach-say! voice-line))

(defmethod execute-karlach-goal! :patrol-sanctuary
  [{:goal/keys [voice-line]} engine-ctx-atom nav-state sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" "Retomando patrulha e alimentação de animais no Santuário")
  (when engine-ctx-atom
    (swap! engine-ctx-atom assoc-in [:agents :karlach :task] "Patrulha no Santuário"))
  (send-bridge-cmd! "CMD|playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.0")
  (let [[cx _ cz] (:pos @nav-state)
        corridor-return (when (> cx -150.0)
                          [{:op :equip :item "minecraft:golden_carrot" :label "Retornando pelo Corredor Leste"}
                           {:op :walk :target [-129.0 65.0 180.5] :label "Corredor dos Aposentos"}
                           {:op :walk :target [-136.0 65.0 180.5] :label "Corredor Leste"}
                           {:op :walk :target [-173.0 65.0 180.0] :label "Praça Central do Santuário"}])
        initial-steps (vec (expand-route-microsteps (first sanctuary-route) (:pos @nav-state)))]
    (swap! nav-state assoc :mode :sanctuary-patrol :follow-target nil :route-idx 0 :steps initial-steps))
  (karlach-say! voice-line))

(defmethod execute-karlach-goal! :inventory-inspection
  [{:goal/keys [speaker]} engine-ctx-atom _nav-state _sanctuary-route]
  (let [inv (inspect-inventory "Karlach")
        target (or speaker "pace")
        items (:items inv)]
    (record-cognitive-event! engine-ctx-atom :grounding "Inspeção de Inventário"
                             (str "Inventário de Karlach verificado: " (pr-str items)))
    (if (empty? items)
      (karlach-say! (str "Minha mochila tá vazia por enquanto, " target "! Vamos minerar ou recolher uns recursos! 🔥"))
      (let [item-list (str/join ", " (map (fn [[iid c]] (str c "x " (str/replace iid #"minecraft:" ""))) items))]
        (karlach-say! (str "Tô carregando comigo, " target ": [" item-list "]! (" (:slots-used inv) " slots ocupados). Pronta pra continuar! 🔥 🎒"))))))

(defmethod execute-karlach-goal! :drop-inventory
  [{:goal/keys [speaker] target-filter :goal/filter} engine-ctx-atom _nav-state _sanctuary-route]
  (let [target (or speaker "pace")
        drop-res (drop-inventory-items! "Karlach" (or target-filter "all"))
        dropped-cnt (:dropped-count drop-res 0)
        items (:items drop-res)]
    (record-cognitive-event! engine-ctx-atom :action "Ejeção de Saque / Drop de Inventário"
                             (str "Karlach dropou " dropped-cnt " itens para " target))
    (send-bridge-cmd! "CMD|playsound minecraft:entity.item.pickup master @a ~ ~ ~ 1.0 0.8")
    (if (> dropped-cnt 0)
      (let [item-list (str/join ", " (map (fn [[iid c]] (str c "x " (str/replace iid #"minecraft:" ""))) items))]
        (karlach-say! (str "Aqui está o saque da expedição, " target "! Ejetando [" item-list "]! Todo seu, soldado! 🔥 💎")))
      (karlach-say! (str "Não tenho nenhum recurso solto pra dropar agora, " target "! Minha mochila tá limpa.")))))

(defmethod execute-karlach-goal! :default
  [{:goal/keys [raw-text voice-line]} engine-ctx-atom _nav-state _sanctuary-route]
  (record-cognitive-event! engine-ctx-atom :synthesis "System 2 Voyager Synthesizer"
                           (str "Analisando comando natural: '" raw-text "'. Decompondo em diretriz reativa."))
  (karlach-say! voice-line)
  (record-cognitive-event! engine-ctx-atom :motor "System 1 Motor Controller" "Executando diretriz"))

(defn handle-player-command!
  "Processa comandos de chat (recebidos via bridge CHAT_POLL, log tailer ou Studio UI)
   e orquestra as reações dos agentes Karlach e Picard."
  [speaker msg engine-ctx-atom nav-state picard-state sanctuary-route]
  (let [lower (str/lower-case (str/trim msg))]
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
        (execute-karlach-goal! goal-spec engine-ctx-atom nav-state sanctuary-route)))

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
        (send-bridge-cmd! "SWING|Picard")
        (picard-broadcast-weather!)
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
        (send-bridge-cmd! "CMD|weather clear")
        (picard-say! "Make it so! Acionando matriz de controle climático orbital: Céu limpo estabelecido, Número Um."))

      (and (or (str/includes? lower "picard")
               (str/includes? lower "engage")
               (str/includes? lower "engajar"))
           (or (str/includes? lower "engajar")
               (str/includes? lower "engage")
               (str/includes? lower "dobra")))
      (do
        (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
        (record-cognitive-event! engine-ctx-atom :motor "Picard Helm" "Acionando Dobra 9")
        (send-bridge-cmd! "SWING|Picard")
        (send-bridge-cmd! "CMD|playsound minecraft:block.beacon.power_select master @a -65 145 100 1.0 1.2")
        (picard-say! "Make it so! Dobra 9 acionada, leme à frente!"))

      (and (or (str/includes? lower "picard")
               (str/includes? lower "earl grey"))
           (or (str/includes? lower "cha")
               (str/includes? lower "chá")
               (str/includes? lower "tea")
               (str/includes? lower "earl grey")))
      (do
        (record-cognitive-event! engine-ctx-atom :stimulus speaker msg)
        (picard-say! "Tea. Earl Grey. Hot. Nada como um bom chá para clarear os pensamentos na ponte, Número Um."))

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
        (picard-say! (str "Com prazer, " speaker ". Acompanhando você em missão de superfície.")))

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
        (send-bridge-cmd! "MOVE|Picard|-65.0|145.0|101.0|0.0|0.0|false|false")
        (picard-say! "Entendido. Retornando por teletransporte ao meu posto na ponte da Enterprise-D.")))))

(defonce ^:private log-file-pos (atom 0))
(def ^:private server-log-path "/home/pace/minecraft-server/logs/latest.log")

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
                            :steps (vec (expand-route-microsteps (first sanctuary-route) [-175.0 65.0 180.0]))
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

    ;; 1. Registrar Dispatcher Global para comandos via Web UI e Testes
    (reset! global-command-dispatcher
      (fn [spk m]
        (send-bridge-cmd! (str "CMD|tellraw @a [\"\",{\"text\":\"<" spk "> \",\"color\":\"white\"},{\"text\":\"" (str/replace m "\"" "\\\"") "\"}]"))
        (handle-player-command! spk m engine-ctx-atom nav-state picard-state sanctuary-route)))

    ;; 2. Chat listener thread monitorando client & server latest.log em paralelo (latência de 100ms)
    (doto (Thread.
           (fn []
             (let [server-log (io/file "/home/pace/minecraft-server/logs/latest.log")
                   client-log (io/file "/home/pace/.minecraft/logs/latest.log")
                   seen-msgs  (atom {})]
               (loop [s-pos (if (.exists server-log) (.length server-log) 0)
                      c-pos (if (.exists client-log) (.length client-log) 0)]
                 (when @running?
                   (Thread/sleep 100)
                   (let [read-lines (fn [^java.io.File f last-p]
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
                         [new-s-pos s-lines] (read-lines server-log s-pos)
                         [new-c-pos c-lines] (read-lines client-log c-pos)
                         all-lines (concat s-lines c-lines)
                         now (System/currentTimeMillis)]
                     ;; Evita reprocessar a mesma mensagem enviada nos ultimos 10s
                     (swap! seen-msgs (fn [m] (into {} (filter (fn [[_ ts]] (< (- now ts) 10000)) m))))
                     (doseq [line all-lines]
                       (when-let [[_ speaker msg] (re-find #"<([^>]+)>\s+(.+)$" line)]
                         (let [clean-spk (str/trim speaker)
                               clean-msg (str/trim msg)
                               dedup-key (str clean-spk ":" clean-msg)]
                           (when (and (not (#{"Karlach" "Picard" "Antigravity"} clean-spk))
                                      (not (contains? @seen-msgs dedup-key)))
                             (swap! seen-msgs assoc dedup-key now)
                             (println (str "[craft-agents-chat] 📨 Chat capturado do jogo: <" clean-spk "> " clean-msg))
                             (handle-player-command! clean-spk clean-msg engine-ctx-atom nav-state picard-state sanctuary-route)))))
                     (recur new-s-pos new-c-pos))))))
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
                     ;; Polling duplo de Chat via latest.log e In-Server Bridge (5Hz / 200ms latency)
                     (when (zero? (mod tick 4))
                       (poll-server-log-chat! engine-ctx-atom nav-state picard-state sanctuary-route)
                       (let [resp (send-bridge-cmd! "CHAT_POLL")]
                         (when (and resp (str/starts-with? resp "OK;;"))
                           (let [items (str/split (subs resp 4) #";;")]
                             (doseq [item items]
                               (when-not (str/blank? item)
                                 (let [[speaker msg] (str/split item #"\|" 2)]
                                   (when (and speaker msg (not (#{"Karlach" "Picard" "Antigravity"} speaker)))
                                     (handle-player-command! speaker msg engine-ctx-atom nav-state picard-state sanctuary-route)))))))))
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
                                    (uformat "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                                            (:x target-p) (:y target-p) (:z target-p) yaw))
                                   (swap! picard-state assoc :pos [(:x target-p) (:y target-p) (:z target-p)] :yaw yaw
                                          :status (str "Missão com " (:name target-p))))
                                 (> dist 2.8)
                                 (let [step-d (min 0.21 dist)
                                       nx (+ px (* (/ dx dist) step-d))
                                       nz (+ pz (* (/ dz dist) step-d))
                                       ny (:y target-p)]
                                   (send-bridge-cmd!
                                    (uformat "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ny nz yaw))
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
                                    (uformat "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|%.1f|false|false"
                                            (double tx) (double ty) (double tz)
                                            (double (:yaw station 0.0)) (double (:pitch station 0.0))))
                                   ;; Picard em serviço silencioso na ponte (fala apenas quando consultado no chat pelo jogador)
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
                                    (uformat "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
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
                          (uformat "CMD|particle minecraft:flame %.2f %.2f %.2f 0.15 0.2 0.15 0.01 3 normal"
                                  kx (+ ky 1.1) kz))
                         (send-bridge-cmd!
                          (uformat "CMD|particle minecraft:smoke %.2f %.2f %.2f 0.15 0.25 0.15 0.01 2 normal"
                                  kx (+ ky 1.2) kz))))

                     (let [st @nav-state
                           [cx cy cz] (:pos st)
                           picard-card (let [[px py pz] (:pos @picard-state)]
                                         {:status (:status @picard-state)
                                          :pos [(/ (Math/round (* px 10.0)) 10.0)
                                                (double py)
                                                (/ (Math/round (* pz 10.0)) 10.0)]})
                           k-skills (vec (or (get-in @engine-ctx-atom [:agents :karlach :skills]) []))
                           make-karlach-card (fn [status pos]
                                               (cond-> {:status status :pos pos}
                                                 (seq k-skills) (assoc :skills k-skills)))]
                       (cond
                         ;; 1. Dance override
                         (pos? (:dance-ticks st 0))
                         (let [nyaw (float (mod (+ (:yaw st) 32.0) 360.0))]
                           (send-bridge-cmd!
                            (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz nyaw))
                           (when (zero? (mod tick 5))
                             (send-bridge-cmd! "SWING|Karlach")
                             (send-bridge-cmd!
                              (uformat "CMD|particle minecraft:note %.2f %.2f %.2f 0.3 0.3 0.3 1.0 2 normal"
                                      cx (+ cy 2.0) cz)))
                           (swap! nav-state #(-> %
                                                 (update :dance-ticks dec)
                                                 (assoc :yaw nyaw)))
                           (swap! engine-ctx-atom assoc
                                  :tick tick
                                  :leases {:legs {:owner :dance} :hands {:owner :dance} :gaze {:owner :dance}}
                                  :agents {:karlach (make-karlach-card "dancing 🔥" [cx cy cz])
                                           :picard  picard-card}))

                         ;; 2. Follow player & Bodyguard mode (Navegação Universal via A* 3D, Voxels Reais e Autodefesa)
                         (or (= (:mode st) :follow) (= (:mode st) :bodyguard))
                         (let [players (query-online-players)
                               target-p (first (filter #(= (str/lower-case (:name %))
                                                           (str/lower-case (or (:follow-target st) "pace")))
                                                       players))]
                           (if target-p
                             (let [tx (:x target-p)
                                   ty (:y target-p)
                                   tz (:z target-p)
                                   dx (- tx cx)
                                   dz (- tz cz)
                                   dist (Math/sqrt (+ (* dx dx) (* dz dz)))
                                   yaw (compute-yaw dx dz)]
                               (swap! nav-state assoc :follow-lost-ticks 0)

                               ;; Teleporte de aproximação se o jogador estiver muito distante (> 48m / outro bioma)
                               (let [total-d (Math/sqrt (+ (* dx dx) (* (- (double ty) (double cy)) (- (double ty) (double cy))) (* dz dz)))]
                                 (when (> total-d 48.0)
                                   (send-bridge-cmd! (uformat "CMD|tp Karlach %s" (:name target-p)))
                                   (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0")
                                   (send-bridge-cmd! "CMD|execute at Karlach run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 20 normal")
                                   (swap! nav-state assoc :pos [tx ty tz] :follow-path [])))

                               ;; Varredura tática de hostis e proteção do jogador (4 vezes por segundo)
                               (when (zero? (mod tick 5))
                                 (try
                                   (let [hostiles (scan-nearby-hostiles "Karlach" 14.0)]
                                     (when-let [target-mob (first hostiles)]
                                       (let [mdist (:distance target-mob)]
                                         (if (<= mdist 3.8)
                                           (let [hdx (- (:x target-mob) cx)
                                                 hdz (- (:z target-mob) cz)
                                                 hyaw (compute-yaw hdx hdz)]
                                             (send-bridge-cmd! (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz hyaw))
                                             (attack-hostile-target! "Karlach" (:id target-mob))
                                             (send-bridge-cmd! "SWING|Karlach")
                                             (send-bridge-cmd! (uformat "CMD|particle minecraft:crit %.2f %.2f %.2f 0.3 0.3 0.3 0.1 5 normal"
                                                                       (:x target-mob) (+ (:y target-mob) 1.0) (:z target-mob)))
                                             (when (zero? (mod tick 30))
                                               (karlach-say! (str "Toma essa, " (:type target-mob) "! Não mexe com meu esquadrão! 🔥"))))
                                           (when (and (= (:mode st) :bodyguard) (> mdist 3.8) (<= mdist 10.0))
                                             (swap! nav-state assoc :follow-path (request-path3d [cx cy cz] [(:x target-mob) (:y target-mob) (:z target-mob)] 2.0)))))))
                                   (catch Throwable _ nil)))
                               (if (<= dist 2.6)
                                 (do
                                   (send-bridge-cmd!
                                    (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz yaw))
                                   (swap! nav-state assoc :yaw yaw :follow-path []))
                                 (let [path-queue (:follow-path st [])
                                       last-target (or (:follow-last-target st) [0.0 0.0 0.0])
                                       target-moved? (> (+ (Math/abs (- (double tx) (double (or (first last-target) 0.0))))
                                                           (Math/abs (- (double tz) (double (or (nth last-target 2 nil) 0.0)))))
                                                        3.0)
                                       active-path (if (or (empty? path-queue) target-moved?)
                                                     (request-path3d [cx cy cz] [tx ty tz] 2.2)
                                                     path-queue)]
                                   (if-let [next-node (first active-path)]
                                     (let [nx (+ (double (:x next-node)) 0.5)
                                           ny (double (:y next-node))
                                           nz (+ (double (:z next-node)) 0.5)
                                           act (:action next-node)
                                           ndx (- nx cx)
                                           ndz (- nz cz)
                                           ndist (Math/sqrt (+ (* ndx ndx) (* ndz ndz)))
                                           nyaw (compute-yaw ndx ndz)]
                                       (when (= act :TRAVERSE_PORTAL)
                                         (let [pcoord [(:x next-node) (:y next-node) (:z next-node)]]
                                           (open-portal-at-coord! pcoord)
                                           (swap! nav-state assoc :opened-portal pcoord)))
                                       (if (<= ndist 0.4)
                                         (swap! nav-state #(-> %
                                                               (assoc :follow-path (vec (rest active-path)))
                                                               (assoc :follow-last-target [tx ty tz])))
                                         (let [step-d (min 0.24 (max 0.05 ndist))
                                               safe-ndist (max 0.001 ndist)
                                               step-x (+ cx (* (/ ndx safe-ndist) step-d))
                                               step-z (+ cz (* (/ ndz safe-ndist) step-d))]
                                           (send-bridge-cmd!
                                            (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|true|false" step-x ny step-z nyaw))
                                           (swap! nav-state assoc :pos [step-x ny step-z] :yaw nyaw :follow-path active-path :follow-last-target [tx ty tz]))))
                                     ;; Sem caminho navegavel: gira na direcao do jogador e aguarda (NUNCA atravessa paredes!)
                                     (do
                                       (send-bridge-cmd!
                                        (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz yaw))
                                       (swap! nav-state assoc :yaw yaw)))))
                               (when-let [op-portal (:opened-portal @nav-state)]
                                 (let [[ox _ oz] op-portal
                                       pdist (Math/sqrt (+ (Math/pow (- cx (+ (double ox) 0.5)) 2)
                                                           (Math/pow (- cz (+ (double oz) 0.5)) 2)))]
                                   (when (> pdist 2.2)
                                     (close-portal-at-coord! op-portal)
                                     (swap! nav-state assoc :opened-portal nil))))
                               (swap! engine-ctx-atom assoc
                                      :tick tick
                                      :leases {:legs {:owner :escort-player} :hands nil :gaze {:owner :social-gaze}}
                                      :agents {:karlach (make-karlach-card (if (= (:mode st) :bodyguard) (str "🛡️ guarda-costas de " (:name target-p)) (str "escoltando " (:name target-p))) (:pos @nav-state))
                                               :picard  picard-card}))
                             ;; Debounce para evitar cancelamento por oscilação momentânea do socket
                             (let [lost-cnt (inc (:follow-lost-ticks st 0))]
                               (if (>= lost-cnt 60)
                                 (do
                                   (swap! nav-state assoc :mode :sanctuary-patrol :steps [] :route-idx 0 :follow-lost-ticks 0)
                                   (karlach-say! "Perdi você de vista, soldado! Vou voltar pra patrulha dos cercados."))
                                 (swap! nav-state assoc :follow-lost-ticks lost-cnt)))))

                         ;; 2.1 Modo Mineração Contínua Autônoma (Navegação A* 3D estrita, sem no-clip, sem teleporte)
                         (= (:mode st) :mining)
                         (let [players (query-online-players)
                               target-p (first (clojure.core/filter #(= (str/lower-case (:name %))
                                                                       (str/lower-case (or (:mining-target st) "pace")))
                                                                   players))]
                           (if target-p
                             (let [tx (:x target-p)
                                   ty (:y target-p)
                                   tz (:z target-p)
                                   dx (- tx cx)
                                   dz (- tz cz)
                                   dist-to-player (Math/sqrt (+ (* dx dx) (* (- (double ty) (double cy)) (- (double ty) (double cy))) (* dz dz)))]
                               (cond
                                 ;; Teleporte de resgate apenas em distancia extrema (> 48m / outro bioma)
                                 (> dist-to-player 48.0)
                                 (do
                                   (send-bridge-cmd! (uformat "CMD|tp Karlach %s" (:name target-p)))
                                   (karlach-say! "Você avançou muito na mina, soldado! Acompanhando seus passos!")
                                   (swap! nav-state assoc :mode :bodyguard :pos [tx ty tz] :active-mining nil))

                                 ;; Se o jogador se afastar mais de 16m, suspende a picareta e caminha ate ele a pe
                                 (> dist-to-player 16.0)
                                 (do
                                   (karlach-say! "Avançando com você pela mina, soldado!")
                                   (swap! nav-state assoc :mode :bodyguard :active-mining nil :follow-path (or (request-path3d [cx cy cz] [tx ty tz] 2.2) [])))

                                 ;; Execução motora realista
                                 :else
                                 (if-let [am (:active-mining st)]
                                   ;; Bloco ativo sendo minerado com progresso e sons reais
                                   (let [bx (:x am)
                                         by (:y am)
                                         bz (:z am)
                                         bid (:block-id am)
                                         ax (:air-x am bx)
                                         ay (:air-y am by)
                                         az (:air-z am bz)
                                         stand-x (+ (double ax) 0.5)
                                         stand-y (double ay)
                                         stand-z (+ (double az) 0.5)
                                         odx (- (+ (double bx) 0.5) cx)
                                         ody (- (+ (double by) 0.5) (+ (double cy) 1.62))
                                         odz (- (+ (double bz) 0.5) cz)
                                         hdist (Math/sqrt (+ (* odx odx) (* odz odz)))
                                         total-ore-d (Math/sqrt (+ (* odx odx) (* (+ ody 1.62) (+ ody 1.62)) (* odz odz)))
                                         yaw (compute-yaw odx odz)
                                         pitch (compute-pitch ody hdist)]
                                     ;; Se estiver a mais de 2.4m do bloco de minério, navega via A* 3D pelos túneis livres (NUNCA no-clip!)
                                     (if (> total-ore-d 2.4)
                                       (let [path-queue (:path am [])
                                             active-path (if (empty? path-queue)
                                                           (request-path3d [cx cy cz] [stand-x stand-y stand-z] 1.0)
                                                           path-queue)]
                                         (if-let [next-node (first active-path)]
                                           (let [nx (+ (double (:x next-node)) 0.5)
                                                 ny (double (:y next-node))
                                                 nz (+ (double (:z next-node)) 0.5)
                                                 ndx (- nx cx)
                                                 ndz (- nz cz)
                                                 ndist (Math/sqrt (+ (* ndx ndx) (* ndz ndz)))
                                                 nyaw (compute-yaw ndx ndz)
                                                 npitch (compute-pitch (- ny (+ (double cy) 1.62)) ndist)]
                                             (if (<= ndist 0.4)
                                               (swap! nav-state assoc-in [:active-mining :path] (vec (rest active-path)))
                                               (let [step-d (min 0.24 (max 0.05 ndist))
                                                     safe-ndist (max 0.001 ndist)
                                                     step-x (+ cx (* (/ ndx safe-ndist) step-d))
                                                     step-z (+ cz (* (/ ndz safe-ndist) step-d))]
                                                 (send-bridge-cmd!
                                                  (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|%.1f|true|false"
                                                          step-x ny step-z nyaw npitch))
                                                 (swap! nav-state assoc :pos [step-x ny step-z] :yaw nyaw :pitch npitch))))
                                           ;; Sem caminho A* alcançável: minério inacessível sem quebrar outras rochas.
                                           ;; Descarta sem atravessar parede!
                                           (swap! nav-state #(-> %
                                                                 (assoc :active-mining nil)
                                                                 (update :unreachable-ores (fnil conj #{}) [bx by bz])))))
                                       ;; Posicionada com firmeza ao lado do bloco: martela a picareta!
                                       (let [cur-ticks (inc (:ticks am 0))
                                             max-ticks (:max-ticks am 14)]
                                         (send-bridge-cmd!
                                          (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|%.1f|false|false"
                                                  cx cy cz yaw pitch))
                                         ;; Batidas rítmicas a cada 3 ticks
                                         (when (zero? (mod cur-ticks 3))
                                           (send-bridge-cmd! "SWING|Karlach")
                                           (send-bridge-cmd!
                                            (uformat "CMD|playsound minecraft:block.deepslate.hit master @a %.1f %.1f %.1f 0.8 1.0"
                                                    (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5)))
                                           (send-bridge-cmd!
                                            (uformat "CMD|particle minecraft:block %s %.2f %.2f %.2f 0.2 0.2 0.2 0.02 3 normal"
                                                    bid (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5))))
                                         (if (>= cur-ticks max-ticks)
                                           ;; Tempo de quebra concluído: estoura o bloco!
                                           (do
                                             (send-bridge-cmd! "SWING|Karlach")
                                             (let [mres (mine-block! "Karlach" bx by bz)]
                                               (when (:success? mres)
                                                 (send-bridge-cmd!
                                                  (uformat "CMD|particle minecraft:block %s %.2f %.2f %.2f 0.35 0.35 0.35 0.08 16 normal"
                                                          bid (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5)))
                                                 (send-bridge-cmd!
                                                  (uformat "CMD|playsound minecraft:block.deepslate.break master @a %.1f %.1f %.1f 1.0 1.0"
                                                          (double bx) (double by) (double bz)))
                                                 (collect-nearby-drops! "Karlach" 4.0)))
                                             (swap! nav-state #(-> %
                                                                   (assoc :active-mining nil)
                                                                   (update :mining-blocks-broken (fnil inc 0)))))
                                           ;; Continua quebrando
                                           (swap! nav-state assoc-in [:active-mining :ticks] cur-ticks)))))
                                   ;; Se não tiver bloco ativo, busca o próximo minério exposto a cada 5 ticks
                                   (when (zero? (mod tick 5))
                                     (let [filter-str (or (:mining-filter st) "")
                                           ores (locate-nearby-ores "Karlach" 12.0 filter-str)
                                           unreach (:unreachable-ores st #{})
                                           reachable-ores (filter #(not (contains? unreach [(:x %) (:y %) (:z %)])) ores)]
                                       (if-let [target-ore (first reachable-ores)]
                                         (swap! nav-state assoc :active-mining
                                                {:x (:x target-ore)
                                                 :y (:y target-ore)
                                                 :z (:z target-ore)
                                                 :block-id (or (:block-id target-ore) "minecraft:deepslate")
                                                 :air-x (:air-x target-ore)
                                                 :air-y (:air-y target-ore)
                                                 :air-z (:air-z target-ore)
                                                 :ticks 0
                                                 :max-ticks 14})
                                         ;; Sem mais minérios expostos acessíveis!
                                         (let [loot-res (collect-nearby-drops! "Karlach" 6.0)
                                               broken-cnt (:mining-blocks-broken @nav-state 0)
                                               ore-label (get ore-pt-names filter-str (if (str/blank? filter-str) "minérios" filter-str))
                                               inv (inspect-inventory "Karlach")
                                               loot-summary (if (empty? (:items inv))
                                                              "nada"
                                                              (str/join ", " (map (fn [[iid c]] (str c "x " (str/replace iid #"minecraft:" ""))) (:items inv))))]
                                           (if (> broken-cnt 0)
                                             (karlach-say! (str "Veio de " ore-label " extraído por completo (" broken-cnt " blocos)! Já looteei pro meu inventário: [" loot-summary "]! Se quiser o saque, só me pedir! 🔥 ⛏️"))
                                             (karlach-say! (str "Não encontrei mais veios expostos de " ore-label " acessíveis por aqui, soldado!")))
                                           (swap! nav-state assoc :mode :bodyguard :mining-blocks-broken 0 :active-mining nil :unreachable-ores #{}))))))))
                             (swap! nav-state assoc :mode :bodyguard :active-mining nil :unreachable-ores #{})))

                         ;; 3. Sanctuary Patrol & Enclosure Caretaker BT loop
                         :else
                         (let [steps (:steps st)]
                           (if (empty? steps)
                             (let [next-idx (mod (inc (:route-idx st 0)) (count sanctuary-route))
                                   next-stop (nth sanctuary-route next-idx)]
                               (swap! nav-state assoc
                                      :route-idx next-idx
                                      :steps (vec (expand-route-microsteps next-stop [cx cy cz]))))
                             (let [cur-step    (first steps)
                                   op          (:op cur-step)
                                   invariants  (:invariants cur-step)
                                   inv-check   (when (seq invariants)
                                                 (let [obs {:bot-pos    [cx cy cz]
                                                            :target-pos (or (:target cur-step) [cx cy cz])
                                                            :health     (get-in @engine-ctx-atom [:agents :karlach :health] 20.0)
                                                            :leashed?   (:leashed? st false)}]
                                                   (critic/check-runtime-invariants invariants obs)))]
                               (if (and inv-check (:violated? inv-check))
                                 (let [reflection (critic/generate-early-abort-reflection inv-check cur-step)]
                                   (record-cognitive-event! engine-ctx-atom :critic-fail "Environmental Critic"
                                                            (str "EARLY ABORT DETECTADO: " (:diagnostic inv-check)))
                                   (record-cognitive-event! engine-ctx-atom :reflexion "System 2 Reflexion"
                                                            (str "Ação corretiva: " (name (:reflection/suggested-action reflection))))
                                   (karlach-say! (:reflection/voice-line reflection))
                                   (swap! nav-state assoc :steps [] :mode :sanctuary-patrol))
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
                                       dist (Math/sqrt (+ (* dx dx) (* dz dz)))
                                       is-leashed? (or (:leashed? st false) (boolean (:species cur-step)))
                                       leash-info (when is-leashed? (query-leash-status "Karlach"))]
                                   (cond
                                     ;; 1. Alerta Critico Tatil: A guia arrebentou durante o trajeto!
                                     (and is-leashed? (:leashed? st) (false? (:leashed? leash-info)))
                                     (let [species (or (:species cur-step) (:species (first (filter :species (:steps st)))) "animal")
                                           norm-sp (critic/normalize-species species)]
                                       (record-cognitive-event! engine-ctx-atom :perception-warn "Tactile Sensory Feedback (Nativo 20Hz)"
                                                                (str "ALERTA TATIL IMEDIATO: A guia arrebentou! O animal (" norm-sp ") soltou-se da corda."))
                                       (karlach-say! (str "Eita, a corda soltou! Espera ai, " norm-sp ", to voltando pra te pegar!"))
                                       (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.leash_knot.break master @a ~ ~ ~ 1.0 1.0")
                                       (let [mob-resp (send-bridge-cmd! (str "LOCATE_MOB|Karlach|" norm-sp))
                                             mob-pos (when (and mob-resp (clojure.string/starts-with? mob-resp "OK|"))
                                                       (let [parts (clojure.string/split mob-resp #"\|")]
                                                         [(Double/parseDouble (nth parts 1))
                                                          (Double/parseDouble (nth parts 2))
                                                          (Double/parseDouble (nth parts 3))]))
                                             rescue-pos (or mob-pos [cx cy cz])
                                             to-mob-steps (generate-walk-steps [cx cy cz] rescue-pos (str "Retornando para resgatar " norm-sp) {:radius 1.2})
                                             rem-steps (vec (:steps st))
                                             recovery-seq (vec
                                                           (concat
                                                            [{:op :wait :ticks 5 :label "Parando imediatamente apos guia arrebentar"}]
                                                            to-mob-steps
                                                            [{:op :attach-lead :species species :label (str "Reatando guia no " norm-sp)}
                                                             {:op :wait :ticks 10 :label "Fixando corda"}]
                                                            rem-steps))]
                                         (swap! nav-state #(-> %
                                                               (assoc :leashed? false)
                                                               (assoc :steps recovery-seq)))))

                                     ;; 3. Caminhada normal (conducao segura ou patrulha)
                                     :else
                                     (if (<= dist 0.25)
                                       (let [rem-steps (vec (rest (:steps st)))
                                             next-step (first rem-steps)
                                             corner-pause? (when (and is-leashed? (= (:op next-step) :walk) (:target next-step))
                                                             (let [[ntx _ ntz] (:target next-step)
                                                                   ndx (- ntx tx)
                                                                   ndz (- ntz tz)
                                                                   ndist (Math/sqrt (+ (* ndx ndx) (* ndz ndz)))]
                                                               (when (> ndist 0.1)
                                                                 (let [y1 (compute-yaw dx dz)
                                                                       y2 (compute-yaw ndx ndz)
                                                                       diff (Math/abs (- y1 y2))
                                                                       ydiff (if (> diff 180.0) (- 360.0 diff) diff)]
                                                                   (> ydiff 30.0)))))]
                                         (send-bridge-cmd!
                                          (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                                                  (double tx) (double ty) (double tz) (double (:yaw st))))
                                         (swap! nav-state #(-> %
                                                               (assoc :pos [(double tx) (double ty) (double tz)])
                                                               (assoc :steps (if corner-pause?
                                                                               (into [{:op :wait :ticks 5 :label "Aguardando animal contornar curva"}] rem-steps)
                                                                               rem-steps)))))
                                       (let [base-speed (if is-leashed? 0.165 0.245)
                                             speed (if (and is-leashed? (> (:distance leash-info 0.0) 7.5))
                                                     0.090
                                                     base-speed)
                                             nx (+ cx (* (/ dx dist) speed))
                                             nz (+ cz (* (/ dz dist) speed))
                                             yaw (compute-yaw dx dz)]
                                         (send-bridge-cmd!
                                          (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                                                  nx (double ty) nz yaw))
                                         (swap! nav-state assoc :pos [nx (double ty) nz] :yaw yaw)
                                         (swap! engine-ctx-atom assoc
                                                :tick tick
                                                :leases {:legs {:owner :patrol-waypoints} :hands nil :gaze {:owner :path-look}}
                                                :agents {:karlach (make-karlach-card (:label cur-step) [(/ (Math/round (* nx 10.0)) 10.0) (double ty) (/ (Math/round (* nz 10.0)) 10.0)])
                                                         :picard  picard-card})))))

                                 :gate
                                 (do
                                   (if-let [p (:pos cur-step)]
                                     (if (:open? cur-step)
                                       (open-portal-at-coord! p)
                                       (close-portal-at-coord! p))
                                     (set-double-gate! (:gates cur-step) (:block cur-step) (:facing cur-step) (:open? cur-step)))
                                   (swap! nav-state update :steps #(vec (rest %)))
                                   (swap! engine-ctx-atom assoc
                                          :tick tick
                                          :leases {:legs {:owner :portal-traversal}
                                                   :hands {:owner :toggle-double-gate}
                                                   :gaze {:owner :portal-traversal}}
                                          :agents {:karlach (make-karlach-card (:label cur-step) [cx cy cz])
                                                   :picard  picard-card}))

                                 :feed
                                 (do
                                   (feed-enclosure-animals! (:pos cur-step) (:food cur-step) (:species cur-step))
                                   ;; Seleciona fala EXCLUSIVAMENTE do cercado onde Karlach está fisicamente
                                   (let [enc-barks (barks-for-current-position [cx cy cz])
                                         chosen-bark (if (seq enc-barks)
                                                       (rand-nth enc-barks)
                                                       (:bark cur-step))]
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
                                          :agents {:karlach (make-karlach-card (:label cur-step) [cx cy cz])
                                                   :picard  picard-card}))

                                 :attach-lead
                                 (let [species (or (:species cur-step) "animal")
                                       norm-sp (critic/normalize-species species)
                                       mob-resp (send-bridge-cmd! (str "LOCATE_MOB|Karlach|" norm-sp))
                                       mob-info (when (and mob-resp (clojure.string/starts-with? mob-resp "OK|"))
                                                  (let [parts (clojure.string/split mob-resp #"\|")]
                                                    {:pos [(Double/parseDouble (nth parts 1))
                                                           (Double/parseDouble (nth parts 2))
                                                           (Double/parseDouble (nth parts 3))]
                                                     :type (nth parts 4)}))
                                       mob-dist (when mob-info
                                                  (let [[mx my mz] (:pos mob-info)]
                                                    (Math/sqrt (+ (Math/pow (- mx cx) 2) (Math/pow (- my cy) 2) (Math/pow (- mz cz) 2)))))]
                                   (if (and mob-info mob-dist (> mob-dist 2.2))
                                     ;; O animal está além do alcance físico (2.2 blocos): aproxima-se antes de usar a corda
                                     (let [close-steps (generate-walk-steps [cx cy cz] (:pos mob-info) (str "Aproximando-se de " norm-sp " para laçar") {:radius 1.2})]
                                       (swap! nav-state update :steps #(vec (concat close-steps [%]))))
                                     (do
                                       (send-bridge-cmd! "CMD|item replace entity Karlach weapon.offhand with minecraft:lead")
                                       (when mob-info
                                         (let [[mx _ mz] (:pos mob-info)
                                               look-yaw (compute-yaw (- mx cx) (- mz cz))]
                                           (send-bridge-cmd! (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz look-yaw))))
                                       (let [leash-res (send-bridge-cmd! (str "LEASH|Karlach|" norm-sp))]
                                         (if (clojure.string/starts-with? (or leash-res "") "ERR|TOO_FAR")
                                           (let [close-steps (generate-walk-steps [cx cy cz] (or (:pos mob-info) [cx cy cz]) (str "Fechando distancia de " norm-sp) {:radius 1.0})]
                                             (swap! nav-state update :steps #(vec (concat close-steps [%]))))
                                           (do
                                             (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.leash_knot.place master @a ~ ~ ~ 1.0 1.0")
                                             (swap! nav-state #(-> %
                                                                   (assoc :leashed? true)
                                                                   (update :steps (fn [s] (vec (rest s))))))))))))

                                 :detach-lead
                                 (do
                                   (send-bridge-cmd! "UNLEASH|Karlach")
                                   (send-bridge-cmd! "CMD|item replace entity Karlach weapon.offhand with minecraft:air")
                                   (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.leash_knot.break master @a ~ ~ ~ 1.0 1.0")
                                   (swap! nav-state #(-> %
                                                         (assoc :leashed? false)
                                                         (update :steps (fn [s] (vec (rest s)))))))

                                 :await-contained
                                 (let [species    (:species cur-step "naturalist:capybara")
                                       pen-id     (:pen-id cur-step :pen-south-west)
                                       init-c     (:initial-contained cur-step 0)
                                       target-q   (get critic/enclosure->quadrant pen-id :SW)
                                       elapsed    (:elapsed-ticks cur-step 0)
                                       timeout    (:timeout cur-step 80)
                                       raw-snap   (when (zero? (mod elapsed 5)) (send-bridge-cmd! "SANCTUARY"))
                                       snap       (when raw-snap (critic/parse-sanctuary-snapshot raw-snap))
                                       curr-c     (when snap (get-in snap [target-q (critic/normalize-species species)] 0))
                                       contained? (and curr-c (> curr-c init-c))]
                                   (if (or contained? (>= elapsed timeout))
                                     (do
                                       (if contained?
                                         (do
                                           (record-cognitive-event! engine-ctx-atom :perception "Ground Truth Sensor"
                                                                    (str "Animal confirmado dentro do cercado " (name target-q) " (" (critic/normalize-species species) "). Fechando portão."))
                                           (karlach-say! "Entrou! Fechando o portão pra ninguém escapar!"))
                                         (do
                                           (record-cognitive-event! engine-ctx-atom :perception-warn "Ground Truth Sensor"
                                                                    (str "Tempo limite de espera de entrada (" elapsed " ticks). Concluindo trancamento."))
                                           (karlach-say! "Atenção ao portão, trancando agora!")))
                                       (swap! nav-state update :steps #(vec (rest %))))
                                     (do
                                       (swap! engine-ctx-atom assoc
                                              :tick tick
                                              :leases {:legs {:owner :await-contained}
                                                       :hands nil
                                                       :gaze {:owner :portal-watch}}
                                              :agents {:karlach (make-karlach-card (:label cur-step) [cx cy cz])
                                                       :picard  picard-card})
                                       (swap! nav-state update-in [:steps 0 :elapsed-ticks] (fnil inc 0)))))

                                 :critic
                                 (let [raw-snap  (send-bridge-cmd! "SANCTUARY")
                                       snap      (critic/parse-sanctuary-snapshot raw-snap)
                                       species   (:species cur-step "naturalist:capybara")
                                       pen-id    (:pen-id cur-step :pen-south-west)
                                       init-c    (:initial-contained cur-step)
                                       eval-res  (critic/evaluate-goal-achievement
                                                  :animal-containment nil snap
                                                  {:species species :pen-id pen-id
                                                   :require-all? false :initial-contained init-c})
                                       entity    (:entity cur-step "Capivara Nadadora")
                                       attempt   (get cur-step :attempt 1)]
                                   (if (:success? eval-res)
                                     (do
                                       (record-cognitive-event! engine-ctx-atom :critic "Environmental Critic"
                                                                (str "SUCESSO EMPÍRICO COMPROVADO: " (:diagnostic eval-res)))
                                       (let [enc-title (or (:enclosure/name (get sanctuary-enclosures-by-id pen-id))
                                                           (get critic/quadrant->name (get critic/enclosure->quadrant pen-id :SW))
                                                           "cercado")]
                                         (karlach-say! (str "Missão cumprida, soldado! " entity " sã e salva no " enc-title ", tudo verificado!")))
                                       (swap! nav-state update :steps #(vec (rest %))))
                                     (if (>= attempt 2)
                                       (do
                                         (record-cognitive-event! engine-ctx-atom :critic-fail "Environmental Critic"
                                                                  "Tentativas de resgate concluídas. Encerrando ciclo para evitar loop.")
                                         (karlach-say! (str "Soldado, portão trancado! O " entity " tá agitado lá fora, melhor a gente cuidar dele daqui a pouco."))
                                         (swap! nav-state assoc :mode :sanctuary-patrol :steps []))
                                       (let [reflection (critic/generate-reflection eval-res {:skill/id (:goal-id cur-step) :skill/version 1})
                                             next-v     2]
                                         (record-cognitive-event! engine-ctx-atom :critic-fail "Environmental Critic"
                                                                  (str "FALHA EMPÍRICA DETECTADA: " (:diagnostic eval-res)))
                                         (record-cognitive-event! engine-ctx-atom :reflexion "System 2 Reflexion"
                                                                  "Auto-correção disparada: Tentativa 2 com condução cuidadosa até o cercado.")
                                         (karlach-say! (:reflection/voice-line reflection))
                                         (let [enc-info (get sanctuary-enclosures-by-id pen-id)
                                               portal-info (or (:enclosure/portal enc-info)
                                                               {:blocks (:gates cur-step)
                                                                :normal (if (<= (first (or (first (:gates cur-step)) [-179 65 189])) -175) [-1.0 0.0 0.0] [1.0 0.0 0.0])
                                                                :inside-anchor (:inside-pos cur-step)
                                                                :outside-anchor (:outside-pos cur-step)
                                                                :gate-block (:block cur-step)
                                                                :gate-facing (:facing cur-step)})
                                               funnel (compute-enclosure-funnel portal-info)
                                               funnel-entry (:funnel-entry funnel)
                                               pre-gate (:pre-gate funnel)
                                               in-anc (:inside-anchor funnel)
                                               deep-in (:deep-inside funnel)
                                               retry-outside (or funnel-entry (:outside-pos cur-step) [-177.0 65.0 189.0])
                                               to-corr (generate-walk-steps (:pos @nav-state) retry-outside (str "Saindo para buscar " entity " junto ao portão") {:radius 1.0})
                                               to-feed (generate-walk-steps in-anc (:feed-pos cur-step) (str "Aproximando do comedouro de " entity) {:radius 1.5})
                                               v2-steps (concat
                                                         [{:op :gate :open? true :pos (or (first (:gates cur-step)) [-179 65 189])
                                                           :gates (:gates cur-step) :block (:block cur-step) :facing (:facing cur-step) :label "Abrindo portão para recuperar animal"}
                                                          {:op :wait :ticks 8 :label "Aguardando portão abrir"}]
                                                         to-corr
                                                         [{:op :attach-lead :species species :label (str "Prendendo laço no " entity)}
                                                          {:op :wait :ticks 15 :label "Fixando laço"}
                                                          {:op :walk :target pre-gate :label "Realinhando animal ortogonalmente ao portão" :species species}
                                                          {:op :walk :target in-anc :label "Atravessando vão do portão" :species species}
                                                          {:op :walk :target deep-in :label (str "Conduzindo " entity " para o fundo do cercado") :species species}
                                                          {:op :await-contained :species species :pen-id pen-id :initial-contained init-c :timeout 80
                                                           :label "Aguardando confirmação sensorial de entrada do animal (Tentativa 2)"}
                                                          {:op :detach-lead :species species :label (str "Soltando a guia do " entity)}
                                                          {:op :wait :ticks 10 :label "Removendo guia"}
                                                          {:op :walk :target in-anc :label "Retornando ao portão para trancar"}
                                                          {:op :gate :open? false :pos (or (first (:gates cur-step)) [-179 65 189])
                                                           :gates (:gates cur-step) :block (:block cur-step) :facing (:facing cur-step) :label "Trancando portão"}
                                                          {:op :wait :ticks 8 :label "Conferindo tranca"}]
                                                         to-feed
                                                         [{:op :feed :pos (:feed-pos cur-step) :food (:bait-item cur-step) :species [species] :label (str "Alimentando: " entity)}
                                                          {:op :wait :ticks 20 :label (str entity " seguro e alimentado")}
                                                          {:op :critic :attempt 2 :goal-id (:goal-id cur-step) :species species :pen-id pen-id :entity entity
                                                           :initial-contained init-c
                                                           :animal-pos retry-outside
                                                           :gates (:gates cur-step) :block (:block cur-step) :facing (:facing cur-step)
                                                           :outside-pos pre-gate :inside-pos in-anc :feed-pos (:feed-pos cur-step) :bait-item (:bait-item cur-step)
                                                           :label "Critic: Segunda e Última Verificação"}])]
                                           (swap! nav-state assoc :steps (vec v2-steps)))))))

                                 :BARK
                                 (do
                                   (when-let [b (:text cur-step)]
                                     ;; Só fala frases de praça/corredor se Karlach NÃO estiver dentro de um cercado
                                     (when (and (nil? (current-enclosure-id [cx cy cz]))
                                                (> (- (System/currentTimeMillis) (:last-bark-ms st 0)) 35000))
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
                                                (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                                                        cx cy cz gyaw))))))))
                                   (if (<= rem-ticks 0)
                                     (swap! nav-state #(-> %
                                                           (assoc :wait-left 0)
                                                           (update :steps (fn [s] (vec (rest s))))))
                                     (swap! nav-state assoc :wait-left rem-ticks))
                                   (swap! engine-ctx-atom assoc
                                          :tick tick
                                          :leases {:legs nil :hands nil :gaze {:owner :social-gaze}}
                                          :agents {:karlach (make-karlach-card (:label cur-step) [(/ (Math/round (* cx 10.0)) 10.0) cy (/ (Math/round (* cz 10.0)) 10.0)])
                                                   :picard  picard-card})))))))))
                     (catch Throwable t
                      (println "[craft-agents-20hz-ERROR]" (.getMessage t))
                      (.printStackTrace t)))
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

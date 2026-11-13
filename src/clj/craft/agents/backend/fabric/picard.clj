(ns craft.agents.backend.fabric.picard
  "Subsistema do Capitao Picard e telemetria meteorologica orbital / planetaria."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.speech :as speech]))

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
  (let [resp (transport/send-bridge-cmd! "WEATHER")]
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

(defn build-picard-stations []
  (try
    (let [picard (edn/read-string (slurp (io/file "agents/picard.edn")))]
      (vec (get-in picard [:agent/dialogues :stations])))
    (catch Throwable _ [])))

(def picard-stations (build-picard-stations))

(defn picard-say! [msg]
  ;; opts preservam a saida historica (a voz do registro do Picard e outra).
  (speech/say! :picard msg {:name-color "aqua" :color "white" :radius 16
                            :sound "minecraft:block.beacon.ambient" :sound-radius 32
                            :volume "0.8" :pitch "1.4"}))

(defn picard-broadcast-weather!
  "Emite o Boletim Meteorológico Orbital (Minecraft) e Planetário (Terra) pelo Capitão Picard."
  []
  (refresh-real-world-weather-async!)
  (let [mc-w   (query-minecraft-weather)
        real-w (:summary @real-weather-cache)]
    (picard-say! (format "📡 [Sensores Orbitais Enterprise-D] Horário local: %s (%s) | %s (%s)."
                         (:clock mc-w) (:phase mc-w) (:sky mc-w) (:next-transition mc-w)))
    (picard-say! (format "🌍 [Telemetria Atmosférica Terrestre] %s." real-w))))

(defn picard-broadcast-rain-alert!
  "Aviso GLOBAL de chuva (CA-060). Diferente de picard-say!, este tellraw usa @a
   SEM seletor de distancia, para alcancar todos os jogadores independente de onde
   estejam — um alerta de ponte nao deve depender de proximidade."
  []
  (let [msg "📡 [Sensores Enterprise-D] Frente de chuva detectada no quadrante. Todos aos abrigos!"
        json-cmd (str "tellraw @a [{\"text\":\"<\",\"color\":\"dark_gray\"},"
                      "{\"text\":\"Picard\",\"color\":\"aqua\",\"bold\":true},"
                      "{\"text\":\"> \",\"color\":\"dark_gray\"},"
                      "{\"text\":\"" msg "\",\"color\":\"yellow\"}]")]
    (transport/send-bridge-cmd! (str "CMD|" json-cmd))
    (transport/send-bridge-cmd! "CMD|playsound minecraft:block.beacon.ambient master @a ~ ~ ~ 0.8 1.4")))

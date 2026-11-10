(ns craft.agents.backend.fabric.engine
  "Loop de 20 TPS ao vivo da Karlach e Picard no servidor Fabric e integracao com o Agent Studio."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions
             :refer [attack-hostile-target! auto-eat-food! collect-nearby-drops!
                     ensure-live-bridge-and-agents! inspect-inventory karlach-say!
                     last-drop-ms locate-dropped-items locate-nearby-ores mine-block!
                     ore-pt-names query-leash-status query-online-players
                     query-sitting-status scan-nearby-hostiles]]
            [craft.agents.backend.fabric.atelier :as atelier]
            [craft.agents.backend.fabric.navigation :as nav
             :refer [compute-pitch compute-yaw expand-route-microsteps
                     generate-walk-steps request-path3d]]
            [craft.agents.backend.fabric.picard :as picard
             :refer [picard-say! picard-stations refresh-real-world-weather-async!]]
            [craft.agents.backend.fabric.sanctuary :as sanctuary
             :refer [barks-for-current-position close-portal-at-coord!
                     compute-enclosure-funnel current-enclosure-id
                     feed-enclosure-animals! open-portal-at-coord!
                     sanctuary-enclosures-by-id sanctuary-route
                     set-double-gate!]]
            [craft.agents.backend.fabric.sentry :as sentry
             :refer [handle-player-command! proactive-sentry-scan!]]
            [craft.agents.backend.fabric.telemetry :as telemetry
             :refer [global-command-dispatcher record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport
             :refer [bridge-alive? send-bridge-cmd! uformat]]
            [craft.agents.astrophysics.ephemeris :as ephem]
            [craft.agents.cognition.critic :as critic])
  (:import [java.io RandomAccessFile]
           [java.nio.charset StandardCharsets]))

(defn evaluate-combat-preemption
  "Avalia preempção de combate reativo, tether anti-kiting de escolta e proteção de embarque (CA-049).
   Retorna mapa de decisão:
     - :action (:combat-strike, :combat-intercept, :tether-snap-recall, :remain-mounted-passive, :follow-player)
     - :target-pos [x y z]
     - :target-id int
     - :clear-follow-path? boolean"
  [bot-pos leader-pos hostiles in-vehicle?]
  (if in-vehicle?
    ;; 1. Proteção de Regressão com o Barco (CA-048 + CA-049):
    ;; Não desembarca sozinha para caçar na margem do rio.
    (if-let [melee-mob (first (filter #(<= (:distance %) 3.5) hostiles))]
      {:action :combat-strike
       :target-pos [(:x melee-mob) (:y melee-mob) (:z melee-mob)]
       :target-id (:id melee-mob)
       :clear-follow-path? true}
      {:action :remain-mounted-passive
       :target-pos nil
       :clear-follow-path? false})

    ;; 2. Modo Terrestre (fora do barco):
    (let [[bx by bz] (or bot-pos [0.0 64.0 0.0])
          [lx ly lz] (or leader-pos bot-pos)
          dist-to-leader (if leader-pos
                           (Math/sqrt (+ (Math/pow (- (double lx) (double bx)) 2)
                                         (Math/pow (- (double ly) (double by)) 2)
                                         (Math/pow (- (double lz) (double bz)) 2)))
                           0.0)
          target-mob (first hostiles)]
      (cond
        ;; A) Tether Tático Anti-Kiting: líder está além de 18.0m -> recall de emergência
        (> dist-to-leader 18.0)
        {:action :tether-snap-recall
         :target-pos leader-pos
         :clear-follow-path? false}

        ;; B) Hostil próximo engajável dentro do tether (<= 18m do líder e <= 14m de Karlach)
        (and target-mob (<= (:distance target-mob) 14.0))
        (let [mdist (:distance target-mob)]
          (if (<= mdist 3.8)
            {:action :combat-strike
             :target-pos [(:x target-mob) (:y target-mob) (:z target-mob)]
             :target-id (:id target-mob)
             :clear-follow-path? true}
            {:action :combat-intercept
             :target-pos [(:x target-mob) (:y target-mob) (:z target-mob)]
             :target-id (:id target-mob)
             :clear-follow-path? false}))

        ;; C) Sem hostis engajáveis: locomoção normal para o líder
        :else
        {:action :follow-player
         :target-pos leader-pos
         :clear-follow-path? false}))))

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
        coco-state   (atelier/create-coco-state)
        richeh-state (atelier/create-richeh-state)
        running?     (atom true)]

    ;; 1. Registrar Dispatcher Global para comandos via Web UI e Testes
    (reset! global-command-dispatcher
      (fn [spk m]
        (send-bridge-cmd! (str "CMD|tellraw @a [\"\",{\"text\":\"<" spk "> \",\"color\":\"white\"},{\"text\":\"" (str/replace m "\"" "\\\"") "\"}]"))
        (handle-player-command! spk m engine-ctx-atom nav-state picard-state sanctuary-route coco-state richeh-state)))

    ;; 2. Chat listener thread monitorando client & server latest.log em paralelo (latência de 100ms)
    (doto (Thread.
           (fn []
             (let [server-log (io/file "/home/pace/dev/minecraft/server/logs/latest.log")
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
                             (handle-player-command! clean-spk clean-msg engine-ctx-atom nav-state picard-state sanctuary-route coco-state richeh-state)))))
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
;;                        (poll-server-log-chat! engine-ctx-atom nav-state picard-state sanctuary-route)
                       (let [resp (send-bridge-cmd! "CHAT_POLL")]
                         (when (and resp (str/starts-with? resp "OK;;"))
                           (let [items (str/split (subs resp 4) #";;")]
                             (doseq [item items]
                               (when-not (str/blank? item)
                                 (let [[speaker msg] (str/split item #"\|" 2)]
                                   (when (and speaker msg (not (#{"Karlach" "Picard" "Antigravity"} speaker)))
                                     (handle-player-command! speaker msg engine-ctx-atom nav-state picard-state sanctuary-route coco-state richeh-state)))))))))
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
                     ;; B) Tick do Ateliê: Coco e Richeh (Castelo Touros Negros)
                     ;; ========================================================
                     (let [online-p (try (query-online-players) (catch Throwable _ []))]
                       (try
                         (atelier/step-coco-tick! coco-state send-bridge-cmd! tick online-p)
                         (atelier/step-richeh-tick! richeh-state coco-state send-bridge-cmd! tick online-p)
                         (atelier/check-duo-synchrony! coco-state richeh-state send-bridge-cmd!
                                                       (fn [who text]
                                                         (if (= who :coco)
                                                           (atelier/coco-say! text)
                                                           (atelier/richeh-say! text))))
                         (let [cel (ephem/compute-celestial-state (mod tick 24000))]
                           (atelier/check-celestial-reaction! coco-state richeh-state cel send-bridge-cmd!
                                                              (fn [who text]
                                                                (if (= who :coco)
                                                                  (atelier/coco-say! text)
                                                                  (atelier/richeh-say! text)))))
                         (catch Throwable t
                           (println "[craft-agents-atelier-ERROR]" (.getMessage t)))))

                     ;; ========================================================
                     ;; C) Tick da Guardiã Karlach (Santuário de Animais)
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
                           coco-card   {:status (:status @coco-state) :pos (:pos @coco-state)}
                           richeh-card {:status (:status @richeh-state) :pos (:pos @richeh-state)}
                           k-skills (vec (or (get-in @engine-ctx-atom [:agents :karlach :skills]) []))
                           make-karlach-card (fn [status pos]
                                               (cond-> {:status status :pos pos}
                                                 (seq k-skills) (assoc :skills k-skills)))
                           make-agents-map (fn [k-status k-pos]
                                             {:karlach (make-karlach-card k-status k-pos)
                                              :picard  picard-card
                                              :coco    coco-card
                                              :richeh  richeh-card})]
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
                                  :agents (make-agents-map "dancing 🔥" [cx cy cz])))

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

                               ;; Sincronização e Composição Multimodal Terra-Barco (2Hz)
                               (when (zero? (mod tick 10))
                                 (try
                                   (let [actual-sitting? (query-sitting-status "Karlach")
                                         target-sitting? (query-sitting-status (:name target-p))]
                                     (when (some? actual-sitting?)
                                       (swap! nav-state assoc :in-vehicle? actual-sitting?))
                                     ;; 1. Se o líder desembarcou e Karlach continua no barco -> desembarque automático nativo
                                     (when (and actual-sitting? (false? target-sitting?))
                                       (send-bridge-cmd! "STAND|Karlach")
                                       (swap! nav-state assoc :in-vehicle? false))
                                     ;; 2. Se o líder embarcou e Karlach está próxima (<= 3.5m) fora do barco -> embarque automático
                                     (when (and (false? actual-sitting?) target-sitting? (<= dist 3.5))
                                       (send-bridge-cmd! "CMD|execute at Karlach run ride Karlach mount @e[type=boat,distance=..4,sort=nearest,limit=1]")
                                       (swap! nav-state assoc :in-vehicle? true)))
                                   (catch Throwable _ nil)))

                               ;; Varredura tática e preempção de combate reativo (CA-049)
                               (let [hostiles (if (zero? (mod tick 4))
                                                (try (let [h (scan-nearby-hostiles "Karlach" 14.0)]
                                                       (swap! nav-state assoc :last-hostiles h)
                                                       h)
                                                     (catch Throwable _ (:last-hostiles @nav-state [])))
                                                (:last-hostiles @nav-state []))
                                     in-veh?  (:in-vehicle? st false)
                                     decision (evaluate-combat-preemption [cx cy cz] [tx ty tz] hostiles in-veh?)]
                                 (case (:action decision)
                                   :combat-strike
                                   (let [[mx my mz] (:target-pos decision)
                                         target-id  (:target-id decision)
                                         hdx        (- (double mx) (double cx))
                                         hdz        (- (double mz) (double cz))
                                         hyaw       (compute-yaw hdx hdz)]
                                     (send-bridge-cmd! (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz hyaw))
                                     (when target-id (attack-hostile-target! "Karlach" target-id))
                                     (send-bridge-cmd! "SWING|Karlach")
                                     (send-bridge-cmd! (uformat "CMD|particle minecraft:crit %.2f %.2f %.2f 0.3 0.3 0.3 0.1 5 normal"
                                                               (double mx) (+ (double my) 1.0) (double mz)))
                                     (when (zero? (mod tick 30))
                                       (karlach-say! "Toma essa! Não mexe com meu esquadrão! 🔥 🛡️"))
                                     (swap! engine-ctx-atom assoc
                                            :tick tick
                                            :leases {:legs {:owner :combat-strike} :hands {:owner :combat-strike} :gaze {:owner :combat-strike}}
                                            :agents (make-agents-map "⚔️ em combate defensivo" (:pos @nav-state))))

                                   :combat-intercept
                                   (let [[mx my mz] (:target-pos decision)
                                         path-queue (:follow-path st [])
                                         last-target (or (:follow-last-target st) [0.0 0.0 0.0])
                                         target-moved? (> (+ (Math/abs (- (double mx) (double (or (first last-target) 0.0))))
                                                             (Math/abs (- (double mz) (double (or (nth last-target 2 nil) 0.0)))))
                                                          2.5)
                                         active-path (if (or (empty? path-queue) target-moved?)
                                                       (request-path3d [cx cy cz] [mx my mz] 2.0)
                                                       path-queue)]
                                     (if-let [next-node (first active-path)]
                                       (let [nx (+ (double (:x next-node)) 0.5)
                                             ny (double (:y next-node))
                                             nz (+ (double (:z next-node)) 0.5)
                                             ndx (- nx cx)
                                             ndz (- nz cz)
                                             ndist (Math/sqrt (+ (* ndx ndx) (* ndz ndz)))
                                             nyaw (compute-yaw ndx ndz)]
                                         (if (<= ndist 0.4)
                                           (swap! nav-state #(-> %
                                                                 (assoc :follow-path (vec (rest active-path)))
                                                                 (assoc :follow-last-target [mx my mz])))
                                           (let [step-d (min 0.28 (max 0.05 ndist))
                                                 safe-ndist (max 0.001 ndist)
                                                 step-x (+ cx (* (/ ndx safe-ndist) step-d))
                                                 step-z (+ cz (* (/ ndz safe-ndist) step-d))]
                                             (send-bridge-cmd!
                                              (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|true|false" step-x ny step-z nyaw))
                                             (swap! nav-state assoc :pos [step-x ny step-z] :yaw nyaw :follow-path active-path :follow-last-target [mx my mz]))))
                                       (let [hdx (- (double mx) (double cx))
                                             hdz (- (double mz) (double cz))
                                             hyaw (compute-yaw hdx hdz)]
                                         (send-bridge-cmd!
                                          (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz hyaw))
                                         (swap! nav-state assoc :yaw hyaw)))
                                     (swap! engine-ctx-atom assoc
                                            :tick tick
                                            :leases {:legs {:owner :combat-intercept} :hands nil :gaze {:owner :combat-intercept}}
                                            :agents (make-agents-map "🏹 interceptando hostil" (:pos @nav-state))))

                                   :remain-mounted-passive
                                   (do
                                     (send-bridge-cmd!
                                      (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz yaw))
                                     (swap! engine-ctx-atom assoc
                                            :tick tick
                                            :leases {:legs nil :hands nil :gaze {:owner :passenger-look}}
                                            :agents (make-agents-map "⛵ navegando embarcada com você" (:pos @nav-state))))

                                   ;; Default: :tether-snap-recall ou :follow-player
                                   (do
                                     (if (and (<= dist 2.6) (not= (:action decision) :tether-snap-recall))
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
                                                     step-z (+ cz (* (/ ndz safe-ndist) step-d))
                                                     safe-ny (cond
                                                               (= act :SWIM) (+ (double ny) 0.2)
                                                               (and (< ny ty) (< ty 64.0) (>= ty 62.0)) (max ny (- (double ty) 0.15))
                                                               :else ny)]
                                                 (send-bridge-cmd!
                                                  (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|true|false" step-x safe-ny step-z nyaw))
                                                 (swap! nav-state assoc :pos [step-x safe-ny step-z] :yaw nyaw :follow-path active-path :follow-last-target [tx ty tz]))))
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
                                            :agents {:karlach (make-karlach-card (if (= (:action decision) :tether-snap-recall)
                                                                                   (str "⚡ retornando ao líder (tether snap)")
                                                                                   (if (= (:mode st) :bodyguard) (str "🛡️ guarda-costas de " (:name target-p)) (str "escoltando " (:name target-p))))
                                                                                 (:pos @nav-state))
                                                     :picard  picard-card})))))
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
                                         ;; Garante a picareta de netherite visível na mão principal durante toda a animação
                                         (when (or (= cur-ticks 1) (zero? (mod cur-ticks 3)))
                                           (send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:netherite_pickaxe")
                                           (send-bridge-cmd! "CMD|item replace entity Karlach hotbar.0 with minecraft:netherite_pickaxe"))
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
                                                 (collect-nearby-drops! "Karlach" 6.0)))
                                             (swap! nav-state #(-> %
                                                                   (assoc :active-mining nil)
                                                                   (update :mining-blocks-broken (fnil inc 0)))))
                                           ;; Continua quebrando
                                           (swap! nav-state assoc-in [:active-mining :ticks] cur-ticks)))))
                                   ;; Se não tiver bloco ativo, busca o próximo minério exposto a cada 5 ticks
                                   (when (zero? (mod tick 5))
                                     (collect-nearby-drops! "Karlach" 6.0)
                                     (let [filter-str (or (:mining-filter st) "")
                                           ores (locate-nearby-ores "Karlach" 12.0 filter-str)
                                           unreach (:unreachable-ores st #{})
                                           reachable-ores (filter #(not (contains? unreach [(:x %) (:y %) (:z %)])) ores)]
                                       (if-let [target-ore (first reachable-ores)]
                                         (do
                                           (send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:netherite_pickaxe")
                                           (send-bridge-cmd! "CMD|item replace entity Karlach hotbar.0 with minecraft:netherite_pickaxe")
                                           (swap! nav-state assoc :active-mining
                                                  {:x (:x target-ore)
                                                   :y (:y target-ore)
                                                   :z (:z target-ore)
                                                   :block-id (or (:block-id target-ore) "minecraft:deepslate")
                                                   :air-x (:air-x target-ore)
                                                   :air-y (:air-y target-ore)
                                                   :air-z (:air-z target-ore)
                                                   :ticks 0
                                                   :max-ticks 14}))
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
                         (and (= (:mode st) :sanctuary-patrol)
                              (proactive-sentry-scan! engine-ctx-atom nav-state sanctuary-route))
                         nil

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
                                   (try
                                     (critic/record-early-abort-event! reflection
                                                                      (or (:skill-id cur-step) :patrol-sanctuary)
                                                                      "state/abort-ledger.edn")
                                     (catch Throwable _ nil))
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
                                                :agents (make-agents-map (:label cur-step) [(/ (Math/round (* nx 10.0)) 10.0) (double ty) (/ (Math/round (* nz 10.0)) 10.0)]))))))

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
                                          :agents (make-agents-map (:label cur-step) [cx cy cz])))

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
                                          :agents (make-agents-map (:label cur-step) [cx cy cz])))

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
                                              :agents (make-agents-map (:label cur-step) [cx cy cz]))
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
                                          :agents (make-agents-map (:label cur-step) [(/ (Math/round (* cx 10.0)) 10.0) cy (/ (Math/round (* cz 10.0)) 10.0)]))))))))))
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
     :picard-state picard-state
     :coco-state coco-state
     :richeh-state richeh-state}))

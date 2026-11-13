(ns craft.agents.backend.fabric.atelier.behavior
  "Estado e motor de 20 TPS de Coco e Richeh, sinergia da dupla e reacao celeste."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :refer [uformat]]
            [craft.agents.backend.fabric.atelier.speech :refer [coco-data richeh-data]]))

;; =============================================================================
;; Waypoints e Configuração de Castelo
;; =============================================================================

(def castle-waypoints
  [[-30.0 66.0 190.0]
   [-25.0 66.0 194.0]
   [-20.0 66.0 190.0]
   [-24.0 66.0 185.0]
   [-28.0 66.0 186.0]])

;; =============================================================================
;; Fábrica de Estado
;; =============================================================================

(defn create-coco-state
  ([] (create-coco-state [-30.0 66.0 190.0]))
  ([pos]
   (atom {:pos (vec pos)
          :yaw 0.0
          :pitch 0.0
          :mode :patrol
          :follow-target nil
          :waypoint-idx 1
          :phase :walk
          :wait-left 0
          :last-bark-ms 0
          :last-duo-ms 0
          :last-spell-ms 0
          :last-spell-said-ms 0
          :spell-said? false
          :status "Estudando glifos no castelo"})))

(defn create-richeh-state
  ([] (create-richeh-state [-30.0 66.0 192.0]))
  ([pos]
   (atom {:pos (vec pos)
          :yaw 0.0
          :pitch 0.0
          :mode :tether-orbit
          :follow-target nil
          :phase :orbit
          :wait-left 0
          :last-bark-ms 0
          :last-spell-ms 0
          :last-spell-said-ms 0
          :spell-said? false
          :status "Artesã de cristal & guarda-costas"})))

;; =============================================================================
;; Motor de 20 TPS: Coco (Curiosa, Observadora e Desenhista)
;; =============================================================================

(defn step-coco-tick!
  "Executa 1 tick motora e comportamental da Coco."
  [coco-st send-cmd! tick online-players]
  (let [st @coco-st
        [cx cy cz] (:pos st)
        mode (:mode st)]
    (cond
      ;; 1. Modo Follow (Jogador solicitou acompanhamento)
      (= mode :follow)
      (let [target-name (or (:follow-target st) "pace")
            target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case target-name)) online-players))]
        (if target-p
          (let [dx (- (:x target-p) cx)
                dz (- (:z target-p) cz)
                dist (Math/sqrt (+ (* dx dx) (* dz dz)))
                yaw (nav/compute-yaw dx dz)]
            (if (> dist 2.4)
              (let [step-d (min 0.20 dist)
                    nx (+ cx (* (/ dx dist) step-d))
                    nz (+ cz (* (/ dz dist) step-d))]
                (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx cy nz yaw))
                (swap! coco-st assoc :pos [nx cy nz] :yaw yaw :status (str "Acompanhando " (:name target-p))))
              (do
                (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz yaw))
                (swap! coco-st assoc :yaw yaw :status (str "Conversando com " (:name target-p))))))
          (swap! coco-st assoc :mode :patrol)))

      ;; 2. Modo Patrulha / Estudo no Castelo
      :else
      (let [wp-idx (:waypoint-idx st 0)
            target-wp (nth castle-waypoints wp-idx (first castle-waypoints))
            [tx ty tz] target-wp
            phase (:phase st :walk)]
        (if (= phase :walk)
          (let [dx (- tx cx)
                dz (- tz cz)
                dist (Math/sqrt (+ (* dx dx) (* dz dz)))
                yaw (nav/compute-yaw dx dz)]
            (if (<= dist 0.35)
              ;; Chegou ao waypoint -> inicia sessão de anotações no caderno
              (do
                (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" tx ty tz yaw))
                (send-cmd! "SWING|Coco")
                (send-cmd! "CMD|execute at Coco run particle minecraft:enchant ~ ~1.0 ~ 0.3 0.2 0.3 0.05 8 normal")
                (swap! coco-st assoc
                       :pos [(double tx) (double ty) (double tz)]
                       :yaw yaw
                       :phase :sketch
                       :wait-left 70
                       :status "Desenhando glifo no caderno"))
              ;; Continua caminhando
              (let [step-d (min 0.18 dist)
                    nx (+ cx (* (/ dx dist) step-d))
                    nz (+ cz (* (/ dz dist) step-d))]
                (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ty nz yaw))
                (swap! coco-st assoc :pos [nx ty nz] :yaw yaw :status "Explorando os salões"))))

          ;; Fase :sketch (Folheando o caderno e estudando)
          (let [rem (dec (:wait-left st 0))]
            (when (zero? (mod rem 25))
              (send-cmd! "SWING|Coco")
              (send-cmd! "CMD|execute at Coco run particle minecraft:witch ~ ~1.1 ~ 0.2 0.2 0.2 0.02 5 normal"))
            ;; Social gaze: se jogador estiver a menos de 7m, vira na direção dele
            (when-let [nearest (first (filter #(let [dx (- (:x %) cx) dz (- (:z %) cz)]
                                                 (< (+ (* dx dx) (* dz dz)) 49.0))
                                              online-players))]
              (let [pdx (- (:x nearest) cx)
                    pdz (- (:z nearest) cz)
                    gyaw (nav/compute-yaw pdx pdz)]
                (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz gyaw))
                (swap! coco-st assoc :yaw gyaw)))
            (if (<= rem 0)
              (swap! coco-st assoc
                     :waypoint-idx (mod (inc wp-idx) (count castle-waypoints))
                     :phase :walk
                     :wait-left 0)
              (swap! coco-st assoc :wait-left rem))))))))

;; =============================================================================
;; Motor de 20 TPS: Richeh (Artesã de Cristal & Protetora Silenciosa)
;; =============================================================================

(defn step-richeh-tick!
  "Executa 1 tick motora e comportamental da Richeh."
  [richeh-st coco-st send-cmd! tick online-players]
  (let [st @richeh-st
        [rx ry rz] (:pos st)
        mode (:mode st)]
    (cond
      ;; 1. Modo Follow (Jogador solicitou acompanhamento)
      (= mode :follow)
      (let [target-name (or (:follow-target st) "pace")
            target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case target-name)) online-players))]
        (if target-p
          (let [dx (- (:x target-p) rx)
                dz (- (:z target-p) rz)
                dist (Math/sqrt (+ (* dx dx) (* dz dz)))
                yaw (nav/compute-yaw dx dz)]
            (if (> dist 2.4)
              (let [step-d (min 0.20 dist)
                    nx (+ rx (* (/ dx dist) step-d))
                    nz (+ rz (* (/ dz dist) step-d))]
                (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ry nz yaw))
                (swap! richeh-st assoc :pos [nx ry nz] :yaw yaw :status (str "Seguindo " (:name target-p))))
              (do
                (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" rx ry rz yaw))
                (swap! richeh-st assoc :yaw yaw :status (str "Vigiando " (:name target-p))))))
          (swap! richeh-st assoc :mode :tether-orbit)))

      ;; 2. Modo Tether Orbit em torno da Coco
      :else
      (let [[cx cy cz] (:pos @coco-st)
            dx (- cx rx)
            dz (- cz rz)
            dist (Math/sqrt (+ (* dx dx) (* dz dz)))
            yaw (nav/compute-yaw dx dz)]
        (cond
          ;; Coco afastou-se demais (> 5.5m): Richeh acelera para alcançá-la
          (> dist 5.5)
          (let [step-d (min 0.22 dist)
                nx (+ rx (* (/ dx dist) step-d))
                nz (+ rz (* (/ dz dist) step-d))]
            (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx cy nz yaw))
            (swap! richeh-st assoc :pos [nx cy nz] :yaw yaw :status "Orbitando Coco"))

          ;; Distância ideal (2.0m a 5.5m): Permanece atenta ou inscreve micro-glifos de cristal
          (>= dist 2.0)
          (do
            (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" rx ry rz yaw))
            (when (zero? (mod tick 60))
              (send-cmd! "SWING|Richeh")
              (send-cmd! "CMD|execute at Richeh run particle minecraft:glow_squid_ink ~ ~0.2 ~ 0.2 0.05 0.2 0.02 4 normal")
              (send-cmd! "CMD|execute at Richeh run playsound minecraft:block.amethyst_block.hit master @a[distance=..16] ~ ~ ~ 0.5 1.8"))
            (swap! richeh-st assoc :yaw yaw :status "Inscrevendo micro-glifos"))

          ;; Muito perto (< 2.0m): dá um passo sutil para o lado
          :else
          (let [side-dx (- dz)
                side-dz dx
                sdist (max 0.01 (Math/sqrt (+ (* side-dx side-dx) (* side-dz side-dz))))
                nx (+ rx (* (/ side-dx sdist) 0.08))
                nz (+ rz (* (/ side-dz sdist) 0.08))]
            (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ry nz yaw))
            (swap! richeh-st assoc :pos [nx ry nz] :yaw yaw :status "Guardando distância")))))))

;; =============================================================================
;; Sinergia de Dupla (Duo Synchrony)
;; =============================================================================

(defn check-duo-synchrony!
  "Dispara interação temática entre Coco e Richeh quando estão próximas."
  [coco-st richeh-st send-cmd! say-fn]
  (let [now (System/currentTimeMillis)
        [cx cy cz] (:pos @coco-st)
        [rx ry rz] (:pos @richeh-st)
        dx (- rx cx)
        dz (- rz cz)
        dist (Math/sqrt (+ (* dx dx) (* dz dz)))]
    (when (and (< dist 3.5)
               (> (- now (:last-duo-ms @coco-st 0)) 45000))
      (swap! coco-st assoc :last-duo-ms now)
      (let [cyaw (nav/compute-yaw dx dz)
            ryaw (nav/compute-yaw (- dx) (- dz))]
        (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz cyaw))
        (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" rx ry rz ryaw))
        (send-cmd! "SWING|Coco")
        (send-cmd! "SWING|Richeh")
        (send-cmd! "CMD|execute at Coco run particle minecraft:enchant ~ ~1.2 ~ 0.3 0.2 0.3 0.05 10 normal")
        (send-cmd! "CMD|execute at Richeh run particle minecraft:block_marker minecraft:amethyst_block ~ ~0.5 ~ 0.2 0.2 0.2 0.05 8 normal")
        (if (zero? (rand-int 2))
          (say-fn :coco (rand-nth (get-in @coco-data [:agent/dialogues :reactions :near-richeh]
                                          ["Richeh! Olha só esse novo arranjo de sinais que tentei rascunhar!"])))
          (say-fn :richeh (rand-nth (get-in @richeh-data [:agent/dialogues :reactions :near-coco]
                                            ["Hmph. Seu traço melhorou um pouquinho hoje, Coco... mas só um pouquinho."]))))))))

(defn check-celestial-reaction!
  "Reage a eventos celestes raros (eclipses, auroras ou alinhamento do gigante gasoso) (CA-052)."
  [coco-st richeh-st celestial-snapshot send-cmd! say-fn]
  (let [phenomenon (:phenomenon celestial-snapshot :nominal)
        now (System/currentTimeMillis)]
    (when (and (not= phenomenon :nominal)
               (> (- now (:last-celestial-ms @coco-st 0)) 45000))
      (swap! coco-st assoc :last-celestial-ms now)
      (let [[cx cy cz] (:pos @coco-st)
            [rx ry rz] (:pos @richeh-st)
            [gx gy gz] (get-in celestial-snapshot [:gas-giant :vector] [0.0 0.8 0.6])
            pitch (float (* (Math/asin (- gy)) (/ 180.0 Math/PI)))
            yaw   (float (* (Math/atan2 (- gx) gz) (/ 180.0 Math/PI)))]
        (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|%.1f|false|false" cx cy cz yaw pitch))
        (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|%.1f|false|false" rx ry rz yaw pitch))
        (send-cmd! "SWING|Coco")
        (send-cmd! "SWING|Richeh")
        (case phenomenon
          :solar-eclipse
          (do
            (send-cmd! "CMD|execute at Coco run particle minecraft:witch ~ ~1.8 ~ 0.4 0.4 0.4 0.05 15 normal")
            (say-fn :coco (rand-nth (get-in @coco-data [:agent/dialogues :celestial]
                                            ["O gigante com anéis cobriu o sol! As sombras ficaram duplas! 🪐✨"]))))
          :crystal-aurora
          (do
            (send-cmd! "CMD|execute at Richeh run particle minecraft:end_rod ~ ~1.8 ~ 0.5 0.5 0.5 0.08 20 normal")
            (say-fn :richeh (rand-nth (get-in @richeh-data [:agent/dialogues :celestial]
                                              ["Olha a refração naquelas cortinas de luz no céu, Coco! 🔮"]))))
          nil)))))

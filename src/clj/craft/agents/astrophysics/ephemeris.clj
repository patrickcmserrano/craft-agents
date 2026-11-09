(ns craft.agents.astrophysics.ephemeris
  "Motor determinístico de efemérides planetárias f(world-time).
   Compartilhado entre o SkyRenderer (Client) e o SceneDigest (Server 20Hz) (CA-050)."
  (:require [clojure.string :as str]))

(def ^:const ticks-per-day 24000.0)
(def ^:const deg->rad (/ Math/PI 180.0))
(def ^:const rad->deg (/ 180.0 Math/PI))

(defn normalize-vec3
  "Normaliza um vetor 3D [x y z] para comprimento 1.0."
  [[x y z]]
  (let [mag (Math/sqrt (+ (* x x) (* y y) (* z z)))]
    (if (< mag 1.0e-6)
      [0.0 1.0 0.0]
      [(/ x mag) (/ y mag) (/ z mag)])))

(defn dot-vec3
  "Produto escalar entre dois vetores 3D."
  [[x1 y1 z1] [x2 y2 z2]]
  (+ (* x1 x2) (* y1 y2) (* z1 z2)))

(defn solar-vector
  "Calcula o vetor unitário da estrela principal no ciclo de 24000 ticks.
   Tick 0 = Nascente (+X)
   Tick 6000 = Meio-dia / Zênite (+Y)
   Tick 12000 = Poente (-X)
   Tick 18000 = Meia-noite (-Y)
   Inclinação orbital de 18 graus."
  [world-time]
  (let [phase (* 2.0 Math/PI (/ (mod world-time 24000) ticks-per-day))
        tilt  (* 18.0 deg->rad)
        sx    (Math/cos phase)
        sy    (* (Math/sin phase) (Math/cos tilt))
        sz    (* (Math/sin phase) (Math/sin tilt))]
    (normalize-vec3 [sx sy sz])))

(defn gas-giant-vector
  "Calcula a posição orbital do Gigante Gasoso (período ressonante de 3.5 dias do Minecraft).
   Inclinação de 28 graus."
  [world-time]
  (let [orbital-period (* 3.5 ticks-per-day)
        theta          (* 2.0 Math/PI (/ (mod world-time orbital-period) orbital-period))
        inclination    (* 28.0 deg->rad)
        gx             (Math/cos theta)
        gy             (* (Math/sin theta) (Math/cos inclination))
        gz             (* (Math/sin theta) (Math/sin inclination))]
    (normalize-vec3 [gx gy gz])))

(defn classify-phase
  "Classifica a fase iluminada do Gigante Gasoso a partir do cosseno de separação angular com a Estrela."
  [cos-sep]
  (cond
    (> cos-sep 0.85)  :new-giant
    (> cos-sep 0.15)  :crescent
    (> cos-sep -0.35) :quarter-illuminated
    (> cos-sep -0.85) :gibbous
    :else             :full-illuminated))

(defn compute-celestial-state
  "Retorna o snapshot astrofísico determinístico completo para o tick atual."
  [world-time]
  (let [sun-v       (solar-vector world-time)
        giant-v     (gas-giant-vector world-time)
        cos-sep     (max -1.0 (min 1.0 (dot-vec3 sun-v giant-v)))
        ang-sep-deg (* (Math/acos cos-sep) rad->deg)
        sun-elev    (* (Math/asin (nth sun-v 1)) rad->deg)
        giant-elev  (* (Math/asin (nth giant-v 1)) rad->deg)
        ;; Raio aparente do Gigante Gasoso = 8.5 graus.
        ;; Se sol está acima do horizonte (> 5.0°) e separação < 8.5°, eclipse solar ativo.
        eclipse?    (and (> sun-elev 5.0) (< ang-sep-deg 8.5))
        obscuration (if eclipse?
                      (max 0.0 (min 1.0 (- 1.0 (/ ang-sep-deg 8.5))))
                      0.0)
        ;; Aurora ocorre à noite (sun-elev < -8°) com pico harmônico periódico
        aurora-wave (Math/sin (* 2.0 Math/PI (/ (double world-time) 48000.0)))
        aurora-int  (if (< sun-elev -8.0)
                      (max 0.0 (min 1.0 (* aurora-wave aurora-wave)))
                      0.0)]
    {:world-time          world-time
     :sun-vector          sun-v
     :sun-elevation-deg   sun-elev
     :gas-giant           {:vector               giant-v
                           :elevation-deg        giant-elev
                           :ring-normal          (normalize-vec3 [0.22 0.91 -0.35])
                           :phase                (classify-phase cos-sep)
                           :angular-separation   ang-sep-deg
                           :visible-above-horiz? (> giant-elev -5.0)}
     :eclipse-active?     eclipse?
     :eclipse-obscuration (double obscuration)
     :aurora-intensity    (double aurora-int)
     :phenomenon          (cond
                            eclipse?            :solar-eclipse
                            (> aurora-int 0.65) :crystal-aurora
                            (and (> giant-elev 35.0)
                                 (= (classify-phase cos-sep) :full-illuminated)) :giant-opposition
                            :else               :nominal)}))

(defn simulate-eclipse-state
  "Constrói um estado com separação angular forçada para testes determinísticos de eclipse."
  [world-time target-sep-deg]
  (let [sun-v       (solar-vector world-time)
        sun-elev    (* (Math/asin (nth sun-v 1)) rad->deg)
        ;; Constrói vetor do gigante alinhado com o sol desviado por target-sep-deg
        rad-sep     (* target-sep-deg deg->rad)
        [sx sy sz]  sun-v
        giant-v     (normalize-vec3 [(+ sx (* (Math/sin rad-sep) 0.5))
                                     (+ sy (* (Math/sin rad-sep) 0.5))
                                     (+ sz (* (Math/cos rad-sep) 0.1))])
        giant-elev  (* (Math/asin (nth giant-v 1)) rad->deg)
        eclipse?    (and (> sun-elev 5.0) (< target-sep-deg 8.5))
        obscuration (if eclipse?
                      (max 0.0 (min 1.0 (- 1.0 (/ target-sep-deg 8.5))))
                      0.0)]
    {:world-time          world-time
     :sun-vector          sun-v
     :sun-elevation-deg   sun-elev
     :gas-giant           {:vector               giant-v
                           :elevation-deg        giant-elev
                           :ring-normal          (normalize-vec3 [0.22 0.91 -0.35])
                           :phase                :new-giant
                           :angular-separation   target-sep-deg
                           :visible-above-horiz? true}
     :eclipse-active?     eclipse?
     :eclipse-obscuration (double obscuration)
     :aurora-intensity    0.0
     :phenomenon          (if eclipse? :solar-eclipse :nominal)}))

(defn shader-uniform-payload
  "Formata o payload de uniforms f(world-time) para consumo direto pelo shader GLSL 330 / WebGL2 (CA-053)."
  ([world-time] (shader-uniform-payload world-time 0.0))
  ([world-time tick-delta]
   (let [state      (compute-celestial-state world-time)
         time-sec   (float (* (+ (double world-time) (double tick-delta)) 0.001))
         sun-v      (mapv float (:sun-vector state))
         giant-v    (mapv float (get-in state [:gas-giant :vector]))
         ring-n     (mapv float (get-in state [:gas-giant :ring-normal]))
         aurora-int (float (:aurora-intensity state))
         eclipse-f  (float (:eclipse-obscuration state))]
     {:uTime             time-sec
      :uSunDir           sun-v
      :uGiantDir         giant-v
      :uRingNormal       ring-n
      :uAuroraIntensity  aurora-int
      :uEclipseFactor    eclipse-f})))

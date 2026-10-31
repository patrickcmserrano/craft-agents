(ns craft.agents.nav-follower
  "Seguidor fisico continuo de rota e deteccao inteligente de stuck sem teleporte cheaty (CA-026).")

(defn compute-steering-controls
  "Calcula controles suaves {:forward :sprint? :jump? :yaw :pitch} sem snap artificial de 0.2 blocos (CA-026)."
  [body-state next-step]
  (let [[bx by bz] (:pos body-state)
        tx (or (:x next-step) (first next-step))
        ty (or (:y next-step) (second next-step))
        tz (or (:z next-step) (nth next-step 2))
        dx (- (+ (double tx) 0.5) (double bx))
        dy (- (double ty) (double by))
        dz (- (+ (double tz) 0.5) (double bz))
        dist (Math/sqrt (+ (* dx dx) (* dz dz)))
        yaw  (Math/toDegrees (Math/atan2 (- dx) dz))]
    {:forward (if (> dist 0.15) 1.0 0.0)
     :sprint? (> dist 2.0)
     :jump?   (> dy 0.5)
     :yaw     (float yaw)
     :pitch   (float (Math/toDegrees (Math/atan2 (- dy) dist)))}))

(defn stuck?
  "Detecta se o agente nao progrediu em direcao ao proximo no dentro da janela de tolerancia de 1500ms (CA-026)."
  [follower-state now-ms]
  (let [last-ms (or (:last-progress-ms follower-state) now-ms)]
    (> (- (long now-ms) (long last-ms)) 1500)))

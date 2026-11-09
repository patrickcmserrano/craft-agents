(ns craft.agents.bt.builtins
  "Biblioteca de Skills Primitivas Built-in com codigos de erro semanticos (CA-033)."
  (:require [craft.agents.backend.protocol :as proto]))

(defn execute-builtin-action!
  "Despacha acao built-in (:care-for-enclosure, :escort-player, :engage-hostiles, :patrol-waypoints, etc.) (CA-033)."
  [action-id params {:keys [body world] :as tick-ctx}]
  (case action-id
    :care-for-enclosure
    (let [food-item (:food-item params)
          body-st   (when body (proto/body-state body))
          inv       (or (:inventory body-st) {})
          cnt       (get inv food-item 0)]
      (if (<= cnt 0)
        {:status :failure :reason :MISSING_ITEM}
        {:status :success :fed 1}))

    :patrol-waypoints
    {:status :success}

    :social-gaze
    {:status :success}

    :escort-player
    {:status :running}

    :engage-hostiles
    {:status :running}

    :run-blackboard-directive
    {:status :success}

    :gaze-at-celestial-body
    (let [body-vec (get-in tick-ctx [:celestial :gas-giant :vector] [0.0 0.7071 0.7071])
          [dx dy dz] body-vec
          pitch (* (Math/asin (- dy)) (/ 180.0 Math/PI))
          yaw   (* (Math/atan2 (- dx) dz) (/ 180.0 Math/PI))]
      {:status :success
       :leases [:gaze]
       :yaw (float yaw)
       :pitch (float pitch)})

    (:tether-follow-ally :crouch-and-inscribe :emote-sketch-notes
     :approach-and-sketch :ward-companion :evade-towards-ally :face-entity)
    {:status :success}

    {:status :failure :reason :UNKNOWN_ACTION}))

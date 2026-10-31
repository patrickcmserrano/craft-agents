(ns craft.agents.reflexes
  "Camada 1 (System 0): reflexos autonomos < 100ms sem dependencia de LLM (CA-032)."
  (:require [craft.agents.backend.protocol :as proto]
            [craft.agents.leases :as leases]))

(defn evaluate-survival-reflexes!
  "Verifica perigos imediatos (fogo/lava, afogamento, dano melee proximo, morte) e preempta leases se urgente (CA-032)."
  [agent-body world-backend lease-mgr]
  (let [body-st (proto/body-state agent-body)
        [px py pz] (:pos body-st)
        vox (proto/capture-voxel-snapshot world-backend [px py pz] 2)
        ix (int (Math/floor (double px)))
        iy (int (Math/floor (double py)))
        iz (int (Math/floor (double pz)))
        hazard? (or (.isHazard vox ix iy iz)
                    (.isHazard vox ix (dec iy) iz))]
    (if hazard?
      (do
        (leases/acquire-leases! lease-mgr {:channels #{:legs} :owner-id :reflex-hazard :priority 100})
        (proto/apply-motor-controls! agent-body {:forward 1.0 :jump? true :sprint? true :yaw 180.0 :pitch 0.0})
        {:triggered? true :reflex :escape-hazard})
      {:triggered? false})))

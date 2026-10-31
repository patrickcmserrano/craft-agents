(ns craft.agents.nav-portals
  "Maquina de estados transacional traverse-portal: abrir -> cruzar plano normal -> finally fechar (CA-027)."
  (:require [craft.agents.backend.protocol :as proto]
            [craft.agents.leases :as leases]))

(defn crossed-portal-plane?
  "Verifica produto escalar (pos - center) . normal > threshold confirmando cruzamento real (CA-027)."
  [pos portal-center portal-normal threshold]
  (let [dx (- (double (nth pos 0)) (double (nth portal-center 0)))
        dy (- (double (nth pos 1)) (double (nth portal-center 1)))
        dz (- (double (nth pos 2)) (double (nth portal-center 2)))
        [nx ny nz] portal-normal
        dot (+ (* dx (double nx)) (* dy (double (or ny 0.0))) (* dz (double nz)))]
    (> dot (double threshold))))

(defn step-portal-traversal!
  "Avanca 1 passo da travessia atomica garantindo fechamento mesmo em preempcao/erro (CA-027)."
  [{:keys [phase portal-blocks priority] :or {priority 40}} agent-body lease-mgr]
  (case phase
    :open
    (let [close-fn (fn []
                     (doseq [b portal-blocks]
                       (proto/interact-portal-block! agent-body b false)))
          lease-res (leases/acquire-leases! lease-mgr
                                            {:channels #{:legs :hands}
                                             :owner-id :portal-traversal
                                             :priority priority
                                             :on-preempt close-fn})]
      (if (:granted? lease-res)
        (do
          (doseq [b portal-blocks]
            (proto/interact-portal-block! agent-body b true))
          {:status :in-progress :phase :cross})
        {:status :failed :reason :LEASE_DENIED}))

    :close
    (do
      (doseq [b portal-blocks]
        (proto/interact-portal-block! agent-body b false))
      (leases/release-leases! lease-mgr :portal-traversal)
      {:status :success})

    {:status :unknown-phase}))

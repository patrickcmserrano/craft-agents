(ns craft.agents.studio.ingame-debug
  "Emissor opcional de particulas de rota 3D visiveis apenas para o admin dentro do Minecraft (CA-042).")

(defn emit-admin-route-particles!
  "Envia pacotes de particulas da rota A* exclusivamente para o jogador admin autorizado (CA-042)."
  [world-backend admin-player-name path-steps]
  (when (= "Pace" admin-player-name)
    (when-let [w-atom (:world-atom world-backend)]
      (swap! w-atom update :particles (fnil conj []) {:recipient admin-player-name :steps path-steps}))
    true))

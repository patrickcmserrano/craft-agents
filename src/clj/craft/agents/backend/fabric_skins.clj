(ns craft.agents.backend.fabric-skins
  "Aplicacao declarativa de skins assinadas e integracao FabricTailor (CA-015).")

(defn apply-skin-profile!
  "Aplica textura/assinatura no GameProfile ou via FabricTailor (CA-015)."
  [profile-atom skin-data]
  (let [tex (:texture-value skin-data)
        sig (:signature skin-data)]
    (swap! profile-atom
           (fn [p]
             (-> p
                 (assoc-in [:properties "textures" :value] tex)
                 (cond-> sig (assoc-in [:properties "textures" :signature] sig)))))
    {:applied? true}))

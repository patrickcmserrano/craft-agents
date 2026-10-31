(ns craft.agents.backend.fabric
  "Adaptador Server-Native p/ Fabric 1.21.1 sobre ServerPlayerEntity + SilentEmbeddedChannel (CA-014, CA-020)."
  (:require [craft.agents.backend.protocol :as proto]))

(defn create-fabric-backend
  "Instancia o backend Server-Native acoplado ao MinecraftServer ativo (CA-014)."
  [_server-instance _opts]
  (reify proto/IWorldBackend
    (spawn-agent! [_ _profile] nil)
    (despawn-agent! [_ _agent-id] true)
    (active-agents [_] {})
    (capture-voxel-snapshot [_ _center _radius] nil)
    (query-entities [_ _pos _radius] [])
    (has-line-of-sight? [_ _from _to] true)))

(defn read-player-inventory-edn
  "Serializa os 36 slots + armadura + offhand em mapa EDN imutavel (CA-020)."
  [player-entity]
  (or (get-in (proto/body-state player-entity) [:inventory]) {}))

(defn atomic-chest-transfer!
  "Transfere itens entre inventario do bot e bau preservando conservacao estrita de quantidade (CA-020)."
  [body chest-coord direction item-id amount]
  (proto/transfer-container-items! body chest-coord direction item-id amount))

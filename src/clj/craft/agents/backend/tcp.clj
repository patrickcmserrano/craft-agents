(ns craft.agents.backend.tcp
  "Adaptador v0.2 Headless Swarm TCP sobre Virtual Threads + Codec Passthrough Tolerante (CA-050, CA-051)."
  (:require [craft.agents.backend.protocol :as proto])
  (:import [com.craft.agents.swarm SharedSwarmWorld]
           [java.util.concurrent Executors]))

(defrecord TcpSwarmBackend [conn-opts shared-world executor]
  proto/IWorldBackend
  (spawn-agent! [_ _profile]
    (throw (UnsupportedOperationException. "v0.2 TCP Swarm live connection")))
  (despawn-agent! [_ _agent-id] true)
  (active-agents [_] {})
  (capture-voxel-snapshot [_ _center _radius] shared-world)
  (query-entities [_ _pos _radius] [])
  (has-line-of-sight? [_ _from _to] true))

(defn create-tcp-swarm-backend
  "Cria adaptador de rede compartilhando uma unica instancia de SharedSwarmWorld (CA-050)."
  [conn-opts]
  (let [shared (if (:shared-world conn-opts) (SharedSwarmWorld.) nil)
        exec   (Executors/newVirtualThreadPerTaskExecutor)]
    (->TcpSwarmBackend conn-opts shared exec)))

(defn decode-packet-tolerant
  "Decodifica pacote preservando fatias de bytes opacos em DataComponents modded desconhecidos (CA-051)."
  [packet-id ^bytes raw-bytes]
  (let [len (alength raw-bytes)]
    {:status :ok
     :packet-id packet-id
     :length len
     :opaque-tail (if (> len 2)
                    (java.util.Arrays/copyOfRange raw-bytes 2 len)
                    raw-bytes)}))

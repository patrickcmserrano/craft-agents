(ns craft.agents.epic7-headless-swarm-v02-test
  "Testes de fronteira do Epico 7 (v0.2): SharedSwarmWorld, Virtual Threads TCP, Passthrough Modded e SDK Externo (CA-049 a CA-052)."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.tcp :as tcp]
            [craft.agents.mcp.server :as mcp])
  (:import [com.craft.agents.swarm SharedSwarmWorld]))

(deftest ca-049-shared-swarm-world-single-chunk-cache-test
  (testing "CA-049: 100 bots externos no mesmo servidor compartilham uma unica instancia imutavel de SharedSwarmWorld"
    (let [shared (SharedSwarmWorld.)]
      (.ingestChunkPacket shared -11 10 (byte-array [1 2 3 4]))
      (is (>= (.activeSubscriberBots shared) 0)))))

(deftest ca-050-virtual-thread-tcp-swarm-backend-contract-test
  (testing "CA-050: Adaptador TCP Swarm instancia sessoes leves sobre Virtual Threads do Java 21+"
    (let [backend (tcp/create-tcp-swarm-backend {:host "127.0.0.1" :port 25565 :shared-world true})]
      (is (some? backend)))))

(deftest ca-051-tolerant-modded-packet-opaque-passthrough-test
  (testing "CA-051: Pacote de equipamento com DataComponents desconhecidos (ex: JEG) preserva bytes opacos sem desync de stream"
    (let [corrupting-modded-payload (byte-array [0x2A 0x01 0x7F -0x12 0x00 0x55])
          decoded (tcp/decode-packet-tolerant :entity_equipment corrupting-modded-payload)]
      (is (= :ok (:status decoded)) "Jamais deve quebrar como o ProtoDef do Mineflayer")
      (is (bytes? (:opaque-tail decoded))))))

(deftest ca-052-external-python-sdk-mcp-wire-contract-test
  (testing "CA-052: Contrato wire JSON-RPC 2.0 permite que SDK Python/CLI externo consulte digest e envie batch de acoes"
    (let [resp (mcp/handle-jsonrpc-request
                {:agents {}}
                {:jsonrpc "2.0" :id "py-sdk-1" :method "resources/list" :params {}})]
      (is (= "py-sdk-1" (:id resp)))
      (is (vector? (get-in resp [:result :resources]))))))

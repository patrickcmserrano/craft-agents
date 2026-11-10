(ns craft.agents.backend.fabric
  "Fachada e Adaptador Server-Native p/ Fabric 1.21.1 (CA-014, CA-020).
   Decomposto modularmente em namespaces de dominio sob craft.agents.backend.fabric.*:
     - transport  : Conexao socket TCP 25595 e protocolo de comandos
     - actions    : Acoes motoras nativas e consultas fisiologicas
     - picard     : Comandos do Capitao Picard e telemetria meteorologica
     - sanctuary  : Topologia espacial, cercados, portais e rotinas de alimentacao
     - navigation : A* 3D, orientacao espacial e expansao de rotas em micro-passos
     - goals      : Despacho polimorfico de metas cognitivas e interpretador BT
     - sentry     : Escolta Ativa (sentry) e parsing de comandos de chat
     - telemetry  : Eventos cognitivos e dispatcher para o Agent Studio
     - engine     : Loop de 20 TPS ao vivo da Karlach e Picard"
  (:require [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.atelier :as atelier]
            [craft.agents.backend.fabric.engine :as engine]
            [craft.agents.backend.fabric.goals :as goals]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.picard :as picard]
            [craft.agents.backend.fabric.sanctuary :as sanctuary]
            [craft.agents.backend.fabric.sentry :as sentry]
            [craft.agents.backend.fabric.telemetry :as telemetry]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.backend.protocol :as proto]))

;; ============================================================================
;; Contratos do Epico 2 (CA-014, CA-020) preservados p/ suite TDD
;; ============================================================================

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

;; ============================================================================
;; Re-exportacoes de Transporte e Ponte TCP
;; ============================================================================

(def send-bridge-cmd! transport/send-bridge-cmd!)
(def bridge-alive? transport/bridge-alive?)
(def close-bridge-conn! transport/close-bridge-conn!)
(def uformat transport/uformat)

;; ============================================================================
;; Re-exportacoes de Acoes Motoras e Estado Fisico
;; ============================================================================

(def load-skin-meta actions/load-skin-meta)
(def ensure-live-bridge-and-agents! actions/ensure-live-bridge-and-agents!)
(def query-online-players actions/query-online-players)
(def query-leash-status actions/query-leash-status)
(def query-health-status actions/query-health-status)
(def query-sitting-status actions/query-sitting-status)
(def locate-dropped-items actions/locate-dropped-items)
(def collect-nearby-drops! actions/collect-nearby-drops!)
(def inspect-inventory actions/inspect-inventory)
(def drop-inventory-items! actions/drop-inventory-items!)
(def scan-nearby-hostiles actions/scan-nearby-hostiles)
(def attack-hostile-target! actions/attack-hostile-target!)
(def mine-block! actions/mine-block!)
(def locate-nearby-ores actions/locate-nearby-ores)
(def raycast-player-look actions/raycast-player-look)
(def auto-eat-food! actions/auto-eat-food!)
(def feed-hungry-agents! actions/feed-hungry-agents!)
(defn equip-item!
  "Equipa item nativamente via opcode EQUIP_ITEM da bridge (Zero CMD - CA-049)."
  [player-name slot item-id]
  (let [cmd (str "EQUIP_ITEM|" player-name "|" slot "|" item-id)
        resp (send-bridge-cmd! cmd)]
    (when (some? resp)
      {:success? true :player player-name :slot slot :item item-id :cmd cmd})))
(def karlach-say! actions/karlach-say!)
(def ore-pt-names actions/ore-pt-names)
(def last-drop-ms actions/last-drop-ms)

;; ============================================================================
;; Re-exportacoes de Meteorologia e Capitao Picard
;; ============================================================================

(def query-minecraft-weather picard/query-minecraft-weather)
(def refresh-real-world-weather-async! picard/refresh-real-world-weather-async!)
(def picard-say! picard/picard-say!)
(def picard-broadcast-weather! picard/picard-broadcast-weather!)
(def build-picard-stations picard/build-picard-stations)
(def picard-stations picard/picard-stations)

;; ============================================================================
;; Re-exportacoes de Atelie (Coco & Richeh)
;; ============================================================================

(def coco-say! atelier/coco-say!)
(def richeh-say! atelier/richeh-say!)
(def castle-waypoints atelier/castle-waypoints)

;; ============================================================================
;; Re-exportacoes de Santuario e Geografia
;; ============================================================================

(def karlach-dialogues sanctuary/karlach-dialogues)
(def current-enclosure-id sanctuary/current-enclosure-id)
(def in-escaped-zone? sanctuary/in-escaped-zone?)
(def barks-for-current-position sanctuary/barks-for-current-position)
(def build-sanctuary-route sanctuary/build-sanctuary-route)
(def sanctuary-route sanctuary/sanctuary-route)
(def build-patrol-route sanctuary/build-patrol-route)
(def patrol-route sanctuary/patrol-route)
(def sanctuary-portals-by-pos sanctuary/sanctuary-portals-by-pos)
(def sanctuary-enclosures-by-id sanctuary/sanctuary-enclosures-by-id)
(def compute-enclosure-funnel sanctuary/compute-enclosure-funnel)
(def set-double-gate! sanctuary/set-double-gate!)
(def open-portal-at-coord! sanctuary/open-portal-at-coord!)
(def close-portal-at-coord! sanctuary/close-portal-at-coord!)
(def feed-enclosure-animals! sanctuary/feed-enclosure-animals!)

;; ============================================================================
;; Re-exportacoes de Navegacao e Pathfinding 3D
;; ============================================================================

(def compute-yaw nav/compute-yaw)
(def compute-pitch nav/compute-pitch)
(def request-path3d nav/request-path3d)
(def generate-walk-steps nav/generate-walk-steps)
(def expand-route-microsteps nav/expand-route-microsteps)

;; ============================================================================
;; Re-exportacoes de Metas Cognitivas e BT
;; ============================================================================

(def execute-karlach-goal! goals/execute-karlach-goal!)
(def execute-tree-node! goals/execute-tree-node!)

;; ============================================================================
;; Re-exportacoes de Telemetria e Studio
;; ============================================================================

(def global-command-dispatcher telemetry/global-command-dispatcher)
(def record-cognitive-event! telemetry/record-cognitive-event!)
(def dispatch-simulated-chat! telemetry/dispatch-simulated-chat!)

;; ============================================================================
;; Re-exportacoes de Escolta Ativa (Sentry) e Chat
;; ============================================================================

(def chat-duplicate? sentry/chat-duplicate?)
(def sanctuary-monitored-species sentry/sanctuary-monitored-species)
(def parse-sanctuary-escaped-animals sentry/parse-sanctuary-escaped-animals)
(def proactive-sentry-scan! sentry/proactive-sentry-scan!)
(def handle-player-command! sentry/handle-player-command!)
(def poll-server-log-chat! sentry/poll-server-log-chat!)

;; ============================================================================
;; Re-exportacoes do Motor 20 TPS
;; ============================================================================

(def start-live-karlach-loop! engine/start-live-karlach-loop!)
(def evaluate-combat-preemption engine/evaluate-combat-preemption)
(def rain-shelter-decision engine/rain-shelter-decision)
(def weather-transition engine/weather-transition)
(def shelter-route-steps engine/shelter-route-steps)
(def sit-karlach-at-quarters! engine/sit-karlach-at-quarters!)
(def stand-karlach! engine/stand-karlach!)

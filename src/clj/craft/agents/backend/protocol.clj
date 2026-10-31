(ns craft.agents.backend.protocol
  "Protocolos Dual-Backend desacoplando o cerebro Clojure do transporte de mundo (CA-003).")

(defprotocol IAgentBody
  "Controles fisicos e interacoes do corpo de um agente individual (CA-003)."
  (body-state [body] "Retorna mapa imutavel {:pos [x y z] :vel [vx vy vz] :yaw :pitch :health :on-ground? :sneaking? :sprinting? :inventory ...}")
  (apply-motor-controls! [body controls] "Aplica {:forward :strafe :jump? :sprint? :sneak? :yaw :pitch} no tick atual")
  (interact-portal-block! [body coord target-open?] "Abre ou fecha bloco de porta/portao em [x y z]")
  (feed-nearby-entity! [body entity-id item-id] "Alimenta entidade com o item especificado")
  (engage-target! [body entity-id mode weapon-id] "Executa ataque :melee ou :ranged contra alvo")
  (transfer-container-items! [body chest-coord direction item-id count] "Transfere itens :withdraw ou :deposit atomicamente")
  (equip-slot! [body slot-or-hand item-id] "Equipa item na mao/armadura")
  (speak-chat! [body message gesture] "Envia mensagem no chat + gesto corporal"))

(defprotocol IWorldBackend
  "Adaptador de mundo (Fabric Server-Native v0.1 ou Headless Swarm TCP v0.2) (CA-003)."
  (spawn-agent! [backend agent-profile] "Materializa agente no mundo e retorna instancia de IAgentBody")
  (despawn-agent! [backend agent-id] "Remove agente limpamente do mundo")
  (active-agents [backend] "Retorna mapa {agent-id IAgentBody}")
  (capture-voxel-snapshot [backend center-pos chunk-radius] "Retorna VoxelAccessor thread-safe p/ PathSolver3D")
  (query-entities [backend center-pos radius] "Lista entidades proximas com IDs reais de registro")
  (has-line-of-sight? [backend from-pos to-pos] "Raycast geometrico entre dois pontos 3D"))

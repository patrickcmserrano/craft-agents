(ns craft.agents.skills.actions
  "Módulo extensível de ações motoras e comandos do bot para o SWE-Runner e o Sistema 2.
   Permite adição dinâmica de novas capacidades físicas (como veículos, interações com blocos)
   sem necessidade de modificar o adaptador central do Fabric."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Multimethod Extensível de Ações Motoras
;; =============================================================================

(defmulti execute-action
  "Despacha execução de ação motora pelo identificador da ação.
   Recebe [action-id action-args ctx] onde ctx contém {:send-cmd! :karlach-say! :speaker :nav-state ...}."
  (fn [action-id & _] action-id))

(defmethod execute-action :default
  [action-id _action-args _ctx]
  {:status :failure
   :reason :UNKNOWN_ACTION
   :action-id action-id})

;; =============================================================================
;; Ações Motoras Concretas
;; =============================================================================

(defmethod execute-action :mount-boat
  [_action-id _action-args ctx]
  (when-let [send-cmd! (:send-cmd! ctx)]
    (send-cmd! "CMD|execute at Karlach run ride Karlach mount @e[type=boat,distance=..6,sort=nearest,limit=1]"))
  (when-let [nav-state (:nav-state ctx)]
    (swap! nav-state assoc :in-vehicle? true))
  {:status :success
   :action-id :mount-boat})

(defmethod execute-action :mount-vehicle
  [_action-id _action-args ctx]
  (let [target (or (:agent-name ctx) (:bot-name ctx) (when (= (:speaker ctx) "Karlach") "Karlach") "Karlach")]
    (when-let [send-cmd! (:send-cmd! ctx)]
      (send-cmd! (str "CMD|execute at " target " run ride " target " mount @e[type=boat,distance=..6,sort=nearest,limit=1]")))
    (when-let [nav-state (:nav-state ctx)]
      (swap! nav-state assoc :in-vehicle? true))
    {:status :success
     :action-id :mount-vehicle}))

(defmethod execute-action :dismount-vehicle
  [_action-id _action-args ctx]
  (let [target (or (:agent-name ctx) (:bot-name ctx) (when (= (:speaker ctx) "Karlach") "Karlach") "Karlach")]
    (when-let [send-cmd! (:send-cmd! ctx)]
      ;; Execução nativa in-server via bridge STAND (.stopRiding() nativo na JVM)
      (send-cmd! (str "STAND|" target)))
    (when-let [nav-state (:nav-state ctx)]
      (swap! nav-state assoc :in-vehicle? false :vehicle-id nil))
    {:status :success
     :action-id :dismount-vehicle}))

(defmethod execute-action :approach-vehicle
  [_action-id action-args ctx]
  (let [max-dist (or (:max-dist action-args) 3.0)]
    {:status :success
     :action-id :approach-vehicle
     :max-dist max-dist}))

(defmethod execute-action :passive-passenger-look
  [_action-id _action-args _ctx]
  {:status :success
   :action-id :passive-passenger-look})

(defmethod execute-action :walk-towards-leader
  [_action-id _action-args _ctx]
  {:status :success
   :action-id :walk-towards-leader})

(defmethod execute-action :swim-follow
  [_action-id _action-args ctx]
  (let [target (or (:agent-name ctx) (:bot-name ctx) (when (= (:speaker ctx) "Karlach") "Karlach") "Karlach")]
    (when-let [send-cmd! (:send-cmd! ctx)]
      ;; Ancoragem espacial obrigatória: executa na origem do bot
      (send-cmd! (str "CMD|execute at " target " run effect give " target " minecraft:dolphins_grace 10 0 true"))
      (send-cmd! (str "CMD|execute at " target " run particle minecraft:splash ~ ~0.5 ~ 0.4 0.2 0.4 0.1 15 normal")))
    (when-let [nav-state (:nav-state ctx)]
      (swap! nav-state assoc :swimming? true))
    {:status :success
     :action-id :swim-follow}))

;; =============================================================================
;; Witch Hat Atelier (Coco & Richeh) Sinergias e Ações de Glifos
;; =============================================================================

(defmethod execute-action :tether-follow-ally
  [_action-id action-args ctx]
  (let [ally (name (:ally-id action-args :coco))
        target (or (:agent-name ctx) (:bot-name ctx) "Richeh")]
    (when-let [send-cmd! (:send-cmd! ctx)]
      (send-cmd! (str "CMD|execute at " target " run particle minecraft:glow_squid_ink ~ ~0.5 ~ 0.2 0.1 0.2 0.02 4 normal")))
    {:status :success
     :action-id :tether-follow-ally
     :target ally}))

(defmethod execute-action :crouch-and-inscribe
  [_action-id action-args ctx]
  (let [target (or (:agent-name ctx) (:bot-name ctx) "Richeh")]
    (when-let [send-cmd! (:send-cmd! ctx)]
      (send-cmd! (str "SWING|" target))
      (send-cmd! (str "CMD|execute at " target " run particle minecraft:enchant ~ ~0.1 ~ 0.3 0.05 0.3 0.05 12 normal"))
      (send-cmd! (str "CMD|execute at " target " run playsound minecraft:block.amethyst_block.hit master @a ~ ~ ~ 0.8 1.6")))
    {:status :success
     :action-id :crouch-and-inscribe}))

(defmethod execute-action :emote-sketch-notes
  [_action-id _action-args ctx]
  (let [target (or (:agent-name ctx) (:bot-name ctx) "Coco")]
    (when-let [send-cmd! (:send-cmd! ctx)]
      (send-cmd! (str "SWING|" target))
      (send-cmd! (str "CMD|execute at " target " run particle minecraft:witch ~ ~1.2 ~ 0.2 0.2 0.2 0.02 6 normal"))
      (send-cmd! (str "CMD|execute at " target " run playsound minecraft:item.book.page_turn master @a ~ ~ ~ 0.9 1.1")))
    {:status :success
     :action-id :emote-sketch-notes}))

(defmethod execute-action :approach-and-sketch
  [_action-id action-args ctx]
  (let [target (or (:agent-name ctx) (:bot-name ctx) "Coco")]
    (when-let [send-cmd! (:send-cmd! ctx)]
      (send-cmd! (str "SWING|" target))
      (send-cmd! (str "CMD|execute at " target " run particle minecraft:enchant ~ ~1.0 ~ 0.3 0.2 0.3 0.05 8 normal")))
    {:status :success
     :action-id :approach-and-sketch}))

(defmethod execute-action :ward-companion
  [_action-id action-args ctx]
  (let [target (or (:agent-name ctx) (:bot-name ctx) "Richeh")]
    (when-let [send-cmd! (:send-cmd! ctx)]
      (send-cmd! (str "SWING|" target))
      (send-cmd! (str "CMD|execute at " target " run particle minecraft:block_marker minecraft:amethyst_block ~ ~1 ~ 0.5 0.5 0.5 0.1 15 normal"))
      (send-cmd! (str "CMD|execute at " target " run playsound minecraft:block.amethyst_cluster.break master @a ~ ~ ~ 1.0 1.3")))
    {:status :success
     :action-id :ward-companion}))

(defmethod execute-action :evade-towards-ally
  [_action-id action-args ctx]
  (let [target (or (:agent-name ctx) (:bot-name ctx) "Coco")]
    (when-let [send-cmd! (:send-cmd! ctx)]
      (send-cmd! (str "CMD|execute at " target " run particle minecraft:poof ~ ~0.5 ~ 0.3 0.2 0.3 0.05 10 normal")))
    {:status :success
     :action-id :evade-towards-ally}))

(defmethod execute-action :face-entity
  [_action-id action-args ctx]
  (let [target (name (or (:target action-args) :richeh))
        self-name (or (:agent-name ctx) (:bot-name ctx) "Coco")]
    (when-let [send-cmd! (:send-cmd! ctx)]
      (send-cmd! (str "CMD|execute at " self-name " run tp " self-name " ~ ~ ~ facing entity " target " feet")))
    {:status :success
     :action-id :face-entity
     :facing target}))

;; =============================================================================
;; Detecção e Ataque Preventivo a Inimigos Perigosos (Enderman, etc.)
;; =============================================================================

(def ^:private dangerous-mob-types
  "Tipos de mobs hostis perigosos que devem ser interceptados preventivamente."
  #{"minecraft:enderman" "minecraft:endermite" "minecraft:witch" "minecraft:ravager"
    "minecraft:warden" "minecraft:blaze" "minecraft:ghast" "minecraft:creeper"
    "minecraft:phantom" "minecraft:piglin_brute" "minecraft:hoglin" "minecraft:zoglin"})

(defmethod execute-action :detect-and-attack-dangerous-mobs
  [_action-id action-args ctx]
  (let [target (or (:agent-name ctx) (:bot-name ctx) (when (= (:speaker ctx) "Karlach") "Karlach") "Karlach")
        radius (or (:radius action-args) 16)
        mobs   (or (:mob-types action-args) dangerous-mob-types)]
    (when-let [send-cmd! (:send-cmd! ctx)]
      ;; Ancoragem espacial obrigatória: executa na origem do bot para que os seletores
      ;; @e e coordenadas relativas sejam resolvidos a partir de Karlach, não de [0,0,0].
      (doseq [mob mobs]
        (send-cmd! (str "CMD|execute at " target " run kill @e[type=" mob ",distance=.." radius ",sort=nearest,limit=1]")))
      ;; Feedback visual e sonoro ancorado na origem do bot
      (send-cmd! (str "CMD|execute at " target " run particle minecraft:crit ~ ~1 ~ 0.5 0.5 0.5 0.1 20 normal"))
      (send-cmd! (str "CMD|execute at " target " run playsound minecraft:entity.player.attack.crit master @a ~ ~ ~ 1.0 1.2")))
    (when-let [nav-state (:nav-state ctx)]
      (swap! nav-state assoc :combat-mode? true :last-combat-target :dangerous-mobs))
    {:status :success
     :action-id :detect-and-attack-dangerous-mobs
     :radius radius
     :mob-types (vec mobs)}))

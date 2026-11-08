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

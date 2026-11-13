(ns craft.agents.backend.fabric.goals.bt-actions
  "Guards puros e registro extensivel de acoes de Behavior Tree (CA-094).
   `bt-action` e o multimethod de acoes de BT; `guard-holds?` avalia guards sem efeito colateral.
   O interpretador (goals.bt) reexporta ambos."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.registry :as registry]
            [craft.agents.skills.actions :as skill-actions]))

;; ----------------------------------------------------------------------------
;; Guards puros
;; ----------------------------------------------------------------------------

(defn- dist3 [[ax ay az] [bx by bz]]
  (let [dx (- (double bx) (double ax)) dy (- (double by) (double ay)) dz (- (double bz) (double az))]
    (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))

(defn guard-holds?
  "Guard puro de BT (CA-094): `(guard-holds? guard-id params world)` sem efeito colateral.
   `world`: {:self-pos :agents {id {:pos}} :perception {:allies :ally :blocks} :preferred-blocks
   :cooldowns {key last-ms} :now-ms}. Cobre :ally-within-range?, :interesting-block-nearby? e
   :cooldown-ready?; outros ids devolvem nil."
  [guard-id params world]
  (case guard-id
    :ally-within-range?
    (let [{:keys [ally-id max-dist] :or {max-dist 3.5}} params
          ally-pos (when ally-id (get-in world [:agents ally-id :pos]))
          known    (when ally-id (get-in world [:perception :allies ally-id :distance]))
          cur (cond
                (nil? ally-id) (or (get-in world [:perception :ally :distance]) 2.5)
                known known
                (and ally-pos (:self-pos world)) (dist3 (:self-pos world) ally-pos)
                :else nil)]
      (boolean (and cur (<= cur max-dist))))

    :interesting-block-nearby?
    (let [radius (or (:radius params) 5.0)
          pref   (:preferred-blocks world)]
      (boolean (some (fn [b] (and (<= (:distance b) radius)
                                  (or (empty? pref) (contains? pref (:id b)))))
                     (get-in world [:perception :blocks]))))

    :cooldown-ready?
    (let [last-ms (get-in world [:cooldowns (:key params)])]
      (boolean (or (nil? last-ms)
                   (>= (- (or (:now-ms world) 0) last-ms) (* 1000 (or (:seconds params) 0))))))

    nil))

(defn guard-world
  "Mundo (puro) dos guards a partir do engine-ctx, do estado e do agente."
  [engine-ctx-atom nav-state agent-id]
  (let [ctx @engine-ctx-atom]
    (assoc ctx
           :self-pos (or (get-in ctx [:agents agent-id :pos]) (:pos @nav-state))
           :now-ms (or (:now-ms ctx) (System/currentTimeMillis))
           :preferred-blocks (get-in (registry/agent-by-id agent-id) [:agent/traits :preferred-blocks]))))

(defn stamp-cooldowns!
  "Sequencia concluida: carimba os `:cooldown-ready?` que ela continha."
  [engine-ctx-atom children]
  (doseq [[op gid params] children
          :when (and (= :guard op) (= :cooldown-ready? gid) (:key params))]
    (swap! engine-ctx-atom assoc-in [:cooldowns (:key params)]
           (or (:now-ms @engine-ctx-atom) (System/currentTimeMillis)))))

;; ----------------------------------------------------------------------------
;; Registro extensivel de acoes de BT
;; ----------------------------------------------------------------------------

(defmulti bt-action
  "Acao de BT: `(bt-action action-id params ctx)` com ctx
   {:agent :state :engine-ctx :sanctuary-route :speaker}. Devolve {:status ...}. O `:default`
   cai nas acoes modulares de skills.actions; id desconhecido registra evento e falha."
  (fn [action-id _params _ctx] action-id))

(defn- note-unimplemented!
  "Evento cognitivo \"ação não implementada\", uma vez por id de acao."
  [engine-ctx-atom action-id agent]
  (when engine-ctx-atom
    (let [[old _] (swap-vals! engine-ctx-atom update :bt-unimplemented (fnil conj #{}) action-id)]
      (when-not (contains? (:bt-unimplemented old) action-id)
        (record-cognitive-event! engine-ctx-atom :motor "Behavior Tree"
                                 (str "ação não implementada: " (name action-id)
                                      " (agente " (name (or (:agent/id agent) :desconhecido)) ")"))))))

(defn- skill-ctx [{:keys [agent state engine-ctx sanctuary-route speaker] :as ctx}]
  {:send-cmd! transport/send-bridge-cmd!
   :say! (fn [msg] (dispatch/say! ctx msg))
   :agent-name (:agent/name agent)
   :agent-id (:agent/id agent)
   :speaker speaker
   :engine-ctx engine-ctx
   :nav-state state
   :sanctuary-route sanctuary-route})

(defmethod bt-action :default
  [action-id params {:keys [agent engine-ctx] :as ctx}]
  (let [res (skill-actions/execute-action action-id params (skill-ctx ctx))]
    (if (= :UNKNOWN_ACTION (:reason res))
      (do (note-unimplemented! engine-ctx action-id agent)
          {:status :failure :reason :UNKNOWN_ACTION :action-id action-id})
      (do (record-cognitive-event! engine-ctx :motor "Ação Modular"
                                   (str "Executando ação modular: " action-id " -> " (:status res)))
          res))))

(defn- self-pos [{:keys [agent state engine-ctx]}]
  (or (get-in @engine-ctx [:agents (:agent/id agent) :pos]) (:pos @state)))

(defn- face-toward!
  [agent-name [sx sy sz] [tx _ tz]]
  (transport/send-bridge-cmd!
   (transport/uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                      agent-name (double sx) (double sy) (double sz)
                      (double (nav/compute-yaw (- (double tx) (double sx)) (- (double tz) (double sz)))))))

(defn- player-pos [p] [(:x p) (:y p) (:z p)])

(defmethod bt-action :face-entity
  [_ params {:keys [agent engine-ctx] :as ctx}]
  (let [target (:target params)
        tpos   (if (keyword? target)
                 (get-in @engine-ctx [:agents target :pos])
                 (some->> (actions/query-online-players)
                          (filter #(= (str/lower-case (str (:name %))) (str/lower-case (str target))))
                          first player-pos))
        spos   (self-pos ctx)]
    (if (and tpos spos)
      (do (face-toward! (:agent/name agent) spos tpos)
          {:status :success :action-id :face-entity :facing target})
      {:status :failure :reason :UNKNOWN_TARGET :action-id :face-entity})))

(defmethod bt-action :social-gaze
  [_ params {:keys [agent] :as ctx}]
  (let [radius  (or (:radius params) 10.0)
        spos    (self-pos ctx)
        npcs    (set (map str/lower-case (registry/agent-names)))
        nearest (when spos
                  (->> (actions/query-online-players)
                       (remove #(contains? npcs (str/lower-case (str (:name %)))))
                       (map (fn [p] [(dist3 spos (player-pos p)) p]))
                       (filter #(<= (first %) radius))
                       (sort-by first)
                       first))]
    (when nearest
      (face-toward! (:agent/name agent) spos (player-pos (second nearest))))
    {:status :success :action-id :social-gaze}))

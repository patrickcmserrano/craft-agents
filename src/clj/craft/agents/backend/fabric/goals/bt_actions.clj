(ns craft.agents.backend.fabric.goals.bt-actions
  "Guards fail-closed (`eval-guard`, `guard-holds?`) e registro unico de acoes de BT (CA-094,
   CA-108, CA-110): `registered-actions` = `{action-key {:run :channels :params :doc}}`
   (`register-action!`; `:run` e `(fn [params ctx])`, `:channels` do arbitro `npc.body`).
   `bt-action`: o `:default` consulta o registro e, depois, skills.actions. goals.bt reexporta."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.engine.combat :as combat]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch :refer [execute-goal!]]
            [craft.agents.backend.fabric.goals.routine-actions :as routine-actions]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.body :as body]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.routine :as routine]
            [craft.agents.skills.actions :as skill-actions]
            [malli.core :as m]))

;; ----------------------------------------------------------------------------
;; Guards: multimethod fail-closed
;; ----------------------------------------------------------------------------

(def ^:private dist3 routine/distance)

(defmulti eval-guard
  "Avalia a condicao `guard-id` (CA-108): `(eval-guard guard-id params world)`, sem efeito colateral.
   `world`: {:self-pos :agents :perception {:allies :ally :leader :blocks :hostiles :players}
   :preferred-blocks :cooldowns :now-ms :nav :agent-id :active-directive}. O `:default` e
   fail-closed (`false`); novos guards entram com `defmethod` e devem falhar fechado sem dados."
  (fn [guard-id _params _world] guard-id))

(defmethod eval-guard :default [_ _ _] false)

(defn guard-holds?
  "Guard puro fail-closed: sempre um boolean; desconhecido, sem snapshot, malformado ou excecao => false."
  [guard-id params world]
  (try
    (true? (boolean (eval-guard guard-id params world)))
    (catch Throwable _ false)))

(defn- add-guard! [ids f] ; um metodo de `eval-guard` para cada id (apelidos incluidos)
  (doseq [id ids] (.addMethod ^clojure.lang.MultiFn eval-guard id f)))

(defn- leader-in-vehicle? [world]
  (boolean (or (get-in world [:perception :leader :in-vehicle?])
               (get-in world [:leader :in-vehicle?])
               (get-in world [:nav :leader-in-vehicle?]))))

(defn- self-in-vehicle? [world]
  (boolean (or (get-in world [:nav :in-vehicle?])
               (get-in world [:perception :self :in-vehicle?])
               (get-in world [:agents (:agent-id world) :in-vehicle?]))))

(defmethod eval-guard :ally-within-range?
  [_ {:keys [ally-id max-dist] :or {max-dist 3.5}} world]
  (let [ally-pos (when ally-id (get-in world [:agents ally-id :pos]))
        known    (when ally-id (get-in world [:perception :allies ally-id :distance]))
        cur (cond
              (nil? ally-id) (get-in world [:perception :ally :distance])
              known known
              (and ally-pos (:self-pos world)) (dist3 (:self-pos world) ally-pos)
              :else nil)]
    (boolean (and cur (<= cur max-dist)))))

(defmethod eval-guard :interesting-block-nearby?
  [_ params world]
  (let [radius (or (:radius params) 5.0)
        pref   (:preferred-blocks world)]
    (boolean (some (fn [b] (and (<= (:distance b) radius)
                                (or (empty? pref) (contains? pref (:id b)))))
                   (get-in world [:perception :blocks])))))

(defmethod eval-guard :cooldown-ready?
  [_ params world]
  (let [now-ms  (:now-ms world)
        last-ms (get-in world [:cooldowns (:key params)])]
    (boolean (and now-ms
                  (or (nil? last-ms)
                      (>= (- now-ms last-ms) (* 1000 (or (:seconds params) 0))))))))

(add-guard! [:leader-in-vehicle? :leader-in-boat?] (fn [_ _ world] (leader-in-vehicle? world)))
(add-guard! [:self-in-vehicle? :self-in-boat?] (fn [_ _ world] (self-in-vehicle? world)))
(add-guard! [:not-self-in-vehicle?] (fn [_ _ world] (not (self-in-vehicle? world))))
(add-guard! [:not-leader-in-vehicle?] (fn [_ _ world] (not (leader-in-vehicle? world))))

(defmethod eval-guard :self-in-same-vehicle?
  [_ _ world]
  (let [self-vid (or (get-in world [:nav :vehicle-id])
                     (get-in world [:perception :self :vehicle-id]))
        lead-vid (or (get-in world [:perception :leader :vehicle :id])
                     (get-in world [:perception :leader :vehicle-id]))]
    (boolean (and (or (get-in world [:nav :in-vehicle?])
                      (get-in world [:perception :self :in-vehicle?]))
                  (leader-in-vehicle? world)
                  (or (= self-vid lead-vid) (and (nil? self-vid) (nil? lead-vid)))))))

(defmethod eval-guard :leader-vehicle-has-seat?
  [_ _ world]
  (let [vehicle (or (get-in world [:perception :leader :vehicle])
                    (get-in world [:leader :vehicle]))]
    (boolean (and vehicle (pos? (:free-seats vehicle 1))))))

(defmethod eval-guard :within-mount-range?
  [_ _ world]
  (let [dist (or (get-in world [:perception :leader :distance])
                 (get-in world [:nav :leader-distance]))]
    (boolean (and dist (<= dist 3.5)))))

(defmethod eval-guard :has-active-directive?
  [_ _ world]
  (boolean (or (:active-directive world) (:directive world))))

(defmethod eval-guard :hostile-nearby?
  [_ params world]
  (let [radius (or (:radius params) 10.0)]
    (boolean (some #(<= (:distance %) radius) (get-in world [:perception :hostiles])))))

(defmethod eval-guard :ally-distance-exceeds?
  [_ params world]
  (let [min-dist (or (:min-dist params) 8.0)
        cur-dist (get-in world [:perception :ally :distance])]
    (boolean (and cur-dist (> cur-dist min-dist)))))

(defmethod eval-guard :in-quiet-corner?
  ;; Exige o snapshot `[:perception :players]` (coll de {:distance}); sem ele nao ha canto quieto.
  [_ params world]
  (let [players (get-in world [:perception :players])
        radius  (or (:radius params) 6.0)
        max-n   (or (:max-nearby-players params) 0)]
    (boolean (and (sequential? players)
                  (<= (count (filter #(<= (:distance %) radius) players)) max-n)))))
(defn- world-agent [w] (or (:agent w) (some-> (:agent-id w) registry/agent-by-id))) ; CA-110
(add-guard! [:routine-activity?] (fn [_ {:keys [activity]} w] (routine/activity-now? (world-agent w) activity (:world-time w))))
(add-guard! [:at-place?] (fn [_ {:keys [place radius] :or {radius 2.0}} w] (routine/at-place? (world-agent w) place (:self-pos w) radius)))
(add-guard! [:celestial-event?] (fn [_ params w] (routine/celestial-event? (:celestial w) (:phenomena params)))) ; CA-111: [:celestial :phenomenon] do ctx

(defn guard-world
  "Mundo (puro) dos guards a partir do engine-ctx, do estado e do agente."
  [engine-ctx-atom nav-state agent-id]
  (let [ctx @engine-ctx-atom
        nav @nav-state]
    (assoc ctx
           :nav nav
           :agent-id agent-id
           :self-pos (or (get-in ctx [:agents agent-id :pos]) (:pos nav))
           :now-ms (or (:now-ms ctx) (System/currentTimeMillis))
           :agent (registry/agent-by-id agent-id)
           :preferred-blocks (get-in (registry/agent-by-id agent-id) [:agent/traits :preferred-blocks]))))

(defn stamp-cooldowns!
  "Sequencia concluida: carimba os `:cooldown-ready?` que ela continha."
  [engine-ctx-atom children]
  (doseq [[op gid params] children
          :when (and (= :guard op) (= :cooldown-ready? gid) (:key params))]
    (swap! engine-ctx-atom assoc-in [:cooldowns (:key params)]
           (or (:now-ms @engine-ctx-atom) (System/currentTimeMillis)))))

(defn eval-condition
  "Avalia `cond-id` de um no :guard/:condition via `eval-guard` (fail-closed)."
  [cond-id args engine-ctx-atom nav-state agent-id]
  (guard-holds? cond-id (second args) (guard-world engine-ctx-atom nav-state agent-id)))

;; ----------------------------------------------------------------------------
;; Registro unico de acoes de BT
;; ----------------------------------------------------------------------------

(defmulti bt-action
  "Ponto de extensao: `(bt-action action-id params ctx)` com ctx {:agent :state :engine-ctx
   :sanctuary-route :speaker} devolve {:status ...}. O `:default` consulta o registro, depois
   skills.actions; id desconhecido registra evento e falha."
  (fn [action-id _params _ctx] action-id))

(def ActionSpec
  "Contrato de uma entrada do registro de acoes."
  [:map
   [:run fn?]
   [:channels [:set [:enum :legs :hands :gaze]]]
   [:params some?]
   [:doc [:string {:min 1}]]])

(defonce ^:private action-registry (atom {}))

(defn register-action!
  "Registra `action-key` -> `{:run :channels :params :doc}` (valida `ActionSpec`); lanca se invalido."
  [action-key spec]
  (when-not (keyword? action-key)
    (throw (ex-info "acao de BT: chave deve ser keyword" {:action-key action-key})))
  (when-not (m/validate ActionSpec spec)
    (throw (ex-info (str "acao de BT invalida: " action-key)
                    {:action-key action-key :explain (m/explain ActionSpec spec)})))
  (m/schema (:params spec)) ; lanca se :params nao for um schema Malli valido
  (swap! action-registry assoc action-key (select-keys spec [:run :channels :params :doc]))
  action-key)

(defn unregister-action! [action-key] (swap! action-registry dissoc action-key) nil)

(defn registered-actions
  "Introspeccao: o mapa `{action-key {:run :channels :params :doc}}` do registro unico."
  [] @action-registry)

(defn known-action-ids
  "Ids executaveis: registro, defmethods de `bt-action` e skills.actions (insumo de `unknown-actions`)."
  []
  (-> (set (keys @action-registry))
      (into (keys (methods bt-action)))
      (into (keys (methods skill-actions/execute-action)))
      (disj :default)))

(defn- note-unimplemented!
  "Evento cognitivo \"ação não implementada\", uma vez por id de acao." [engine-ctx-atom action-id agent]
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
  (if-let [{:keys [run channels]} (get @action-registry action-id)]
    (run params (assoc ctx :channels channels))
    (let [res (skill-actions/execute-action action-id params (skill-ctx ctx))]
      (if (= :UNKNOWN_ACTION (:reason res))
        (do (note-unimplemented! engine-ctx action-id agent)
            {:status :failure :reason :UNKNOWN_ACTION :action-id action-id})
        (do (record-cognitive-event! engine-ctx :motor "Ação Modular"
                                     (str "Executando ação modular: " action-id " -> " (:status res)))
            res)))))

;; ----------------------------------------------------------------------------
;; Acoes declaradas
;; ----------------------------------------------------------------------------

(defn- self-pos [{:keys [agent state engine-ctx]}]
  (or (get-in @engine-ctx [:agents (:agent/id agent) :pos]) (:pos @state)))

(defn- face-toward!
  "Gira `agent` para `tpos` pelo canal declarado da acao (`:channels` do ctx, :gaze) do arbitro
   npc.body (fonte :bt): nunca toma :legs."
  [{:keys [agent channels]} [sx sy sz] [tx _ tz]]
  (body/step! {:agent (:agent/id agent) :agent-name (:agent/name agent)
               :source :bt :channel (or (first channels) :gaze)
               :x (double sx) :y (double sy) :z (double sz) :pitch 0.0
               :yaw (double (nav/compute-yaw (- (double tx) (double sx)) (- (double tz) (double sz))))}))

(defn- player-pos [p] [(:x p) (:y p) (:z p)])

(defn- goal-ctx
  "ctx de meta (sem :speaker/:channels): o que `execute-goal!` espera."
  [ctx]
  (select-keys ctx [:agent :state :engine-ctx :sanctuary-route]))

(defn- goal-action
  "Delega a uma meta do multimethod `execute-goal!`. Devolve {:status :success :action-id result-id}."
  [goal-type result-id extra ctx]
  (execute-goal! (merge {:goal/type goal-type :goal/speaker (:speaker ctx)} extra) (goal-ctx ctx))
  {:status :success :action-id result-id})

(defn- goal-spec
  "Entrada do registro de uma acao que apenas delega a uma meta. `extra-fn`: params -> extra."
  ([doc channels goal-type result-id] (goal-spec doc channels goal-type result-id (constantly {})))
  ([doc channels goal-type result-id extra-fn]
   {:run      (fn [params ctx] (goal-action goal-type result-id (extra-fn params) ctx))
    :channels channels
    :params   [:map]
    :doc      doc}))

(defn- evade-towards-ally!
  "`:evade-towards-ally` (CA-093): `combat-decision` (politica :flee); havendo fuga, delega a meta
   :follow-player ao aliado mais proximo (`:ally-ids`; posicoes em `[:agents id :pos]`). Sem hostil
   ou sem posicao de aliado devolve :failure."
  [params {:keys [agent state engine-ctx] :as ctx}]
  (let [ectx      @engine-ctx
        allies    (into {} (keep (fn [[id a]] (when-let [p (:pos a)] [id (vec p)]))) (:agents ectx))
        decision  (combat/combat-decision
                   (assoc agent :agent/combat-policy :flee)
                   {:bot-pos  (:pos @state)
                    :hostiles (get-in ectx [:perception :hostiles] [])
                    :allies   allies
                    :ally-ids (:ally-ids params)})
        ally-name (some-> (:ally-id decision) registry/agent-by-id :agent/name)]
    (if (and (= :evade-towards-ally (:action decision)) ally-name)
      (goal-action :follow-player :evade-towards-ally {:goal/target ally-name} ctx)
      {:status :failure :action :evade-towards-ally})))

(defn- face-entity! [params {:keys [agent engine-ctx] :as ctx}]
  (let [target (:target params)
        tpos   (if (keyword? target)
                 (get-in @engine-ctx [:agents target :pos])
                 (some->> (actions/query-online-players)
                          (filter #(= (str/lower-case (str (:name %))) (str/lower-case (str target))))
                          first player-pos))
        spos   (self-pos ctx)]
    (if (and tpos spos)
      (do (face-toward! ctx spos tpos)
          {:status :success :action-id :face-entity :facing target})
      {:status :failure :reason :UNKNOWN_TARGET :action-id :face-entity})))

(defn- social-gaze! [params {:keys [agent] :as ctx}]
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
      (face-toward! ctx spos (player-pos (second nearest))))
    {:status :success :action-id :social-gaze}))

(def ^:private builtin-actions
  {:face-entity
   {:run face-entity! :channels #{:gaze}
    :params [:map [:target [:or :keyword :string]]]
    :doc "Gira o agente para um agente (keyword) ou jogador (nome) pelo canal :gaze."}

   :social-gaze
   {:run social-gaze! :channels #{:gaze}
    :params [:map [:radius {:optional true} number?]]
    :doc "Olha o jogador humano mais proximo dentro do raio; passivo se nao houver ninguem."}

   :say-message
   {:run (fn [params ctx]
           (dispatch/say! (goal-ctx ctx) (or (:text params) "Comando concluído com sucesso, soldado!"))
           {:status :success :action-id :say-message})
    :channels #{}
    :params [:map [:text {:optional true} :string]]
    :doc "Fala uma mensagem no chat do agente."}

   :play-sound
   {:run (fn [params _ctx]
           (transport/send-bridge-cmd!
            (str "CMD|playsound " (:sound params "minecraft:entity.experience_orb.pickup")
                 " master @a ~ ~ ~ 1.0 1.0"))
           {:status :success :action-id :play-sound})
    :channels #{}
    :params [:map [:sound {:optional true} :string]]
    :doc "Toca um som para todos os jogadores."}

   :social-dance
   {:run (fn [_ ctx]
           (execute-goal! {:goal/type :social-dance :goal/voice-line "Olha o passinho de vitória! 🔥"}
                          (goal-ctx ctx))
           {:status :success :action-id :social-dance})
    :channels #{:legs}
    :params [:map]
    :doc "Danca de comemoracao com fala."}

   :sit-furniture       (goal-spec "Senta no movel mais proximo." #{:legs} :sit-furniture :sit-furniture)
   :stand-up            (goal-spec "Levanta do movel." #{:legs} :stand-up :stand-up)
   :patrol-sanctuary    (goal-spec "Patrulha o santuario." #{:legs} :patrol-sanctuary :patrol-sanctuary)
   :breed-animals       (goal-spec "Alimenta e reproduz animais." #{:hands} :breed-animals :breed-animals)
   :follow-player       (goal-spec "Segue o jogador." #{:legs} :follow-player :follow-player)
   :walk-towards-leader (goal-spec "Anda ate o lider." #{:legs} :follow-player :walk-towards-leader)
   :bodyguard           (goal-spec "Escolta o jogador como guarda-costas." #{:legs} :bodyguard :bodyguard)
   :mining-cooperation  (assoc (goal-spec "Mineracao cooperativa." #{:hands} :mining-cooperation :mining-cooperation
                                          (fn [params] {:goal/filter (:target params "diamond")}))
                               :params [:map [:target {:optional true} :string]])
   :drop-inventory      (goal-spec "Larga o inventario." #{:hands} :drop-inventory :drop-inventory)
   :teleport-to-player  (goal-spec "Teleporta ate o jogador." #{:legs} :teleport-to-player :teleport-to-player)
   :move-to-quarters    (goal-spec "Recolhe-se aos aposentos (abrigo)." #{:legs} :weather-shelter :move-to-quarters)

   :evade-towards-ally
   {:run evade-towards-ally! :channels #{:legs}
    :params [:map [:ally-ids {:optional true} [:vector :keyword]]]
    :doc "Foge de hostis rumo ao aliado mais proximo (politica :flee); :failure sem hostil."}})

(defn- pending-spec
  "Acao declarada por agentes mas ainda sem implementacao de BT: falha e registra o evento (1x por
   id), como uma acao desconhecida, mas consta no registro para validar o `:bt/root`."
  [id doc]
  {:run (fn [_ {:keys [agent engine-ctx]}]
          (note-unimplemented! engine-ctx id agent)
          {:status :failure :reason :UNKNOWN_ACTION :action-id id})
   :channels #{} :params [:map] :doc (str "PENDENTE: " doc)})

(def ^:private pending-actions
  (into {} (map (fn [[id doc]] [id (pending-spec id doc)]))
        {:run-blackboard-directive "executa a diretiva do blackboard."
         :engage-hostiles          "engaja hostis (hoje no tick de combate)."
         :patrol-waypoints         "patrulha waypoints (hoje no tick dedicado)."
         :care-for-enclosure       "cuida do recinto (hoje no tick do santuario)."
         :escort-player            "escolta o jogador (hoje no tick de escolta)."}))

(doseq [[id spec] (merge pending-actions builtin-actions routine-actions/actions)]
  (register-action! id spec))

(ns craft.agents.backend.fabric.goals.routine-actions
  "Acoes genericas de rotina, lugares nomeados e interacao social (CA-110, SUG-06 parte 1).
   Tudo vem do spec `.edn` do agente (`:agent/routine`, `:agent/places`, `:agent/relations`,
   `:agent/lines`, `:agent/effects`) via `npc.routine`; nenhuma logica por agente. `actions` e o
   mapa `{action-key {:run :channels :params :doc}}` que `bt-actions` registra no registro unico.
   `:run` e `(fn [params ctx])` com ctx {:agent :state :engine-ctx :speaker}."
  (:require [craft.agents.backend.fabric.goals.dispatch :as dispatch]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.body :as body]
            [craft.agents.npc.routine :as routine]))

(defn- world-time [{:keys [engine-ctx]}] (:world-time @engine-ctx))

(defn- self-pos [{:keys [agent state engine-ctx]}]
  (or (get-in @engine-ctx [:agents (:agent/id agent) :pos]) (:pos @state)))

(defn- step! [{:keys [agent]} channel [x y z] [fx _ fz] extra]
  (body/step! (merge {:agent (:agent/id agent) :agent-name (:agent/name agent)
                      :source :bt :channel channel
                      :x (double x) :y (double y) :z (double z) :pitch 0.0
                      :yaw (double (nav/compute-yaw (- (double x) (double fx)) (- (double z) (double fz))))}
                     extra)))

(defn- walk-to!
  "Um passo (pernas, fonte :bt) rumo a `dest`: :arrived, :moving ou :no-position."
  [ctx dest {:keys [speed arrive-dist] :or {speed 1.0 arrive-dist 1.5}}]
  (let [spos (self-pos ctx)]
    (cond
      (nil? spos) :no-position
      (routine/arrived? spos dest arrive-dist) :arrived
      :else (do (step! ctx :legs (routine/step-toward spos dest speed) spos nil)
                :moving))))

(defn- go-to-place
  "Anda ate o lugar `place-key` do agente. Devolve {:status ...} (+ :place)."
  [ctx place-key params]
  (if-let [{:keys [pos]} (routine/place (:agent ctx) place-key)]
    (case (walk-to! ctx pos params)
      :arrived     {:status :success :place place-key}
      :moving      {:status :running :place place-key}
      :no-position {:status :failure :reason :UNKNOWN_POSITION :place place-key})
    {:status :failure :reason :UNKNOWN_PLACE :place place-key}))

(defn- speak-line! [ctx topic]
  (when-let [line (routine/pick-line (:agent ctx) topic (world-time ctx))]
    (dispatch/say! (select-keys ctx [:agent :state :engine-ctx :sanctuary-route]) line)))

(defn- visit-place! [params ctx]
  (assoc (go-to-place ctx (:place params) params) :action-id :visit-place))

(defn- follow-routine-slot! [params ctx]
  (let [{:keys [activity place]} (routine/current-slot (:agent ctx) (world-time ctx))]
    (cond
      (nil? activity) {:status :failure :reason :NO_ROUTINE_SLOT :action-id :follow-routine-slot}
      (nil? place)    {:status :success :activity activity :action-id :follow-routine-slot}
      :else           (assoc (go-to-place ctx place params)
                             :activity activity :action-id :follow-routine-slot))))

(defn- greet-companion! [params ctx]
  (let [agent    (:agent ctx)
        target   (or (:target params) (routine/companion-id agent))
        rel      (routine/relation agent target)
        tpos     (get-in @(:engine-ctx ctx) [:agents target :pos])
        spos     (self-pos ctx)
        fail     (fn [reason] {:status :failure :reason reason :target target :action-id :greet-companion})]
    (cond
      (nil? rel) (fail :UNKNOWN_RELATION)
      (< (double (or (:affinity rel) 0.0)) (or (:min-affinity params) 0.0)) (fail :LOW_AFFINITY)
      (not (and tpos spos)) (fail :UNKNOWN_TARGET)
      (> (routine/distance spos tpos) (or (:max-dist params) 6.0)) (fail :TOO_FAR)
      :else (do (step! ctx :gaze spos spos
                       {:yaw (double (nav/compute-yaw (- (double (first tpos)) (double (first spos)))
                                                      (- (double (nth tpos 2)) (double (nth spos 2)))))})
                (speak-line! ctx (or (:line params) :greeting))
                {:status :success :target target :action-id :greet-companion}))))

(defn- workbench-study! [params ctx]
  (let [res (assoc (go-to-place ctx (or (:place params) :workbench) params) :action-id :workbench-study)]
    (when (= :success (:status res))
      (let [spos (self-pos ctx)]
        (step! ctx :legs spos spos {:use true})
        (speak-line! ctx :studying)))
    res))

(defn- cast-declared-spell! [params ctx]
  (let [agent    (:agent ctx)
        effect   (:effect params)
        declared (some #(when (= effect (:effect %)) %) (:agent/effects agent))]
    (if declared
      (do (transport/send-bridge-cmd!
           (str "CMD|effect give " (:agent/name agent) " " effect " " (or (:seconds params) 30) " "
                (or (:amplifier declared) 0) " true"))
          {:status :success :effect effect :action-id :cast-declared-spell})
      {:status :failure :reason :UNDECLARED_EFFECT :effect effect :action-id :cast-declared-spell})))

(defn- react-celestial!
  "CA-111: reage a um evento celeste do engine-ctx (`:celestial`): olha o gigante gasoso (:gaze) e
   fala uma linha `:celestial` de `:agent/lines`. `:phenomena` restringe o que o agente declara."
  [params ctx]
  (let [cel  (:celestial @(:engine-ctx ctx))
        spos (self-pos ctx)
        fail (fn [reason] {:status :failure :reason reason :action-id :react-celestial})]
    (cond
      (not (routine/celestial-event? cel (:phenomena params))) (fail :NO_CELESTIAL_EVENT)
      (nil? spos) (fail :UNKNOWN_POSITION)
      :else (let [[gx gy gz] (get-in cel [:gas-giant :vector] [0.0 0.8 0.6])]
              (step! ctx :gaze spos spos
                     {:yaw   (double (* (Math/atan2 (- (double gx)) (double gz)) (/ 180.0 Math/PI)))
                      :pitch (double (* (Math/asin (- (double gy))) (/ 180.0 Math/PI)))})
              (speak-line! ctx (or (:line params) :celestial))
              {:status :success :phenomenon (:phenomenon cel) :action-id :react-celestial}))))

(defn- tether-follow-ally!
  "CA-111: um passo (:legs) rumo ao aliado ate ficar a `:target-dist` dele. Devolve :success a cada
   tick (nunca :running): a BT reavalia as prioridades (hostil, dupla) antes do proximo passo."
  [params ctx]
  (let [agent (:agent ctx)
        ally  (or (:ally-id params) (routine/companion-id agent))
        tpos  (get-in @(:engine-ctx ctx) [:agents ally :pos])
        spos  (self-pos ctx)
        tdist (double (or (:target-dist params) 3.5))]
    (cond
      (not (and tpos spos)) {:status :failure :reason :UNKNOWN_TARGET :ally ally :action-id :tether-follow-ally}
      (<= (routine/distance spos tpos) tdist) {:status :success :ally ally :action-id :tether-follow-ally}
      :else (let [goal (routine/step-toward tpos spos tdist)]
              (step! ctx :legs (routine/step-toward spos goal (or (:speed params) 1.0)) spos nil)
              {:status :success :moving? true :ally ally :action-id :tether-follow-ally}))))

(def actions
  {:visit-place
   {:run visit-place! :channels #{:legs}
    :params [:map [:place :keyword] [:speed {:optional true} number?] [:arrive-dist {:optional true} number?]]
    :doc "Anda ate um lugar nomeado de :agent/places; :running ate chegar, :UNKNOWN_PLACE se nao declarado."}

   :follow-routine-slot
   {:run follow-routine-slot! :channels #{:legs}
    :params [:map [:speed {:optional true} number?] [:arrive-dist {:optional true} number?]]
    :doc "Segue o bloco de :agent/routine da hora do mundo, indo ao :place do bloco."}

   :greet-companion
   {:run greet-companion! :channels #{:gaze}
    :params [:map [:target {:optional true} :keyword] [:line {:optional true} :keyword]
             [:min-affinity {:optional true} number?] [:max-dist {:optional true} number?]]
    :doc "Olha e cumprimenta um agente de :agent/relations (padrao: o companheiro) com fala de :agent/lines."}

   :workbench-study
   {:run workbench-study! :channels #{:legs :hands}
    :params [:map [:place {:optional true} :keyword] [:speed {:optional true} number?]
             [:arrive-dist {:optional true} number?]]
    :doc "Vai ao bloco de trabalho (workstation, :agent/places) e o usa; fala :studying de :agent/lines."}

   :cast-declared-spell
   {:run cast-declared-spell! :channels #{:hands}
    :params [:map [:effect :string] [:seconds {:optional true} pos-int?]]
    :doc "Lanca um efeito declarado em :agent/effects; :UNDECLARED_EFFECT para o que o agente nao declara."}

   :react-celestial
   {:run react-celestial! :channels #{:gaze}
    :params [:map [:phenomena {:optional true} [:vector :keyword]] [:line {:optional true} :keyword]]
    :doc "Olha o gigante gasoso e fala uma linha :celestial de :agent/lines quando ha fenomeno celeste declarado."}

   :tether-follow-ally
   {:run tether-follow-ally! :channels #{:legs}
    :params [:map [:ally-id {:optional true} :keyword] [:target-dist {:optional true} number?]
             [:speed {:optional true} number?]]
    :doc "Um passo por tick rumo ao aliado (padrao: o companheiro) ate :target-dist; sempre :success."}})

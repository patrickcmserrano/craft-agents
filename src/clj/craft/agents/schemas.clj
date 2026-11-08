(ns craft.agents.schemas
  "Catalogo central de schemas Malli e geracao de JSON Schema p/ MCP (CA-004)."
  (:require [clojure.walk :as walk]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as json-schema]))

(def Coord3D
  [:tuple number? number? number?])

(def LeaseChannel
  [:enum :legs :hands :gaze])

(def PortalSpec
  [:map
   [:blocks [:vector Coord3D]]
   [:normal [:tuple number? number? number?]]
   [:inside-anchor Coord3D]
   [:outside-anchor Coord3D]
   [:gate-block {:optional true} :string]
   [:gate-facing {:optional true} :string]])

(def ResidentSpec
  [:map
   [:type :string]
   [:name :string]
   [:pos {:optional true} Coord3D]])

(def EnclosureSpec
  [:map
   [:enclosure/id :keyword]
   [:enclosure/name {:optional true} :string]
   [:enclosure/bounds [:map [:min Coord3D] [:max Coord3D]]]
   [:enclosure/portal PortalSpec]
   [:enclosure/feed-pos {:optional true} Coord3D]
   [:enclosure/food {:optional true} :string]
   [:enclosure/species {:optional true} [:vector :string]]
   [:enclosure/residents {:optional true} [:vector ResidentSpec]]])

(def RegionProfile
  [:map
   [:region/id :keyword]
   [:region/name :string]
   [:region/dimension :string]
   [:region/corridor-waypoints [:vector Coord3D]]
   [:region/enclosures [:vector EnclosureSpec]]])

(defn valid-bt-node?
  "Valida nos recursivos de Behavior Tree em modo fail-closed."
  [node]
  (boolean
   (and (vector? node)
        (seq node)
        (let [op (first node)
              args (rest node)]
          (case op
            (:selector :fallback :sequence :parallel)
            (and (seq args)
                 (every? valid-bt-node? args))

            (:action :guard :condition)
            (and (keyword? (first args))
                 (or (nil? (second args))
                     (map? (second args))))

            (:cooldown :repeat)
            (and (map? (first args))
                 (= 1 (count (rest args)))
                 (valid-bt-node? (second args)))

            :inverter
            (and (= 1 (count args))
                 (valid-bt-node? (first args)))

            false)))))

(def BTNode
  [:fn {:error/message "Invalid BTNode vector"} valid-bt-node?])

;; =============================================================================
;; ANÁLISE ESTÁTICA DE CONFLITOS DE LEASES SIMBÓLICAS (CA-RSI.3.2)
;; =============================================================================

(def action->lease-channel
  "Mapeamento determinístico de primitivas de ação para o canal de lease exigido."
  {:walk                 :legs
   :move-to              :legs
   :patrol-waypoints     :legs
   :patrol-sanctuary     :legs
   :follow-player        :legs
   :teleport-to-player   :legs
   :lure-to-gate         :legs
   :lure-inside          :legs
   :approach-vehicle     :legs
   :walk-towards-leader  :legs

   :attach-lead          :hands
   :detach-lead          :hands
   :open-gate            :hands
   :close-gate           :hands
   :equip-bait           :hands
   :feed-animal          :hands
   :feed-nearby          :hands
   :excavate-block       :hands
   :drop-inventory       :hands
   :breed-animals        :hands
   :mining-cooperation   :hands
   :mount-boat           :hands
   :mount-vehicle        :hands
   :dismount-vehicle     :hands

   :social-gaze          :gaze
   :look-at-player       :gaze
   :passive-passenger-look :gaze})

(defn extract-requested-lease-channels
  "Extrai todos os canais de lease requisitados por ações dentro de um nó ou sub-árvore.
   Se composite-skills for fornecido, expande recursivamente nós de macro.
   Ações desconhecidas fora de action->lease-channel são tratadas fail-closed como :unknown."
  ([node]
   (extract-requested-lease-channels node nil))
  ([node ctx]
   (let [composites (or (:composite-skills ctx) ctx {})
         acc (atom #{})]
     (walk/postwalk
      (fn [n]
        (when (and (vector? n) (= (first n) :action))
          (let [act-id (second n)]
            (if-let [comp (get composites act-id)]
              (swap! acc into (extract-requested-lease-channels comp composites))
              (if-let [ch (get action->lease-channel act-id)]
                (swap! acc conj ch)
                (swap! acc conj :unknown)))))
        n)
      node)
     @acc)))

(defn detect-lease-conflicts
  "Verificação Estática de Conflito de Leases Simbólicas (CA-RSI.3.2).
   Analisa todos os nós [:parallel ...] da árvore e detecta se dois ou mais ramos
   concorrentes requisitam o mesmo canal de lease (:legs, :hands, :gaze ou :unknown fail-closed)."
  ([tree]
   (detect-lease-conflicts tree nil))
  ([tree ctx]
   (let [conflicts (atom [])]
     (walk/postwalk
      (fn [n]
        (when (and (vector? n) (= (first n) :parallel))
          (let [children           (rest n)
                channels-per-child (mapv #(extract-requested-lease-channels % ctx) children)
                all-channels       (apply concat channels-per-child)
                freqs              (frequencies all-channels)
                clashing           (vec (sort (distinct
                                               (concat (keep (fn [[ch count]] (when (> count 1) ch)) freqs)
                                                       (when (and (contains? (set all-channels) :unknown)
                                                                  (> (count children) 1))
                                                         [:unknown])))))]
            (when (seq clashing)
              (swap! conflicts conj {:node n :channels clashing}))))
        n)
      tree)
     (let [all-clashing (vec (sort (distinct (mapcat :channels @conflicts))))]
       {:conflict?            (boolean (seq @conflicts))
        :conflicting-channels all-clashing
        :conflicts            @conflicts}))))

(def AgentProfile
  [:map
   [:agent/id :keyword]
   [:agent/name :string]
   [:agent/role {:optional true} :string]
   [:agent/spawn {:optional true} [:map [:dimension {:optional true} :string] [:pos Coord3D]]]
   [:agent/loadout {:optional true} :map]
   [:agent/dialogues {:optional true} :map]
   [:agent/quarters {:optional true} :map]
   [:bt/root BTNode]])

(def SceneDigest
  [:map
   [:self
    [:map
     [:name :string]
     [:pos [:tuple number? number? number?]]
     [:health number?]
     [:dimension :string]
     [:inventory [:map-of :string :int]]]]
   [:entities
    [:map
     [:players [:vector [:map [:name :string] [:pos [:tuple number? number? number?]]]]]
     [:animals-summary [:map-of :string :int]]
     [:hostiles [:vector [:map [:type :string] [:pos [:tuple number? number? number?]]]]]]]
   [:environment
    [:map
     [:time-of-day :int]
     [:weather [:enum :clear :rain :thunder]]
     [:nearby-portals [:vector [:map [:id :string] [:pos [:tuple number? number? number?]] [:open? :boolean]]]]]]
   [:recent-events [:vector :map]]])

(defn valid-coord3d? [v]
  (m/validate Coord3D v))

(defn valid-lease-channel? [v]
  (m/validate LeaseChannel v))

(defn valid-region-profile? [v]
  (m/validate RegionProfile v))

(defn valid-agent-profile? [v]
  (m/validate AgentProfile v))

(defn valid-scene-digest? [v]
  (m/validate SceneDigest v))

(defn explain-validation [schema value]
  (me/humanize (m/explain schema value)))

(defn ->json-schema [schema]
  (json-schema/transform schema))

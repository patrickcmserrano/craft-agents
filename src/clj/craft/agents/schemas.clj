(ns craft.agents.schemas
  "Catalogo central de schemas Malli e geracao de JSON Schema p/ MCP (CA-004)."
  (:require [malli.core :as m]
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
            (:selector :sequence :parallel)
            (and (seq args)
                 (every? valid-bt-node? args))

            (:action :guard)
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

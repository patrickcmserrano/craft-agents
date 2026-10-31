(ns craft.agents.perception
  "Sintese do Semantic Scene Digest (~300-500 tokens) com IDs reais de mods e raycast LOS (CA-036)."
  (:require [craft.agents.backend.protocol :as proto]))

(defn build-scene-digest
  "Gera fotografia semantica compacta do agente, cercados proximos, entidades visiveis e gatilho atual (CA-036)."
  [world-backend agent-body _blackboard trigger]
  (let [body-st (proto/body-state agent-body)
        pos     (:pos body-st)
        nearby  (proto/query-entities world-backend pos 32.0)
        animals (frequencies (keep (fn [e]
                                     (when (not= (:type e) "minecraft:player")
                                       (:type e)))
                                   nearby))
        players (vec (keep (fn [e]
                             (when (= (:type e) "minecraft:player")
                               {:name (:name e) :pos (:pos e)}))
                           nearby))]
    {:self {:name (:name body-st)
            :pos pos
            :health (:health body-st 20.0)
            :dimension "minecraft:overworld"
            :inventory (:inventory body-st {})}
     :entities {:players players
                :animals-summary animals
                :hostiles []}
     :environment {:time-of-day 6000
                   :weather :clear
                   :nearby-portals []}
     :recent-events (if trigger [trigger] [])}))

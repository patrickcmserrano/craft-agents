(ns craft.agents.fixtures.world-fixture
  "Harness 3D em memoria implementando VoxelAccessor, IWorldBackend e IAgentBody para testes TDD rapidos e deterministicos."
  (:require [clojure.test :refer [is]]
            [craft.agents.backend.protocol :as proto])
  (:import [com.craft.agents.api CollisionType PortalGroup VoxelAccessor]))

(defn assert-quick-check!
  "Valida resultado de test.check relancando excecoes de tasks TDD pendentes dentro de (is ...)."
  [qc-res]
  (is (do
        (when (instance? Throwable (:result qc-res))
          (throw (:result qc-res)))
        (:pass? qc-res))
      (pr-str (dissoc qc-res :shrunk))))

(defn make-flat-world-state
  "Cria estado mutavel controlado em memoria com chao solido em y = floor-y."
  ([] (make-flat-world-state 64))
  ([floor-y]
   (atom {:floor-y floor-y
          :blocks {}      ;; {[x y z] {:id "minecraft:oak_fence_gate" :collision CollisionType/PORTAL :open? false :height 1.0 :hazard? false}}
          :portals {}     ;; {[x y z] PortalGroup}
          :entities {}    ;; {id {:id id :type "naturalist:capybara" :pos [x y z] :health 20.0 :in-love? false}}
          :chests {}      ;; {[x y z] {"minecraft:apple" 16}}
          :agents {}      ;; {agent-id body-atom}
          :particles []   ;; [{:recipient "Pace" :steps [...]}]
          :chat-log []})))

(defn set-block!
  [world-atom [x y z] block-spec]
  (swap! world-atom assoc-in [:blocks [(int x) (int y) (int z)]] block-spec)
  world-atom)

(defn add-double-fence-gate!
  "Adiciona portao duplo empilhado em [x y z] e [x (inc y) z] (cenario real do santuario)."
  [world-atom [x y z] {:keys [id normal open?] :or {id "pen-gate" normal [1.0 0.0 0.0] open? false}}]
  (let [b1 [(int x) (int y) (int z)]
        b2 [(int x) (int (inc y)) (int z)]
        pg (PortalGroup. (str id)
                         (long-array 2)
                         (+ x 0.5) (double y) (+ z 0.5)
                         (double (nth normal 0)) (double (nth normal 2))
                         true
                         (boolean open?))
        spec {:id "minecraft:oak_fence_gate"
              :collision (if open? CollisionType/EMPTY CollisionType/PORTAL)
              :open? (boolean open?)
              :height 1.5
              :hazard? false}]
    (swap! world-atom
           (fn [st]
             (-> st
                 (assoc-in [:blocks b1] spec)
                 (assoc-in [:blocks b2] spec)
                 (assoc-in [:portals b1] pg)
                 (assoc-in [:portals b2] pg))))
    world-atom))

(defn add-entity!
  [world-atom entity-map]
  (swap! world-atom assoc-in [:entities (:id entity-map)] entity-map)
  world-atom)

(defn ->voxel-accessor
  ^VoxelAccessor [world-atom]
  (reify VoxelAccessor
    (getCollisionType [_ x y z]
      (let [{:keys [floor-y blocks]} @world-atom]
        (if-let [b (get blocks [x y z])]
          (if (:open? b) CollisionType/EMPTY (:collision b CollisionType/SOLID))
          (if (<= y floor-y) CollisionType/SOLID CollisionType/EMPTY))))
    (getFloorHeight [_ x y z]
      (let [{:keys [floor-y blocks]} @world-atom]
        (if-let [b (get blocks [x y z])]
          (+ (double y) (double (:height b 1.0)))
          (if (<= y floor-y) (double (inc y)) (double y)))))
    (isHazard [_ x y z]
      (boolean (get-in @world-atom [:blocks [x y z] :hazard?] false)))
    (getBlockId [_ x y z]
      (let [{:keys [floor-y blocks]} @world-atom]
        (or (get-in blocks [[x y z] :id])
            (if (<= y floor-y) "minecraft:grass_block" "minecraft:air"))))
    (getPortalAt [_ x y z]
      (get-in @world-atom [:portals [x y z]]))))

(defn make-mock-body
  [world-atom {:keys [id name pos inventory] :or {pos [0.0 65.0 0.0] inventory {}}}]
  (let [state (atom {:id id
                     :name (or name (clojure.core/name id))
                     :pos (vec (map double pos))
                     :vel [0.0 0.0 0.0]
                     :yaw 0.0
                     :pitch 0.0
                     :health 20.0
                     :on-ground? true
                     :sneaking? false
                     :sprinting? false
                     :equipped {:mainhand nil}
                     :inventory inventory})]
    (reify
      clojure.lang.ILookup
      (valAt [_ k] (when (= k :state-atom) state))
      proto/IAgentBody
      (body-state [_] @state)
      (apply-motor-controls! [_ {:keys [forward jump? sprint? sneak? yaw pitch]}]
        (swap! state
               (fn [s]
                 (let [[px py pz] (:pos s)
                       rad (Math/toRadians (double (or yaw (:yaw s))))
                       step (* (double (or forward 0.0)) (if sprint? 0.28 0.21))
                       nx (- px (* (Math/sin rad) step))
                       nz (+ pz (* (Math/cos rad) step))]
                   (assoc s
                          :pos [nx (if jump? (+ py 0.42) py) nz]
                          :sneaking? (boolean sneak?)
                          :sprinting? (boolean sprint?)
                          :yaw (double (or yaw (:yaw s)))
                          :pitch (double (or pitch (:pitch s))))))))
      (interact-portal-block! [_ [x y z] target-open?]
        (let [coord [(int x) (int y) (int z)]]
          (swap! world-atom assoc-in [:blocks coord :open?] (boolean target-open?))
          true))
      (feed-nearby-entity! [_ entity-id item-id]
        (when (pos? (get-in @state [:inventory item-id] 0))
          (swap! state update-in [:inventory item-id] dec)
          (swap! world-atom assoc-in [:entities entity-id :in-love?] true)
          true))
      (engage-target! [_ entity-id _mode _weapon-id]
        (swap! world-atom update-in [:entities entity-id :health] (fnil #(- % 6.0) 20.0))
        true)
      (transfer-container-items! [_ chest-coord direction item-id count]
        (let [c [(int (nth chest-coord 0)) (int (nth chest-coord 1)) (int (nth chest-coord 2))]]
          (case direction
            :withdraw (let [avail (get-in @world-atom [:chests c item-id] 0)
                            moved (min avail count)]
                        (swap! world-atom update-in [:chests c item-id] (fnil - 0) moved)
                        (swap! state update-in [:inventory item-id] (fnil + 0) moved)
                        moved)
            :deposit  (let [avail (get-in @state [:inventory item-id] 0)
                            moved (min avail count)]
                        (swap! state update-in [:inventory item-id] (fnil - 0) moved)
                        (swap! world-atom update-in [:chests c item-id] (fnil + 0) moved)
                        moved))))
      (equip-slot! [_ slot item-id]
        (swap! state assoc-in [:equipped slot] item-id)
        true)
      (speak-chat! [_ message gesture]
        (swap! world-atom update :chat-log conj {:speaker (:name @state) :message message :gesture gesture})
        true))))

(defn make-mock-backend
  [world-atom]
  (reify
    clojure.lang.ILookup
    (valAt [_ k] (when (= k :world-atom) world-atom))
    proto/IWorldBackend
    (spawn-agent! [_ profile]
      (let [aid (:agent/id profile)
            body (make-mock-body world-atom {:id aid
                                             :name (:agent/name profile)
                                             :pos (get-in profile [:agent/spawn :pos] [0.0 65.0 0.0])})]
        (swap! world-atom assoc-in [:agents aid] body)
        body))
    (despawn-agent! [_ agent-id]
      (swap! world-atom update :agents dissoc agent-id)
      true)
    (active-agents [_]
      (:agents @world-atom))
    (capture-voxel-snapshot [_ _center _radius]
      (->voxel-accessor world-atom))
    (query-entities [_ [cx cy cz] radius]
      (let [r2 (* radius radius)]
        (->> (vals (:entities @world-atom))
             (filter (fn [{:keys [pos]}]
                       (let [[ex ey ez] pos
                             dx (- ex cx) dy (- ey cy) dz (- ez cz)]
                         (<= (+ (* dx dx) (* dy dy) (* dz dz)) r2))))
             vec)))
    (has-line-of-sight? [_ _from _to]
      true)))

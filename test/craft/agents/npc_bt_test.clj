(ns craft.agents.npc-bt-test
  "CA-094 — Behavior Tree para todo agente com `:bt/root`: o engine roda um passo de BT por
   agente que declara a arvore (Karlach segue no tick proprio); acao desconhecida nao lanca,
   registra evento cognitivo uma vez por id e devolve :failure; guards da Coco puros;
   `:face-entity` e `:social-gaze` genericos via `defmulti bt-action`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.engine.bt-tick :as bt-tick]
            [craft.agents.backend.fabric.goals :as goals]
            [craft.agents.backend.fabric.goals.bt :as bt]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.state :as npc-state]
            [craft.agents.skills.actions :as skill-actions]))

(defn- run-node
  "Executa `node` como `agent-id` com a ponte stubada. Devolve {:res :cmds :ctx :nav}."
  [agent-id node & {:keys [ctx0 pos players]}]
  (let [cmds (atom [])
        ectx (atom (or ctx0 {}))
        st   (npc-state/agent-atom ectx agent-id {:pos (or pos [0.0 65.0 0.0])})
        agent (registry/agent-by-id agent-id)
        res  (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                           actions/query-online-players (fn [] (or players []))]
               (goals/execute-tree-node! node ectx st [] "Pace" agent))]
    {:res res :cmds @cmds :ctx @ectx :nav @st}))

(defn- unimplemented-events [ctx]
  (filter #(str/includes? (:text %) "ação não implementada") (:events ctx)))

;; ----------------------------------------------------------------------------
;; Engine: um passo de BT por agente com :bt/root
;; ----------------------------------------------------------------------------

(deftest engine-ticks-every-agent-with-bt-root-test
  (let [ticked (atom [])
        env    {:engine-ctx-atom (atom {}) :tick 4}]
    (with-redefs [bt-tick/tick-agent! (fn [_env agent] (swap! ticked conj (:agent/id agent)))]
      (bt-tick/tick-bt-agents! env))
    (let [with-root (set (map :agent/id (filter :bt/root (registry/all-agents))))
          got       (set @ticked)]
      (is (contains? got :coco) "Coco declara :bt/root e roda a arvore")
      (is (every? with-root got) "so agente com :bt/root e ticado")
      (is (= (apply disj with-root bt-tick/self-driven) got)
          "todos menos os que ja tem tick dedicado (Karlach, Picard, Richeh)")
      (is (= #{:karlach :picard :richeh} bt-tick/self-driven))
      (is (= (count @ticked) (count got)) "um passo por agente"))))

(deftest agents-without-bt-root-keep-their-tick-test
  (let [ticked (atom [])
        real   (registry/all-agents)
        plain  {:agent/id :plain :agent/name "Plain"
                :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 65.0 0.0]}}]
    (with-redefs [registry/all-agents (fn [] (conj real plain))
                  bt-tick/tick-agent! (fn [_ agent] (swap! ticked conj (:agent/id agent)))]
      (bt-tick/tick-bt-agents! {:engine-ctx-atom (atom {}) :tick 4}))
    (is (not (some #{:plain} @ticked)) "agente sem :bt/root mantem o tick atual")
    (doseq [id [:picard :richeh :karlach]]
      (is (not (some #{id} @ticked)) (str id " mantem o tick dedicado (tick-picard!/atelier/karlach)")))))

(deftest bt-engine-runs-at-5hz-and-isolates-errors-test
  (let [ticked (atom [])]
    (with-redefs [bt-tick/tick-agent! (fn [_ agent]
                                        (swap! ticked conj (:agent/id agent))
                                        (throw (ex-info "boom" {})))]
      (bt-tick/tick-bt-agents! {:engine-ctx-atom (atom {}) :tick 1})
      (is (empty? @ticked) "tick fora do periodo nao roda BT")
      (is (nil? (bt-tick/tick-bt-agents! {:engine-ctx-atom (atom {}) :tick 4})) "erro de um agente nao propaga")
      (is (seq @ticked)))))

(deftest tick-agent-runs-one-step-of-the-coco-tree-test
  (let [cmds (atom [])
        ectx (atom {:agents {:richeh {:pos [1.0 65.0 0.0]}}})]
    (npc-state/agent-atom ectx :coco {:pos [0.0 65.0 0.0]})
    (let [res (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                            actions/query-online-players (fn [] [])]
                (bt-tick/tick-agent! {:engine-ctx-atom ectx :tick 4} (registry/agent-by-id :coco)))]
      (is (= :success (:status res)))
      (is (some #(str/starts-with? % "MOVE|Coco|") @cmds) "olha para a Richeh como Coco")
      (is (not-any? #(str/includes? % "Karlach") @cmds))
      (is (some #(str/starts-with? % "SWING|Coco") @cmds) "rabisca no caderno (acao modular)")
      (is (number? (get-in @ectx [:cooldowns :duo-study])) "cooldown :duo-study carimbado ao completar"))))

;; ----------------------------------------------------------------------------
;; Acao desconhecida
;; ----------------------------------------------------------------------------

(deftest unknown-action-fails-and-logs-once-per-id-test
  (let [ctx0 {}
        node [:action :no-such-action {}]
        a    (run-node :coco node :ctx0 ctx0)
        b    (run-node :coco node :ctx0 (:ctx a))]
    (is (= :failure (get-in a [:res :status])))
    (is (= 1 (count (unimplemented-events (:ctx b)))) "uma vez por id, mesmo em ticks repetidos")
    (is (str/includes? (:text (first (unimplemented-events (:ctx b)))) "no-such-action"))))

(deftest coco-drawing-actions-without-motor-impl-do-not-throw-test
  (with-redefs [skill-actions/execute-action
                (fn [id _ _] {:status :failure :reason :UNKNOWN_ACTION :action-id id})]
    (let [r (run-node :coco [:sequence
                             [:action :emote-sketch-notes {:duration-ticks 60}]
                             [:action :approach-and-sketch {:max-dist 2.2}]])
          r2 (run-node :coco [:selector
                              [:action :approach-and-sketch {}]
                              [:action :emote-sketch-notes {}]]
                       :ctx0 (:ctx r))]
      (is (= :failure (get-in r [:res :status])))
      (is (= :failure (get-in r2 [:res :status])))
      (let [texts (map :text (unimplemented-events (:ctx r2)))]
        (is (= 1 (count (filter #(str/includes? % "emote-sketch-notes") texts))))
        (is (= 1 (count (filter #(str/includes? % "approach-and-sketch") texts))))))))

;; ----------------------------------------------------------------------------
;; Registro extensivel
;; ----------------------------------------------------------------------------

(deftest bt-action-is-an-extensible-multimethod-test
  (is (instance? clojure.lang.MultiFn bt/bt-action))
  (doseq [id [:face-entity :social-gaze]]
    (is (contains? (methods bt/bt-action) id) (str id))))

(deftest face-entity-is-generic-test
  (doseq [[id nm] [[:coco "Coco"] [:picard "Picard"]]]
    (let [r (run-node id [:action :face-entity {:target :richeh}]
                      :ctx0 {:agents {:richeh {:pos [5.0 65.0 0.0]}}})]
      (testing nm
        (is (= :success (get-in r [:res :status])))
        (is (some #(str/starts-with? % (str "MOVE|" nm "|")) (:cmds r)))
        (is (not-any? #(str/includes? % "Karlach") (:cmds r)))))))

(deftest face-entity-without-known-target-fails-test
  (let [r (run-node :coco [:action :face-entity {:target :ghost}])]
    (is (= :failure (get-in r [:res :status])))
    (is (empty? (:cmds r)))))

(deftest social-gaze-faces-nearest-player-within-radius-test
  (let [near (run-node :picard [:action :social-gaze {:radius 10.0}]
                       :players [{:name "Pace" :x 4.0 :y 65.0 :z 0.0}])
        far  (run-node :picard [:action :social-gaze {:radius 10.0}]
                       :players [{:name "Pace" :x 40.0 :y 65.0 :z 0.0}])]
    (is (= :success (get-in near [:res :status])))
    (is (some #(str/starts-with? % "MOVE|Picard|") (:cmds near)))
    (is (not-any? #(str/includes? % "Karlach") (:cmds near)))
    (is (= :success (get-in far [:res :status])) "sem ninguem por perto o olhar e passivo")
    (is (empty? (:cmds far)))))

;; ----------------------------------------------------------------------------
;; Guards puros
;; ----------------------------------------------------------------------------

(deftest ally-within-range-guard-test
  (let [w {:self-pos [0.0 65.0 0.0] :agents {:richeh {:pos [2.0 65.0 0.0]}}}]
    (is (true?  (bt/guard-holds? :ally-within-range? {:ally-id :richeh :max-dist 3.5} w)))
    (is (false? (bt/guard-holds? :ally-within-range? {:ally-id :richeh :max-dist 1.0} w)))
    (is (false? (bt/guard-holds? :ally-within-range? {:ally-id :ghost :max-dist 3.5} w))
        "aliado sem posicao conhecida nao esta no alcance")
    (is (true?  (bt/guard-holds? :ally-within-range? {:ally-id :richeh :max-dist 3.5}
                                 {:perception {:allies {:richeh {:distance 1.0}}}})))))

(deftest interesting-block-nearby-guard-test
  (let [w {:perception {:blocks [{:id "minecraft:lantern" :distance 3.0}
                                 {:id "minecraft:dirt" :distance 1.0}]}
           :preferred-blocks #{"minecraft:lantern"}}]
    (is (true?  (bt/guard-holds? :interesting-block-nearby? {:radius 5.0} w)))
    (is (false? (bt/guard-holds? :interesting-block-nearby? {:radius 2.0} w))
        "so o dirt esta dentro de 2m e ele nao e preferido")
    (is (false? (bt/guard-holds? :interesting-block-nearby? {:radius 5.0} {})) "sem blocos percebidos")))

(deftest cooldown-ready-guard-test
  (let [p {:key :duo-study :seconds 45}]
    (is (true?  (bt/guard-holds? :cooldown-ready? p {:now-ms 1000})))
    (is (false? (bt/guard-holds? :cooldown-ready? p {:now-ms 20000 :cooldowns {:duo-study 1000}})))
    (is (true?  (bt/guard-holds? :cooldown-ready? p {:now-ms 46000 :cooldowns {:duo-study 1000}})))
    (is (true?  (bt/guard-holds? :cooldown-ready? p {:now-ms 20000 :cooldowns {:other 1000}})))))

(deftest guards-are-pure-test
  (let [w {:now-ms 5000 :cooldowns {:duo-study 1000} :self-pos [0.0 0.0 0.0]
           :agents {:richeh {:pos [1.0 0.0 0.0]}}
           :perception {:blocks [{:id "minecraft:lantern" :distance 1.0}]}}
        w0 w]
    (doseq [[g p] [[:ally-within-range? {:ally-id :richeh :max-dist 3.5}]
                   [:interesting-block-nearby? {:radius 5.0}]
                   [:cooldown-ready? {:key :duo-study :seconds 45}]]]
      (is (= (bt/guard-holds? g p w) (bt/guard-holds? g p w)) (str g " deterministico")))
    (is (= w0 w) "o mundo nao e alterado")))

(deftest cooldown-guard-in-tree-stamps-on-completed-sequence-test
  (let [node [:sequence [:guard :cooldown-ready? {:key :duo-study :seconds 45}]
              [:action :play-sound {}]]
        a (run-node :coco node :ctx0 {:now-ms 100000})
        b (run-node :coco node :ctx0 (assoc (:ctx a) :now-ms 110000))
        c (run-node :coco node :ctx0 (assoc (:ctx b) :now-ms 200000))]
    (is (= :success (get-in a [:res :status])))
    (is (= 100000 (get-in (:ctx a) [:cooldowns :duo-study])))
    (is (= :failure (get-in b [:res :status])) "dentro dos 45 s")
    (is (= :success (get-in c [:res :status])) "depois dos 45 s")))

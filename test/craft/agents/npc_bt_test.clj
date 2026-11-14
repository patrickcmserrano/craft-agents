(ns craft.agents.npc-bt-test
  "CA-094 — Behavior Tree para todo agente com `:bt/root`: o engine roda um passo de BT por
   agente que declara a arvore (Karlach segue no tick proprio); acao desconhecida nao lanca,
   registra evento cognitivo uma vez por id e devolve :failure; guards da Coco puros;
   `:face-entity` e `:social-gaze` genericos via `defmulti bt-action`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.atelier.behavior :as atelier-behavior]
            [craft.agents.backend.fabric.engine.bt-tick :as bt-tick]
            [craft.agents.backend.fabric.goals :as goals]
            [craft.agents.backend.fabric.goals.bt :as bt]
            [craft.agents.backend.fabric.navigation :as nav]
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
      (is (contains? got :coco) "Coco roda a BT sob o arbitro npc.body (canal :gaze), sem ser segundo motorista de :legs")
      (is (every? with-root got) "so agente com :bt/root e ticado")
      (is (= (apply disj with-root bt-tick/self-driven) got)
          "todos os agentes com :bt/root; nenhum fica de fora")
      (is (= with-root got) "todos os agentes com :bt/root sao ticados")
      (is (= #{} bt-tick/self-driven) "CA-104: ninguem excluido, o arbitro unico resolve o conflito corpo/cabeca")
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
    (doseq [id [:picard :richeh :karlach]
            :when (:bt/root (registry/agent-by-id id))]
      (is (some #{id} @ticked) (str id " roda a BT sob arbitragem (CA-104), alem do tick dedicado")))))

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

;; ----------------------------------------------------------------------------
;; CA-104: Coco = rotina do atelie (:legs) + BT (:gaze) no mesmo tick, sob npc.body
;; ----------------------------------------------------------------------------

(defn- body-fn [sym] (requiring-resolve (symbol "craft.agents.npc.body" (name sym))))

(defn- move-fields
  "Campos numericos [x y z yaw pitch] de uma linha MOVE|nome|x|y|z|yaw|pitch|..."
  [line]
  (mapv parse-double (subvec (str/split line #"\|") 2 7)))

(defn- coco-tick-with-bt!
  "Um tick aberto: atelie da Coco (estado `coco-st`) e BT :face-entity da Coco (olhar para `look-pos`
   a partir de `bt-self-pos`), depois flush!. `pre` roda dentro do tick antes do atelie. Devolve
   {:cmds (todas as linhas enviadas ao transport) :before-flush (linhas antes do flush!)}."
  [coco-st bt-self-pos look-pos pre]
  (let [begin! (body-fn 'begin-tick!) flush! (body-fn 'flush!) clear! (body-fn 'reset-buffer!)
        cmds   (atom [])
        ectx   (atom {:agents {:richeh {:pos look-pos}}})
        st     (npc-state/agent-atom ectx :coco {:pos bt-self-pos})
        before (atom nil)]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                  actions/query-online-players (fn [] [])]
      (clear!)
      (begin!)
      (try
        (pre)
        (atelier-behavior/step-coco-tick! coco-st transport/send-bridge-cmd! 1 [])
        (goals/execute-tree-node! [:action :face-entity {:target :richeh}] ectx st [] "Pace"
                                  (registry/agent-by-id :coco))
        (reset! before @cmds)
        (finally (flush!))))
    {:cmds @cmds :before-flush @before}))

(defn- coco-moves [cmds] (filter #(str/starts-with? % "MOVE|Coco|") cmds))

(deftest coco-atelier-legs-and-bt-gaze-emit-a-single-move-test
  (let [coco-st (atelier-behavior/create-coco-state)
        start   (:pos @coco-st)
        {:keys [cmds before-flush]} (coco-tick-with-bt! coco-st start [-20.0 66.0 190.0] (fn []))
        ms      (coco-moves cmds)
        [x y z yaw] (some-> (first ms) move-fields)
        [nx ny nz]  (:pos @coco-st)]
    (testing "o arbitro retem os dois pedidos: nada de MOVE antes do flush!"
      (is (empty? (coco-moves before-flush))))
    (testing "exatamente 1 MOVE|Coco| por tick"
      (is (= 1 (count ms))))
    (testing ":legs (atelie) define a posicao; o olhar da BT nao a sobrescreve"
      (is (not= start [nx ny nz]) "o atelie andou")
      (is (< (Math/abs (- (double (or x 1e9)) nx)) 0.001))
      (is (< (Math/abs (- (double (or y 1e9)) ny)) 0.001))
      (is (< (Math/abs (- (double (or z 1e9)) nz)) 0.001)))
    (testing ":gaze (BT, prioridade maior) define o yaw: Richeh esta a +x"
      (is (< (Math/abs (- (double (or yaw 1e9)) (nav/compute-yaw 10.0 0.0))) 0.06)))))

(deftest coco-lower-priority-legs-never-overrides-higher-test
  (let [coco-st  (atelier-behavior/create-coco-state)
        request! (body-fn 'request!)
        target   [3.0 70.0 4.0]
        {:keys [cmds]} (coco-tick-with-bt!
                        coco-st (:pos @coco-st) [-20.0 66.0 190.0]
                        #(request! {:agent :coco :channel :legs :source :combat :target target}))
        ms (coco-moves cmds)
        [x y z] (some-> (first ms) move-fields)]
    (is (= 1 (count ms)) "um unico MOVE|Coco| mesmo com tres fontes no tick")
    (is (= [3.0 70.0 4.0] [x y z]) ":legs de prioridade :combat vence o :atelier")))

(deftest body-step-outside-an-open-tick-sends-immediately-test
  (let [step! (body-fn 'step!)
        cmds  (atom [])]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")]
      (step! {:agent :coco :source :atelier :x 1.0 :y 64.0 :z 2.0 :yaw 10.0 :pitch 0.0}))
    (is (= ["MOVE|Coco|1.000|64.000|2.000|10.0|0.0|false|false"] @cmds)
        "fora de tick aberto (testes isolados) o passo sai direto, no mesmo formato")))

;; ----------------------------------------------------------------------------
;; CA-106: interpretador unico com :running, memoria por no e :cooldown/:repeat reais
;; ----------------------------------------------------------------------------

(def ^:private probe-script (atom {}))
(def ^:private probe-calls (atom []))

(defmethod bt/bt-action :bt-probe
  ;; Acao de teste: cada chamada consome o proximo status do roteiro de `:id` (esgotado = :success).
  [_ {:keys [id]} _ctx]
  (swap! probe-calls conj id)
  (let [[status & more] (get @probe-script id)]
    (swap! probe-script assoc id (vec more))
    {:status (or status :success) :action-id :bt-probe}))

(defn- probe-reset! [script]
  (reset! probe-script script)
  (reset! probe-calls []))

(defn- probe [id] [:action :bt-probe {:id id}])

(defn- bt-rig
  "Rig de BT: engine-ctx compartilhavel e o atomo de estado de `agent-id`."
  ([agent-id] (bt-rig (atom {}) agent-id))
  ([ectx agent-id]
   {:ectx ectx :agent-id agent-id
    :st (npc-state/agent-atom ectx agent-id {:pos [0.0 65.0 0.0]})}))

(defn- bt-tick!
  "Um tick da raiz `node` para o agente do rig. Devolve o :status do no raiz."
  [{:keys [ectx st agent-id]} node]
  (:status (bt/execute-tree-node! node ectx st [] "Pace" (registry/agent-by-id agent-id))))

(defn- take-calls!
  "Devolve e zera as chamadas registradas pela acao de teste."
  []
  (let [c @probe-calls] (reset! probe-calls []) c))

(deftest sequence-resumes-from-running-child-test
  (let [rig  (bt-rig :coco)
        tree [:sequence (probe :a) (probe :b) (probe :c)]]
    (probe-reset! {:a [:success] :b [:running :running :success] :c [:success]})
    (testing "o 2o filho devolve :running: a sequencia propaga :running"
      (is (= :running (bt-tick! rig tree)))
      (is (= [:a :b] (take-calls!)))
      (is (seq (:bt-memory @(:st rig))) "a memoria do no vive no estado do agente"))
    (testing "tick seguinte retoma do 2o filho, sem re-executar o 1o"
      (is (= :running (bt-tick! rig tree)))
      (is (= [:b] (take-calls!))))
    (testing "ao concluir com :success avanca para o 3o filho e a sequencia termina"
      (is (= :success (bt-tick! rig tree)))
      (is (= [:b :c] (take-calls!)))
      (is (empty? (:bt-memory @(:st rig))) "memoria limpa ao concluir"))
    (testing "depois de concluida, a proxima passagem recomeca do 1o filho"
      (bt-tick! rig tree)
      (is (= :a (first (take-calls!)))))))

(deftest sequence-restarts-after-running-child-fails-test
  (let [rig  (bt-rig :coco)
        tree [:sequence (probe :a) (probe :b) (probe :c)]]
    (probe-reset! {:b [:running :failure]})
    (is (= :running (bt-tick! rig tree)))
    (take-calls!)
    (is (= :failure (bt-tick! rig tree)) "o filho que concluiu em :failure falha a sequencia")
    (is (= [:b] (take-calls!)) "sem re-executar o 1o filho enquanto o 2o estava :running")
    (is (empty? (:bt-memory @(:st rig))))
    (is (= :success (bt-tick! rig tree)))
    (is (= [:a :b :c] (take-calls!)) "apos a falha recomeca do 1o filho")))

(deftest selector-resumes-from-running-child-test
  (let [rig  (bt-rig :coco)
        tree [:selector (probe :a) (probe :b)]]
    (probe-reset! {:a [:failure :failure :failure] :b [:running :success]})
    (is (= :running (bt-tick! rig tree)))
    (is (= [:a :b] (take-calls!)))
    (is (= :success (bt-tick! rig tree)))
    (is (= [:b] (take-calls!)) "o seletor retoma do filho :running sem reavaliar o 1o")
    (is (empty? (:bt-memory @(:st rig))))))

(deftest bt-memory-is-isolated-per-agent-test
  (let [ectx (atom {})
        coco (bt-rig ectx :coco)
        pic  (bt-rig ectx :picard)
        tree [:sequence (probe :a) (probe :b)]]
    (probe-reset! {:b [:running]})
    (is (= :running (bt-tick! coco tree)))
    (take-calls!)
    (is (empty? (:bt-memory @(:st pic))) "a memoria da Coco nao vaza para o Picard")
    (is (= :success (bt-tick! pic tree)) "o Picard comeca do 1o filho")
    (is (= [:a :b] (take-calls!)))))

(deftest cooldown-blocks-child-for-n-ticks-test
  (let [rig  (bt-rig :coco)
        tree [:cooldown {:ticks 3} (probe :x)]]
    (probe-reset! {})
    (is (= :success (bt-tick! rig tree)) "tick 1 executa o filho")
    (is (= [:x] (take-calls!)))
    (testing "durante N=3 ticks o filho nao reexecuta e o no falha"
      (dotimes [_ 3]
        (is (= :failure (bt-tick! rig tree))))
      (is (empty? (take-calls!))))
    (testing "passados N ticks o filho volta a executar"
      (is (= :success (bt-tick! rig tree)))
      (is (= [:x] (take-calls!))))))

(deftest cooldown-is-keyed-by-agent-and-node-path-test
  (let [ectx (atom {})
        coco (bt-rig ectx :coco)
        pic  (bt-rig ectx :picard)
        tree [:parallel
              [:cooldown {:ticks 2} (probe :x)]
              [:cooldown {:ticks 2} (probe :y)]]]
    (probe-reset! {})
    (is (= :success (bt-tick! coco tree)))
    (is (= [:x :y] (take-calls!)))
    (testing "outro agente tem o proprio cooldown para os mesmos caminhos"
      (bt-tick! pic tree)
      (is (= [:x :y] (take-calls!))))
    (testing "os dois nos da Coco seguem bloqueados no tick seguinte"
      (is (= :failure (bt-tick! coco tree)))
      (is (empty? (take-calls!))))
    (testing "um cooldown em outro caminho nao e afetado pelo bloqueio vizinho"
      (let [only-y [:selector
                    [:cooldown {:ticks 5} (probe :x)]
                    (probe :y)]
            r      (bt-rig :karlach)]
        (probe-reset! {})
        (is (= :success (bt-tick! r only-y)))
        (is (= [:x] (take-calls!)))
        (is (= :success (bt-tick! r only-y)) "o :x bloqueado cai no irmao :y")
        (is (= [:y] (take-calls!)))))))

(deftest repeat-runs-child-k-times-returning-running-test
  (let [rig  (bt-rig :coco)
        tree [:repeat {:times 3} (probe :r)]]
    (probe-reset! {})
    (is (= :running (bt-tick! rig tree)) "1a repeticao concluida: ainda :running")
    (is (= :running (bt-tick! rig tree)) "2a repeticao concluida: ainda :running")
    (is (= :success (bt-tick! rig tree)) "3a repeticao concluida: :success")
    (is (= [:r :r :r] (take-calls!)))
    (is (empty? (:bt-memory @(:st rig))))
    (testing "ao concluir, a contagem reinicia"
      (is (= :running (bt-tick! rig tree)))
      (is (= [:r] (take-calls!))))))

(deftest repeat-propagates-running-child-and-stops-on-failure-test
  (let [rig  (bt-rig :coco)
        tree [:repeat {:times 2} (probe :r)]]
    (testing "filho :running nao conta como sucesso"
      (probe-reset! {:r [:running :success :success]})
      (is (= :running (bt-tick! rig tree)))
      (is (= :running (bt-tick! rig tree)) "1o sucesso do filho")
      (is (= :success (bt-tick! rig tree)) "2o sucesso do filho")
      (is (= [:r :r :r] (take-calls!))))
    (testing "contrato: falha do filho encerra o :repeat em :failure e zera a contagem"
      (probe-reset! {:r [:success :failure]})
      (is (= :running (bt-tick! rig tree)))
      (is (= :failure (bt-tick! rig tree)))
      (is (empty? (:bt-memory @(:st rig))))
      (probe-reset! {})
      (is (= :running (bt-tick! rig tree)) "recomeca do zero apos a falha")
      (is (= :success (bt-tick! rig tree))))))

(deftest repeat-state-is-keyed-by-agent-test
  (let [ectx (atom {})
        coco (bt-rig ectx :coco)
        pic  (bt-rig ectx :picard)
        tree [:repeat {:times 2} (probe :r)]]
    (probe-reset! {})
    (is (= :running (bt-tick! coco tree)))
    (is (= :running (bt-tick! pic tree)) "a contagem do Picard nao herda a da Coco")
    (is (= :success (bt-tick! coco tree)))))

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
            [craft.agents.skills.actions :as skill-actions]
            [malli.core :as m]))

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
    (is (contains? (bt/registered-actions) id) (str id " vive no registro unico (CA-108)"))))

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

;; ----------------------------------------------------------------------------
;; CA-107: timeout por ticks em acoes assincronas e nucleo unico com bt/engine
;; ----------------------------------------------------------------------------

(defn- pending-for [agent-id]
  (filter (fn [[[aid _ch] _]] (= aid agent-id)) ((body-fn 'pending))))

(deftest timeout-aborts-child-stuck-running-after-n-ticks-test
  (let [rig  (bt-rig :coco)
        tree [:timeout {:ticks 3} (probe :slow)]]
    (probe-reset! {:slow (vec (repeat 20 :running))})
    (testing "durante N ticks o filho segue :running"
      (dotimes [_ 3] (is (= :running (bt-tick! rig tree)))))
    (is (= [:slow :slow :slow] (take-calls!)))
    (testing "no tick N+1 o no aborta o filho preso e devolve :failure sem reexecuta-lo"
      (is (= :failure (bt-tick! rig tree)))
      (is (empty? (take-calls!)))
      (is (empty? (:bt-memory @(:st rig))) "memoria do timeout e do filho descartada"))
    (testing "depois do aborto a arvore recomeca do zero"
      (is (= :running (bt-tick! rig tree)))
      (is (= [:slow] (take-calls!))))))

(deftest timeout-reports-reason-and-lets-fast-child-through-test
  (let [rig (bt-rig :coco)]
    (probe-reset! {:ok [:running :success]})
    (is (= :running (bt-tick! rig [:timeout {:ticks 5} (probe :ok)])))
    (is (= :success (bt-tick! rig [:timeout {:ticks 5} (probe :ok)])) "filho que conclui a tempo passa")
    (is (empty? (:bt-memory @(:st rig))))
    (probe-reset! {:slow (vec (repeat 5 :running))})
    (let [tree [:timeout {:ticks 1} (probe :slow)]
          _    (bt-tick! rig tree)
          res  (bt/execute-tree-node! tree (:ectx rig) (:st rig) [] "Pace" (registry/agent-by-id :coco))]
      (is (= :failure (:status res)))
      (is (= :timeout (:reason res))))))

(deftest timeout-inside-sequence-fails-the-sequence-and-restarts-test
  (let [rig  (bt-rig :coco)
        tree [:sequence (probe :a) [:timeout {:ticks 2} (probe :slow)] (probe :c)]]
    (probe-reset! {:slow (vec (repeat 10 :running))})
    (is (= :running (bt-tick! rig tree)))
    (is (= :running (bt-tick! rig tree)))
    (take-calls!)
    (is (= :failure (bt-tick! rig tree)) "timeout expirado falha a sequencia")
    (is (empty? (:bt-memory @(:st rig))))
    (is (= [] (take-calls!)))
    (is (= :running (bt-tick! rig tree)))
    (is (= [:a :slow] (take-calls!)) "a sequencia recomeca do 1o filho")))

(deftest timeout-ticks-on-async-action-test
  (let [rig  (bt-rig :coco)
        tree [:action :bt-probe {:id :slow :timeout-ticks 2}]]
    (probe-reset! {:slow (vec (repeat 10 :running))})
    (is (= :running (bt-tick! rig tree)))
    (is (= :running (bt-tick! rig tree)))
    (is (= :failure (bt-tick! rig tree)))
    (is (= [:slow :slow] (take-calls!)))))

(deftest timeout-state-is-keyed-by-agent-test
  (let [ectx (atom {})
        coco (bt-rig ectx :coco)
        pic  (bt-rig ectx :picard)
        tree [:timeout {:ticks 2} (probe :slow)]]
    (probe-reset! {:slow (vec (repeat 20 :running))})
    (bt-tick! coco tree)
    (bt-tick! coco tree)
    (is (= :running (bt-tick! pic tree)) "o relogio do Picard comeca no 1o tick dele")
    (is (= :failure (bt-tick! coco tree)))
    (is (= :running (bt-tick! pic tree)))))

(deftest timeout-releases-pending-body-requests-and-leases-test
  (let [rig  (bt-rig :coco)
        lmgr ((requiring-resolve 'craft.agents.leases/create-lease-manager))
        acq! (requiring-resolve 'craft.agents.leases/acquire-agent-leases!)
        act  (requiring-resolve 'craft.agents.leases/active-agent-leases)
        request! (body-fn 'request!)
        tree [:timeout {:ticks 1} (probe :slow)]]
    ((body-fn 'reset-buffer!))
    (swap! (:ectx rig) assoc :lease-mgr lmgr)
    (probe-reset! {:slow (vec (repeat 5 :running))})
    (acq! lmgr :coco {:channels #{:legs} :owner-id :bt-probe :priority 40})
    (acq! lmgr :coco {:channels #{:gaze} :owner-id :combat :priority 80})
    (acq! lmgr :picard {:channels #{:legs} :owner-id :bt-probe :priority 40})
    (request! {:agent :coco :channel :legs :source :bt :target [1.0 65.0 1.0]})
    (request! {:agent :coco :channel :hands :source :combat :use true})
    (request! {:agent :picard :channel :legs :source :bt :target [2.0 65.0 2.0]})
    (is (= :running (bt-tick! rig tree)))
    (is (= :failure (bt-tick! rig tree)))
    (testing "lease da acao abortada e pedido pendente do :bt da Coco sao liberados"
      (is (nil? (get-in (act lmgr :coco) [:legs :owner-id])))
      (is (nil? (get (pending-for :coco) [:coco :legs]))))
    (testing "o que pertence a outra fonte ou a outro agente permanece"
      (is (= :combat (get-in (act lmgr :coco) [:gaze :owner-id])))
      (is (some? (get (into {} (pending-for :coco)) [:coco :hands])))
      (is (= :bt-probe (get-in (act lmgr :picard) [:legs :owner-id])))
      (is (seq (pending-for :picard))))
    ((body-fn 'reset-buffer!))))

;; ----------------------------------------------------------------------------
;; CA-107: ponto de entrada de bt/engine sobre o mesmo nucleo
;; ----------------------------------------------------------------------------

(defn- engine-actions
  "Acoes de `bt/engine` que seguem o mesmo roteiro de `probe-script` (id em :params)."
  []
  {:bt-probe (fn [{{:keys [id]} :params}]
               (swap! probe-calls conj id)
               (let [[status & more] (get @probe-script id)]
                 (swap! probe-script assoc id (vec more))
                 {:status (or status :success)}))})

(deftest engine-entry-point-shares-the-unified-core-test
  (let [tick-tree (requiring-resolve 'craft.agents.bt.engine/tick-tree)
        tree [:sequence (probe :a) (probe :b) (probe :c)]
        ctx  {:actions (engine-actions) :bt-state (atom {})}]
    (probe-reset! {:b [:running :success]})
    (testing "mesma semantica de :running e memoria que goals.bt"
      (is (= :running (:status (tick-tree tree ctx))))
      (is (= [:a :b] (take-calls!)))
      (is (= :success (:status (tick-tree tree ctx))))
      (is (= [:b :c] (take-calls!)) "retoma do filho :running"))
    (testing "sem :bt-state o engine segue puro e sem memoria"
      (probe-reset! {:b [:running]})
      (is (= :running (:status (tick-tree tree {:actions (engine-actions)}))))
      (take-calls!)
      (tick-tree tree {:actions (engine-actions)})
      (is (= :a (first (take-calls!)))))
    (testing "o nucleo comum e exposto"
      (is (fn? @(requiring-resolve 'craft.agents.bt.core/exec-node))))))

(deftest engine-supports-timeout-and-cooldown-test
  (let [tick-tree (requiring-resolve 'craft.agents.bt.engine/tick-tree)
        ctx  {:actions (engine-actions) :bt-state (atom {})}
        tree [:timeout {:ticks 2} (probe :slow)]]
    (probe-reset! {:slow (vec (repeat 10 :running))})
    (is (= :running (:status (tick-tree tree ctx))))
    (is (= :running (:status (tick-tree tree ctx))))
    (is (= :failure (:status (tick-tree tree ctx))))
    (is (= [:slow :slow] (take-calls!)))
    (testing "ganchos :on-timeout recebem o filho abortado"
      (let [seen (atom nil)
            ctx2 {:actions (engine-actions) :bt-state (atom {}) :on-timeout (fn [child] (reset! seen child))}]
        (probe-reset! {:slow (vec (repeat 10 :running))})
        (tick-tree [:timeout {:ticks 1} (probe :slow)] ctx2)
        (tick-tree [:timeout {:ticks 1} (probe :slow)] ctx2)
        (is (= (probe :slow) @seen))))
    (testing "cooldown multi-tick tambem vale no engine"
      (let [ctx3 {:actions (engine-actions) :bt-state (atom {})}
            t    [:cooldown {:ticks 2} (probe :x)]]
        (probe-reset! {})
        (is (= :success (:status (tick-tree t ctx3))))
        (is (= :failure (:status (tick-tree t ctx3))))
        (is (= :failure (:status (tick-tree t ctx3))))
        (is (= :success (:status (tick-tree t ctx3))))
        (is (= [:x :x] (take-calls!)))))))

;; ----------------------------------------------------------------------------
;; CA-108: registro unico declarativo de acoes {:run :channels :params :doc}
;; ----------------------------------------------------------------------------

(def ^:private migrated-actions
  [:face-entity :social-gaze :say-message :play-sound :social-dance :sit-furniture :stand-up
   :patrol-sanctuary :breed-animals :follow-player :walk-towards-leader :bodyguard
   :mining-cooperation :drop-inventory :teleport-to-player :move-to-quarters :evade-towards-ally])

(deftest action-registry-declares-every-bt-action-test
  (let [reg (bt/registered-actions)]
    (is (map? reg))
    (doseq [id migrated-actions]
      (is (contains? reg id) (str id " esta no registro unico")))
    (doseq [[id spec] reg]
      (testing (str id)
        (is (fn? (:run spec)))
        (is (set? (:channels spec)))
        (is (every? #{:legs :hands :gaze} (:channels spec)) "canais do arbitro npc.body")
        (is (some? (m/schema (:params spec))) ":params e um schema Malli valido")
        (is (and (string? (:doc spec)) (seq (:doc spec))) ":doc nao vazio")))
    (is (= #{:gaze} (get-in reg [:face-entity :channels])))
    (is (= #{:gaze} (get-in reg [:social-gaze :channels])))
    (is (= #{:legs} (get-in reg [:follow-player :channels])))))

(deftest bt-clj-has-no-hidden-parallel-action-case-test
  (let [src (slurp "src/clj/craft/agents/backend/fabric/goals/bt.clj")]
    (is (not (str/includes? src "(case action-id")) "acoes vivem so no registro")
    (is (not (str/includes? src ":teleport-to-player")))
    (is (not (str/includes? src ":social-dance")))))

(deftest registered-action-runs-from-the-tree-test
  (let [r (run-node :coco [:action :play-sound {:sound "minecraft:test.registry"}])]
    (is (= :success (get-in r [:res :status])))
    (is (some #(str/includes? % "playsound minecraft:test.registry") (:cmds r)))))

(deftest register-action-extends-the-registry-test
  (bt/register-action! :ca108-extra
                       {:run (fn [params _ctx] {:status :success :action-id :ca108-extra :got (:v params)})
                        :channels #{:gaze}
                        :params [:map [:v {:optional true} :int]]
                        :doc "acao de teste do registro"})
  (try
    (is (contains? (bt/registered-actions) :ca108-extra))
    (is (contains? (bt/known-action-ids) :ca108-extra))
    (let [r (run-node :coco [:action :ca108-extra {:v 7}])]
      (is (= :success (get-in r [:res :status])))
      (is (= 7 (get-in r [:res :got]))))
    (finally (bt/unregister-action! :ca108-extra)))
  (is (not (contains? (bt/registered-actions) :ca108-extra))))

(deftest register-action-rejects-incomplete-spec-test
  (is (thrown? Exception (bt/register-action! :ca108-bad {:run (fn [_ _] {:status :success})})))
  (is (not (contains? (bt/registered-actions) :ca108-bad))))

(deftest real-agents-only-reference-known-actions-test
  (let [known (bt/known-action-ids)]
    (doseq [a (registry/all-agents)]
      (is (empty? (registry/unknown-actions a known))
          (str (:agent/id a) " referencia acao fora do registro")))))

;; ----------------------------------------------------------------------------
;; CA-108: guards por multimethod eval-guard, fail-closed
;; ----------------------------------------------------------------------------

(deftest eval-guard-is-an-extensible-multimethod-test
  (is (instance? clojure.lang.MultiFn bt/eval-guard))
  (doseq [id [:ally-within-range? :interesting-block-nearby? :cooldown-ready? :hostile-nearby?
              :has-active-directive? :leader-in-vehicle? :self-in-vehicle? :within-mount-range?
              :ally-distance-exceeds? :in-quiet-corner?]]
    (is (contains? (methods bt/eval-guard) id) (str id)))
  (defmethod bt/eval-guard :ca108-always [_ _ _] true)
  (try
    (is (true? (bt/guard-holds? :ca108-always {} {})))
    (finally (remove-method bt/eval-guard :ca108-always))))

(deftest guards-fail-closed-on-unknown-condition-test
  (is (false? (bt/guard-holds? :no-such-guard {} {:self-pos [0.0 0.0 0.0]})))
  (is (false? (bt/guard-holds? :no-such-guard nil nil)))
  (is (false? (bt/guard-holds? nil {} {})))
  (is (= (bt/guard-holds? :no-such-guard {} {}) (bt/guard-holds? :no-such-guard {} {})) "deterministico"))

(deftest guards-fail-closed-without-required-snapshot-test
  (is (false? (bt/guard-holds? :ally-within-range? {} {})) "sem percepcao de aliado")
  (is (false? (bt/guard-holds? :ally-within-range? {:ally-id :richeh} {})) "sem posicoes")
  (is (false? (bt/guard-holds? :ally-within-range? {:ally-id :richeh} nil)))
  (is (false? (bt/guard-holds? :interesting-block-nearby? {} nil)))
  (is (false? (bt/guard-holds? :hostile-nearby? {:radius 10.0} {})))
  (is (false? (bt/guard-holds? :has-active-directive? {} {})))
  (is (false? (bt/guard-holds? :within-mount-range? {} {})) "sem distancia ao lider")
  (is (false? (bt/guard-holds? :cooldown-ready? {:key :k :seconds 5} {})) "sem relogio no mundo")
  (is (false? (bt/guard-holds? :cooldown-ready? {:key :k :seconds 5} nil)))
  (is (false? (bt/guard-holds? :in-quiet-corner? {:max-nearby-players 0 :radius 6.0} {}))
      "sem snapshot de jogadores nao ha canto quieto")
  (testing "com snapshot o guard decide"
    (is (true? (bt/guard-holds? :hostile-nearby? {:radius 10.0}
                                {:perception {:hostiles [{:distance 4.0}]}})))
    (is (false? (bt/guard-holds? :hostile-nearby? {:radius 10.0}
                                 {:perception {:hostiles [{:distance 40.0}]}})))
    (is (true? (bt/guard-holds? :has-active-directive? {} {:active-directive {:id 1}})))
    (is (true? (bt/guard-holds? :within-mount-range? {} {:perception {:leader {:distance 2.0}}})))
    (is (true? (bt/guard-holds? :in-quiet-corner? {:max-nearby-players 0 :radius 6.0}
                                {:perception {:players []}})))
    (is (false? (bt/guard-holds? :in-quiet-corner? {:max-nearby-players 0 :radius 6.0}
                                 {:perception {:players [{:distance 2.0}]}})))))

(deftest guard-with-malformed-snapshot-is-false-not-an-exception-test
  (is (false? (bt/guard-holds? :interesting-block-nearby? {:radius 5.0}
                               {:perception {:blocks [{:id "minecraft:lantern"}]}}))))

(deftest unknown-guard-in-tree-fails-the-node-test
  (is (= :failure (get-in (run-node :coco [:guard :no-such-guard]) [:res :status])))
  (is (= :failure (get-in (run-node :coco [:condition :no-such-condition {}]) [:res :status])))
  (is (= :success (get-in (run-node :coco [:inverter [:guard :no-such-guard]]) [:res :status]))
      "o guard falha fechado; o inversor entao sucede"))

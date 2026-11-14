(ns craft.agents.npc-routine-test
  "CA-110 — primitivas genericas de rotina, lugares nomeados e interacao social do NPC Core
   (SUG-06 parte 1): `:visit-place`, `:follow-routine-slot`, `:greet-companion`,
   `:workbench-study`, `:cast-declared-spell` e os guards `:routine-activity?`/`:at-place?`.
   Dois agentes ficticios (Alpha, Beta) provam que o comportamento segue apenas o `.edn`.
   CA-111 (SUG-06 parte 2): Coco e Richeh rodam rotina, dupla, pontos do atelie e reacoes so pela
   `:bt/root` dos `.edn` via `bt-tick/tick-agent!`, com emissao unica pelo `npc.body`."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric :as fabric]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.atelier :as atelier]
            [craft.agents.backend.fabric.engine.atelier-tick :as atelier-tick]
            [craft.agents.backend.fabric.engine.bt-tick :as bt-tick]
            [craft.agents.backend.fabric.goals.bt-actions :as bta]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.body :as body]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.routine :as routine]
            [craft.agents.npc.state :as npc-state]
            [craft.agents.studio.server :as studio]
            [malli.core :as m]))

(def alpha
  {:agent/id :alpha :agent/name "Alpha"
   :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 65.0 0.0]}
   :agent/lines {:greeting ["Oi, Beta!" "Bom dia, Beta!"] :studying ["Estudando..."]}
   :agent/relations {:beta {:kind :companion :affinity 0.9}
                     :gamma {:kind :rival :affinity 0.1}}
   :agent/places {:lab   {:name "Laboratorio" :pos [10.0 65.0 0.0]}
                  :bed   {:name "Cama" :pos [0.0 65.0 10.0]}
                  :bench {:name "Bancada" :pos [4.0 65.0 0.0]}}
   :agent/routine [{:from 6 :to 12 :activity :study :place :lab}
                   {:from 12 :to 18 :activity :stroll}
                   {:from 18 :to 6 :activity :rest :place :bed}]
   :agent/effects [{:effect "minecraft:speed" :amplifier 1}]})

(def beta
  {:agent/id :beta :agent/name "Beta"
   :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 65.0 0.0]}
   :agent/lines {:greeting ["Salve, Alpha."]}
   :agent/relations {:alpha {:kind :friend :affinity 0.3}}
   :agent/places {:gate {:name "Portao" :pos [0.0 65.0 -20.0]}}
   :agent/routine [{:from 6 :to 18 :activity :patrol :place :gate}
                   {:from 18 :to 6 :activity :rest}]})

(defn- run
  "Executa a acao `id` como `agent` com ponte e fala stubadas. Devolve {:res :cmds :said :ctx}."
  [agent id params & {:keys [pos ticks agents] :or {ticks 0}}]
  (let [cmds (atom []) said (atom [])
        ectx (atom {:world-time ticks :agents (or agents {})})
        st   (atom {:pos (or pos [0.0 65.0 0.0])})
        ctx  {:agent agent :state st :engine-ctx ectx :speaker "Pace"}]
    (body/reset-buffer!)
    (body/flush!)
    (let [res (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                            dispatch/say! (fn [_ msg] (swap! said conj msg))]
                (bta/bt-action id params ctx))]
      {:res res :cmds @cmds :said @said :ctx @ectx})))

;; ----------------------------------------------------------------------------
;; Funcoes puras de npc.routine
;; ----------------------------------------------------------------------------

(deftest world-hour-from-ticks-test
  (testing "tick 0 do dia = 06:00; 6000 = 12:00; 18000 = 00:00"
    (is (= 6 (routine/world-hour 0)))
    (is (= 12 (routine/world-hour 6000)))
    (is (= 19 (routine/world-hour 13000)))
    (is (= 0 (routine/world-hour 18000)))
    (is (= 6 (routine/world-hour 24000)) "o dia se repete"))
  (testing "sem tempo do mundo nao ha hora"
    (is (nil? (routine/world-hour nil)))))

(deftest slot-selection-is-pure-and-half-open-test
  (let [r (:agent/routine alpha)]
    (is (= :study (:activity (routine/slot-at r 6))) "[from, to): from inclusivo")
    (is (= :study (:activity (routine/slot-at r 11))))
    (is (= :stroll (:activity (routine/slot-at r 12))) "to exclusivo")
    (is (= :rest (:activity (routine/slot-at r 23))) "faixa que cruza a meia-noite (18->6)")
    (is (= :rest (:activity (routine/slot-at r 2))))
    (is (nil? (routine/slot-at [] 8)))
    (is (nil? (routine/slot-at nil 8)))
    (is (nil? (routine/slot-at r nil)) "sem hora: fail-closed")
    (is (= (routine/slot-at r 8) (routine/slot-at r 8)) "pura")))

(deftest places-and-relations-resolution-test
  (is (= [10.0 65.0 0.0] (:pos (routine/place alpha :lab))))
  (is (nil? (routine/place alpha :gate)) "lugar de outro agente nao existe aqui")
  (is (= [0.0 65.0 -20.0] (:pos (routine/place beta :gate))))
  (is (= :companion (:kind (routine/relation alpha :beta))))
  (is (nil? (routine/relation alpha :zeta)))
  (is (= :beta (routine/companion-id alpha)) "relacao :companion")
  (is (= :alpha (routine/companion-id beta)) "sem :companion: maior afinidade")
  (is (nil? (routine/companion-id {:agent/id :solo}))))

(deftest current-slot-resolves-place-test
  (let [s (routine/current-slot alpha 0)]
    (is (= :study (:activity s)))
    (is (= :lab (:place s)))
    (is (= [10.0 65.0 0.0] (:pos (routine/place alpha (:place s))))))
  (is (= :patrol (:activity (routine/current-slot beta 0))))
  (is (= :stroll (:activity (routine/current-slot alpha 6000)))))

(deftest step-toward-test
  (is (= [1.0 65.0 0.0] (routine/step-toward [0.0 65.0 0.0] [10.0 65.0 0.0] 1.0)))
  (is (= [10.0 65.0 0.0] (routine/step-toward [9.5 65.0 0.0] [10.0 65.0 0.0] 1.0)) "nao passa do alvo")
  (is (true? (routine/arrived? [9.0 65.0 0.0] [10.0 65.0 0.0] 1.5)))
  (is (false? (routine/arrived? [0.0 65.0 0.0] [10.0 65.0 0.0] 1.5))))

;; ----------------------------------------------------------------------------
;; Registro de acoes
;; ----------------------------------------------------------------------------

(deftest actions-are-registered-with-valid-specs-test
  (doseq [id [:visit-place :follow-routine-slot :greet-companion :workbench-study :cast-declared-spell]]
    (let [spec (get (bta/registered-actions) id)]
      (is (some? spec) (str id " esta no registro unico"))
      (is (m/validate bta/ActionSpec spec) (str id " cumpre ActionSpec"))
      (is (contains? (bta/known-action-ids) id)))))

;; ----------------------------------------------------------------------------
;; :visit-place
;; ----------------------------------------------------------------------------

(deftest visit-place-walks-to-the-agents-own-place-test
  (let [{:keys [res cmds]} (run alpha :visit-place {:place :lab})]
    (is (= :running (:status res)) "longe: segue andando")
    (is (= 1 (count cmds)))
    (is (str/starts-with? (first cmds) "MOVE|Alpha|1.000|65.000|0.000")))
  (let [{:keys [res cmds]} (run beta :visit-place {:place :gate})]
    (is (= :running (:status res)))
    (is (str/starts-with? (first cmds) "MOVE|Beta|0.000|65.000|-1.000") "o destino vem do .edn de Beta")))

(deftest visit-place-arrives-and-fails-closed-test
  (let [{:keys [res]} (run alpha :visit-place {:place :lab} :pos [9.5 65.0 0.0])]
    (is (= :success (:status res)))
    (is (= :lab (:place res))))
  (let [{:keys [res cmds]} (run alpha :visit-place {:place :gate})]
    (is (= :failure (:status res)))
    (is (= :UNKNOWN_PLACE (:reason res)) "lugar nao declarado em :agent/places")
    (is (empty? cmds)))
  (let [{:keys [res]} (run {:agent/id :solo :agent/name "Solo"} :visit-place {:place :lab})]
    (is (= :UNKNOWN_PLACE (:reason res)) "agente sem :agent/places"))
  (let [{:keys [res]} (run alpha :visit-place {:place :lab}
                           :pos nil)]
    (is (contains? #{:running :failure} (:status res)))))

;; ----------------------------------------------------------------------------
;; :follow-routine-slot
;; ----------------------------------------------------------------------------

(deftest follow-routine-slot-follows-each-agents-edn-test
  (testing "mesma hora do mundo, agentes diferentes, lugares diferentes"
    (let [a (run alpha :follow-routine-slot {} :ticks 0)
          b (run beta :follow-routine-slot {} :ticks 0)]
      (is (= :running (:status (:res a))))
      (is (= :study (:activity (:res a))))
      (is (str/starts-with? (first (:cmds a)) "MOVE|Alpha|1.000|65.000|0.000"))
      (is (= :patrol (:activity (:res b))))
      (is (str/starts-with? (first (:cmds b)) "MOVE|Beta|0.000|65.000|-1.000"))))
  (testing "a hora muda o bloco"
    (let [a (run alpha :follow-routine-slot {} :ticks 13000 :pos [0.0 65.0 9.5])]
      (is (= :rest (:activity (:res a))))
      (is (= :success (:status (:res a))) "ja no lugar do bloco"))
    (let [b (run beta :follow-routine-slot {} :ticks 13000)]
      (is (= :rest (:activity (:res b))))
      (is (= :success (:status (:res b))) "bloco sem :place: nada a andar")
      (is (empty? (:cmds b)))))
  (testing "bloco sem lugar nem rotina"
    (let [r (run alpha :follow-routine-slot {} :ticks 6000)]
      (is (= :success (:status (:res r))))
      (is (= :stroll (:activity (:res r)))))
    (let [r (run {:agent/id :solo :agent/name "Solo"} :follow-routine-slot {})]
      (is (= :failure (:status (:res r))))
      (is (= :NO_ROUTINE_SLOT (:reason (:res r)))))
    (let [r (run alpha :follow-routine-slot {} :ticks nil)]
      (is (= :NO_ROUTINE_SLOT (:reason (:res r))) "sem world-time: fail-closed"))))

;; ----------------------------------------------------------------------------
;; :greet-companion
;; ----------------------------------------------------------------------------

(deftest greet-companion-uses-relations-and-lines-test
  (let [agents {:beta {:pos [2.0 65.0 0.0]} :alpha {:pos [0.0 65.0 0.0]}}
        a (run alpha :greet-companion {} :agents agents)
        b (run beta :greet-companion {} :agents agents :pos [2.0 65.0 0.0])]
    (is (= :success (:status (:res a))))
    (is (= :beta (:target (:res a))))
    (is (contains? (set (get-in alpha [:agent/lines :greeting])) (first (:said a)))
        "fala vem de :agent/lines do Alpha")
    (is (= ["Salve, Alpha."] (:said b)) "Beta fala a linha dele")
    (is (= :alpha (:target (:res b))))
    (is (every? #(str/starts-with? % "MOVE|") (:cmds a)) "so olha (corpo via npc.body)")))

(deftest greet-companion-fails-closed-test
  (testing "alvo longe, desconhecido ou relacao ausente"
    (let [r (run alpha :greet-companion {} :agents {:beta {:pos [50.0 65.0 0.0]}})]
      (is (= :TOO_FAR (:reason (:res r))))
      (is (empty? (:said r))))
    (let [r (run alpha :greet-companion {})]
      (is (= :UNKNOWN_TARGET (:reason (:res r))) "sem posicao do alvo"))
    (let [r (run alpha :greet-companion {:target :zeta} :agents {:zeta {:pos [1.0 65.0 0.0]}})]
      (is (= :UNKNOWN_RELATION (:reason (:res r))) "so cumprimenta quem esta em :agent/relations"))
    (let [r (run {:agent/id :solo :agent/name "Solo"} :greet-companion {})]
      (is (= :UNKNOWN_RELATION (:reason (:res r))))))
  (testing "afinidade minima declarada nos params"
    (let [agents {:gamma {:pos [1.0 65.0 0.0]}}
          low  (run alpha :greet-companion {:target :gamma :min-affinity 0.5} :agents agents)
          ok   (run alpha :greet-companion {:target :gamma :min-affinity 0.05} :agents agents)]
      (is (= :LOW_AFFINITY (:reason (:res low))))
      (is (empty? (:said low)))
      (is (= :success (:status (:res ok)))))))

;; ----------------------------------------------------------------------------
;; :workbench-study e :cast-declared-spell
;; ----------------------------------------------------------------------------

(deftest workbench-study-walks-then-uses-the-workstation-test
  (let [far (run alpha :workbench-study {:place :bench} :pos [20.0 65.0 0.0])]
    (is (= :running (:status (:res far))))
    (is (empty? (:said far))))
  (let [near (run alpha :workbench-study {:place :bench} :pos [3.5 65.0 0.0])]
    (is (= :success (:status (:res near))))
    (is (= :bench (:place (:res near))))
    (is (= ["Estudando..."] (:said near)) "fala :studying de :agent/lines"))
  (let [r (run beta :workbench-study {:place :bench} :pos [0.0 65.0 0.0])]
    (is (= :UNKNOWN_PLACE (:reason (:res r))) "Beta nao declara :bench")))

(deftest cast-declared-spell-only-casts-declared-effects-test
  (let [ok (run alpha :cast-declared-spell {:effect "minecraft:speed"})]
    (is (= :success (:status (:res ok))))
    (is (some #(and (str/starts-with? % "CMD|effect give Alpha minecraft:speed")) (:cmds ok))))
  (let [no (run alpha :cast-declared-spell {:effect "minecraft:levitation"})]
    (is (= :UNDECLARED_EFFECT (:reason (:res no))))
    (is (empty? (:cmds no))))
  (let [b (run beta :cast-declared-spell {:effect "minecraft:speed"})]
    (is (= :UNDECLARED_EFFECT (:reason (:res b))) "Beta nao declara efeitos")
    (is (empty? (:cmds b)))))

;; ----------------------------------------------------------------------------
;; Guards
;; ----------------------------------------------------------------------------

(deftest routine-guards-follow-the-agents-edn-test
  (let [w (fn [agent ticks pos] {:agent agent :agent-id (:agent/id agent) :world-time ticks :self-pos pos})]
    (is (true? (bta/guard-holds? :routine-activity? {:activity :study} (w alpha 0 nil))))
    (is (false? (bta/guard-holds? :routine-activity? {:activity :study} (w beta 0 nil))))
    (is (true? (bta/guard-holds? :routine-activity? {:activity :patrol} (w beta 0 nil))))
    (is (false? (bta/guard-holds? :routine-activity? {:activity :study} (w alpha 6000 nil))))
    (is (true? (bta/guard-holds? :at-place? {:place :lab :radius 2.0} (w alpha 0 [9.0 65.0 0.0]))))
    (is (false? (bta/guard-holds? :at-place? {:place :lab :radius 2.0} (w alpha 0 [0.0 65.0 0.0]))))
    (is (false? (bta/guard-holds? :at-place? {:place :gate} (w alpha 0 [0.0 65.0 -20.0])))
        "lugar nao declarado: fail-closed")))

(deftest routine-guards-fail-closed-without-data-test
  (is (false? (bta/guard-holds? :routine-activity? {:activity :study} {})))
  (is (false? (bta/guard-holds? :routine-activity? {:activity :study} {:agent alpha})) "sem world-time")
  (is (false? (bta/guard-holds? :at-place? {:place :lab} {:agent alpha})) "sem posicao")
  (is (false? (bta/guard-holds? :at-place? nil nil))))

(deftest guard-world-carries-agent-and-time-test
  (with-redefs [registry/agent-by-id (fn [id] (when (= id :alpha) alpha))]
    (let [w (bta/guard-world (atom {:world-time 0}) (atom {:pos [10.0 65.0 0.0]}) :alpha)]
      (is (= 0 (:world-time w)))
      (is (true? (bta/guard-holds? :routine-activity? {:activity :study} w)))
      (is (true? (bta/guard-holds? :at-place? {:place :lab :radius 1.0} w))))))

;; ----------------------------------------------------------------------------
;; CA-111: Coco e Richeh integralmente em BT/EDN
;; ----------------------------------------------------------------------------

(defn- bt-step!
  "Um tick ABERTO do engine para `agent-id` (spec REAL do `.edn`) por `bt-tick/tick-agent!`, ponte e
   fala stubadas, `flush!` ao fim. `ectx`: atom do engine-ctx (reaproveitavel entre ticks; use um
   `:tick` diferente por chamada, o snapshot de percepcao e cacheado por tick)."
  [ectx agent-id & {:keys [players tick] :or {players [] tick 4}}]
  (let [cmds (atom []) said (atom [])
        res  (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                           actions/query-online-players (fn [] players)
                           dispatch/say! (fn [_ msg] (swap! said conj msg))]
               (body/reset-buffer!)
               (body/begin-tick!)
               (try (bt-tick/tick-agent! {:engine-ctx-atom ectx :tick tick} (registry/agent-by-id agent-id))
                    (finally (body/flush!))))
        nm   (:agent/name (registry/agent-by-id agent-id))]
    {:res res :cmds @cmds :said @said
     :moves (filterv #(str/starts-with? % (str "MOVE|" nm "|")) @cmds)}))

(defn- bt-ctx
  "engine-ctx de um agente em `pos`: hora do mundo, relogio e posicoes publicadas em `:agents`."
  [agent-id pos & {:keys [world-time now-ms agents ctx0] :or {world-time 0 now-ms 1000000}}]
  (let [ectx (atom (merge {:world-time world-time :now-ms now-ms
                           :agents (merge {agent-id {:pos pos}} agents)}
                          ctx0))]
    (npc-state/agent-atom ectx agent-id {:pos pos})
    ectx))

(defn- move-fields [line] (mapv parse-double (subvec (str/split line #"\|") 2 7)))

(defn- tree-nodes [node]
  (when (vector? node)
    (cons node (mapcat tree-nodes (rest node)))))

(defn- tree-action-ids [agent-id]
  (set (keep (fn [n] (when (= :action (first n)) (second n)))
             (tree-nodes (:bt/root (registry/agent-by-id agent-id))))))

(deftest coco-and-richeh-edns-are-complete-bt-agents-test
  (let [{:keys [agents errors]} (registry/load-agents "agents" {:known-actions (bta/known-action-ids)})]
    (is (empty? (filter #(re-find #"(?i)coco|richeh" (str (:file %))) errors))
        "os .edn da dupla validam contra o registro de acoes (nenhuma acao fora do registro)")
    (doseq [id [:coco :richeh]]
      (let [ids (tree-action-ids id)]
        (is (contains? agents id))
        (is (contains? ids :follow-routine-slot) (str id " segue a propria rotina"))
        (is (contains? ids :greet-companion) (str id " interage com a companheira pela BT"))
        (is (contains? ids :react-celestial) (str id " reage ao ceu pela BT"))
        (is (not (contains? ids :patrol-waypoints)) "sem acao pendente de patrulha: a rotina vem de :agent/routine")
        (is (seq (get-in (registry/agent-by-id id) [:agent/lines :celestial]))
            (str id " declara falas :celestial em :agent/lines"))))))

(def ^:private routine-expectations
  "[world-time atividade lugar] esperados de cada bruxa (hora = 6 + world-time/1000)."
  {:coco   [[0 :study [-30.0 66.0 190.0]] [6000 :sketch [-25.0 66.0 195.0]]
            [11000 :stroll [-18.0 66.0 190.0]] [16000 :rest [-30.0 66.0 190.0]]]
   :richeh [[0 :craft [-30.0 66.0 192.0]] [6000 :inscribe [-28.0 66.0 196.0]]
            [12000 :guard [-20.0 66.0 192.0]] [16000 :rest [-30.0 66.0 192.0]]]})

(deftest daily-routine-walks-to-each-slot-place-only-via-bt-tick-test
  (doseq [[id slots] routine-expectations
          [wt activity place] slots]
    (let [start [0.0 66.0 0.0]
          ectx  (bt-ctx id start :world-time wt)
          r     (bt-step! ectx id)
          nm    (:agent/name (registry/agent-by-id id))]
      (testing (str id " hora " (routine/world-hour wt) " " activity)
        (is (= activity (:activity (routine/current-slot (registry/agent-by-id id) wt))))
        (is (= :running (:status (:res r))) "caminhando ate o lugar do bloco")
        (is (= 1 (count (:moves r))) (str "exatamente 1 MOVE|" nm "| pelo npc.body"))
        (is (empty? (body/pending)) "buffer do arbitro esvaziado pelo flush!")
        (let [[x y z] (move-fields (first (:moves r)))
              before  (routine/distance start place)
              after   (routine/distance [x y z] place)]
          (is (< after before) "aproxima-se do lugar declarado no .edn")
          (is (<= (- before after) 1.0001) "no maximo 1 bloco por passo de BT (5Hz)"))))))

(deftest slot-places-are-visited-and-arrival-is-quiet-test
  (doseq [[id [wt _ place]] [[:coco (first (:coco routine-expectations))]
                             [:richeh (first (:richeh routine-expectations))]]]
    (let [ectx (bt-ctx id place :world-time wt)
          r    (bt-step! ectx id)]
      (is (= :success (:status (:res r))) (str id " ja esta no lugar do bloco"))
      (is (empty? (:moves r)) "chegou: nada a andar")))
  (testing "Coco: ao chegar na estante no bloco :sketch, rabisca no caderno (acao modular)"
    (let [ectx (bt-ctx :coco [-25.0 66.0 195.0] :world-time 6000)
          r    (bt-step! ectx :coco)]
      (is (= :success (:status (:res r))))
      (is (some #{"SWING|Coco"} (:cmds r)))
      (is (number? (get-in @ectx [:cooldowns :sketch-notes])) "cooldown do rabisco carimbado")
      (let [r2 (bt-step! ectx :coco :tick 8)]
        (is (not-any? #{"SWING|Coco"} (:cmds r2)) "dentro do cooldown nao rabisca de novo"))))
  (testing "Richeh: ao chegar no canto no bloco :inscribe, agacha e inscreve; fora do canto nao"
    (let [r (bt-step! (bt-ctx :richeh [-28.0 66.0 196.0] :world-time 6000) :richeh)]
      (is (some #{"SWING|Richeh"} (:cmds r))))
    (let [r (bt-step! (bt-ctx :richeh [0.0 66.0 0.0] :world-time 6000) :richeh)]
      (is (not-any? #{"SWING|Richeh"} (:cmds r)) "longe do canto ela caminha, nao inscreve")
      (is (= 1 (count (:moves r)))))))

(deftest duo-interaction-runs-through-the-edn-bt-test
  (let [coco-pos [-30.0 66.0 190.0] richeh-pos [-30.0 66.0 192.0]
        agents   {:coco {:pos coco-pos} :richeh {:pos richeh-pos}}]
    (testing "Coco"
      (let [ectx (bt-ctx :coco coco-pos :agents agents)
            r    (bt-step! ectx :coco :tick 4)
            [_ _ _ yaw] (some-> (first (:moves r)) move-fields)]
        (is (= :success (:status (:res r))))
        (is (contains? (set (get-in (registry/agent-by-id :coco) [:agent/lines :near-richeh])) (first (:said r)))
            "fala de :agent/lines :near-richeh do coco.edn")
        (is (= 1 (count (:moves r))) "olhar para a Richeh: 1 MOVE")
        (is (< (Math/abs (- (double (or yaw 1e9)) (nav/compute-yaw 0.0 2.0))) 0.06))
        (is (some #{"SWING|Coco"} (:cmds r)))
        (is (number? (get-in @ectx [:cooldowns :duo-study])))
        (is (empty? (:said (bt-step! ectx :coco :tick 8))) "cooldown de 45 s")
        (swap! ectx assoc :now-ms (+ 1000000 46000))
        (is (= 1 (count (:said (bt-step! ectx :coco :tick 12)))) "passados 45 s interage de novo")))
    (testing "Richeh"
      (let [ectx (bt-ctx :richeh richeh-pos :agents agents)
            r    (bt-step! ectx :richeh :tick 4)]
        (is (= :success (:status (:res r))))
        (is (contains? (set (get-in (registry/agent-by-id :richeh) [:agent/lines :near-coco])) (first (:said r)))
            "fala de :agent/lines :near-coco do richeh.edn")
        (is (= 1 (count (:moves r))))
        (is (number? (get-in @ectx [:cooldowns :duo-craft])))
        (is (empty? (:said (bt-step! ectx :richeh :tick 8))) "cooldown de 45 s")))
    (testing "longe uma da outra nao ha interacao"
      (let [ectx (bt-ctx :coco coco-pos :agents {:richeh {:pos [-30.0 66.0 200.0]}})]
        (is (empty? (:said (bt-step! ectx :coco))))))))

(deftest reactions-run-through-the-edn-bt-test
  (testing "Richeh interpoe-se quando ha hostil por perto (:ward-companion)"
    (let [ectx (bt-ctx :richeh [0.0 66.0 0.0]
                       :ctx0 {:perception {:hostiles [{:id 1 :distance 5.0 :x 3.0 :y 66.0 :z 3.0}]}})
          r    (bt-step! ectx :richeh)]
      (is (= :success (:status (:res r))))
      (is (some #{"SWING|Richeh"} (:cmds r)))
      (is (empty? (:moves r)) "a reacao tem prioridade sobre a rotina")))
  (testing "Coco reage ao eclipse: olha o gigante gasoso e fala uma linha :celestial do coco.edn"
    (let [cel  {:phenomenon :solar-eclipse :gas-giant {:vector [0.0 0.8 0.6]}}
          ectx (bt-ctx :coco [-30.0 66.0 190.0] :ctx0 {:celestial cel})
          r    (bt-step! ectx :coco)
          [_ _ _ yaw pitch] (some-> (first (:moves r)) move-fields)]
      (is (= :success (:status (:res r))))
      (is (contains? (set (get-in (registry/agent-by-id :coco) [:agent/lines :celestial])) (first (:said r))))
      (is (= 1 (count (:moves r))))
      (is (< (Math/abs (- (double (or pitch 1e9)) -53.1)) 0.2) "pitch = asin(-gy): olha para cima")
      (is (< (Math/abs (double (or yaw 1e9))) 0.2))
      (is (empty? (:said (bt-step! ectx :coco :tick 8))) "cooldown de 45 s")))
  (testing "Richeh reage a aurora de cristal"
    (let [ectx (bt-ctx :richeh [-30.0 66.0 192.0]
                       :ctx0 {:celestial {:phenomenon :crystal-aurora :gas-giant {:vector [0.0 0.8 0.6]}}})
          r    (bt-step! ectx :richeh)]
      (is (contains? (set (get-in (registry/agent-by-id :richeh) [:agent/lines :celestial])) (first (:said r))))))
  (testing "ceu nominal ou fenomeno nao declarado: ninguem reage"
    (doseq [phenomenon [:nominal :meteor]]
      (let [r (bt-step! (bt-ctx :coco [-30.0 66.0 190.0] :ctx0 {:celestial {:phenomenon phenomenon}}) :coco)]
        (is (empty? (:said r)))))))

(deftest richeh-tether-follows-coco-when-she-drifts-away-test
  (let [ectx (bt-ctx :richeh [-40.0 66.0 190.0] :agents {:coco {:pos [-30.0 66.0 190.0]}})
        r    (bt-step! ectx :richeh)
        [x _ _] (some-> (first (:moves r)) move-fields)]
    (is (= 1 (count (:moves r))))
    (is (> (double (or x -1e9)) -40.0) "aproxima-se da Coco (a +x)")))

(deftest bt-tick-publishes-the-agents-real-position-test
  (let [ectx (atom {:world-time 0 :now-ms 1000000})
        r    (bt-step! ectx :coco :players [{:name "Coco" :x -20.0 :y 66.0 :z 190.0}])
        [x _ z] (some-> (first (:moves r)) move-fields)]
    (is (= [-20.0 66.0 190.0] (get-in @ectx [:agents :coco :pos])) "posicao real do servidor publicada")
    (is (= [-20.0 66.0 190.0] (:pos @(npc-state/agent-atom ectx :coco))))
    (is (and x (< x -20.0) (< (Math/abs (- z 190.0)) 0.01))
        "o passo parte da posicao REAL (nao do spawn): anda de -20 rumo ao atelie em -30")))

;; ----------------------------------------------------------------------------
;; Primitivas novas de CA-111 (registro unico, guards)
;; ----------------------------------------------------------------------------

(defn- run-with
  "Como `run`, mas com engine-ctx completo (`ectx0`)."
  [agent id params ectx0 & {:keys [pos]}]
  (let [cmds (atom []) said (atom [])
        ectx (atom ectx0)
        st   (atom {:pos (or pos [0.0 65.0 0.0])})]
    (body/reset-buffer!)
    (body/flush!)
    (let [res (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                            dispatch/say! (fn [_ msg] (swap! said conj msg))]
                (bta/bt-action id params {:agent agent :state st :engine-ctx ectx :speaker "Pace"}))]
      {:res res :cmds @cmds :said @said})))

(deftest ca111-actions-are-registered-with-valid-specs-test
  (doseq [id [:react-celestial :tether-follow-ally]]
    (let [spec (get (bta/registered-actions) id)]
      (is (some? spec) (str id " esta no registro unico"))
      (is (m/validate bta/ActionSpec spec)))))

(deftest celestial-event-guard-test
  (let [g #(bta/guard-holds? :celestial-event? %1 %2)]
    (is (true? (g {:phenomena [:solar-eclipse]} {:celestial {:phenomenon :solar-eclipse}})))
    (is (false? (g {:phenomena [:solar-eclipse]} {:celestial {:phenomenon :crystal-aurora}})))
    (is (true? (g {} {:celestial {:phenomenon :meteor}})) "sem :phenomena, qualquer fenomeno nao nominal")
    (is (false? (g {} {:celestial {:phenomenon :nominal}})))
    (is (false? (g {:phenomena [:solar-eclipse]} {})) "sem dado celeste: fail-closed")
    (is (false? (g nil nil)))))

(deftest react-celestial-uses-the-agents-own-lines-test
  (let [agent {:agent/id :alpha :agent/name "Alpha" :agent/lines {:celestial ["Olha o ceu!"]}}
        cel   {:phenomenon :solar-eclipse :gas-giant {:vector [0.0 0.8 0.6]}}
        r     (run-with agent :react-celestial {} {:celestial cel})]
    (is (= :success (:status (:res r))))
    (is (= ["Olha o ceu!"] (:said r)))
    (is (some #(str/starts-with? % "MOVE|Alpha|") (:cmds r)) "olha para o gigante gasoso (so :gaze)"))
  (testing "sem fenomeno nao reage; sem linha :celestial so olha"
    (let [r (run-with {:agent/id :alpha :agent/name "Alpha" :agent/lines {:celestial ["x"]}}
                      :react-celestial {} {:celestial {:phenomenon :nominal}})]
      (is (= :failure (:status (:res r))))
      (is (= :NO_CELESTIAL_EVENT (:reason (:res r))))
      (is (empty? (:cmds r))))
    (let [r (run-with {:agent/id :alpha :agent/name "Alpha"} :react-celestial {}
                      {:celestial {:phenomenon :solar-eclipse}})]
      (is (= :success (:status (:res r))))
      (is (empty? (:said r))))))

(deftest tether-follow-ally-steps-toward-the-ally-test
  (let [agent {:agent/id :alpha :agent/name "Alpha"}
        far   (run-with agent :tether-follow-ally {:ally-id :beta :target-dist 3.5}
                        {:agents {:beta {:pos [10.0 65.0 0.0]}}} :pos [0.0 65.0 0.0])
        near  (run-with agent :tether-follow-ally {:ally-id :beta :target-dist 3.5}
                        {:agents {:beta {:pos [2.0 65.0 0.0]}}} :pos [0.0 65.0 0.0])
        none  (run-with agent :tether-follow-ally {:ally-id :ghost} {:agents {}} :pos [0.0 65.0 0.0])]
    (is (= :success (:status (:res far))) "um passo por tick; nunca fica :running (a BT segue reativa)")
    (is (= 1 (count (:cmds far))))
    (is (str/starts-with? (first (:cmds far)) "MOVE|Alpha|1.000|65.000|0.000"))
    (is (= :success (:status (:res near))))
    (is (empty? (:cmds near)) "ja a menos de :target-dist: nao anda")
    (is (= :failure (:status (:res none))))
    (is (= :UNKNOWN_TARGET (:reason (:res none))))))

;; ----------------------------------------------------------------------------
;; Engine: atelier_tick delega a BT; so resta o seguir por ordem do jogador e a auto-defesa
;; ----------------------------------------------------------------------------

(deftest atelier-tick-no-longer-drives-witches-imperatively-test
  (let [calls (atom [])
        env   (fn [tick] {:coco-state (atelier/create-coco-state) :richeh-state (atelier/create-richeh-state)
                          :tick tick :engine-ctx-atom (atom {})})
        spy   (fn [k] (fn [& _] (swap! calls conj k) nil))
        run!  (fn [e]
                (reset! calls [])
                (with-redefs [transport/send-bridge-cmd! (fn [_] "OK|0|")
                              actions/query-online-players (fn [] [])
                              atelier/step-coco-tick! (spy :coco-step)
                              atelier/step-richeh-tick! (spy :richeh-step)
                              atelier/check-duo-synchrony! (spy :duo)
                              atelier/check-celestial-reaction! (spy :celestial)
                              atelier/spell-auto-defense! (fn [& _] (swap! calls conj :defense) false)]
                  (atelier-tick/tick-atelier! e))
                @calls)]
    (testing "patrulha/orbita/dupla/ceu: nada imperativo, e a BT que decide"
      (is (= [] (run! (env 1)))))
    (testing "auto-defesa das duas segue a cada 1 s"
      (is (= [:defense :defense] (run! (env 20)))))
    (testing "ordem de seguir do jogador (:mode :follow) continua no driver de jogador"
      (let [e (env 1)]
        (swap! (:coco-state e) assoc :mode :follow)
        (is (= [:coco-step] (run! e)))
        (swap! (:richeh-state e) assoc :mode :follow)
        (is (= [:coco-step :richeh-step] (run! e)))))
    (testing "publica hora do mundo e ceu no engine-ctx para guards/acoes da BT"
      (let [e (env 1234)]
        (run! e)
        (is (= 1234 (:world-time @(:engine-ctx-atom e))))
        (is (contains? @(:engine-ctx-atom e) :celestial))
        (is (keyword? (get-in @(:engine-ctx-atom e) [:celestial :phenomenon])))))))

;; ----------------------------------------------------------------------------
;; CA-113: fechamento integrado — Studio /api/state + BT unificada + Coco/Richeh declarativos
;; ----------------------------------------------------------------------------

(defn- studio-state
  "GET /api/state do Studio sobre `ectx`, com a ponte do jogo stubada. Devolve o JSON (chaves keyword)."
  [ectx]
  (with-redefs [fabric/query-online-players (fn [] [])
                fabric/query-minecraft-weather (fn [] {:clock "12:00" :phase "Dia" :sky "Ceu Limpo"})
                fabric/inspect-inventory (fn [_] {:slots-used 0 :items {}})]
    (let [resp ((studio/create-app-handler ectx) {:uri "/api/state" :request-method :get})]
      (is (= 200 (:status resp)))
      (json/read-str (:body resp) :key-fn keyword))))

(deftest studio-state-exposes-routine-and-bt-memory-end-to-end-test
  (let [ectx (bt-ctx :coco [0.0 66.0 0.0] :world-time 6000
                     :agents {:richeh {:pos [-30.0 66.0 192.0]}})]
    (is (= :running (:status (:res (bt-step! ectx :coco)))) "Coco caminha ate o bloco :sketch")
    (bt-step! ectx :richeh :tick 8)
    (let [state (studio-state ectx)
          coco  (get-in state [:agents :coco])]
      (testing "o cartao da Coco resume a rotina declarada no .edn"
        (is (= "sketch" (get-in coco [:routine :activity])))
        (is (= 12 (get-in coco [:routine :hour]))))
      (testing "memoria da BT serializavel (caminhos viram texto) e contador de ticks"
        (is (map? (:bt-memory coco)))
        (is (seq (:bt-memory coco)) "a BT deixou memoria de no em andamento")
        (is (pos? (:bt-tick coco))))
      (testing "Richeh tambem tem cartao com rotina; campos legados preservados"
        (is (some? (get-in state [:agents :richeh :routine])))
        (is (contains? coco :leases))
        (is (contains? coco :cooldowns))
        (is (contains? state :tick))))))

(deftest studio-state-survives-agents-without-routine-test
  (let [ectx  (atom {:agents {:karlach {:pos [0.0 65.0 0.0]}}})
        state (studio-state ectx)]
    (is (contains? (:agents state) :karlach))
    (is (nil? (get-in state [:agents :karlach :routine])) "sem hora do mundo: fail-closed")))

(deftest fail-closed-guards-hold-without-perception-test
  (is (false? (bta/guard-holds? :in-quiet-corner? {} {})))
  (is (false? (bta/guard-holds? :at-place? {:place :lab :radius 1.0} {})))
  (is (false? (bta/guard-holds? :routine-activity? {:activity :study} {}))))

(ns craft.agents.npc-routine-test
  "CA-110 — primitivas genericas de rotina, lugares nomeados e interacao social do NPC Core
   (SUG-06 parte 1): `:visit-place`, `:follow-routine-slot`, `:greet-companion`,
   `:workbench-study`, `:cast-declared-spell` e os guards `:routine-activity?`/`:at-place?`.
   Dois agentes ficticios (Alpha, Beta) provam que o comportamento segue apenas o `.edn`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.goals.bt-actions :as bta]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.body :as body]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.routine :as routine]
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
  [agent id params & {:keys [pos ticks agents]}]
  (let [cmds (atom []) said (atom [])
        ectx (atom {:world-time (or ticks 0) :agents (or agents {})})
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

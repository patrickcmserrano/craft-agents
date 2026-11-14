(ns craft.agents.goals-characterization-test
  "CA-085: caracterizacao do goals.clj. Fixa, para cada :goal/type, os comandos de
   ponte emitidos e as mudancas em engine-ctx/nav-state, e o interpretador de
   Behavior Tree, chamando os nomes NOVOS nos sub-namespaces de backend/fabric/goals/
   e o contrato da fachada goals.clj."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.goals :as goals]
            [craft.agents.backend.fabric.goals.bt :as bt]
            [craft.agents.backend.fabric.goals.companion]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch]
            [craft.agents.backend.fabric.goals.furniture]
            [craft.agents.backend.fabric.goals.rescue]
            [craft.agents.backend.fabric.goals.skills]
            [craft.agents.backend.fabric.goals.work]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.cognition.critic :as critic]
            [craft.agents.rsi.curriculum :as curriculum]
            [craft.agents.skills.actions :as skill-actions]
            [craft.agents.skills.composer :as composer]))

(def ^:private players
  [{:name "Pace" :x 10.0 :y 65.0 :z 0.0}])

(defn- run-goal
  "Executa o goal com a ponte e as acoes stubadas.
   Devolve {:cmds [...] :said [...] :tp [...] :ctx map :nav map}."
  [goal & {:keys [nav0 route bridge extra]}]
  (let [cmds (atom [])
        said (atom [])
        tps  (atom [])
        ctx  (atom {})
        nst  (atom (merge {:pos [0.0 65.0 0.0] :mode :idle} nav0))]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) (if bridge (bridge c) "OK"))
                  actions/karlach-say! (fn [m] (swap! said conj m))
                  actions/query-online-players (fn [] players)
                  actions/teleport-agent-to! (fn [n p] (swap! tps conj [n p]))
                  nav/request-path3d (fn [_ _ & _] [{:x 1.0 :y 65.0 :z 0.0}])
                  nav/expand-route-microsteps (fn [stop pos] [{:op :walk :target pos :stop stop}])]
      (if extra
        (extra #(dispatch/execute-karlach-goal! goal ctx nst route))
        (dispatch/execute-karlach-goal! goal ctx nst route)))
    {:cmds @cmds :said @said :tp @tps :ctx @ctx :nav @nst}))

(defn- has-cmd? [{:keys [cmds]} s] (boolean (some #(str/includes? % s) cmds)))

;; ----------------------------------------------------------------------------
;; fachada e multimethod unico
;; ----------------------------------------------------------------------------

(deftest facade-registers-all-goal-types-test
  (is (identical? dispatch/execute-karlach-goal! goals/execute-karlach-goal!))
  (is (= bt/execute-tree-node! goals/execute-tree-node!))
  (doseq [t [:animal-rescue :weather-shelter :teleport-to-player :follow-player :bodyguard
             :mining-cooperation :spatial-attention :social-dance :patrol-sanctuary
             :inventory-inspection :drop-inventory :breed-animals :sit-furniture :stand-up
             :teach-skill :execute-dynamic-skill]]
    (is (contains? (methods dispatch/execute-karlach-goal!) t) (str t))))

(deftest facade-and-sub-namespaces-size-test
  (let [lines (fn [p] (count (str/split-lines (slurp (io/file p)))))
        base  "src/clj/craft/agents/backend/fabric/"]
    (is (< (lines (str base "goals.clj")) 120))
    (doseq [n ["dispatch" "rescue" "companion" "work" "furniture" "skills" "bt"]]
      (is (< (lines (str base "goals/" n ".clj")) 300) n))))

(deftest default-goal-test
  (let [r (run-goal {:goal/type :nao-existe :goal/raw-text "oi" :goal/voice-line "Fala!"})]
    (is (= ["Fala!"] (:said r)))
    (is (= [] (:cmds r)))
    (is (= [:synthesis :motor] (mapv :type (get-in r [:ctx :events]))))))

;; ----------------------------------------------------------------------------
;; companion
;; ----------------------------------------------------------------------------

(deftest teleport-to-player-test
  (let [r (run-goal {:goal/type :teleport-to-player :goal/target "Pace" :goal/voice-line "Cheguei"})]
    (is (= [["Karlach" [10.0 65.0 0.0]]] (:tp r)))
    (is (has-cmd? r "entity.enderman.teleport"))
    (is (has-cmd? r "particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 35 normal"))
    (is (= :bodyguard (get-in r [:nav :mode])))
    (is (= [10.0 65.0 0.0] (get-in r [:nav :pos])))
    (is (= "Pace" (get-in r [:nav :follow-target])))
    (is (= [] (get-in r [:nav :steps])))
    (is (= ["Cheguei"] (:said r)))
    (is (= :motor (:type (first (get-in r [:ctx :events])))))))

(deftest teleport-to-unknown-player-is-noop-test
  (let [r (run-goal {:goal/type :teleport-to-player :goal/target "Ninguem" :goal/voice-line "x"})]
    (is (empty? (:tp r)))
    (is (= :idle (get-in r [:nav :mode])))))

(deftest follow-player-test
  (testing "perto: sem teleporte, caminho A*"
    (let [r (run-goal {:goal/type :follow-player :goal/target "Pace"})]
      (is (empty? (:tp r)))
      (is (= :follow (get-in r [:nav :mode])))
      (is (= [0.0 65.0 0.0] (get-in r [:nav :pos])))
      (is (= [{:x 1.0 :y 65.0 :z 0.0}] (get-in r [:nav :follow-path])))
      (is (= 0 (get-in r [:nav :follow-lost-ticks])))
      (is (empty? (:said r)) "sem voice-line nao fala")))
  (testing "longe (>48m): teleporta e zera caminho"
    (let [r (run-goal {:goal/type :follow-player :goal/target "Pace" :goal/voice-line "Indo"}
                      :nav0 {:pos [-100.0 65.0 0.0]})]
      (is (= 1 (count (:tp r))))
      (is (has-cmd? r "0.5 0.8 0.5 0.1 20 normal"))
      (is (= [10.0 65.0 0.0] (get-in r [:nav :pos])))
      (is (= [] (get-in r [:nav :follow-path])))
      (is (= ["Indo"] (:said r))))))

(deftest bodyguard-test
  (let [r (run-goal {:goal/type :bodyguard :goal/target "Pace" :goal/voice-line "Guardando"})]
    (is (has-cmd? r "item replace entity Karlach weapon.mainhand with minecraft:netherite_axe"))
    (is (has-cmd? r "item replace entity Karlach weapon.offhand with minecraft:shield"))
    (is (has-cmd? r "SWING|Karlach"))
    (is (= :bodyguard (get-in r [:nav :mode])))
    (is (= "Pace" (get-in r [:nav :follow-target])))
    (is (= "Guarda-Costas de Pace" (get-in r [:ctx :agents :karlach :task])))
    (is (= ["Guardando"] (:said r)))))

(deftest spatial-attention-test
  (testing "jogador visivel: olha para ele"
    (let [r (run-goal {:goal/type :spatial-attention :goal/entity "creeper" :goal/speaker "Pace"})]
      (is (has-cmd? r "MOVE|Karlach|0.000|65.000|0.000|"))
      (is (float? (get-in r [:nav :yaw])))
      (is (str/includes? (first (:said r)) "creeper"))
      (is (= :grounding (:type (first (get-in r [:ctx :events])))))))
  (testing "jogador ausente"
    (let [r (run-goal {:goal/type :spatial-attention :goal/entity "creeper" :goal/speaker "Fantasma"})]
      (is (empty? (:cmds r)))
      (is (str/includes? (first (:said r)) "Fantasma")))))

(deftest social-dance-test
  (let [r (run-goal {:goal/type :social-dance :goal/voice-line "Dancei"})]
    (is (= 80 (get-in r [:nav :dance-ticks])))
    (is (= ["Dancei"] (:said r)))))

;; ----------------------------------------------------------------------------
;; work
;; ----------------------------------------------------------------------------

(deftest mining-autonomous-test
  (let [r (run-goal {:goal/type :mining-cooperation :goal/target "Pace" :goal/filter "diamond"})]
    (is (has-cmd? r "item replace entity Karlach weapon.mainhand with minecraft:netherite_pickaxe"))
    (is (= :mining (get-in r [:nav :mode])))
    (is (= "diamond" (get-in r [:nav :mining-filter])))
    (is (= "Pace" (get-in r [:nav :mining-target])))
    (is (= 0 (get-in r [:nav :mining-blocks-broken])))
    (is (str/starts-with? (get-in r [:ctx :agents :karlach :task]) "Minerando "))))

(deftest mining-crosshair-test
  (testing "bloco achado e quebrado"
    (let [r (run-goal {:goal/type :mining-cooperation :goal/target "Pace" :goal/filter "crosshair"}
                      :extra (fn [f]
                               (with-redefs [actions/raycast-player-look (fn [_ _] {:found? true :x 11 :y 65 :z 0 :block-id "minecraft:stone"})
                                             actions/mine-block! (fn [_ _ _ _] {:success? true})]
                                 (f))))]
      (is (has-cmd? r "particle minecraft:block minecraft:stone"))
      (is (has-cmd? r "block.deepslate.break"))
      (is (not= :mining (get-in r [:nav :mode])))
      (is (str/includes? (last (:said r)) "stone"))))
  (testing "nada na mira"
    (let [r (run-goal {:goal/type :mining-cooperation :goal/target "Pace" :goal/filter "crosshair"}
                      :extra (fn [f]
                               (with-redefs [actions/raycast-player-look (fn [_ _] {:found? false})]
                                 (f))))]
      (is (str/includes? (last (:said r)) "mira")))))

(deftest inventory-inspection-test
  (testing "vazio"
    (let [r (run-goal {:goal/type :inventory-inspection :goal/speaker "Pace"}
                      :extra (fn [f] (with-redefs [actions/inspect-inventory (fn [_] {:items {} :slots-used 0})] (f))))]
      (is (str/includes? (first (:said r)) "vazia"))))
  (testing "com itens"
    (let [r (run-goal {:goal/type :inventory-inspection :goal/speaker "Pace"}
                      :extra (fn [f] (with-redefs [actions/inspect-inventory (fn [_] {:items {"minecraft:diamond" 3} :slots-used 1})] (f))))]
      (is (str/includes? (first (:said r)) "3x diamond"))
      (is (= :grounding (:type (first (get-in r [:ctx :events]))))))))

(deftest drop-inventory-test
  (testing "dropou algo"
    (let [r (run-goal {:goal/type :drop-inventory :goal/speaker "Pace" :goal/filter "ores"}
                      :extra (fn [f] (with-redefs [actions/drop-inventory-items! (fn [n flt] (is (= ["Karlach" "ores"] [n flt]))
                                                                                   {:dropped-count 2 :items {"minecraft:coal" 2}})]
                                       (f))))]
      (is (has-cmd? r "entity.item.pickup"))
      (is (str/includes? (first (:said r)) "2x coal"))))
  (testing "nada para dropar"
    (let [r (run-goal {:goal/type :drop-inventory :goal/speaker "Pace"}
                      :extra (fn [f] (with-redefs [actions/drop-inventory-items! (fn [_ flt] (is (= "all" flt)) {:dropped-count 0})] (f))))]
      (is (str/includes? (first (:said r)) "limpa")))))

(deftest patrol-sanctuary-test
  (let [r (run-goal {:goal/type :patrol-sanctuary :goal/voice-line "Patrulhando"}
                    :route [{:type :quarters}])]
    (is (= :sanctuary-patrol (get-in r [:nav :mode])))
    (is (= 0 (get-in r [:nav :route-idx])))
    (is (= [{:op :walk :target [0.0 65.0 0.0] :stop {:type :quarters}}] (get-in r [:nav :steps])))
    (is (= "Patrulha no Santuário" (get-in r [:ctx :agents :karlach :task])))
    (is (= ["Patrulhando"] (:said r)))))

;; ----------------------------------------------------------------------------
;; rescue
;; ----------------------------------------------------------------------------

(deftest breed-animals-test
  (let [r (run-goal {:goal/type :breed-animals :goal/speaker "Pace"})]
    (is (has-cmd? r "item replace entity Karlach weapon.mainhand with minecraft:cod"))
    (is (has-cmd? r "InLove:600"))
    (is (has-cmd? r "particle minecraft:heart"))
    (is (has-cmd? r "entity.cat.purr"))
    (is (= :sanctuary-patrol (get-in r [:nav :mode])))
    (is (str/includes? (first (:said r)) "Pace"))
    (is (= "Reprodução de Gatos em AFK (Cupido Felino)" (get-in r [:ctx :agents :karlach :task])))))

(deftest animal-rescue-test
  (let [goal {:goal/type :animal-rescue :goal/id :g1 :goal/name "Resgate" :goal/entity "vaca"
              :goal/species "cow" :goal/pen-id :pen-south-west
              :goal/gate-pos [-170 65 190] :goal/gate-block "minecraft:oak_fence_gate" :goal/gate-facing "west"
              :goal/inside-pos [-172.0 65.0 190.0] :goal/outside-pos [-168.0 65.0 190.0]
              :goal/feed-pos [-174.0 65.0 192.0] :goal/bait-item "minecraft:wheat"
              :goal/voice-line "Vou buscar!" :goal/speaker "Pace"}
        r (run-goal goal
                    :bridge (fn [c] (if (str/starts-with? c "LOCATE_MOB|") "OK|-177.0|65.0|189.0" "OK"))
                    :extra (fn [f]
                             (with-redefs [composer/validate-and-register-skill! (fn [& _] {:ok? true})
                                           critic/parse-sanctuary-snapshot (fn [_] {})
                                           nav/generate-walk-steps (fn [a b label & _] [{:op :walk :target b :label label}])]
                               (f))))
        ops (mapv :op (get-in r [:nav :steps]))]
    (is (has-cmd? r "SWING|Karlach"))
    (is (has-cmd? r "LOCATE_MOB|Karlach|cow"))
    (is (= ["Vou buscar!"] (:said r)))
    (is (= :sanctuary-patrol (get-in r [:nav :mode])))
    (is (nil? (get-in r [:nav :follow-target])))
    (is (= :equip (first ops)))
    (is (= :critic (last ops)))
    (is (= 2 (count (filter #{:gate} ops))))
    (is (some #{:attach-lead} ops))
    (is (some #{:await-contained} ops))
    (is (some #{:feed} ops))
    (is (= ["rescue-animal"] (get-in r [:ctx :agents :karlach :skills])))
    (is (str/starts-with? (get-in r [:ctx :agents :karlach :task]) "Conduzindo vaca"))
    (is (= #{:grounding :gatekeeper :skill-lib :motor}
           (set (map :type (get-in r [:ctx :events])))))))

;; ----------------------------------------------------------------------------
;; furniture
;; ----------------------------------------------------------------------------

(deftest sit-and-stand-test
  (let [r (run-goal {:goal/type :sit-furniture :goal/speaker "Pace"})]
    (is (= "SIT|Karlach" (first (:cmds r))))
    (is (has-cmd? r "block.wool.place"))
    (is (str/includes? (first (:said r)) "Pace")))
  (let [r (run-goal {:goal/type :stand-up :goal/voice-line "De pé"})]
    (is (= "STAND|Karlach" (first (:cmds r))))
    (is (has-cmd? r "entity.player.attack.sweep"))
    (is (= ["De pé"] (:said r)))))

(deftest weather-shelter-test
  (let [r (run-goal {:goal/type :weather-shelter :goal/voice-line "Chuva"}
                    :route [{:type :quarters}]
                    :extra (fn [f] (with-redefs [composer/validate-and-register-skill! (fn [& _] {:ok? true})] (f))))]
    (is (= :sanctuary-patrol (get-in r [:nav :mode])))
    (is (= [{:op :walk :target [0.0 65.0 0.0] :stop {:type :quarters}}] (get-in r [:nav :steps])))
    (is (= ["shelter-from-rain"] (get-in r [:ctx :agents :karlach :skills])))
    (is (= "Indo para o Quarto se abrigar" (get-in r [:ctx :agents :karlach :task])))
    (is (= ["Chuva" "Chuva detectada, soldado! Indo pro meu quarto me proteger!"] (:said r))))
  (testing "sem quarto na rota"
    (let [r (run-goal {:goal/type :weather-shelter}
                      :route []
                      :extra (fn [f] (with-redefs [composer/validate-and-register-skill! (fn [& _] {:ok? true})] (f))))]
      (is (= :idle (get-in r [:nav :mode])))
      (is (str/includes? (last (:said r)) "arrumado")))))

;; ----------------------------------------------------------------------------
;; skills
;; ----------------------------------------------------------------------------

(deftest teach-skill-ok-test
  (let [r (run-goal {:goal/type :teach-skill :goal/instruction "dance" :goal/speaker "Pace"}
                    :extra (fn [f]
                             (with-redefs [composer/synthesize-and-register-skill-from-chat!
                                           (fn [_ _ _ _] {:ok? true :skill-id :danca :skill-name "danca"
                                                          :skill-def {:skill/tree [:action :social-dance]}})]
                               (f))))]
    (is (has-cmd? r "entity.player.levelup"))
    (is (= ["danca"] (get-in r [:ctx :agents :karlach :skills])))
    (is (= ":danca (Chat OK)" (get-in r [:ctx :agents :karlach :last-learned])))
    (is (str/includes? (first (:said r)) "aprendida"))))

(deftest teach-skill-gap-dispatches-curriculum-test
  (let [dispatched (atom nil)
        r (run-goal {:goal/type :teach-skill :goal/instruction "voar" :goal/speaker "Pace"}
                    :extra (fn [f]
                             (with-redefs [composer/synthesize-and-register-skill-from-chat!
                                           (fn [& _] {:ok? false :reason :UNKNOWN_ACTION})
                                           curriculum/dispatch-curiosity-to-swe!
                                           (fn [hyp opts] (reset! dispatched [hyp opts]))]
                               (f))))]
    (is (= :motor-capability-gap (:target/category (first @dispatched))))
    (is (str/starts-with? (:task-id (second @dispatched)) "ca-gap-voar-"))
    (is (has-cmd? r "block.beacon.activate"))
    (is (= :rsi (:type (first (get-in r [:ctx :events])))))))

(deftest teach-skill-rejected-test
  (let [r (run-goal {:goal/type :teach-skill :goal/instruction "x" :goal/speaker "Pace"}
                    :extra (fn [f]
                             (with-redefs [composer/synthesize-and-register-skill-from-chat!
                                           (fn [& _] {:ok? false :reason :OTHER :message "ruim"})]
                               (f))))]
    (is (has-cmd? r "block.note_block.bass"))
    (is (str/includes? (first (:said r)) "ruim"))))

(deftest execute-dynamic-skill-test
  (testing "skill ausente"
    (let [r (run-goal {:goal/type :execute-dynamic-skill :goal/skill-id :zz-ca085-nao-existe :goal/speaker "Pace"})]
      (is (str/includes? (first (:said r)) "zz-ca085-nao-existe"))
      (is (empty? (:cmds r)))))
  (testing "skill presente: percorre a AST"
    (let [f (io/file "skills" "zz-ca085-tmp.edn")]
      (try
        (spit f (pr-str {:skill/id :zz-ca085-tmp :skill/name "Tmp"
                         :skill/tree [:sequence [:action :say-message {:text "ola"}]]}))
        (let [r (run-goal {:goal/type :execute-dynamic-skill :goal/skill-id :zz-ca085-tmp :goal/speaker "Pace"})]
          (is (has-cmd? r "SWING|Karlach"))
          (is (= ["Executando habilidade aprendida 'Tmp', Pace! 🔥" "ola"] (:said r)))
          (is (= :skill-lib (:type (first (get-in r [:ctx :events]))))))
        (finally (.delete f))))))

;; ----------------------------------------------------------------------------
;; bt: execute-tree-node!
;; ----------------------------------------------------------------------------

(defn- run-node
  ([node] (run-node node {}))
  ([node ctx0 & {:keys [nav0 speaker]}]
   (let [cmds (atom [])
         said (atom [])
         ctx  (atom ctx0)
         nst  (atom (merge {:pos [0.0 65.0 0.0]} nav0))
         res  (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                            actions/karlach-say! (fn [m] (swap! said conj m))
                            actions/query-online-players (fn [] players)
                            nav/request-path3d (fn [& _] [])]
                (bt/execute-tree-node! node ctx nst nil speaker))]
     {:res res :cmds @cmds :said @said :ctx @ctx :nav @nst})))

(def ^:private ok [:guard :cooldown-ready?])
(def ^:private ko [:guard :hostile-nearby?])

(deftest bt-invalid-node-test
  (is (= {:status :failure :reason :INVALID_NODE} (:res (run-node :oops))))
  (is (= {:status :success} (:res (run-node [:desconhecido])))))

(deftest bt-sequence-test
  (is (= {:status :success} (:res (run-node [:sequence ok ok]))))
  (is (= {:status :success} (:res (run-node [:sequence]))))
  (is (= {:status :failure} (:res (run-node [:sequence ok ko ok]))))
  (testing "devolve o ultimo resultado com :action-id"
    (is (= {:status :success :action-id :say-message}
           (:res (run-node [:sequence [:action :say-message {:text "a"}] ok]))))))

(deftest bt-selector-test
  (is (= {:status :success} (:res (run-node [:selector ko ok]))))
  (is (= {:status :success} (:res (run-node [:fallback ko ok]))))
  (is (= {:status :failure} (:res (run-node [:selector ko ko]))))
  (is (= {:status :failure} (:res (run-node [:selector])))))

(deftest bt-parallel-test
  (is (= {:status :success} (:res (run-node [:parallel ok ok]))))
  (is (= {:status :failure} (:res (run-node [:parallel ok ko]))))
  (testing "executa todos os filhos mesmo com falha"
    (is (= ["a" "b"] (:said (run-node [:parallel ko [:action :say-message {:text "a"}] [:action :say-message {:text "b"}]]))))))

(deftest bt-inverter-test
  (is (= {:status :failure} (:res (run-node [:inverter ok]))))
  (is (= {:status :success} (:res (run-node [:inverter ko])))))

(deftest bt-guard-and-cooldown-test
  (testing ":cooldown-ready? e :condition"
    (is (= {:status :success} (:res (run-node [:guard :cooldown-ready?]))))
    (is (= {:status :success} (:res (run-node [:condition :cooldown-ready?])))))
  (testing "perceptos do engine-ctx"
    (is (= {:status :success} (:res (run-node [:guard :leader-in-vehicle?] {:perception {:leader {:in-vehicle? true}}}))))
    (is (= {:status :failure} (:res (run-node [:guard :leader-in-vehicle?]))))
    (is (= {:status :success} (:res (run-node [:guard :not-self-in-vehicle?]))))
    (is (= {:status :failure} (:res (run-node [:guard :self-in-vehicle?] {} :nav0 {:in-vehicle? false}))))
    (is (= {:status :success} (:res (run-node [:guard :hostile-nearby? {:radius 5.0}]
                                              {:perception {:hostiles [{:distance 3.0}]}}))))
    (is (= {:status :failure} (:res (run-node [:guard :hostile-nearby? {:radius 2.0}]
                                              {:perception {:hostiles [{:distance 3.0}]}}))))
    (is (= {:status :success} (:res (run-node [:guard :ally-distance-exceeds? {:min-dist 5.0}]
                                              {:perception {:ally {:distance 9.0}}}))))
    ;; CA-108: fail-closed — sem snapshot de distancia ao lider o guard falha
    (is (= {:status :failure} (:res (run-node [:guard :within-mount-range?]))))
    (is (= {:status :success} (:res (run-node [:guard :within-mount-range?]
                                              {:perception {:leader {:distance 2.0}}}))))
    (is (= {:status :failure} (:res (run-node [:guard :has-active-directive?]))))
    (is (= {:status :success} (:res (run-node [:guard :has-active-directive?] {:directive :x}))))
    ;; CA-108: condicao desconhecida falha fechado (antes sucedia)
    (is (= {:status :failure} (:res (run-node [:guard :condicao-desconhecida]))))))

(deftest bt-action-test
  (testing "acoes embutidas"
    (is (= {:status :success :action-id :say-message}
           (:res (run-node [:action :say-message {:text "oi"}]))))
    (is (= ["oi"] (:said (run-node [:action :say-message {:text "oi"}]))))
    (is (= {:status :success :action-id :play-sound}
           (:res (run-node [:action :play-sound {:sound "minecraft:x"}]))))
    (is (= ["CMD|playsound minecraft:x master @a ~ ~ ~ 1.0 1.0"]
           (:cmds (run-node [:action :play-sound {:sound "minecraft:x"}])))))
  (testing "acoes que delegam ao multimethod de metas"
    (let [r (run-node [:action :social-dance] {} :speaker "Pace")]
      (is (= {:status :success :action-id :social-dance} (:res r)))
      (is (= 80 (get-in r [:nav :dance-ticks]))))
    (let [r (run-node [:action :sit-furniture] {} :speaker "Pace")]
      (is (= {:status :success :action-id :sit-furniture} (:res r)))
      (is (= "SIT|Karlach" (first (:cmds r)))))
    (let [r (run-node [:action :walk-towards-leader] {} :speaker "Pace")]
      (is (= {:status :success :action-id :walk-towards-leader} (:res r)))
      (is (= :follow (get-in r [:nav :mode])))))
  (testing "macro em engine-ctx :composite-skills"
    (let [r (run-node [:action :minha-macro] {:composite-skills {:minha-macro [:action :say-message {:text "macro"}]}})]
      (is (= ["macro"] (:said r)))
      (is (= {:status :success :action-id :say-message} (:res r)))))
  (testing "acao desconhecida cai em skills.actions/execute-action"
    (let [seen (atom nil)
          cmds (atom [])
          res  (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                             skill-actions/execute-action (fn [id args ctx] (reset! seen [id args (:speaker ctx)])
                                                            {:status :success :action-id id})]
                 (bt/execute-tree-node! [:action :zz-nova {:a 1}] (atom {}) (atom {}) nil "Pace"))]
      (is (= {:status :success :action-id :zz-nova} res))
      (is (= [:zz-nova {:a 1} "Pace"] @seen))))
  (testing "excecao vira :failure com :action"
    (let [res (with-redefs [skill-actions/execute-action (fn [& _] (throw (ex-info "x" {})))]
                (bt/execute-tree-node! [:action :zz-quebra] (atom {}) (atom {}) nil "Pace"))]
      (is (= {:status :failure :action :zz-quebra} res)))))

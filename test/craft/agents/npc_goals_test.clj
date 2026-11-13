(ns craft.agents.npc-goals-test
  "CA-091 — despacho de metas por agente: `execute-goal!` recebe
   `{:agent spec :state atom :engine-ctx atom :sanctuary-route route}` e despacha por
   `:goal/type`. Metas genericas emitem comandos com o nome do agente certo; metas de
   papel exigem a capacidade em `:agent/capabilities`; `skills.actions` usa o
   `:agent-name` do contexto."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.goals :as goals]
            [craft.agents.backend.fabric.goals.bt :as bt]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.state :as npc-state]
            [craft.agents.skills.actions :as skill-actions]
            [craft.agents.skills.composer :as composer]))

(def ^:private players [{:name "Pace" :x 10.0 :y 65.0 :z 0.0}])

(defn- run-goal
  "Executa `goal` para `agent-id` com a ponte stubada. Devolve
   {:cmds [...] :nav map :ctx map :state0 map :tp [...]}."
  [agent-id goal & {:keys [agent pos extra]}]
  (let [cmds  (atom [])
        tps   (atom [])
        ectx  (atom {})
        spec  (or agent (registry/agent-by-id agent-id))
        id    (:agent/id spec)
        st    (npc-state/agent-atom ectx id {:pos (or pos [0.0 65.0 0.0])})
        state0 @st
        run   (fn []
                (dispatch/execute-goal! goal {:agent spec :state st :engine-ctx ectx
                                              :sanctuary-route [{:type :quarters :pos [1.0 65.0 1.0]}]}))]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                  actions/query-online-players (fn [] players)
                  nav/request-path3d (fn [_ _ & _] [{:x 1.0 :y 65.0 :z 0.0}])
                  nav/expand-route-microsteps (fn [stop pos] [{:op :walk :target pos :stop stop}])]
      (if extra (extra run) (run)))
    {:cmds @cmds :tp @tps :nav @st :ctx @ectx :state0 state0}))

(defn- has-cmd? [{:keys [cmds]} s] (boolean (some #(str/includes? % s) cmds)))
(defn- no-karlach? [{:keys [cmds]}] (not-any? #(str/includes? % "Karlach") cmds))
(defn- spoke-as? [r nm] (has-cmd? r (str "{\"text\":\"" nm "\"")))

(def ^:private role-goals
  [{:goal/type :animal-rescue :goal/id "g1" :goal/name "x" :goal/entity "Panda" :goal/species "minecraft:panda"
    :goal/pen-id :pen-south-west :goal/gate-pos [-176 65 189] :goal/inside-pos [-172.0 65.0 189.0]
    :goal/outside-pos [-178.0 65.0 189.0] :goal/feed-pos [-170.0 65.0 190.0] :goal/bait-item "minecraft:bamboo"
    :goal/voice-line "Resgate!" :goal/speaker "Pace"}
   {:goal/type :breed-animals :goal/speaker "Pace"}
   {:goal/type :patrol-sanctuary :goal/voice-line "Patrulhando"}])

(deftest facade-expoe-execute-goal-test
  (is (identical? dispatch/execute-goal! goals/execute-goal!))
  (doseq [t [:follow-player :teleport-to-player :bodyguard :mining-cooperation :spatial-attention
             :inventory-inspection :drop-inventory :sit-furniture :stand-up :animal-rescue
             :breed-animals :patrol-sanctuary :teach-skill :execute-dynamic-skill :weather-shelter]]
    (is (contains? (methods dispatch/execute-goal!) t) (str t))))

(deftest metas-genericas-usam-o-nome-do-agente-test
  (doseq [[id nm] [[:picard "Picard"] [:coco "Coco"]]]
    (testing (str nm ": teleport-to-player")
      (let [r (run-goal id {:goal/type :teleport-to-player :goal/target "Pace" :goal/voice-line "Cheguei"})]
        (is (has-cmd? r (str "MOVE|" nm "|10.000|65.000|0.000")))
        (is (has-cmd? r (str "execute at " nm " run playsound minecraft:entity.enderman.teleport")))
        (is (has-cmd? r (str "execute at " nm " run particle minecraft:portal")))
        (is (spoke-as? r nm))
        (is (no-karlach? r))
        (is (= :bodyguard (get-in r [:nav :mode])))))
    (testing (str nm ": follow-player longe teleporta, perto segue")
      (let [far (run-goal id {:goal/type :follow-player :goal/target "Pace" :goal/voice-line "Indo"}
                          :pos [-100.0 65.0 0.0])
            near (run-goal id {:goal/type :follow-player :goal/target "Pace"})]
        (is (has-cmd? far (str "MOVE|" nm "|10.000")))
        (is (has-cmd? far (str "execute at " nm " run playsound")))
        (is (no-karlach? far))
        (is (= :follow (get-in near [:nav :mode])))
        (is (empty? (:cmds near)))))
    (testing (str nm ": bodyguard")
      (let [r (run-goal id {:goal/type :bodyguard :goal/target "Pace" :goal/voice-line "Guardando"})]
        (is (has-cmd? r (str "item replace entity " nm " weapon.mainhand with minecraft:netherite_axe")))
        (is (has-cmd? r (str "item replace entity " nm " weapon.offhand with minecraft:shield")))
        (is (has-cmd? r (str "SWING|" nm)))
        (is (spoke-as? r nm))
        (is (no-karlach? r))
        (is (= "Guarda-Costas de Pace" (get-in r [:ctx :agents id :task])))
        (is (nil? (get-in r [:ctx :agents :karlach])))))
    (testing (str nm ": mining-cooperation")
      (let [r (run-goal id {:goal/type :mining-cooperation :goal/target "Pace" :goal/filter "diamond"})]
        (is (has-cmd? r (str "item replace entity " nm " weapon.mainhand with minecraft:netherite_pickaxe")))
        (is (has-cmd? r (str "SWING|" nm)))
        (is (spoke-as? r nm))
        (is (no-karlach? r))
        (is (= :mining (get-in r [:nav :mode])))
        (is (str/starts-with? (get-in r [:ctx :agents id :task]) "Minerando "))))
    (testing (str nm ": spatial-attention")
      (let [r (run-goal id {:goal/type :spatial-attention :goal/entity "creeper" :goal/speaker "Pace"})]
        (is (has-cmd? r (str "MOVE|" nm "|0.000|65.000|0.000|")))
        (is (spoke-as? r nm))
        (is (no-karlach? r))))
    (testing (str nm ": inventory-inspection e drop-inventory consultam o proprio inventario")
      (let [seen (atom [])]
        (run-goal id {:goal/type :inventory-inspection :goal/speaker "Pace"}
                  :extra (fn [f] (with-redefs [actions/inspect-inventory (fn [n] (swap! seen conj [:inspect n]) {:items {} :slots-used 0})] (f))))
        (let [r (run-goal id {:goal/type :drop-inventory :goal/speaker "Pace"}
                          :extra (fn [f] (with-redefs [actions/drop-inventory-items! (fn [n _] (swap! seen conj [:drop n]) {:dropped-count 0 :items {}})] (f))))]
          (is (spoke-as? r nm))
          (is (no-karlach? r)))
        (is (= [[:inspect nm] [:drop nm]] @seen))))
    (testing (str nm ": sit-furniture e stand-up")
      (let [sit (run-goal id {:goal/type :sit-furniture :goal/speaker "Pace"})
            up  (run-goal id {:goal/type :stand-up :goal/speaker "Pace"})]
        (is (has-cmd? sit (str "SIT|" nm)))
        (is (has-cmd? up (str "STAND|" nm)))
        (is (spoke-as? sit nm))
        (is (spoke-as? up nm))
        (is (no-karlach? sit))
        (is (no-karlach? up))))))

(deftest metas-de-papel-sem-capacidade-recusam-test
  (doseq [id [:picard :coco]
          goal role-goals]
    (let [nm (:agent/name (registry/agent-by-id id))
          r  (run-goal id goal)]
      (testing (str nm " " (:goal/type goal))
        (is (seq (:cmds r)) "responde com fala de recusa")
        (is (spoke-as? r nm))
        (is (every? #(or (str/includes? % "tellraw") (str/includes? % "playsound")) (:cmds r))
            "so fala: nada de SWING, LOCATE_MOB, MOVE, item replace")
        (is (not-any? #(re-find #"SWING|LOCATE_MOB|MOVE|item replace|SANCTUARY" %) (:cmds r)))
        (is (= (:state0 r) (:nav r)) "ninguem se move")
        (is (nil? (get-in r [:ctx :agents id :task])))))))

(deftest metas-de-papel-com-capacidade-executam-test
  (let [fake {:agent/id :zed :agent/name "Zed"
              :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 65.0 0.0]}
              :agent/capabilities #{:patrol-sanctuary :breed-animals}}
        patrol (run-goal :zed {:goal/type :patrol-sanctuary :goal/voice-line "Ronda"} :agent fake)
        breed  (run-goal :zed {:goal/type :breed-animals :goal/speaker "Pace"} :agent fake)]
    (is (= :sanctuary-patrol (get-in patrol [:nav :mode])))
    (is (= "Patrulha no Santuário" (get-in patrol [:ctx :agents :zed :task])))
    (is (= :sanctuary-patrol (get-in breed [:nav :mode])))
    (is (has-cmd? breed "SWING|Zed"))
    (is (no-karlach? breed)))
  (testing "Karlach declara as tres capacidades de papel no registro"
    (let [caps (:agent/capabilities (registry/agent-by-id :karlach))]
      (is (every? caps [:animal-rescue :breed-animals :patrol-sanctuary])))
    (let [r (run-goal :karlach {:goal/type :patrol-sanctuary :goal/voice-line "Ronda"})]
      (is (= :sanctuary-patrol (get-in r [:nav :mode]))))))

(deftest wrapper-da-karlach-test
  (let [cmds (atom [])
        nst  (atom {:pos [0.0 65.0 0.0] :mode :idle})
        ectx (atom {})]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                  actions/query-online-players (fn [] players)]
      (dispatch/execute-karlach-goal! {:goal/type :stand-up :goal/speaker "Pace"} ectx nst []))
    (is (some #(str/includes? % "STAND|Karlach") @cmds))))

(deftest skills-aprendidas-gravam-em-agents-id-skills-test
  (doseq [[id nm] [[:picard "Picard"] [:coco "Coco"]]]
    (let [r (run-goal id {:goal/type :teach-skill :goal/instruction "pular tres vezes" :goal/speaker "Pace"}
                      :extra (fn [f]
                               (with-redefs [composer/synthesize-and-register-skill-from-chat!
                                             (fn [& _] {:ok? true :skill-id :pular :skill-name "pular"
                                                        :skill-def {:skill/tree [:action :social-dance]}})]
                                 (f))))]
      (is (= ["pular"] (get-in r [:ctx :agents id :skills])) nm)
      (is (= ":pular (Chat OK)" (get-in r [:ctx :agents id :last-learned])))
      (is (nil? (get-in r [:ctx :agents :karlach])))
      (is (has-cmd? r (str "SWING|" nm)))
      (is (spoke-as? r nm))
      (is (no-karlach? r)))))

(deftest skills-actions-usa-agent-name-do-contexto-test
  (doseq [nm ["Picard" "Coco"]]
    (let [cmds (atom [])
          nst  (atom {})
          ctx  {:send-cmd! #(swap! cmds conj %) :agent-name nm :nav-state nst :speaker "Pace"}]
      (doseq [a [:mount-vehicle :mount-boat :dismount-vehicle :swim-follow :detect-and-attack-dangerous-mobs]]
        (is (= :success (:status (skill-actions/execute-action a {} ctx))) (str a)))
      (is (seq @cmds))
      (is (every? #(str/includes? % nm) @cmds))
      (is (not-any? #(str/includes? % "Karlach") @cmds))))
  ;; :mount-boat (alias legado) e :dismount-vehicle com :speaker "Karlach" seguem fixados
  ;; pelos testes travados de CA-047/CA-048 (epic5) e ficam fora desta lista.
  (testing "sem :agent-name nao ha padrao escondido: falha e nao emite nada"
    (doseq [a [:mount-vehicle :swim-follow :detect-and-attack-dangerous-mobs]]
      (let [cmds (atom [])
            res  (skill-actions/execute-action a {} {:send-cmd! #(swap! cmds conj %) :speaker "Karlach"})]
        (is (= :failure (:status res)) (str a))
        (is (= :NO_AGENT_NAME (:reason res)) (str a))
        (is (empty? @cmds) (str a))))))

(deftest bt-propaga-agent-name-para-acoes-modulares-test
  (let [cmds (atom [])
        ectx (atom {})
        nst  (atom {:pos [0.0 65.0 0.0]})
        coco (registry/agent-by-id :coco)]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")]
      (is (= :success (:status (bt/execute-tree-node! [:action :mount-vehicle] ectx nst [] "Pace" coco)))))
    (is (= ["CMD|execute at Coco run ride Coco mount @e[type=boat,distance=..6,sort=nearest,limit=1]"] @cmds))))

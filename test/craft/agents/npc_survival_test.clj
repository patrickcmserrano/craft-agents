(ns craft.agents.npc-survival-test
  "CA-093 — sobrevivencia compartilhada: abrigo de chuva por `:agent/quarters` de qualquer
   agente, alimentacao de todos os agentes do registro e `combat-decision` pura por
   `:agent/combat-policy` (:fight reaproveita a preempcao, :flee corre ao aliado mais
   proximo, :none ignora). A acao de BT `:evade-towards-ally` da coco.edn executa a fuga."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.engine.combat :as combat]
            [craft.agents.backend.fabric.engine.shelter :as shelter]
            [craft.agents.backend.fabric.goals :as goals]
            [craft.agents.backend.fabric.goals.bt :as bt]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.picard :as picard]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.registry :as registry]))

(def ^:private with-quarters
  {:agent/id :picard :agent/name "Picard"
   :agent/quarters {:id :picard-quarters :pos [10.0 65.0 10.0] :seat-pos [10.0 65.0 12.0]}})

(def ^:private without-quarters
  {:agent/id :richeh :agent/name "Richeh"})

;; ----------------------------------------------------------------------------
;; Abrigo de chuva generalizado
;; ----------------------------------------------------------------------------

(deftest rain-shelter-usa-quarters-de-qualquer-agente-test
  (testing "agente com :agent/quarters se abriga ao chover, mesmo fora da patrulha"
    (let [d (shelter/rain-shelter-decision {:agent with-quarters :raining? true
                                            :mode :idle :sheltering? false})]
      (is (= :shelter (:action d)))
      (is (true? (:speak? d)))))

  (testing "abrigado e chovendo: espera; tempo abriu: retoma"
    (is (= :wait (:action (shelter/rain-shelter-decision
                           {:agent with-quarters :raining? true :mode :idle :sheltering? true}))))
    (is (= :resume (:action (shelter/rain-shelter-decision
                             {:agent with-quarters :raining? false :mode :idle :sheltering? true})))))

  (testing "sem quarters o agente nao se abriga e continua o que fazia"
    (doseq [mode [:sanctuary-patrol :idle :station]]
      (is (= :none (:action (shelter/rain-shelter-decision
                             {:agent without-quarters :raining? true :thundering? true
                              :mode mode :sheltering? false})))
          (str "modo " mode))))

  (testing "seguindo/guardando o lider, nao abandona a missao pela chuva"
    (is (= :none (:action (shelter/rain-shelter-decision
                           {:agent with-quarters :raining? true :mode :follow :sheltering? false})))))

  (testing "ceu limpo ou leitura ausente: nada a fazer (fail-safe)"
    (is (= :none (:action (shelter/rain-shelter-decision
                           {:agent with-quarters :raining? false :mode :idle :sheltering? false}))))
    (is (= :none (:action (shelter/rain-shelter-decision
                           {:agent with-quarters :mode :idle :sheltering? false})))))

  (testing "sem :agent no input, o contrato legado da Karlach segue valendo"
    (is (= :shelter (:action (shelter/rain-shelter-decision
                              {:raining? true :mode :sanctuary-patrol :sheltering? false}))))
    (is (= :none (:action (shelter/rain-shelter-decision
                           {:raining? true :mode :idle :sheltering? false}))))))

(deftest alerta-de-chuva-continua-so-do-picard-test
  (testing "a borda ceu limpo -> chuva dispara UM alerta global do Picard (nao um por agente)"
    (let [alerts (atom 0)
          nav-state (atom {:last-weather {:raining? false :thundering? false}})
          ectx (atom {})]
      (with-redefs [picard/query-minecraft-weather (fn [] {:raining? true :thundering? false})
                    picard/picard-broadcast-rain-alert! (fn [] (swap! alerts inc))]
        (shelter/check-weather! ectx nav-state))
      (is (= 1 @alerts))
      (is (= :started-raining (:weather-edge @ectx))))))

;; ----------------------------------------------------------------------------
;; Alimentacao de todos os agentes do registro
;; ----------------------------------------------------------------------------

(deftest feed-hungry-agents-cobre-o-registro-test
  (let [cmds (atom [])]
    (with-redefs [registry/agent-names (fn [] ["Karlach" "Picard" "Coco" "Richeh" "Zelda"])
                  actions/query-health-status (fn [nm]
                                                {:hp 20.0 :alive? true
                                                 :food (if (= nm "Richeh") 18 2)})
                  transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")]
      (let [fed (actions/feed-hungry-agents!)]
        (testing "todo agente do registro com fome e alimentado (inclusive o que nao estava na lista fixa)"
          (is (= #{"Karlach" "Picard" "Coco" "Zelda"} (set fed))))
        (testing "quem esta saciado nao e tocado"
          (is (not-any? #(str/includes? % "SET_VITALS|Richeh|") @cmds)))
        (testing "a escrita de vitais usa o nome do agente"
          (is (some #(str/starts-with? % "SET_VITALS|Zelda|") @cmds)))))))

(deftest auto-eat-food-vale-para-qualquer-agente-test
  (let [cmds (atom [])]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")]
      (doseq [nm (registry/agent-names)]
        (is (true? (boolean (:fed? (actions/auto-eat-food! nm)))) nm))
      (is (= (count (registry/agent-names)) (count @cmds)))
      (is (every? #(str/starts-with? % "SET_VITALS|") @cmds)))))

;; ----------------------------------------------------------------------------
;; combat-decision pura por :agent/combat-policy
;; ----------------------------------------------------------------------------

(def ^:private hostiles-near
  [{:id 7 :x 5.0 :y 65.0 :z 0.0 :distance 5.0}])

(defn- agent-with [policy & {:as traits}]
  (cond-> {:agent/id :coco :agent/name "Coco" :agent/combat-policy policy}
    traits (assoc :agent/traits traits)))

(deftest combat-decision-fight-reaproveita-preempcao-test
  (let [bot [0.0 65.0 0.0]
        leader [2.0 65.0 0.0]
        agent (agent-with :fight)]
    (is (= (combat/evaluate-combat-preemption bot leader hostiles-near false)
           (combat/combat-decision agent {:bot-pos bot :leader-pos leader
                                          :hostiles hostiles-near :in-vehicle? false})))
    (is (= :combat-strike
           (:action (combat/combat-decision agent {:bot-pos bot :leader-pos leader
                                                   :hostiles [{:id 9 :x 1.0 :y 65.0 :z 0.0 :distance 1.0}]}))))
    (is (= (combat/evaluate-combat-preemption bot leader hostiles-near true)
           (combat/combat-decision agent {:bot-pos bot :leader-pos leader
                                          :hostiles hostiles-near :in-vehicle? true})))))

(deftest combat-decision-flee-corre-ao-aliado-mais-proximo-test
  (let [bot [0.0 65.0 0.0]
        allies {:karlach [10.0 65.0 0.0] :richeh [3.0 65.0 4.0]}]
    (testing "entre os aliados de :ally-ids, escolhe o mais proximo"
      (let [d (combat/combat-decision (agent-with :flee :flee-target :karlach)
                                      {:bot-pos bot :hostiles hostiles-near :allies allies
                                       :ally-ids [:karlach :richeh]})]
        (is (= :evade-towards-ally (:action d)))
        (is (= :richeh (:ally-id d)))
        (is (= [3.0 65.0 4.0] (:target-pos d)))))

    (testing "sem :ally-ids, usa o :flee-target dos traits"
      (let [d (combat/combat-decision (agent-with :flee :flee-target :karlach)
                                      {:bot-pos bot :hostiles hostiles-near :allies allies})]
        (is (= :evade-towards-ally (:action d)))
        (is (= :karlach (:ally-id d)))
        (is (= [10.0 65.0 0.0] (:target-pos d)))))

    (testing "hostil longe (fora do raio de ameaca) nao dispara a fuga"
      (is (= :none (:action (combat/combat-decision
                             (agent-with :flee :flee-target :karlach)
                             {:bot-pos bot :allies allies
                              :hostiles [{:id 1 :x 50.0 :y 65.0 :z 0.0 :distance 50.0}]})))))

    (testing "sem hostis, sem fuga"
      (is (= :none (:action (combat/combat-decision (agent-with :flee :flee-target :karlach)
                                                    {:bot-pos bot :allies allies :hostiles []})))))

    (testing "sem posicao conhecida de aliado, nao inventa destino"
      (let [d (combat/combat-decision (agent-with :flee :flee-target :karlach)
                                      {:bot-pos bot :hostiles hostiles-near :allies {}})]
        (is (= :none (:action d)))
        (is (nil? (:target-pos d)))))))

(deftest combat-decision-none-ignora-test
  (doseq [agent [(agent-with :none) {:agent/id :picard :agent/name "Picard"}]]
    (is (= :none (:action (combat/combat-decision agent {:bot-pos [0.0 65.0 0.0]
                                                         :leader-pos [1.0 65.0 0.0]
                                                         :hostiles hostiles-near
                                                         :allies {:karlach [3.0 65.0 0.0]}})))
        (str (:agent/combat-policy agent)))))

;; ----------------------------------------------------------------------------
;; Acao de BT :evade-towards-ally (declarada na coco.edn)
;; ----------------------------------------------------------------------------

(defn- run-evade
  [& {:keys [hostiles agents]}]
  (let [coco  (registry/agent-by-id :coco)
        cmds  (atom [])
        ectx  (atom {:perception {:hostiles hostiles} :agents agents})
        st    (atom {:pos [0.0 65.0 0.0]})
        res   (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                            actions/query-online-players
                            (fn [] [{:name "Karlach" :x 10.0 :y 65.0 :z 0.0}
                                    {:name "Richeh" :x 3.0 :y 65.0 :z 4.0}])
                            nav/request-path3d (fn [_ _ & _] [{:x 1.0 :y 65.0 :z 0.0}])]
                (bt/execute-tree-node!
                 [:action :evade-towards-ally {:ally-ids [:karlach :richeh] :safe-dist 4.0}]
                 ectx st [] "Pace" coco))]
    {:res res :cmds @cmds :nav @st}))

(deftest coco-declara-a-acao-evade-towards-ally-test
  (let [coco (registry/agent-by-id :coco)]
    (is (= :flee (:agent/combat-policy coco)))
    (is (some #{:evade-towards-ally} (flatten (:bt/root coco))))))

(deftest bt-evade-towards-ally-foge-para-o-aliado-mais-proximo-test
  (let [{:keys [res nav]} (run-evade :hostiles hostiles-near
                                     :agents {:karlach {:pos [10.0 65.0 0.0]}
                                              :richeh  {:pos [3.0 65.0 4.0]}})]
    (is (= :success (:status res)))
    (is (= :evade-towards-ally (:action-id res)))
    (is (= :follow (:mode nav)) "a fuga reaproveita o follow rumo ao aliado")
    (is (= "Richeh" (:follow-target nav)) "aliado mais proximo, nao o :flee-target distante")))

(deftest bt-evade-towards-ally-sem-aliado-falha-test
  (let [{:keys [res nav]} (run-evade :hostiles hostiles-near :agents {})]
    (is (= :failure (:status res)) "sem posicao de aliado a selector segue para o proximo ramo")
    (is (nil? (:mode nav)))))

(deftest facade-de-goals-carrega-test
  (is (some? goals/execute-tree-node!)))

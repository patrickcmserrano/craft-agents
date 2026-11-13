(ns craft.agents.engine-characterization-test
  "CA-084: caracterizacao do engine.clj. Fixa as decisoes puras do loop de 20Hz
   (dado estado + leitura do mundo -> comando de ponte + estado novo) chamando os
   nomes NOVOS nos sub-namespaces de backend/fabric/engine/, e o contrato da
   fachada engine.clj (reexporta todos os nomes publicos de antes)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.engine :as engine]
            [craft.agents.backend.fabric.engine.atelier-tick :as atelier-tick]
            [craft.agents.backend.fabric.engine.chat :as chat]
            [craft.agents.backend.fabric.engine.combat :as combat]
            [craft.agents.backend.fabric.engine.follow :as follow]
            [craft.agents.backend.fabric.engine.karlach-tick :as karlach-tick]
            [craft.agents.backend.fabric.engine.patrol :as patrol]
            [craft.agents.backend.fabric.engine.picard-tick :as picard-tick]
            [craft.agents.backend.fabric.engine.shelter :as shelter]
            [craft.agents.backend.fabric.engine.watchdog :as ewd]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.picard :as picard]
            [craft.agents.backend.fabric.transport :as transport]))

(defn- capture
  "Roda `f` com a ponte stubada; devolve os comandos enviados."
  [f]
  (let [sent (atom [])]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! sent conj c) "OK")]
      (f))
    @sent))

(defn- mob [x dist] {:id 7 :type "minecraft:zombie" :x x :y 64.0 :z 0.0 :distance dist})

;; ----------------------------------------------------------------------------
;; combat
;; ----------------------------------------------------------------------------

(deftest combat-preemption-decisions-test
  (testing "no barco: so golpeia mob colado (<= 3.5m), senao fica passiva"
    (let [d (combat/evaluate-combat-preemption [0.0 64.0 0.0] [5.0 64.0 0.0] [(mob 2.0 2.0)] true)]
      (is (= :combat-strike (:action d)))
      (is (= 7 (:target-id d)))
      (is (= [2.0 64.0 0.0] (:target-pos d)))
      (is (true? (:clear-follow-path? d))))
    (is (= {:action :remain-mounted-passive :target-pos nil :clear-follow-path? false}
           (combat/evaluate-combat-preemption [0.0 64.0 0.0] [5.0 64.0 0.0] [(mob 9.0 9.0)] true))))
  (testing "em terra: tether, intercept, strike e follow"
    (is (= :tether-snap-recall
           (:action (combat/evaluate-combat-preemption [0.0 64.0 0.0] [14.0 64.0 0.0] [] false))))
    (is (= :combat-intercept
           (:action (combat/evaluate-combat-preemption [0.0 64.0 0.0] [10.0 64.0 0.0] [(mob 7.0 7.0)] false))))
    (is (= :combat-strike
           (:action (combat/evaluate-combat-preemption [0.0 64.0 0.0] [10.0 64.0 0.0] [(mob 3.0 3.0)] false))))
    (is (= {:action :follow-player :target-pos [10.0 64.0 0.0] :clear-follow-path? false}
           (combat/evaluate-combat-preemption [0.0 64.0 0.0] [10.0 64.0 0.0] [] false)))))

(deftest combat-cooldowns-test
  (is (= 20 combat/strike-cooldown-ticks))
  (is (= 600 combat/battle-cry-cooldown-ticks))
  (is (true? (combat/strike-ready? nil 5)))
  (is (false? (combat/strike-ready? 100 119)))
  (is (true? (combat/strike-ready? 100 120)))
  (is (true? (combat/battle-cry-ready? nil 5)))
  (is (false? (combat/battle-cry-ready? 100 699)))
  (is (true? (combat/battle-cry-ready? 100 700))))

;; ----------------------------------------------------------------------------
;; shelter
;; ----------------------------------------------------------------------------

(deftest shelter-decisions-test
  (is (= {:action :shelter :speak? true}
         (shelter/rain-shelter-decision {:raining? true :mode :sanctuary-patrol :sheltering? false})))
  (is (= {:action :wait :speak? false}
         (shelter/rain-shelter-decision {:thundering? true :mode :sanctuary-patrol :sheltering? true})))
  (is (= {:action :resume :speak? true}
         (shelter/rain-shelter-decision {:raining? false :mode :sanctuary-patrol :sheltering? true})))
  (is (= {:action :none :speak? false}
         (shelter/rain-shelter-decision {:mode :sanctuary-patrol :sheltering? false})))
  (is (= {:action :none :speak? false}
         (shelter/rain-shelter-decision {:raining? true :mode :follow :sheltering? false}))))

(deftest weather-transition-edges-test
  (is (= :started-raining (shelter/weather-transition {:raining? false} {:raining? true})))
  (is (= :stopped-raining (shelter/weather-transition {:thundering? true} {:raining? false :thundering? false})))
  (is (= :none (shelter/weather-transition {:raining? true} {:thundering? true})))
  (is (= :none (shelter/weather-transition nil nil))))

(deftest shelter-route-steps-test
  (is (nil? (shelter/shelter-route-steps nil [0.0 64.0 0.0])))
  (let [steps (shelter/shelter-route-steps {:type :quarters :pos [10.0 64.0 10.0]} [0.0 64.0 0.0])]
    (is (vector? steps))
    (is (seq steps))
    (is (= :wait (:op (peek steps))))
    (is (str/includes? (:label (peek steps)) "Descansando"))
    (is (= 1 (count (filter #(some-> (:label %) (str/includes? "Descansando")) steps)))
        "corta logo apos o descanso: sem retorno a ronda")))

(deftest shelter-seat-commands-test
  (is (empty? (capture #(shelter/sit-karlach-at-quarters! nil))))
  (is (empty? (capture #(shelter/sit-karlach-at-quarters! [1 2]))))
  (is (seq (capture #(shelter/sit-karlach-at-quarters! [1.0 64.0 2.0]))))
  (is (seq (capture shelter/stand-karlach!))))

;; ----------------------------------------------------------------------------
;; watchdog
;; ----------------------------------------------------------------------------

(deftest follow-watchdog-escalation-test
  (let [nav-state (atom {})
        st0 {:pos [0.0 64.0 0.0] :yaw 0.0 :follow-path [[1 2 3]]}
        leader [20.0 64.0 0.0]
        sent (atom [])]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! sent conj c) "OK")]
      (testing "tick de armamento: nada acontece, st intacto"
        (is (= st0 (ewd/follow-watchdog-step! nav-state st0 "pace" leader 0)))
        (is (= :follow (:kind (:watchdog @nav-state))))
        (is (nil? (:fired (:watchdog @nav-state)))))
      (testing "40 ticks parado: replan limpa o caminho, uma vez so"
        (let [st1 (ewd/follow-watchdog-step! nav-state st0 "pace" leader 40)]
          (is (= [] (:follow-path st1)))
          (is (= :replan (:fired (:watchdog @nav-state))))
          (is (= st1 (ewd/follow-watchdog-step! nav-state st1 "pace" leader 41))
              "mesmo nivel de escalada nao age de novo")))
      (testing "160 ticks parado: teleporta ao lider via MOVE"
        (let [st2 (ewd/follow-watchdog-step! nav-state st0 "pace" leader 160)]
          (is (= leader (:pos st2)))
          (is (= [] (:follow-path st2)))
          (is (= leader (:pos @nav-state)))
          (is (some #(str/starts-with? % "MOVE|Karlach|20.000|64.000|0.000|") @sent))
          (is (not-any? #(str/starts-with? % "CMD|tp ") @sent)))))))

(deftest patrol-watchdog-fired-test
  (let [nav-state (atom {})
        step {:op :walk :target [10.0 64.0 0.0]}
        pos [0.0 64.0 0.0]]
    (is (false? (ewd/patrol-watchdog-fired! nav-state {} step pos 0)))
    (is (= :patrol (:kind (:watchdog @nav-state))))
    (is (false? (ewd/patrol-watchdog-fired! nav-state @nav-state step pos 399)))
    (is (true? (ewd/patrol-watchdog-fired! nav-state @nav-state step pos 400)))
    (is (nil? (:watchdog @nav-state)) "dispara e limpa o watchdog")))

(deftest teleport-to-leader-lives-in-engine-watchdog-test
  (let [nav-state (atom {:pos [0.0 64.0 0.0] :follow-path [[1 2 3]]})
        sent (capture #(ewd/teleport-karlach-to-leader! nav-state "pace" [5.0 70.0 5.0]))]
    (is (some #(str/starts-with? % "MOVE|Karlach|5.000|70.000|5.000|") sent))
    (is (= [5.0 70.0 5.0] (:pos @nav-state)))
    (is (= [] (:follow-path @nav-state)))))

;; ----------------------------------------------------------------------------
;; picard
;; ----------------------------------------------------------------------------

(deftest picard-walk-tick-test
  (let [r (picard-tick/picard-walk-tick {:pos [0.0 64.0 0.0] :yaw 0.0} {:pos [10.0 64.0 0.0]})]
    (is (= [0.16 64.0 0.0] (:pos r)))
    (is (false? (:arrived? r)))
    (is (== (nav/compute-yaw 10.0 0.0) (:yaw r))))
  (is (true? (:arrived? (picard-tick/picard-walk-tick {:pos [9.9 64.0 0.0] :yaw 0.0}
                                                      {:pos [10.0 64.0 0.0]})))))

(deftest picard-follow-decision-test
  (let [snap (picard-tick/picard-follow-decision [0.0 64.0 0.0] {:x 40.0 :y 70.0 :z 0.0})
        appr (picard-tick/picard-follow-decision [0.0 64.0 0.0] {:x 10.0 :y 64.0 :z 0.0})
        hold (picard-tick/picard-follow-decision [0.0 64.0 0.0] {:x 2.0 :y 64.0 :z 0.0})]
    (is (= :snap (:action snap)))
    (is (= [40.0 70.0 0.0] (:pos snap)))
    (is (= :approach (:action appr)))
    (is (= [0.21 64.0 0.0] (:pos appr)))
    (is (== (nav/compute-yaw 10.0 0.0) (:yaw appr)))
    (is (= :hold (:action hold)))))

(deftest tick-picard-station-cycle-test
  (let [station (first picard/picard-stations)
        [sx sy sz] (:pos station)
        picard-state (atom {:pos [(double sx) (double sy) (double sz)] :yaw 0.0 :pitch 0.0
                            :mode :bridge-command :station-idx 0 :phase :walk :wait-left 0})
        sent (capture #(picard-tick/tick-picard! {:picard-state picard-state}))]
    (testing "chegou na estacao: MOVE final e entra em :wait"
      (is (some #(str/starts-with? % "MOVE|Picard|") sent))
      (is (= :wait (:phase @picard-state)))
      (is (= (:wait-ticks station 120) (:wait-left @picard-state))))
    (testing "ultimo tick de espera: avanca a estacao e volta a :walk"
      (swap! picard-state assoc :wait-left 1)
      (is (empty? (capture #(picard-tick/tick-picard! {:picard-state picard-state}))))
      (is (= :walk (:phase @picard-state)))
      (is (= (mod 1 (count picard/picard-stations)) (:station-idx @picard-state))))))

(deftest tick-picard-follow-without-leader-test
  (with-redefs [actions/query-online-players (fn [] [])]
    (let [picard-state (atom {:pos [0.0 64.0 0.0] :mode :follow :follow-target "pace"})]
      (capture #(picard-tick/tick-picard! {:picard-state picard-state}))
      (is (= :bridge-command (:mode @picard-state))))))

;; ----------------------------------------------------------------------------
;; follow / barco
;; ----------------------------------------------------------------------------

(deftest boat-transition-test
  (testing "lider desembarcou e ela segue no barco -> STAND"
    (is (= {:commands ["STAND|Karlach"] :in-vehicle? false}
           (follow/boat-transition {:actual-sitting? true :target-sitting? false :dist 9.0}))))
  (testing "lider embarcou e ela esta perto, fora do barco -> monta"
    (let [r (follow/boat-transition {:actual-sitting? false :target-sitting? true :dist 3.0})]
      (is (true? (:in-vehicle? r)))
      (is (= 1 (count (:commands r))))
      (is (str/includes? (first (:commands r)) "ride Karlach mount"))))
  (testing "lider embarcou mas ela esta longe -> so registra que nao esta no barco"
    (is (= {:commands [] :in-vehicle? false}
           (follow/boat-transition {:actual-sitting? false :target-sitting? true :dist 5.0}))))
  (testing "ambos no barco -> segue embarcada, sem comando"
    (is (= {:commands [] :in-vehicle? true}
           (follow/boat-transition {:actual-sitting? true :target-sitting? true :dist 1.0}))))
  (testing "leitura ausente (ponte caida) -> nada muda"
    (is (= {:commands []}
           (follow/boat-transition {:actual-sitting? nil :target-sitting? true :dist 1.0})))))

(deftest follow-tick-decision-test
  (testing "lider proximo, sem hostis: segue"
    (let [d (follow/follow-tick-decision {:pos [0.0 64.0 0.0] :leader [10.0 64.0 0.0]
                                          :in-vehicle? false :hostiles []})]
      (is (false? (:teleport? d)))
      (is (= 10.0 (:dist d)))
      (is (== (nav/compute-yaw 10.0 0.0) (:yaw d)))
      (is (= :follow-player (:action (:combat d))))))
  (testing "lider a mais de 48m (3D): teleporte de aproximacao"
    (is (true? (:teleport? (follow/follow-tick-decision {:pos [0.0 64.0 0.0] :leader [60.0 64.0 0.0]
                                                         :in-vehicle? false :hostiles []}))))
    (is (true? (:teleport? (follow/follow-tick-decision {:pos [0.0 64.0 0.0] :leader [0.0 120.0 40.0]
                                                         :in-vehicle? false :hostiles []})))))
  (testing "hostil colado: a decisao de combate vem junto"
    (is (= :combat-strike
           (:action (:combat (follow/follow-tick-decision {:pos [0.0 64.0 0.0] :leader [10.0 64.0 0.0]
                                                           :in-vehicle? false :hostiles [(mob 3.0 3.0)]})))))))

(deftest follow-hold-and-lost-test
  (is (true? (follow/follow-hold? 2.6 :follow-player)))
  (is (false? (follow/follow-hold? 2.7 :follow-player)))
  (is (false? (follow/follow-hold? 1.0 :tether-snap-recall)))
  (is (= {:action :wait :follow-lost-ticks 1} (follow/follow-lost-decision {})))
  (is (= {:action :wait :follow-lost-ticks 60} (follow/follow-lost-decision {:follow-lost-ticks 59})))
  (is (= {:action :give-up :follow-lost-ticks 0} (follow/follow-lost-decision {:follow-lost-ticks 60}))))

;; ----------------------------------------------------------------------------
;; patrulha
;; ----------------------------------------------------------------------------

(deftest patrol-step-decision-test
  (let [walk {:op :walk :target [10.0 64.0 0.0]}]
    (testing "longe, sem guia: avanca a 0.245"
      (is (= {:action :advance :speed 0.245}
             (patrol/patrol-step-decision {:cur-step walk :pos [0.0 64.0 0.0] :leashed? false}))))
    (testing "conduzindo animal: 0.165, e 0.090 se o animal ficou > 7.5m"
      (let [led (assoc walk :species "naturalist:capybara")]
        (is (= {:action :advance :speed 0.165}
               (patrol/patrol-step-decision {:cur-step led :pos [0.0 64.0 0.0] :leashed? true
                                             :leash-info {:leashed? true :distance 3.0}})))
        (is (= {:action :advance :speed 0.090}
               (patrol/patrol-step-decision {:cur-step led :pos [0.0 64.0 0.0] :leashed? true
                                             :leash-info {:leashed? true :distance 8.0}})))))
    (testing "guia arrebentou: prioridade sobre chegada/avanco"
      (is (= {:action :leash-broken}
             (patrol/patrol-step-decision {:cur-step (assoc walk :species "x") :pos [10.0 64.0 0.0]
                                           :leashed? true :leash-info {:leashed? false}}))))
    (testing "chegou: arrive, sem pausa de curva quando solta"
      (is (= {:action :arrive :corner-pause? nil}
             (patrol/patrol-step-decision {:cur-step walk :pos [10.1 64.0 0.0] :leashed? false}))))
    (testing "chegou conduzindo animal e a proxima perna dobra > 30 graus: pausa de curva"
      (let [r (patrol/patrol-step-decision {:cur-step (assoc walk :species "x") :pos [10.1 64.0 0.0]
                                            :leashed? true :leash-info {:leashed? true :distance 2.0}
                                            :next-step {:op :walk :target [10.0 64.0 10.0]}})]
        (is (= :arrive (:action r)))
        (is (true? (:corner-pause? r)))))))

;; ----------------------------------------------------------------------------
;; karlach-tick / atelier-tick / chat
;; ----------------------------------------------------------------------------

(deftest tick-karlach-dance-test
  (let [nav-state (atom {:pos [1.0 2.0 3.0] :yaw 0.0 :mode :sanctuary-patrol :dance-ticks 3})
        ctx (atom {})
        env {:engine-ctx-atom ctx :nav-state nav-state
             :picard-state (atom {:pos [-65.0 145.0 101.0] :status "Ponte"})
             :coco-state (atom {:status "c" :pos [0 0 0]})
             :richeh-state (atom {:status "r" :pos [0 0 0]})
             :tick 1}
        sent (capture #(karlach-tick/tick-karlach! env))]
    (is (= ["MOVE|Karlach|1.000|2.000|3.000|32.0|0.0|false|false"] sent))
    (is (= 2 (:dance-ticks @nav-state)))
    (is (== 32.0 (:yaw @nav-state)))
    (is (= 1 (:tick @ctx)))
    (is (= "dancing 🔥" (get-in @ctx [:agents :karlach :status])))
    (is (= {:legs {:owner :dance} :hands {:owner :dance} :gaze {:owner :dance}} (:leases @ctx)))
    (is (= :sanctuary-patrol (:karlach-mode @ctx)))))

(deftest tick-and-thread-entry-points-exist-test
  (is (fn? atelier-tick/tick-atelier!))
  (is (fn? chat/start-chat-listener!))
  (is (fn? chat/start-item-poll!))
  (is (fn? chat/poll-bridge-chat!)))

;; ----------------------------------------------------------------------------
;; fachada
;; ----------------------------------------------------------------------------

(deftest engine-facade-reexports-all-public-names-test
  (doseq [sym '[start-live-karlach-loop! picard-walk-tick strike-cooldown-ticks strike-ready?
                battle-cry-cooldown-ticks battle-cry-ready? evaluate-combat-preemption
                rain-shelter-decision weather-transition shelter-route-steps
                sit-karlach-at-quarters! stand-karlach! teleport-karlach-to-leader!
                follow-watchdog-step! patrol-watchdog-fired!]]
    (is (some? (ns-resolve 'craft.agents.backend.fabric.engine sym)) (str sym)))
  (is (identical? combat/evaluate-combat-preemption engine/evaluate-combat-preemption))
  (is (identical? shelter/weather-transition engine/weather-transition))
  (is (identical? ewd/follow-watchdog-step! engine/follow-watchdog-step!))
  (is (identical? picard-tick/picard-walk-tick engine/picard-walk-tick)))

(defn- line-count [path]
  (with-open [r (io/reader path)] (count (line-seq r))))

(deftest engine-size-budget-test
  (let [dir "src/clj/craft/agents/backend/fabric/engine"
        subs (filter #(str/ends-with? (.getName ^java.io.File %) ".clj") (.listFiles (io/file dir)))]
    (is (< (line-count "src/clj/craft/agents/backend/fabric/engine.clj") 150))
    (is (seq subs))
    (doseq [f subs]
      (is (<= (line-count f) 400) (str (.getName ^java.io.File f) " passou de 400 linhas")))))

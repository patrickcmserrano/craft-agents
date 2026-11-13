(ns craft.agents.npc-locomotion-test
  "CA-092 — locomocao compartilhada: follow, watchdog, teleporte de recuperacao e
   barco parametrizados pelo agente (nao so a Karlach). Mundo de
   fixtures/world_fixture.clj; ponte stubada."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.engine.follow :as follow]
            [craft.agents.backend.fabric.engine.karlach-tick :as karlach-tick]
            [craft.agents.backend.fabric.engine.watchdog :as ewd]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.fixtures.world-fixture :as wf]
            [craft.agents.npc.state :as npc-state]))

(def ^:private world (wf/make-flat-world-state 64))
(def ^:private ground-y (+ 1.0 (:floor-y @world)))

(defn- straight-path [[x _ z] [tx _ tz]]
  (let [n 6]
    (mapv (fn [i] {:x (+ (double x) (* (/ i n) (- (double tx) (double x))))
                   :y ground-y
                   :z (+ (double z) (* (/ i n) (- (double tz) (double z))))
                   :action :WALK})
          (range 1 (inc n)))))

(defn- capture [f]
  (let [sent (atom [])]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! sent conj c) "OK")]
      (f))
    @sent))

;; ----------------------------------------------------------------------------
;; follow-tick puro
;; ----------------------------------------------------------------------------

(deftest follow-tick-moves-each-agent-test
  (doseq [nm ["Picard" "Coco" "Karlach"]]
    (let [leader [12.5 ground-y 0.5]
          r (follow/follow-tick {:agent-name nm :pos [0.5 ground-y 0.5] :leader leader
                                 :path (straight-path [0.5 ground-y 0.5] leader)
                                 :speed 0.21 :sprint false :sneak false})
          cmd (first (:commands r))]
      (testing nm
        (is (= 1 (count (:commands r))))
        (is (str/starts-with? cmd (str "MOVE|" nm "|")))
        (when-not (= nm "Karlach")
          (is (not (str/includes? cmd "Karlach")) "nao vaza o nome da Karlach"))
        (is (> (first (:pos r)) 0.5) "avancou em direcao ao lider")
        (is (false? (:holding? r)))))))

(deftest follow-tick-holds-near-leader-test
  (let [r (follow/follow-tick {:agent-name "Coco" :pos [0.5 ground-y 0.5] :leader [2.0 ground-y 0.5]
                               :path [] :speed 0.21 :sprint false :sneak true})]
    (is (true? (:holding? r)))
    (is (= [0.5 ground-y 0.5] (:pos r)))
    (is (str/starts-with? (first (:commands r)) "MOVE|Coco|"))
    (is (str/ends-with? (first (:commands r)) "|false|true") "espelha o agachar do lider")))

;; ----------------------------------------------------------------------------
;; barco
;; ----------------------------------------------------------------------------

(deftest boat-transition-uses-agent-name-test
  (is (= {:commands ["STAND|Picard"] :in-vehicle? false}
         (follow/boat-transition {:agent-name "Picard" :actual-sitting? true :target-sitting? false :dist 1.0})))
  (let [r (follow/boat-transition {:agent-name "Coco" :actual-sitting? false :target-sitting? true :dist 2.0})]
    (is (true? (:in-vehicle? r)))
    (is (str/includes? (first (:commands r)) "execute at Coco run ride Coco mount"))
    (is (not (str/includes? (first (:commands r)) "Karlach"))))
  (testing "default continua Karlach"
    (is (= ["STAND|Karlach"]
           (:commands (follow/boat-transition {:actual-sitting? true :target-sitting? false :dist 1.0}))))))

;; ----------------------------------------------------------------------------
;; watchdog e teleporte
;; ----------------------------------------------------------------------------

(deftest recovery-teleport-uses-agent-name-test
  (let [nav-state (atom {:pos [0.0 ground-y 0.0] :follow-path [[1 2 3]]})
        sent (capture #(ewd/teleport-agent-to-leader! nav-state "Picard" "pace" [30.0 ground-y 4.0]))]
    (is (some #(str/starts-with? % "MOVE|Picard|30.000|") sent))
    (is (not-any? #(or (str/includes? % "Karlach") (str/starts-with? % "CMD|tp ")) sent))
    (is (= [30.0 ground-y 4.0] (:pos @nav-state)))
    (is (= [] (:follow-path @nav-state)))))

(defn- run-watchdog [nav-state nm leader ticks]
  (capture
   (fn []
     (loop [t 0 st @nav-state]
       (when (<= t ticks)
         ;; o watchdog viaja em `st`: reinjeta o de nav-state a cada tick
         (let [st' (ewd/follow-watchdog-step! nav-state (assoc st :watchdog (:watchdog @nav-state))
                                              "pace" leader t nm)]
           (recur (inc t) st')))))))

(deftest watchdogs-fire-independently-per-agent-test
  (let [leader [200.0 ground-y 0.0]
        picard (atom {:pos [0.0 ground-y 0.0] :follow-path [[1 2 3]]})
        coco   (atom {:pos [0.0 ground-y 0.0] :follow-path [[1 2 3]]})
        sent-p (run-watchdog picard "Picard" leader 600)
        coco0  @coco]
    (testing "Picard parado e longe: teleporta com MOVE|Picard"
      (is (some #(str/starts-with? % "MOVE|Picard|200.000|") sent-p))
      (is (not-any? #(str/includes? % "Coco") sent-p))
      (is (= leader (:pos @picard))))
    (testing "o estado da Coco nao foi tocado"
      (is (= coco0 @coco)))
    (testing "Coco dispara so pelo proprio estado, com o proprio nome"
      (let [sent-c (run-watchdog coco "Coco" leader 600)]
        (is (some #(str/starts-with? % "MOVE|Coco|200.000|") sent-c))
        (is (not-any? #(str/includes? % "Picard") sent-c))))))

(deftest follow-watchdog-default-is-karlach-test
  (let [nav-state (atom {:pos [0.0 ground-y 0.0]})
        sent (capture
              #(loop [t 0 st @nav-state]
                 (when (<= t 600)
                   (recur (inc t) (ewd/follow-watchdog-step! nav-state (assoc st :watchdog (:watchdog @nav-state))
                                                             "pace" [200.0 ground-y 0.0] t)))))]
    (is (some #(str/starts-with? % "MOVE|Karlach|200.000|") sent))))

;; ----------------------------------------------------------------------------
;; tick-follow! por agente
;; ----------------------------------------------------------------------------

(defn- follow-env [ectx]
  {:engine-ctx-atom ectx
   :tick 3
   :make-agents-map (fn [_ _] {})
   :make-karlach-card (fn [s p] {:status s :pos p})
   :picard-card {}})

(deftest tick-follow-moves-picard-test
  (let [ectx (atom {})
        st   (npc-state/agent-atom ectx :picard {:pos [0.5 ground-y 0.5] :mode :follow :follow-target "pace"})
        sent (atom [])]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! sent conj c) "OK")
                  actions/query-online-players
                  (fn [] [{:name "pace" :x 12.5 :y ground-y :z 0.5}
                          {:name "Picard" :x 0.5 :y ground-y :z 0.5}])
                  nav/request-path3d (fn [from to & _] (straight-path from to))]
      (follow/tick-follow! (follow-env ectx) :picard))
    (is (some #(str/starts-with? % "MOVE|Picard|") @sent))
    (is (not-any? #(str/includes? % "Karlach") @sent))
    (is (> (first (:pos @st)) 0.5))))

(deftest tick-karlach-drives-other-agents-in-follow-mode-test
  (let [ectx (atom {})
        nav  (npc-state/agent-atom ectx :karlach {:pos [0.5 ground-y 0.5] :mode :idle :yaw 0.0})
        _    (npc-state/agent-atom ectx :coco {:pos [0.5 ground-y 0.5] :mode :follow :follow-target "pace"})
        sent (atom [])]
    (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! sent conj c) "OK")
                  actions/query-online-players
                  (fn [] [{:name "pace" :x 12.5 :y ground-y :z 0.5}
                          {:name "Coco" :x 0.5 :y ground-y :z 0.5}])
                  nav/request-path3d (fn [from to & _] (straight-path from to))]
      (karlach-tick/tick-karlach! {:engine-ctx-atom ectx :nav-state nav :tick 3
                                   :picard-state (atom {:pos [0.0 65.0 0.0]})
                                   :coco-state (atom {}) :richeh-state (atom {})}))
    (is (some #(str/starts-with? % "MOVE|Coco|") @sent))))

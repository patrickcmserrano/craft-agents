(ns craft.agents.npc-perception-tick-test
  "CA-100 — Snapshot unico de percepcao por tick (`craft.agents.npc.perception-tick`)
   e metricas de tick `:tick-ms` / `:cmds-per-tick` / `:tick-p95-ms` (`SUG-02`)."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric :as fabric]
            [craft.agents.backend.fabric.engine.bt-tick :as bt-tick]
            [craft.agents.backend.fabric.engine.karlach-tick :as karlach-tick]
            [craft.agents.backend.fabric.goals.bt-actions :as bt-actions]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.state :as npc-state]
            [craft.agents.studio.server :as studio]))

(defn- resolve-pt [sym]
  (try
    (require 'craft.agents.npc.perception-tick)
    (ns-resolve 'craft.agents.npc.perception-tick sym)
    (catch Throwable _ nil)))

(def ^:private sample-players-wire
  (str "OK|"
       "pace,10.0,65.0,10.0,true,false,false;"
       "Karlach,-175.0,65.0,180.0,false,false,false;"
       "Picard,-65.0,145.0,101.0,false,false,false;"
       "Coco,-30.0,66.0,190.0,false,false,false;"
       "Richeh,-30.0,66.0,192.0,false,true,false;"
       "Antigravity,-26.0,68.0,-48.0,false,false,false"))

(deftest capture-snapshot-single-players-call-and-structure-test
  (let [capture-snapshot! (resolve-pt 'capture-snapshot!)]
    (is (fn? capture-snapshot!) "craft.agents.npc.perception-tick/capture-snapshot! deve existir")
    (when (fn? capture-snapshot!)
      (let [sent (atom [])
            ctx  (atom {:tick 42})]
        (doseq [id [:karlach :picard :coco :richeh :antigravity]]
          (npc-state/agent-atom ctx id))
        (with-redefs [transport/send-bridge-cmd!
                      (fn [cmd]
                        (swap! sent conj cmd)
                        (if (= cmd "PLAYERS")
                          sample-players-wire
                          "OK"))]
          (let [snap1 (capture-snapshot! ctx {:tick 42 :captured-at-ms 1700000000000})
                snap2 (capture-snapshot! ctx {:tick 42 :captured-at-ms 1700000000050})]
            (testing "no maximo 1 chamada PLAYERS por invocacao e com cache no mesmo tick"
              (is (= 1 (count (filter #(= % "PLAYERS") @sent)))
                  "Deve realizar no maximo 1 chamada PLAYERS no mesmo tick para todos os 5 agentes")
              (is (= snap1 snap2) "Segunda chamada no mesmo tick reutiliza o snapshot em cache"))

            (testing "estrutura do snapshot {:tick N :captured-at-ms T :players ... :by-agent {id {:self ... :leader ... :hostiles ...}}}"
              (is (= 42 (:tick snap1)))
              (is (= 1700000000000 (:captured-at-ms snap1)))
              (is (vector? (:players snap1)))
              (is (= 6 (count (:players snap1))))
              (is (map? (:by-agent snap1)))
              (doseq [id [:karlach :picard :coco :richeh :antigravity]
                      :let [entry (get-in snap1 [:by-agent id])]]
                (testing (str "agente " id)
                  (is (map? entry))
                  (is (contains? entry :self))
                  (is (contains? entry :leader))
                  (is (contains? entry :hostiles))
                  (is (vector? (get-in entry [:self :pos])))
                  (is (= "pace" (get-in entry [:leader :name])))
                  (is (number? (get-in entry [:leader :distance])))
                  (is (vector? (:hostiles entry))))))))))))

(deftest agent-perception-and-inject-perception-for-bt-guards-test
  (let [capture-snapshot!  (resolve-pt 'capture-snapshot!)
        agent-perception   (resolve-pt 'agent-perception)
        inject-perception! (resolve-pt 'inject-perception!)]
    (is (fn? capture-snapshot!))
    (is (fn? agent-perception) "craft.agents.npc.perception-tick/agent-perception deve existir")
    (is (fn? inject-perception!) "craft.agents.npc.perception-tick/inject-perception! deve existir")
    (when (and (fn? capture-snapshot!) (fn? agent-perception) (fn? inject-perception!))
      (let [ctx (atom {:tick 7
                       :perception {:blocks [{:id "minecraft:lantern" :distance 2.5}]}})
            coco-st (npc-state/agent-atom ctx :coco {:pos [-30.0 66.0 190.0]})]
        (with-redefs [transport/send-bridge-cmd!
                      (fn [cmd]
                        (if (= cmd "PLAYERS") sample-players-wire "OK"))]
          (let [snap (capture-snapshot! ctx {:tick 7})
                coco-p (agent-perception snap :coco)]
            (testing "agent-perception extrai a visao indexada do agente"
              (is (map? coco-p))
              (is (= [-30.0 66.0 190.0] (get-in coco-p [:self :pos])))
              (is (= 2.0 (get-in coco-p [:allies :richeh :distance]))))

            (testing "inject-perception! atualiza :perception e :perception-by-agent para consumo de bt_actions/guard-holds?"
              (inject-perception! ctx :coco snap)
              (is (map? (:perception @ctx)))
              (is (map? (:perception-by-agent @ctx)))
              (is (= 2.0 (get-in @ctx [:perception-by-agent :coco :allies :richeh :distance])))
              (is (= 2.0 (get-in @ctx [:perception :allies :richeh :distance])))
              (let [world (bt-actions/guard-world ctx coco-st :coco)]
                (is (true? (bt-actions/guard-holds? :ally-within-range?
                                                    {:ally-id :richeh :max-dist 3.5}
                                                    world)))
                (is (false? (bt-actions/guard-holds? :ally-within-range?
                                                     {:ally-id :richeh :max-dist 1.0}
                                                     world)))
                (is (false? (bt-actions/guard-holds? :ally-within-range?
                                                     {:ally-id :karlach :max-dist 10.0}
                                                     world)))
                (is (true? (bt-actions/guard-holds? :interesting-block-nearby?
                                                    {:radius 5.0}
                                                    world))))))))

      (testing "bt-tick/tick-bt-agents! compartilha 1 unico PLAYERS entre multiplos agentes com BT no mesmo tick"
        (let [sent (atom [])
              ctx  (atom {:tick 4})]
          (with-redefs [bt-tick/self-driven #{:karlach}
                        transport/send-bridge-cmd!
                        (fn [cmd]
                          (swap! sent conj cmd)
                          (if (= cmd "PLAYERS") sample-players-wire "OK"))]
            (bt-tick/tick-bt-agents! {:engine-ctx-atom ctx :tick 4})
            (is (= 1 (count (filter #(= % "PLAYERS") @sent)))
                "Mesmo ticando Picard, Coco, Richeh e Antigravity no mesmo tick, PLAYERS roda 1 unica vez")
            (is (map? (:perception-by-agent @ctx)))))))))

(deftest record-tick-metrics-and-studio-api-state-test
  (let [record-tick-metrics! (resolve-pt 'record-tick-metrics!)]
    (is (fn? record-tick-metrics!) "craft.agents.npc.perception-tick/record-tick-metrics! deve existir")
    (when (fn? record-tick-metrics!)
      (let [ctx (atom {:tick 20 :agents {} :leases {} :events []})]
        (record-tick-metrics! ctx {:tick 19 :tick-ms 8.0 :cmds-per-tick 3})
        (record-tick-metrics! ctx {:tick 20 :tick-ms 14.5 :cmds-per-tick 5})
        (testing "grava {:tick-ms :cmds-per-tick :tick-p95-ms} em [:telemetry :tick] e [:metrics] no engine-ctx-atom"
          (let [tm (or (get-in @ctx [:telemetry :tick]) (:metrics @ctx))]
            (is (map? tm))
            (is (= 14.5 (:tick-ms tm)))
            (is (= 5 (:cmds-per-tick tm)))
            (is (number? (:tick-p95-ms tm)))
            (is (>= (:tick-p95-ms tm) 8.0))))

        (testing "endpoint /api/state (e /api/status) do Studio expoe :tick-ms, :cmds-per-tick e :tick-p95-ms"
          (with-redefs [fabric/inspect-inventory (fn [_] {:slots-used 0 :items {}})
                        fabric/query-online-players (fn [] [])
                        fabric/query-minecraft-weather (fn [] {:clock "12:00" :phase "Dia" :sky "Céu Limpo"})]
            (let [handler (studio/create-app-handler ctx)
                  resp    (handler {:uri "/api/state" :request-method :get})
                  body    (when (= 200 (:status resp))
                            (json/read-str (:body resp) :key-fn keyword))]
              (is (= 200 (:status resp)) "GET /api/state deve responder 200")
              (is (= 14.5 (or (:tick-ms body)
                              (get-in body [:telemetry :tick :tick-ms])
                              (get-in body [:metrics :tick-ms]))))
              (is (= 5 (or (:cmds-per-tick body)
                           (get-in body [:telemetry :tick :cmds-per-tick])
                           (get-in body [:metrics :cmds-per-tick]))))
              (is (number? (or (:tick-p95-ms body)
                               (get-in body [:telemetry :tick :tick-p95-ms])
                               (get-in body [:metrics :tick-p95-ms])))))))

        (testing "karlach-tick/tick-karlach! registra metricas de tick no engine-ctx-atom"
          (let [kctx (atom {})
                nav  (atom {:pos [1.0 2.0 3.0] :yaw 0.0 :mode :sanctuary-patrol :dance-ticks 2})]
            (with-redefs [transport/send-bridge-cmd! (fn [_] "OK")]
              (karlach-tick/tick-karlach! {:engine-ctx-atom kctx
                                           :nav-state nav
                                           :picard-state (atom {:pos [-65.0 145.0 101.0] :status "Ponte"})
                                           :coco-state (atom {:status "c" :pos [0 0 0]})
                                           :richeh-state (atom {:status "r" :pos [0 0 0]})
                                           :tick 1}))
            (let [tm (or (get-in @kctx [:telemetry :tick]) (:metrics @kctx))]
              (is (map? tm))
              (is (number? (:tick-ms tm)))
              (is (pos? (:cmds-per-tick tm))))))))))

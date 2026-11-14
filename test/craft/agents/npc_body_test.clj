(ns craft.agents.npc-body-test
  "CA-102 — Arbitro unico de atuadores corporais (`craft.agents.npc.body`): canais, prioridades,
   compositor de comando MOVE unico por tick e API direta `move!` (`SUG-01` parte 1)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.leases :as leases]))

(defn- resolve-body [sym]
  (try
    (require 'craft.agents.npc.body)
    (ns-resolve 'craft.agents.npc.body sym)
    (catch Throwable _ nil)))

(defn- with-sent
  "Executa `f` com transport/send-bridge-cmd! capturando os comandos; devolve [resultado comandos]."
  [f]
  (let [sent (atom [])]
    (with-redefs [transport/send-bridge-cmd! (fn [cmd] (swap! sent conj cmd) "OK")]
      (let [r (f)]
        [r @sent]))))

(defn- moves [cmds] (filter #(str/starts-with? % "MOVE|") cmds))

(deftest channels-and-priorities-test
  (let [channels   (some-> (resolve-body 'channels) deref)
        priorities (some-> (resolve-body 'priorities) deref)]
    (is (= #{:legs :gaze :hands :pose} channels))
    (is (map? priorities))
    (when (map? priorities)
      (is (apply > (map priorities [:survival :combat :player-goal :bt :atelier :idle]))
          "survival > combat > player-goal > bt > atelier > idle"))))

(deftest request-keeps-highest-priority-per-agent-channel-test
  (let [request! (resolve-body 'request!)
        reset!   (resolve-body 'reset-buffer!)
        pending  (resolve-body 'pending)]
    (is (every? fn? [request! reset! pending]))
    (when (every? fn? [request! reset! pending])
      (reset!)
      (request! {:agent :coco :channel :legs :source :atelier :target [1.0 64.0 1.0]})
      (request! {:agent :coco :channel :legs :source :survival :target [9.0 64.0 9.0]})
      (request! {:agent :coco :channel :legs :source :bt :target [5.0 64.0 5.0]})
      (request! {:agent :richeh :channel :legs :source :idle :target [2.0 64.0 2.0]})
      (testing "maior prioridade vence por [agent channel]"
        (is (= :survival (get-in (pending) [[:coco :legs] :source])))
        (is (= [9.0 64.0 9.0] (get-in (pending) [[:coco :legs] :target])))
        (is (= :idle (get-in (pending) [[:richeh :legs] :source]))))
      (testing "empate: o primeiro pedido do tick permanece (determinístico)"
        (request! {:agent :picard :channel :gaze :source :bt :yaw 10.0 :pitch 0.0})
        (request! {:agent :picard :channel :gaze :source :bt :yaw 99.0 :pitch 0.0})
        (is (= 10.0 (get-in (pending) [[:picard :gaze] :yaw]))))
      (reset!))))

(deftest flush-composes-single-move-per-agent-test
  (let [request! (resolve-body 'request!)
        reset!   (resolve-body 'reset-buffer!)
        flush!   (resolve-body 'flush!)
        metrics  (resolve-body 'metrics)]
    (is (every? fn? [request! reset! flush!]))
    (when (every? fn? [request! reset! flush!])
      (reset!)
      (request! {:agent :karlach :channel :legs :source :bt :target [10.0 65.0 20.0]})
      (request! {:agent :karlach :channel :gaze :source :atelier :yaw 90.0 :pitch 15.0})
      (request! {:agent :karlach :channel :pose :source :bt :sprint true})
      (request! {:agent :coco :channel :legs :source :bt :target [1.0 64.0 2.0]})
      (let [[res cmds] (with-sent flush!)
            ms (moves cmds)]
        (testing "no maximo 1 MOVE por agente por tick"
          (is (= 2 (count ms)))
          (is (= 2 (count (distinct (map #(second (str/split % #"\|")) ms))))))
        (testing "comando composto de pernas + olhar + pose"
          (let [k (first (filter #(str/starts-with? % "MOVE|Karlach|") ms))]
            (is (= "MOVE|Karlach|10.000|65.000|20.000|90.0|15.0|true|false" k))))
        (testing "flush! devolve os comandos emitidos e atualiza :cmds-per-tick"
          (is (= (set ms) (set (map :cmd res))))
          (when (fn? metrics)
            (is (= 2 (:cmds-per-tick (metrics))))))
        (testing "buffer limpo apos flush"
          (is (empty? (second (with-sent flush!)))))))))

(deftest flush-gaze-only-uses-known-position-test
  (let [request! (resolve-body 'request!)
        reset!   (resolve-body 'reset-buffer!)
        flush!   (resolve-body 'flush!)]
    (when (every? fn? [request! reset! flush!])
      (reset!)
      (request! {:agent :picard :channel :gaze :source :bt :pos [0.0 64.0 0.0] :yaw 45.0 :pitch -5.0})
      (let [[_ cmds] (with-sent flush!)]
        (is (= ["MOVE|Picard|0.000|64.000|0.000|45.0|-5.0|false|false"] cmds))))))

(def ^:private ca-103-migrated-files
  "Arquivos que emitiam MOVE| direto e agora devem passar por npc.body (CA-103, SUG-01 parte 2a)."
  ["src/clj/craft/agents/backend/fabric/actions.clj"
   "src/clj/craft/agents/backend/fabric/engine/karlach_tick.clj"
   "src/clj/craft/agents/backend/fabric/engine/follow.clj"
   "src/clj/craft/agents/backend/fabric/engine/watchdog.clj"
   "src/clj/craft/agents/backend/fabric/engine/patrol.clj"
   "src/clj/craft/agents/backend/fabric/engine/patrol_ops.clj"
   "src/clj/craft/agents/backend/fabric/engine/mining.clj"
   "src/clj/craft/agents/backend/fabric/goals/companion.clj"
   "src/clj/craft/agents/backend/fabric/goals/work.clj"
   "src/clj/craft/agents/backend/fabric/sentry.clj"])

(deftest no-direct-move-literal-in-migrated-subsystems-test
  (doseq [f ca-103-migrated-files]
    (testing f
      (is (.exists (java.io.File. ^String f)) "arquivo existe")
      (when (.exists (java.io.File. ^String f))
        (is (not (str/includes? (slurp f) "\"MOVE|"))
            "so craft.agents.npc.body pode conter o literal \"MOVE|")))))

(deftest move-direct-api-test
  (let [move! (resolve-body 'move!)]
    (is (fn? move!))
    (when (fn? move!)
      (testing "mapa com chaves claras, sem o caller conhecer o wire"
        (let [[r cmds] (with-sent #(move! {:agent :karlach :x 1.5 :y 64.0 :z -2.25
                                            :yaw 10.0 :pitch 5.0 :sprint true}))]
          (is (= ["MOVE|Karlach|1.500|64.000|-2.250|10.0|5.0|true|false"] cmds))
          (is (:sent? r))))
      (testing "leases com ttl-ticks: outro dono de maior prioridade bloqueia, expira apos o ttl"
        (let [mgr (leases/create-lease-manager)
              _   (leases/acquire-leases! mgr {:agent-id :coco :channels #{:legs} :owner-id :survival
                                                :priority 100 :ttl-ticks 2})
              base {:agent :coco :x 0.0 :y 64.0 :z 0.0 :lease-mgr mgr :owner :atelier :priority 10}
              [r1 c1] (with-sent #(move! base))
              _   (leases/tick-expire-leases! mgr :coco 2)
              [r2 c2] (with-sent #(move! (assoc base :ttl-ticks 3)))]
          (is (empty? c1))
          (is (false? (:sent? r1)))
          (is (= 1 (count c2)))
          (is (:sent? r2)))))))

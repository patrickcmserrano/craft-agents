(ns craft.agents.npc-state-test
  "CA-089 — Estado por agente (`npc.state`) e snapshot generico do Studio."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [craft.agents.backend.fabric :as fabric]
            [craft.agents.npc.registry :as reg]
            [craft.agents.npc.state :as state]
            [craft.agents.studio.server :as studio]))

(use-fixtures :once (fn [f] (reg/reload! "agents") (f)))

(deftest agent-atom-cria-sob-demanda-com-defaults
  (let [ctx (atom {})
        a   (state/agent-atom ctx :picard)]
    (is (instance? clojure.lang.IAtom a))
    (is (= [-65.0 145.0 101.0] (:pos @a)))
    (is (= :idle (:mode @a)))
    (is (= [] (:path @a)))
    (is (contains? @a :watchdog))
    (is (nil? (:watchdog @a)))
    (testing "chamadas repetidas devolvem o mesmo atomo"
      (is (identical? a (state/agent-atom ctx :picard))))))

(deftest estados-de-agentes-diferentes-sao-independentes
  (let [ctx (atom {})
        k   (state/agent-atom ctx :karlach)
        p   (state/agent-atom ctx :picard)]
    (is (not (identical? k p)))
    (swap! k assoc :mode :follow :pos [1.0 2.0 3.0])
    (is (= :idle (:mode @p)))
    (is (= [-65.0 145.0 101.0] (:pos @p)))
    (is (= [1.0 2.0 3.0] (:pos @k)))
    (testing "o mapa vive dentro do engine-ctx"
      (is (= #{:karlach :picard} (set (keys (:agent-states @ctx))))))))

(deftest agent-atom-aceita-estado-inicial-sobre-os-defaults
  (let [ctx (atom {})
        a   (state/agent-atom ctx :karlach {:mode :sanctuary-patrol :yaw 0.0})]
    (is (= :sanctuary-patrol (:mode @a)))
    (is (= [-175.0 65.0 180.0] (:pos @a)))
    (is (= [] (:path @a)))
    (testing "ja existente: nao sobrescreve"
      (is (identical? a (state/agent-atom ctx :karlach {:mode :idle})))
      (is (= :sanctuary-patrol (:mode @a))))))

(deftest studio-snapshot-cobre-todos-os-agentes-registrados
  (let [ctx (atom {})
        _   (swap! (state/agent-atom ctx :karlach) assoc :pos [9.0 9.0 9.0])
        asked (atom [])]
    (with-redefs [fabric/inspect-inventory
                  (fn [nm] (swap! asked conj nm) {:slots-used 1 :items {nm 1}})]
      (let [snap (studio/studio-snapshot ctx)
            ids  (set (map :agent/id (reg/all-agents)))]
        (is (seq ids))
        (is (= ids (set (keys (:inventory snap)))))
        (is (= ids (set (keys (:agents snap)))))
        (doseq [spec (reg/all-agents)
                :let [id (:agent/id spec)]]
          (is (= {:slots-used 1 :items {(:agent/name spec) 1}} (get-in snap [:inventory id])))
          (is (some? (get-in snap [:agents id :pos])) (str id)))
        (is (= [9.0 9.0 9.0] (get-in snap [:agents :karlach :pos])))
        (is (= [-65.0 145.0 101.0] (get-in snap [:agents :picard :pos])))
        (is (= (set (map :agent/name (reg/all-agents))) (set @asked)))))))

(deftest telemetria-mantem-chaves-de-compatibilidade
  (with-redefs [fabric/inspect-inventory (fn [_] {:slots-used 0 :items {}})
                fabric/query-online-players (fn [] [])
                fabric/query-minecraft-weather (fn [] {})]
    (let [ctx (atom {:karlach-mode :follow :karlach-nav-pos [1.0 2.0 3.0]})
          frame (studio/build-telemetry-frame ctx)]
      (is (= :follow (:karlach-mode frame)))
      (is (= [1.0 2.0 3.0] (:karlach-nav-pos frame)))
      (is (contains? (:inventory frame) :karlach))
      (is (contains? (:inventory frame) :picard))
      (is (contains? (:agents frame) :coco)))))

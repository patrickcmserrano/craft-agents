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

(defn- resolve-state-fn [sym]
  (some-> (ns-resolve 'craft.agents.npc.state sym) deref))

(deftest ca-101-isolamento-cooldowns-eventos-e-leases-por-agente-test
  (let [set-cooldown!       (resolve-state-fn 'set-cooldown!)
        cooldown-active?    (resolve-state-fn 'cooldown-active?)
        record-agent-event! (resolve-state-fn 'record-agent-event!)
        agent-leases        (resolve-state-fn 'agent-leases)]
    (is (fn? set-cooldown!) "npc.state/set-cooldown! deve existir")
    (is (fn? cooldown-active?) "npc.state/cooldown-active? deve existir")
    (is (fn? record-agent-event!) "npc.state/record-agent-event! deve existir")
    (is (fn? agent-leases) "npc.state/agent-leases deve existir")
    (when (and (fn? set-cooldown!) (fn? cooldown-active?)
               (fn? record-agent-event!) (fn? agent-leases))
      (let [ctx (atom {})]
        (doseq [id [:karlach :coco :richeh]]
          (state/agent-atom ctx id))

        (testing "cooldowns sao isolados por agent-id em (:agent-states @ctx)"
          (set-cooldown! ctx :coco :duo-study 5000)
          (is (true? (cooldown-active? ctx :coco :duo-study)))
          (is (true? (cooldown-active? ctx :coco :duo-study 3000)))
          (is (false? (cooldown-active? ctx :coco :duo-study 5000)))
          (is (false? (cooldown-active? ctx :coco :duo-study 6000)))
          (is (false? (cooldown-active? ctx :richeh :duo-study))
              "cooldown de :coco nao interfere com :richeh")
          (is (false? (cooldown-active? ctx :karlach :duo-study))
              "cooldown de :coco nao interfere com :karlach")
          (is (= 5000 (get-in @(get-in @ctx [:agent-states :coco]) [:cooldowns :duo-study])))
          (is (empty? (:cooldowns @(get-in @ctx [:agent-states :richeh]))))
          (is (empty? (:cooldowns @(get-in @ctx [:agent-states :karlach])))))

        (testing "eventos sao isolados por agent-id em (:agent-states @ctx)"
          (record-agent-event! ctx :coco {:type :bt :text "Coco rabiscou no caderno"})
          (let [coco-evts    (:events @(get-in @ctx [:agent-states :coco]))
                richeh-evts  (:events @(get-in @ctx [:agent-states :richeh]))
                karlach-evts (:events @(get-in @ctx [:agent-states :karlach]))]
            (is (= 1 (count coco-evts)))
            (is (= "Coco rabiscou no caderno" (:text (first coco-evts))))
            (is (= :coco (:agent-id (first coco-evts))))
            (is (empty? richeh-evts) "evento de :coco nao vaza para :richeh")
            (is (empty? karlach-evts) "evento de :coco nao vaza para :karlach")))

        (testing "leases sao isolados por agent-id em (:agent-states @ctx)"
          (agent-leases ctx :coco {:legs {:owner-id :atelier-walk :priority 20}})
          (is (= :atelier-walk (get-in (agent-leases ctx :coco) [:legs :owner-id])))
          (is (nil? (:legs (agent-leases ctx :richeh)))
              "lease de :coco nao interfere com :richeh")
          (is (nil? (:legs (agent-leases ctx :karlach)))
              "lease de :coco nao interfere com :karlach"))))))

(deftest ca-101-studio-cartoes-dinamicos-do-registro-e-agent-states-test
  (with-redefs [fabric/inspect-inventory (fn [_] {:slots-used 0 :items {}})
                fabric/query-online-players (fn [] [])
                fabric/query-minecraft-weather (fn [] {})]
    (let [set-cooldown!       (resolve-state-fn 'set-cooldown!)
          record-agent-event! (resolve-state-fn 'record-agent-event!)
          agent-leases        (resolve-state-fn 'agent-leases)
          ;; Mesmo quando :agents no ctx tem apenas os 4 cartoes legados fixos:
          ctx (atom {:agents {:karlach {:status "patrol" :pos [-175.0 65.0 180.0]}
                              :picard  {:status "bridge" :pos [-65.0 145.0 101.0]}
                              :coco    {:status "atelier" :pos [-30.0 66.0 190.0]}
                              :richeh  {:status "atelier" :pos [-30.0 66.0 192.0]}}})]
      (when (and (fn? set-cooldown!) (fn? record-agent-event!) (fn? agent-leases))
        (set-cooldown! ctx :antigravity :tactical-scan 9000)
        (record-agent-event! ctx :antigravity {:type :motor :text "Escolta pronta"})
        (agent-leases ctx :antigravity {:gaze {:owner-id :social-gaze :priority 15}}))
      (let [frame (studio/build-telemetry-frame ctx)
            cards (:agents frame)]
        (testing "inclui dinamicamente todos os agentes de registry/all-agents (incluindo :antigravity)"
          (doseq [id [:karlach :picard :coco :richeh :antigravity]]
            (is (contains? cards id) (str "cartao ausente para " id))
            (is (vector? (get-in cards [id :pos])) (str "pos ausente para " id))))
        (testing "mescla estado isolado de [:agent-states id] no cartao do agente no Studio"
          (is (= {:tactical-scan 9000} (get-in cards [:antigravity :cooldowns])))
          (is (= 1 (count (get-in cards [:antigravity :events]))))
          (is (= :social-gaze (get-in cards [:antigravity :leases :gaze :owner-id]))))
        (testing "preserva os 4 cartoes existentes sem regressao"
          (is (= "patrol" (get-in cards [:karlach :status])))
          (is (= "bridge" (get-in cards [:picard :status]))))))))

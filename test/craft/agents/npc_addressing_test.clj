(ns craft.agents.npc-addressing-test
  "CA-090 — enderecamento de comandos por nome de qualquer agente do registro."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.npc.addressing :as addressing]
            [craft.agents.cognition.intent :as intent]
            [craft.agents.skills.composer :as composer]))

(def ^:private fake-agents
  [{:agent/id :karlach :agent/name "Karlach" :agent/capabilities #{:follow :combat}}
   {:agent/id :picard :agent/name "Picard" :agent/capabilities #{:weather}}
   {:agent/id :coco :agent/name "Coco" :agent/capabilities #{:follow :build}}
   {:agent/id :richeh :agent/name "Richeh"}])

(defn- addressed [msg & [opts]]
  (addressing/addressed-agents msg (merge {:agents fake-agents} opts)))

(deftest addressed-agents-por-nome
  (testing "um nome, com ou sem prefixo"
    (is (= [:karlach] (addressed "karlach me segue")))
    (is (= [:coco] (addressed "!coco vem cá")))
    (is (= [:karlach] (addressed "Pace: KARLACH, vem"))))
  (testing "varios nomes, na ordem em que aparecem"
    (is (= [:karlach :picard] (addressed "karlach e picard, venham")))
    (is (= [:picard :karlach] (addressed "picard e karlach, venham"))))
  (testing "sem nome"
    (is (= [] (addressed "bom dia a todos")))
    (is (= [] (addressed "")))
    (is (= [] (addressed "cocoon eh um filme")))))

(deftest addressed-agents-grupo
  (testing "todos/pessoal no inicio chamam todos os agentes"
    (is (= [:karlach :picard :coco :richeh] (addressed "todos, venham")))
    (is (= [:karlach :picard :coco :richeh] (addressed "!pessoal vamos"))))
  (testing "filtra pela capacidade pedida"
    (is (= [:karlach :coco] (addressed "todos me sigam" {:capability :follow})))
    (is (= [:picard] (addressed "pessoal, clima" {:capability :weather}))))
  (testing "'todos' no meio da frase nao e chamado de grupo"
    (is (= [] (addressed "alimente todos os animais")))))

(deftest addressed-agents-registro-real
  (is (= [:coco] (addressing/addressed-agents "!coco vem cá")))
  (is (= [:karlach :picard] (addressing/addressed-agents "karlach e picard, venham"))))

(deftest strip-addressing-remove-qualquer-nome
  (is (= "me segue" (addressing/strip-addressing "karlach me segue" fake-agents)))
  (is (= "vem cá" (addressing/strip-addressing "!coco vem cá" fake-agents)))
  (is (= "venham" (addressing/strip-addressing "karlach e picard, venham" fake-agents)))
  (is (= "vamos" (addressing/strip-addressing "Todos: vamos" fake-agents)))
  (is (= "sem nome aqui" (addressing/strip-addressing "sem nome aqui" fake-agents))))

(deftest intent-extract-teach-usa-qualquer-nome
  (is (= "pular tres vezes"
         (intent/extract-teach-instruction "coco, aprenda pular tres vezes")))
  (is (= "pular tres vezes"
         (intent/extract-teach-instruction "karlach aprenda pular tres vezes"))))

(deftest composer-gera-aliases-para-o-agente-que-aprendeu
  (let [anchors (composer/custom-anchors "Coco" {:body "pular tres vezes"
                                                 :clean-name "pular"
                                                 :skill-name "pular"
                                                 :raw "aprenda pular tres vezes"})]
    (is (some #{"coco pular tres vezes"} anchors))
    (is (some #{"coco pular"} anchors))
    (is (not-any? #(re-find #"karlach" %) anchors)))
  (testing "default continua Karlach"
    (is (some #{"karlach pular"}
              (composer/custom-anchors nil {:body "pular" :clean-name "pular"
                                            :skill-name "pular" :raw "pular"})))))

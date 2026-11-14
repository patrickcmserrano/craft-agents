(ns craft.agents.npc.registry-test
  "CA-086 — Registro de agentes com schema Malli (agents/*.edn)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [craft.agents.npc.registry :as reg]))

(defn- tmp-dir []
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "agents-reg" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit d)
    d))

(deftest carrega-todos-os-agentes-validos
  (let [{:keys [agents errors]} (reg/load-agents "agents")]
    (is (empty? errors))
    (is (= #{:antigravity :coco :karlach :picard :richeh} (set (keys agents))))
    (doseq [[id spec] agents]
      (is (m/validate reg/AgentSpec spec) (str id " deve validar pelo AgentSpec")))))

(deftest ignora-subdiretorios
  (let [{:keys [agents errors]} (reg/load-agents "agents")]
    (is (empty? errors))
    (is (not-any? #(contains? #{:sanctuary :shelter-from-rain :detectar} %) (keys agents)))))

(deftest agent-by-name-aceita-qualquer-caixa
  (reg/reload! "agents")
  (is (= :karlach (:agent/id (reg/agent-by-name "karlach"))))
  (is (= :karlach (:agent/id (reg/agent-by-name "Karlach"))))
  (is (= :karlach (:agent/id (reg/agent-by-name "KARLACH"))))
  (is (nil? (reg/agent-by-name "ninguem"))))

(deftest agent-by-id-e-agent-names
  (reg/reload! "agents")
  (is (= "Coco" (:agent/name (reg/agent-by-id :coco))))
  (is (nil? (reg/agent-by-id :nao-existe)))
  (is (= #{"Antigravity" "Coco" "Karlach" "Picard" "Richeh"} (set (reg/agent-names)))))

(deftest campos-novos-nos-edn-existentes
  (reg/reload! "agents")
  (let [k (reg/agent-by-id :karlach)]
    (is (= :fight (:agent/combat-policy k)))
    (is (= "block.campfire.crackle" (get-in k [:agent/voice :sound])))
    (is (string? (get-in k [:agent/voice :color]))))
  (is (= :flee (:agent/combat-policy (reg/agent-by-id :coco))))
  (is (= :flee (:agent/combat-policy (reg/agent-by-id :richeh))))
  (doseq [id [:karlach :coco :richeh :picard]]
    (is (map? (:agent/voice (reg/agent-by-id id))) (str id " tem voz"))
    (is (string? (:agent/skin (reg/agent-by-id id))) (str id " tem skin"))))

(deftest schema-valida-campos-novos
  (let [base {:agent/id :x :agent/name "X"
              :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 64.0 0.0]}}]
    (is (m/validate reg/AgentSpec base))
    (is (m/validate reg/AgentSpec
                    (assoc base
                           :agent/voice {:color "red" :sound "block.x" :radius 12.0}
                           :agent/combat-policy :none
                           :agent/capabilities #{:escort :social}
                           :agent/skin "karlach")))
    (is (not (m/validate reg/AgentSpec (assoc base :agent/combat-policy :berserk))))
    (is (not (m/validate reg/AgentSpec (dissoc base :agent/spawn))))))

(deftest edn-invalido-gera-erro-sem-derrubar-os-outros
  (let [d (tmp-dir)]
    (spit (io/file d "bom.edn")
          (pr-str {:agent/id :bom :agent/name "Bom"
                   :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 64.0 0.0]}}))
    (spit (io/file d "ruim.edn") (pr-str {:agent/name "Ruim"}))
    (let [{:keys [agents errors]} (reg/load-agents (.getPath d))]
      (is (= [:bom] (keys agents)))
      (is (= 1 (count errors)))
      (let [{:keys [file message]} (first errors)]
        (is (str/includes? file "ruim.edn"))
        (is (str/includes? message ":agent/id"))))))

(deftest reload-troca-o-registro
  (let [d (tmp-dir)]
    (spit (io/file d "a.edn")
          (pr-str {:agent/id :a :agent/name "Alfa"
                   :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 64.0 0.0]}}))
    (reg/reload! (.getPath d))
    (is (= ["Alfa"] (vec (reg/agent-names))))
    (is (some? (reg/agent-by-name "alfa")))
    (reg/reload! "agents")
    (is (nil? (reg/agent-by-name "alfa")))))

(deftest schema-valida-bt-root-estrutural
  (reg/reload! "agents")
  (let [base {:agent/id :x :agent/name "X"
              :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 64.0 0.0]}}
        valid-trees [;; Todas as árvores declaradas em agents/*.edn
                     (:bt/root (reg/agent-by-id :antigravity))
                     (:bt/root (reg/agent-by-id :coco))
                     (:bt/root (reg/agent-by-id :karlach))
                     (:bt/root (reg/agent-by-id :picard))
                     (:bt/root (reg/agent-by-id :richeh))
                     ;; Formas suportadas por goals.bt/execute-tree-node!
                     [:selector [:action :a]]
                     [:sequence [:action :a]]
                     [:fallback [:action :a]]
                     [:parallel [:action :a] [:action :b]]
                     [:action :a]
                     [:action :a {:foo "bar"}]
                     [:condition :cond-x]
                     [:guard :guard-y {:radius 10.0}]
                     [:cooldown {:ms 1000} [:action :a]]
                     [:repeat {:times 3} [:action :a]]
                     [:invert [:action :a]]
                     [:inverter [:action :a]]]
        invalid-trees [[]
                       [123]
                       [:op-inexistente-xyz {:x 1}]
                       [:action]
                       [:sequence]
                       [:cooldown {:ms 100}]
                       [:invert]]]
    (doseq [tree valid-trees]
      (is (some? tree) "Árvore de agente existente não pode ser nil")
      (is (m/validate reg/AgentSpec (assoc base :bt/root tree))
          (str "Deveria aceitar árvore válida: " (pr-str tree))))
    (doseq [bad invalid-trees]
      (is (not (m/validate reg/AgentSpec (assoc base :bt/root bad)))
          (str "Deveria rejeitar árvore malformada: " (pr-str bad))))))

(deftest default-dir-respeita-env-ou-fallback
  (is (= (or (System/getenv "CRAFT_AGENTS_DIR") "agents") reg/default-dir)))

;; ----------------------------------------------------------------------------
;; CA-108: AgentSpec confere as :action de :bt/root contra o registro de acoes
;; ----------------------------------------------------------------------------

(def ^:private base-agent
  {:agent/id :x :agent/name "X"
   :agent/spawn {:dimension "minecraft:overworld" :pos [0.0 64.0 0.0]}})

(deftest unknown-actions-lista-acoes-fora-do-registro
  (let [spec (assoc base-agent :bt/root
                    [:selector
                     [:sequence [:guard :g] [:action :a]]
                     [:parallel [:action :b {}] [:cooldown {:ms 10} [:action :zzz]]]
                     [:repeat {:times 2} [:invert [:action :yyy {:k 1}]]]])]
    (is (= #{:zzz :yyy} (reg/unknown-actions spec #{:a :b})))
    (is (= #{} (reg/unknown-actions spec #{:a :b :zzz :yyy})))
    (is (= #{} (reg/unknown-actions base-agent #{})) "sem :bt/root nao ha acao")
    (is (= #{} (reg/unknown-actions (assoc base-agent :bt/root [:guard :g]) #{}))
        "guards nao sao acoes")))

(deftest load-agents-valida-acoes-contra-o-registro-quando-informado
  (let [d (tmp-dir)]
    (spit (io/file d "ok.edn")
          (pr-str (assoc base-agent :agent/id :ok :agent/name "Ok" :bt/root [:action :a])))
    (spit (io/file d "mau.edn")
          (pr-str (assoc base-agent :agent/id :mau :agent/name "Mau"
                         :bt/root [:sequence [:action :a] [:action :nao-registrada]])))
    (let [{:keys [agents errors]} (reg/load-agents (.getPath d) {:known-actions #{:a}})]
      (is (= [:ok] (keys agents)))
      (is (= 1 (count errors)))
      (is (str/includes? (:file (first errors)) "mau.edn"))
      (is (str/includes? (:message (first errors)) ":nao-registrada")))
    (let [{:keys [agents errors]} (reg/load-agents (.getPath d))]
      (is (= [:mau :ok] (vec (sort (keys agents)))) "sem registro informado nada muda")
      (is (empty? errors)))))

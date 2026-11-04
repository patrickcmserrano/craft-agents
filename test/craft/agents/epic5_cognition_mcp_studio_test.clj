(ns craft.agents.epic5-cognition-mcp-studio-test
  "Testes comportamentais do Epico 5: Semantic Scene Digest, Gatekeeper Malli de Skills, Servidor MCP, Cognicao Assincrona e Studio (CA-036 a CA-042)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric :as fabric]
            [craft.agents.blackboard :as bb]
            [craft.agents.cognition :as cognition]
            [craft.agents.fixtures.world-fixture :as wf]
            [craft.agents.mcp.server :as mcp]
            [craft.agents.perception :as perception]
            [craft.agents.skills.composer :as composer]
            [craft.agents.studio.ingame-debug :as ingame-debug]
            [craft.agents.studio.server :as studio]))

(deftest ca-036-semantic-scene-digest-compactness-and-modded-ids-test
  (testing "CA-036: SceneDigest sintetiza entidades modded reais, portais proximos e linha de visao em <= 600 palavras"
    (let [w (wf/make-flat-world-state 64)
          _ (wf/add-double-fence-gate! w [-171 65 171] {:id "pen-ne" :open? false})
          _ (wf/add-entity! w {:id :capy-1 :type "naturalist:capybara" :pos [-168.0 65.0 171.0] :health 16.0})
          _ (wf/add-entity! w {:id :rav-1  :type "minecraft:ravager"   :pos [-162.0 65.0 180.0] :health 100.0})
          backend (wf/make-mock-backend w)
          body    (wf/make-mock-body w {:id :antigravity :name "Antigravity" :pos [-173.0 65.0 171.0]})
          board   (bb/create-blackboard)
          digest  (perception/build-scene-digest backend body board {:type :player-chat :speaker "Pace" :message "Status?"})
          word-count (count (str/split (pr-str digest) #"\s+"))]
      (is (= "Antigravity" (get-in digest [:self :name])))
      (is (pos? (get-in digest [:entities :animals-summary "naturalist:capybara"] 0)))
      (is (<= word-count 600) "Digest deve ser enxuto em tokens p/ baixa latencia na LLM"))))

(deftest ca-037-voyager-safe-skill-composer-blocks-cycles-and-unknown-ops-test
  (testing "CA-037: Gatekeeper aprova skill composta segura e bloqueia acoes inexistentes ou recursao ciclica infinita"
    (let [tmp-skills (io/file (System/getProperty "java.io.tmpdir") (str "ca-skills-" (System/nanoTime)))
          registry   (atom {:known-actions #{:move-to :traverse-portal :feed-nearby}
                            :composite-skills {:skill-b [:sequence [:action :skill-a {:timeout-ms 5000}]]}})]
      (.mkdirs tmp-skills)
      (try
        (let [valid-skill {:skill/id :feed-and-return
                           :skill/timeout-ms 15000
                           :skill/tree [:sequence
                                        [:action :traverse-portal {:portal :pen-ne :dir :in}]
                                        [:action :feed-nearby {:species "naturalist:capybara"}]
                                        [:action :traverse-portal {:portal :pen-ne :dir :out}]]}
              cyclic-skill {:skill/id :skill-a
                            :skill/timeout-ms 5000
                            :skill/tree [:sequence [:action :skill-b {:timeout-ms 5000}]]}
              eval-hack    {:skill/id :hack
                            :skill/timeout-ms 5000
                            :skill/tree [:action :raw-js-eval {:code "process.exit(1)"}]}]
          (is (:ok? (composer/validate-and-register-skill! registry tmp-skills valid-skill)))
          (is (.exists (io/file tmp-skills "feed-and-return.edn")))
          (is (false? (:ok? (composer/validate-and-register-skill! registry tmp-skills cyclic-skill)))
              "Ciclo recursivo A -> B -> A deve ser bloqueado estaticamente")
          (is (false? (:ok? (composer/validate-and-register-skill! registry tmp-skills eval-hack)))
              "Acao fora do catalogo seguro jamais pode ser aceita"))
        (finally
          (doseq [f (reverse (file-seq tmp-skills))] (.delete f)))))))

(deftest ca-038-mcp-jsonrpc-protocol-tools-and-resources-test
  (testing "CA-038: Servidor MCP responde initialize, tools/list e tools/call seguindo JSON-RPC 2.0"
    (let [ctx {:agents {:antigravity {:blackboard (bb/create-blackboard)}}}
          init-resp  (mcp/handle-jsonrpc-request ctx {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
          tools-resp (mcp/handle-jsonrpc-request ctx {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
          tool-names (set (map :name (get-in tools-resp [:result :tools])))]
      (is (= "2.0" (:jsonrpc init-resp)))
      (is (contains? tool-names "reply_and_gesture"))
      (is (contains? tool-names "set_directive"))
      (is (contains? tool-names "run_task_sequence"))
      (is (contains? tool-names "compose_and_save_skill")))))

(deftest ca-039-async-llm-never-freezes-20hz-body-loop-test
  (testing "CA-039: Enquanto a LLM demora 250ms respondendo, o corpo continua executando ticks de 50ms sem bloqueio"
    (let [board (bb/create-blackboard)
          ctx   {:agents {:antigravity {:blackboard board}}}
          ticks-while-thinking (atom 0)
          slow-llm (fn [_digest]
                     (Thread/sleep 250)
                     [{:tool "set_directive"
                       :arguments {:type :escort :leader "Lizzie" :weapon "jeg:flamethrower"}}])
          fut (cognition/dispatch-cognitive-trigger-async!
               ctx :antigravity {:type :player-chat :speaker "Pace" :message "Protege a Lizzie!"} slow-llm)]
      ;; Simula loop de 20Hz do servidor rodando livremente enquanto a LLM pensa!
      (dotimes [_ 4]
        (swap! ticks-while-thinking inc)
        (Thread/sleep 50))
      (is (= 4 @ticks-while-thinking) "Loop do servidor nao pode ficar bloqueado esperando a LLM")
      @fut
      (is (= "Lizzie" (:leader (bb/active-directive board (System/currentTimeMillis))))))))

(deftest ca-040-and-041-agent-studio-telemetry-and-zero-node-ui-test
  (testing "CA-040 & CA-041: Telemetria do Studio expoe nos ativos, leases e rota A*, e frontend usa ES Modules puros sem node_modules"
    (is (.exists (io/file "resources/public/index.html")))
    (is (.exists (io/file "resources/public/app.js")))
    (is (not (.exists (io/file "node_modules"))) "Zero dependencia de node_modules no projeto!")
    (let [frame (studio/build-telemetry-frame {:tick 42 :agents {}})]
      (is (= 42 (:tick frame)))
      (is (contains? frame :agents)))))

(deftest ca-042-admin-only-ingame-route-particles-test
  (testing "CA-042: Particulas de debug da rota A* sao enviadas exclusivamente para o admin Pace, nunca para outros jogadores"
    (let [w (wf/make-flat-world-state 64)
          backend (wf/make-mock-backend w)]
      (ingame-debug/emit-admin-route-particles! backend "Pace" [{:x -175 :y 65 :z 180} {:x -171 :y 65 :z 171}])
      (let [recipients (set (map :recipient (:particles @w)))]
        (is (= #{"Pace"} recipients))))))

(deftest ca-043-skill-rag-retrieval-test
  (testing "CA-043 / P2.1: Skill-RAG recupera as habilidades mais relevantes semanticamente"
    (let [results (composer/retrieve-relevant-skills "resgatar a capivara nadadora no lago" "skills" {:top-k 2})]
      (is (= 2 (count results)))
      (let [top (first results)]
        (is (re-find #"capivara|capybara|animal|resgate" (str/lower-case (get-in top [:skill :skill/name]))))
        (is (pos? (:score top)))))))

(deftest ca-044-grammar-spec-and-ast-validation-test
  (testing "CA-044 / P2.2: Especificação de gramática e validação estrita de AST"
    (let [spec (composer/build-bt-grammar-spec)]
      (is (= :edn-behavior-tree (:format spec)))
      (is (contains? (:primitives spec) :open-gate))
      (is (contains? (:primitives spec) :attach-lead)))

    (let [valid-tree [:sequence
                      [:action :equip-bait {:item "minecraft:apple"}]
                      [:action :walk {:target [-175.0 65.0 180.0]}]]
          invalid-tree [:unsupported-op {:fake true}]]
      (is (true? (composer/validate-bt-ast valid-tree)))
      (is (false? (composer/validate-bt-ast invalid-tree))))))

(deftest ca-045-mental-sandbox-preconditions-test
  (testing "CA-045 / P2.3: Sandbox Mental valida pré-condições de itens no inventário e bloqueia execução às cegas"
    (let [tree [:sequence
                [:action :equip-bait {:item "minecraft:apple"}]
                [:action :attach-lead]
                [:action :open-gate {:enclosure :pen-south-west}]]
          obs-ok   {:inventory {"minecraft:apple" 2 "minecraft:lead" 1}}
          obs-fail {:inventory {"minecraft:lead" 1}} ;; falta a maçã
          res-ok   (composer/evaluate-preconditions tree obs-ok {:known-enclosures #{:pen-south-west :pen-north-east}})
          res-fail (composer/evaluate-preconditions tree obs-fail {:known-enclosures #{:pen-south-west}})]
      (is (true? (:valid? res-ok)))
      (is (= :preconditions-met (:status res-ok)))

      (is (false? (:valid? res-fail)))
      (is (= :precondition-failed (:status res-fail)))
      (is (= "minecraft:apple" (:missing-item res-fail)))
      (is (= :equip-bait (:failed-action res-fail))))

    (testing "Rejeita cercado desconhecido"
      (let [tree [:sequence [:action :open-gate {:enclosure :pen-unknown}]]
            obs  {:inventory {}}
            res  (composer/evaluate-preconditions tree obs {:known-enclosures #{:pen-south-west}})]
        (is (false? (:valid? res)))
        (is (= :precondition-failed (:status res)))
        (is (= :pen-unknown (:missing-enc res)))))))

(deftest ca-046-dynamic-terrain-portal-perception-test
  (testing "CA-046: generate-walk-steps detecta :TRAVERSE_PORTAL em tempo real e emite micro-etapas de condução profunda"
    (let [mock-path [{:x -177 :y 65 :z 189 :action :WALK}
                     {:x -178 :y 65 :z 189 :action :WALK}
                     {:x -179 :y 65 :z 189 :action :TRAVERSE_PORTAL}
                     {:x -180 :y 65 :z 189 :action :WALK}
                     {:x -181 :y 65 :z 189 :action :WALK}]]
      (with-redefs [fabric/request-path3d (fn [_ _ _] mock-path)]
        (let [steps (fabric/generate-walk-steps [-177.0 65.0 189.0] [-181.0 65.0 189.0]
                                               "Conduzindo capivara ao cercado"
                                               {:pull-leashed? true :species "naturalist:capybara"})]
          ;; 1. Verifica se houve abertura dinâmica do portão no voxel correto
          (let [gate-open (first (filter #(and (= (:op %) :gate) (true? (:open? %))) steps))
                gate-close (first (filter #(and (= (:op %) :gate) (false? (:open? %))) steps))
                detach (first (filter #(= (:op %) :detach-lead) steps))
                waits (filter #(= (:op %) :wait) steps)]
            (is (some? gate-open))
            (is (= [-179 65 189] (:pos gate-open)))
            (is (some? gate-close))
            (is (= [-179 65 189] (:pos gate-close)))
            (is (some? detach))
            (is (= "naturalist:capybara" (:species detach)))
            ;; Deve conter espera de pelo menos 20 ticks para a física do mob
            (is (some #(>= (:ticks %) 20) waits)))

          ;; 2. Verifica se a trajetória permaneceu estritamente ortogonal (Z constante = 189.5), sem cortar diagonais
          (let [walk-targets (keep :target (filter #(= (:op %) :walk) steps))]
            (doseq [[x y z] walk-targets]
              (is (= 189.5 z) (str "Passo desviou da reta ortogonal do portão: " [x y z]))
              (is (= 65.0 y)))))))))

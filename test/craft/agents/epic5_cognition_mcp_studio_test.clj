(ns craft.agents.epic5-cognition-mcp-studio-test
  "Testes comportamentais do Epico 5: Semantic Scene Digest, Gatekeeper Malli de Skills, Servidor MCP, Cognicao Assincrona e Studio (CA-036 a CA-042)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric :as fabric]
            [craft.agents.backend.fabric.goals :as fabric-goals]
            [craft.agents.blackboard :as bb]
            [craft.agents.cognition :as cognition]
            [craft.agents.fixtures.world-fixture :as wf]
            [craft.agents.mcp.server :as mcp]
            [craft.agents.perception :as perception]
            [craft.agents.schemas :as schemas]
            [craft.agents.skills.actions :as actions]
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

(deftest ca-047-mount-boat-action-and-mapping-test
  (testing "CA-047: Nova capacidade motora :mount-boat mapeada em linguagem natural e executada via send-cmd!"
    (testing "Mapeamento de linguagem natural para [:action :mount-boat]"
      (is (= [:action :mount-boat {:speaker "Pace"}]
             (composer/step->action-node "suba no barco comigo" "Pace")))
      (is (= [:action :mount-boat {:speaker "Pace"}]
             (composer/step->action-node "entrar-barco" "Pace")))
      (is (= [:action :mount-boat {:speaker "Pace"}]
             (composer/step->action-node "montar no barco" "Pace")))
      (is (= [:action :mount-boat {:speaker "Pace"}]
             (composer/step->action-node "vamos de boat" "Pace"))))

    (testing ":mount-boat está no catálogo default-known-actions"
      (is (contains? composer/default-known-actions :mount-boat)))

    (testing "execute-action :mount-boat despacha o comando correto e retorna :success"
      (let [sent (atom [])
            ctx  {:send-cmd! (fn [c] (swap! sent conj c))}
            res  (actions/execute-action :mount-boat {} ctx)]
        (is (= :success (:status res)))
        (is (= :mount-boat (:action-id res)))
        (is (= ["CMD|execute at Karlach run ride Karlach mount @e[type=boat,distance=..6,sort=nearest,limit=1]"] @sent))))

    (testing "Sintetização via chat gera skill válida com :mount-boat e registra âncoras primárias no router"
      (let [tmp-skills (io/file (System/getProperty "java.io.tmpdir") (str "ca-mount-boat-" (System/nanoTime)))
            tmp-routes (io/file (System/getProperty "java.io.tmpdir") (str "ca-routes-" (System/nanoTime)))
            registry   (atom {:known-actions composer/default-known-actions
                              :composite-skills {}})]
        (.mkdirs tmp-skills)
        (.mkdirs tmp-routes)
        (try
          (let [res (composer/synthesize-and-register-skill-from-chat!
                     registry tmp-skills "entrar-barco: suba no barco comigo" "Pace" {:routes-dir (.getPath tmp-routes)})
                route-file (io/file tmp-routes "entrar-barco.edn")]
            (is (:ok? res))
            (is (= [:sequence [:action :mount-boat {:speaker "Pace"}]]
                   (get-in res [:skill-def :skill/tree])))
            (is (.exists route-file) "Arquivo de rota semântica deve ter sido persistido")
            (let [route-data (clojure.edn/read-string (slurp route-file))
                  anchors    (set (:anchors route-data))]
              (is (contains? anchors "suba no barco comigo") "A frase literal falada pelo jogador deve ser âncora")
              (is (contains? anchors "karlach suba no barco comigo") "Variação com nome da agente deve ser âncora")))
          (finally
            (doseq [f (reverse (file-seq tmp-skills))] (.delete f))
            (doseq [f (reverse (file-seq tmp-routes))] (.delete f))))))))

(deftest ca-040-studio-hot-reload-endpoint-test
  (testing "POST /api/reload recarrega namespaces e rotas sem derrubar JVM"
    (let [ctx-atom (atom {:events []})
          handler  (studio/create-app-handler ctx-atom)
          resp     (handler {:uri "/api/reload" :request-method :post})]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "\"reloaded\":true"))
      (is (pos? (count (:events @ctx-atom))) "Evento de reload deve ser registrado no blackboard do studio"))))


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

(deftest ca-048-multimodal-vehicle-following-and-native-dismount-test
  (testing "CA-048: Multimodal Following com Isolamento de Leases e Desembarque Server-Native (Zero CMD|...)"

    (testing "1. Desembarque Server-Native invoca STAND nativo e restaura estado terrestre sem CMD string"
      (let [sent (atom [])
            nav-state (atom {:in-vehicle? true :vehicle-id "boat-1"})
            ctx {:send-cmd! (fn [c] (swap! sent conj c))
                 :speaker "Karlach"
                 :nav-state nav-state}
            res (actions/execute-action :dismount-vehicle {} ctx)]
        (is (= :success (:status res)))
        (is (= :dismount-vehicle (:action-id res)))
        (is (= ["STAND|Karlach"] @sent) "Deve usar comando nativo STAND da JVM sem invocar console CMD|execute")
        (is (false? (:in-vehicle? @nav-state)) "Deve atualizar estado corporal para fora do veículo")))

    (testing "2. Isolamento Estrito de Leases: Ramo :sync-passive-ride requisita apenas :gaze e NÃO aloca :legs"
      (let [passive-branch [:sequence
                            [:condition :leader-in-vehicle?]
                            [:condition :self-in-same-vehicle?]
                            [:action :passive-passenger-look]]
            leases (schemas/extract-requested-lease-channels passive-branch)]
        (is (contains? leases :gaze) "Passageiro embarcado deve alocar lease visual :gaze")
        (is (not (contains? leases :legs)) "Passageiro embarcado NUNCA pode alocar lease motora :legs (PathSolver3D ocioso)"))

      (let [board-branch [:sequence
                          [:condition :leader-in-vehicle?]
                          [:condition :not-self-in-vehicle?]
                          [:condition :leader-vehicle-has-seat?]
                          [:action :approach-vehicle {:max-dist 3.0}]
                          [:action :mount-vehicle]]
            leases (schemas/extract-requested-lease-channels board-branch)]
        (is (contains? leases :legs) "Aproximação do casco aloca :legs")
        (is (contains? leases :hands) "Interação de montagem aloca :hands")))

    (testing "3. Execução Reativa da Árvore Multimodal em 4 Cenários"
      (let [tree composer/follow-player-multimodal-tree]
        (is (schemas/valid-bt-node? tree) "A árvore composta multimodal deve atender estritamente ao schema Malli BTNode")

        ;; Cenário A: Ambos no barco -> executa :sync-passive-ride (:passive-passenger-look)
        (let [ctx-atom (atom {:perception {:leader {:in-vehicle? true :vehicle {:id "boat-99"}}
                                           :self   {:in-vehicle? true :vehicle-id "boat-99"}}})
              nav-state (atom {:in-vehicle? true :vehicle-id "boat-99"})
              res (fabric-goals/execute-tree-node! tree ctx-atom nav-state [] "pace")]
          (is (= :success (:status res)))
          (is (= :passive-passenger-look (:action-id res))))

        ;; Cenário B: Líder no barco, Karlach na praia, assento livre -> executa :approach-vehicle e :mount-vehicle
        (let [ctx-atom (atom {:perception {:leader {:in-vehicle? true :vehicle {:id "boat-99" :free-seats 1}}
                                           :self   {:in-vehicle? false}}})
              nav-state (atom {:in-vehicle? false})
              res (fabric-goals/execute-tree-node! tree ctx-atom nav-state [] "pace")]
          (is (= :success (:status res)))
          (is (= :mount-vehicle (:action-id res))))

        ;; Cenário C: Barco lotado (0 assentos livres) -> Ramo 2 falha no guard e cai no Ramo 4 (caminhada/fallback)
        (let [ctx-atom (atom {:perception {:leader {:in-vehicle? true :vehicle {:id "boat-99" :free-seats 0}}
                                           :self   {:in-vehicle? false}}})
              nav-state (atom {:in-vehicle? false})
              res (fabric-goals/execute-tree-node! tree ctx-atom nav-state [] "pace")]
          (is (= :success (:status res)))
          (is (contains? #{:walk-towards-leader :follow-player} (:action-id res))
              "Com barco lotado, deve fazer fallback seguro para locomoção terrestre sem erro"))

        ;; Cenário D: Líder desembarcou na margem -> Karlach dispara :dismount-vehicle nativo
        (let [ctx-atom (atom {:perception {:leader {:in-vehicle? false}
                                           :self   {:in-vehicle? true :vehicle-id "boat-99"}}})
              nav-state (atom {:in-vehicle? true :vehicle-id "boat-99"})
              res (fabric-goals/execute-tree-node! tree ctx-atom nav-state [] "pace")]
          (is (= :success (:status res)))
          (is (= :dismount-vehicle (:action-id res))))))

    (testing "4. Mapeamento de Linguagem Natural para Ações de Veículo"
      (is (= [:action :dismount-vehicle {:speaker "Pace"}]
             (composer/step->action-node "karlach desça do barco" "Pace")))
      (is (= [:action :dismount-vehicle {:speaker "Pace"}]
             (composer/step->action-node "desembarcar agora" "Pace")))
      (is (= [:action :dismount-vehicle {:speaker "Pace"}]
             (composer/step->action-node "sair do barco" "Pace")))
      (is (= [:action :mount-vehicle {:speaker "Pace"}]
             (composer/step->action-node "subir no barco" "Pace"))))))

(deftest ca-049-reactive-combat-preemption-and-tether-test
  (testing "CA-049: Preempção de Combate Reativo, Tether Anti-Kiting (18m) e Equipamento Server-Native"

    (testing "1. Preempção de Combate & Gap-Closing sem Sobrescrita do Follow-Path"
      ;; Cenário do esqueleto a 6.0m: mesmo com líder a 1.5m (<= 2.6m),
      ;; o combate assume e fecha distância até o hostil, sem limpar ou sobrescrever com caminho para o jogador
      (let [bot-pos    [0.0 64.0 0.0]
            leader-pos [1.5 64.0 0.0]  ;; dist = 1.5m (abaixo de 2.6m)
            mob-target {:id 375651 :type "minecraft:skeleton" :x 6.0 :y 64.0 :z 0.0 :distance 6.0 :hp 10.9}
            decision   (fabric/evaluate-combat-preemption bot-pos leader-pos [mob-target] false)]
        (is (= :combat-intercept (:action decision))
            "Deve selecionar ação :combat-intercept para fechar distância até o esqueleto")
        (is (= [6.0 64.0 0.0] (:target-pos decision))
            "Caminho deve apontar exclusivamente para o mob hostil")
        (is (false? (:clear-follow-path? decision))
            "NUNCA pode limpar :follow-path enquanto engajando o hostil")))

    (testing "2. Tether Tático Anti-Kiting (Raio Máximo de 18.0m)"
      ;; Se o líder se afasta além de 18.0m, Karlach aborta perseguição e retorna ao perímetro do líder
      (let [bot-pos    [0.0 64.0 0.0]
            leader-pos [22.0 64.0 0.0] ;; dist = 22.0m (> 18.0m)
            mob-target {:id 375651 :type "minecraft:skeleton" :x 6.0 :y 64.0 :z 0.0 :distance 6.0 :hp 10.9}
            decision   (fabric/evaluate-combat-preemption bot-pos leader-pos [mob-target] false)]
        (is (= :tether-snap-recall (:action decision))
            "Distância > 18.0m deve disparar recall forçado de escolta")
        (is (= leader-pos (:target-pos decision))
            "Deve direcionar retorno para o líder")))

    (testing "3. Proteção de Regressão com o Barco (Preservação de Embarque)"
      ;; Karlach embarcada no mesmo veículo que o líder NÃO deve desembarcar sozinha para caçar na margem
      (let [bot-pos    [-100.0 62.0 180.0]
            leader-pos [-100.0 62.0 180.0]
            mob-bank   {:id 375651 :type "minecraft:skeleton" :x -90.0 :y 63.0 :z 180.0 :distance 10.0 :hp 10.9}
            decision   (fabric/evaluate-combat-preemption bot-pos leader-pos [mob-bank] true)]
        (is (= :remain-mounted-passive (:action decision))
            "Enquanto embarcada, não deve desembarcar para perseguir hostil distante")
        (is (nil? (:target-pos decision)))))

    (testing "4. Equipamento Nativo de Escudo e Machado sem CMD string"
      (let [sent (atom [])]
        (with-redefs [fabric/send-bridge-cmd! (fn [c] (swap! sent conj c))]
          (let [res (fabric/equip-item! "Karlach" "offhand" "minecraft:shield")]
            (is (some? res))
            (is (= ["EQUIP_ITEM|Karlach|offhand|minecraft:shield"] @sent)
                "Deve despachar comando nativo EQUIP_ITEM sem console string CMD|item replace")))))))

(ns craft.agents.backend.fabric.goals.skills
  "Metas de skills da Karlach: ensinar skill pelo chat e executar skill dinamica (CA-085)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.goals.bt :as bt]
            [craft.agents.backend.fabric.goals.dispatch :refer [execute-karlach-goal!]]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.rsi.curriculum :as curriculum]
            [craft.agents.skills.composer :as composer]))

(defn- gap-directive
  "Diretriz enviada ao SWE-Runner quando falta uma capacidade motora para `instruction`."
  [instruction]
  (str "Implementar a nova capacidade motora que falta no motor para o comando: '" instruction "'.\n"
       "REQUISITOS:\n"
       "1. Em `src/clj/craft/agents/skills/composer.clj`:\n"
       "   - Adicionar a nova ação em `default-known-actions`.\n"
       "   - Em `step->action-node`, mapear o termo e intenções da instrução para [:action :nome-acao {:speaker speaker}].\n"
       "2. Em `src/clj/craft/agents/skills/actions.clj`:\n"
       "   - Implementar `(defmethod execute-action :nome-acao [action-id action-args ctx] ...)` retornando `{:status :success :action-id action-id}`.\n"
       "   - REGRA DE PRATA ESPACIAL (CRÍTICO): Comandos Minecraft enviados via socket console executam na origem [0, 0, 0] se não forem explicitamente ancorados. Para qualquer comando que interaja com entidades próximas, seletores (@e, @p) ou coordenadas relativas, você DEVE prefixar com `execute at Karlach run ...` (exemplo: `\"CMD|execute at Karlach run ride Karlach mount @e[type=boat,distance=..6,sort=nearest,limit=1]\"`). NUNCA envie comandos relativos sem ancoragem espacial.\n"
       "3. Em `test/craft/agents/epic5_cognition_mcp_studio_test.clj`:\n"
       "   - Cobrir com teste unitário a nova ação e mapeamento mantendo 100% GREEN.\n"
       "   - Não modifique testes existentes (Locked-Test protocol)."))

(defn- handle-capability-gap!
  "Lacuna motora: despacha tarefa de auto-engenharia (RSI) e avisa no chat."
  [engine-ctx-atom instruction]
  (let [slug (composer/slugify instruction)
        tid  (str "ca-gap-" (subs slug 0 (min (count slug) 24)) "-" (System/currentTimeMillis))
        hyp  {:hypothesis/type :frontier-exploration
              :target/category :motor-capability-gap
              :target/identifier slug
              :prompt/directive (gap-directive instruction)}]
    (try
      (curriculum/dispatch-curiosity-to-swe!
       hyp
       {:repo-dir "/home/pace/dev/minecraft/craft-agents"
        :task-id tid
        :model "deepseek-v4-1-flash"
        :test-cmd "make test"
        :target-files ["src/clj/craft/agents/skills/composer.clj"
                       "src/clj/craft/agents/skills/actions.clj"
                       "test/craft/agents/epic5_cognition_mcp_studio_test.clj"]})
      (catch Throwable t
        (println "[curriculum] Erro ao despachar SWE:" (.getMessage t))))
    (transport/send-bridge-cmd! "SWING|Karlach")
    (transport/send-bridge-cmd! "CMD|playsound minecraft:block.beacon.activate master @a ~ ~ ~ 1.0 1.2")
    (record-cognitive-event! engine-ctx-atom :rsi "Curriculum RSI Loop"
                             (str "Lacuna motora detectada no chat: '" instruction "'. Tarefa " tid " despachada para target/curiosity-queue/."))
    (actions/karlach-say! (str "Soldado, meu corpo ainda não tem a capacidade motora para '" instruction "'! ⚡ Despachei uma tarefa de auto-engenharia (" tid ") para a fábrica autônoma. O SWE-Runner está programando essa habilidade agora!"))))

(defmethod execute-karlach-goal! :teach-skill
  [{:goal/keys [instruction speaker]} engine-ctx-atom _nav-state _sanctuary-route]
  (let [skills-dir (io/file "skills")
        _ (.mkdirs skills-dir)
        skill-reg (atom {:known-actions composer/default-known-actions
                         :composite-skills {}})
        synth-res (composer/synthesize-and-register-skill-from-chat! skill-reg skills-dir instruction speaker)]
    (if (:ok? synth-res)
      (let [sid   (:skill-id synth-res)
            sname (:skill-name synth-res)
            sdef  (:skill-def synth-res)]
        (transport/send-bridge-cmd! "SWING|Karlach")
        (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.player.levelup master @a ~ ~ ~ 1.0 1.2")
        (record-cognitive-event! engine-ctx-atom :synthesis "System 2 Voyager Synthesizer"
                                 (str "Nova skill sintetizada do chat: '" sname "' -> " (pr-str (:skill/tree sdef))))
        (record-cognitive-event! engine-ctx-atom :gatekeeper "Malli Schema Gatekeeper"
                                 (str "Skill " sid " APROVADA: Schema BTNode OK / Ciclos 0 / Ações conhecidas conferidas."))
        (record-cognitive-event! engine-ctx-atom :skill-lib "Skill Library"
                                 (str sid " persistida permanentemente em skills/" (clojure.core/name sid) ".edn."))
        (when engine-ctx-atom
          (swap! engine-ctx-atom update-in [:agents :karlach :skills] (fnil #(vec (distinct (conj % (clojure.core/name sid)))) []))
          (swap! engine-ctx-atom assoc-in [:agents :karlach :last-learned] (str sid " (Chat OK)")))
        (actions/karlach-say! (str "Entendido, " (or speaker "soldado") "! Nova habilidade '" sname "' aprendida e gravada na minha memória! 🔥 Experimente dizer '" sname "' quando quiser que eu execute!")))
      (let [reason (:reason synth-res)
            msg    (or (:message synth-res) (str reason))]
        (if (contains? #{:UNRECOGNIZED_STEPS :UNKNOWN_ACTION} reason)
          (handle-capability-gap! engine-ctx-atom instruction)
          (do
            (transport/send-bridge-cmd! "CMD|playsound minecraft:block.note_block.bass master @a ~ ~ ~ 1.0 0.8")
            (actions/karlach-say! (str "Soldado, não consegui aprender essa habilidade: " msg ". Tente algo como 'karlach aprenda danca-e-senta: dance e sente no sofa'!"))))))))

(defmethod execute-karlach-goal! :execute-dynamic-skill
  [{:goal/keys [skill-id speaker]} engine-ctx-atom nav-state sanctuary-route]
  (let [skills-dir (io/file "skills")
        skill-file (io/file skills-dir (str (clojure.core/name skill-id) ".edn"))]
    (if (.exists skill-file)
      (let [skill-def  (try (edn/read-string (slurp skill-file)) (catch Throwable _ nil))
            skill-tree (:skill/tree skill-def)
            skill-name (or (:skill/name skill-def) (clojure.core/name skill-id))]
        (record-cognitive-event! engine-ctx-atom :skill-lib "Execução de Skill Aprendida"
                                 (str "Iniciando AST da skill " skill-id ": " (pr-str skill-tree)))
        (actions/karlach-say! (str "Executando habilidade aprendida '" skill-name "', " (or speaker "soldado") "! 🔥"))
        (transport/send-bridge-cmd! "SWING|Karlach")
        (transport/send-bridge-cmd! "CMD|playsound minecraft:entity.experience_orb.pickup master @a ~ ~ ~ 1.0 1.2")
        (bt/execute-tree-node! skill-tree engine-ctx-atom nav-state sanctuary-route speaker))
      (actions/karlach-say! (str "Não encontrei os registros da skill " skill-id " no meu córtex, " (or speaker "soldado") "!")))))

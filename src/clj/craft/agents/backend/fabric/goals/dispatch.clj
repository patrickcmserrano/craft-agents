(ns craft.agents.backend.fabric.goals.dispatch
  "Despacho de metas cognitivas por agente (CA-085, CA-091).

   `execute-goal!` e o multimethod unico: recebe a meta fundamentada (GoalSpecification) e o
   contexto do agente
     {:agent agent-spec :state agent-atom :engine-ctx engine-ctx-atom :sanctuary-route route}
   e despacha por `:goal/type`. Os defmethod vivem em goals.rescue/companion/work/furniture/skills;
   a fachada craft.agents.backend.fabric.goals garante o require de todos.

   Metas de papel (`role-goals`) so executam se o agente declara a capacidade de mesmo nome em
   `:agent/capabilities`; sem ela, o agente responde com uma fala de recusa e nada se move.

   `execute-karlach-goal!` e o wrapper legado: monta o contexto da Karlach e chama `execute-goal!`."
  (:require [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.speech :as speech]))

(def role-goals
  "Metas de papel: tipo da meta -> capacidade exigida em `:agent/capabilities`."
  {:animal-rescue    :animal-rescue
  :breed-animals    :breed-animals
  :patrol-sanctuary :patrol-sanctuary})

(def ^:private karlach-fallback
  {:agent/id :karlach :agent/name "Karlach"
   :agent/capabilities (set (vals role-goals))})

(defn karlach-agent
  "Spec da Karlach no registro (com fallback se o registro nao a tem)."
  []
  (or (registry/agent-by-id :karlach) karlach-fallback))

(defn agent-id [ctx]
  (or (:agent-id ctx)
      (get-in ctx [:agent :agent/id])))

(defn agent-spec [ctx]
  (or (:agent ctx)
      (when-let [id (agent-id ctx)]
        (if (= :karlach id)
          (karlach-agent)
          (registry/agent-by-id id)))))

(defn agent-name [ctx]
  (or (:agent-name ctx)
      (get-in ctx [:agent :agent/name])
      (:agent/name (agent-spec ctx))))

(defn nav-state [ctx]
  (or (:nav-state ctx)
      (:state ctx)))

(defn say!
  "Fala `msg` como o agente do contexto (voz do registro). A Karlach mantem o caminho
   `actions/karlach-say!`, que os testes de caracterizacao observam."
  [ctx msg]
  (if (= :karlach (agent-id ctx))
    (actions/karlach-say! msg)
    (speech/say! (agent-id ctx) msg)))

(defn missing-capability
  "A capacidade que `goal-spec` exige e o agente do `ctx` nao declara, ou `nil`."
  [goal-spec ctx]
  (when-let [cap (get role-goals (:goal/type goal-spec))]
    (let [spec (agent-spec ctx)]
      (when-not (contains? (:agent/capabilities spec) cap)
        cap))))

(defmulti execute-goal!
  "Despacha a meta cognitiva fundamentada para atuacao fisica e telemetria (CA-040, CA-091)."
  (fn [goal-spec ctx]
    (if (missing-capability goal-spec ctx) ::refused (:goal/type goal-spec))))

(defmethod execute-goal! ::refused
  [{:goal/keys [speaker] :as goal-spec} ctx]
  (let [cap (missing-capability goal-spec ctx)]
    (record-cognitive-event! (:engine-ctx ctx) :motor "System 1 Motor Controller"
                             (str (agent-name ctx) " recusou a meta " cap ": capacidade ausente em :agent/capabilities"))
    (say! ctx (str "Isso não é comigo, " (or speaker "amigo") "! Eu não tenho a capacidade de "
                   (name cap) "."))))

(defmethod execute-goal! :default
  [{:goal/keys [raw-text voice-line]} ctx]
  (record-cognitive-event! (:engine-ctx ctx) :synthesis "System 2 Voyager Synthesizer"
                           (str "Analisando comando natural: '" raw-text "'. Decompondo em diretriz reativa."))
  (say! ctx voice-line)
  (record-cognitive-event! (:engine-ctx ctx) :motor "System 1 Motor Controller" "Executando diretriz"))

;; ----------------------------------------------------------------------------
;; Wrapper legado da Karlach
;; ----------------------------------------------------------------------------

(defn karlach-ctx
  "Contexto de `execute-goal!` para a Karlach."
  [engine-ctx-atom nav-state sanctuary-route]
  (let [spec (karlach-agent)]
    {:agent spec
     :agent-id (:agent/id spec)
     :agent-name (:agent/name spec)
     :state nav-state
     :nav-state nav-state
     :engine-ctx engine-ctx-atom
     :sanctuary-route sanctuary-route}))

(defmulti execute-karlach-goal!
  "Wrapper: despacha a meta com o contexto da Karlach. A fachada registra um metodo por
   tipo de meta de `execute-goal!`; `:default` cobre os demais."
  (fn [goal-spec _engine-ctx _nav-state _sanctuary-route] (:goal/type goal-spec)))

(defn- karlach-wrapper
  [goal-spec engine-ctx-atom nav-state sanctuary-route]
  (execute-goal! goal-spec (karlach-ctx engine-ctx-atom nav-state sanctuary-route)))

(defmethod execute-karlach-goal! :default
  [goal-spec engine-ctx-atom nav-state sanctuary-route]
  (karlach-wrapper goal-spec engine-ctx-atom nav-state sanctuary-route))

(defn register-karlach-wrappers!
  "Registra em `execute-karlach-goal!` um metodo para cada tipo de meta de `execute-goal!`."
  []
  (doseq [t (keys (methods execute-goal!))
          :when (and (keyword? t) (not= t :default) (not= t ::refused))]
    (.addMethod ^clojure.lang.MultiFn execute-karlach-goal! t karlach-wrapper)))

(ns craft.agents.npc.state
  "Estado por agente (CA-089, CA-101): mapa `agent-id -> atom` dentro do engine-ctx.

   `(agent-atom ctx id)` cria sob demanda o atomo do agente com os defaults
   `{:pos <spawn do registro> :mode :idle :path [] :watchdog nil
     :cooldowns {} :events [] :leases {:legs nil :hands nil :gaze nil}}`.
   Um estado inicial opcional e mesclado sobre os defaults, apenas na criacao.
   O mapa vive em `(:agent-states @ctx)`; `ctx` e o atom do engine-ctx."
  (:require [craft.agents.npc.registry :as registry]))

(def default-leases
  "Mapa default de canais de lease por agente."
  {:legs nil :hands nil :gaze nil})

(def max-agent-events
  "Limite do historico circular de eventos por agente."
  40)

(defn default-state
  "Estado default de um agente: posicao do spawn (nil se o agente nao esta no registro),
   modo, rota, watchdog e namespaces isolados de `:cooldowns`, `:events` e `:leases` (CA-101)."
  [id]
  {:pos (get-in (registry/agent-by-id id) [:agent/spawn :pos])
   :mode :idle
   :path []
   :watchdog nil
   :cooldowns {}
   :events []
   :leases default-leases})

(defn agent-atom
  "Atomo de estado do agente `id`, criado sob demanda e guardado em `ctx`."
  ([ctx id] (agent-atom ctx id nil))
  ([ctx id init]
   (or (get-in @ctx [:agent-states id])
       (let [fresh (atom (merge (default-state id) init))]
         (-> (swap! ctx update :agent-states
                    (fn [m] (if (contains? m id) m (assoc (or m {}) id fresh))))
             (get-in [:agent-states id]))))))

(defn- read-agent-state
  [ctx id]
  (let [a (if (instance? clojure.lang.IAtom ctx)
            (agent-atom ctx id)
            (get-in ctx [:agent-states id]))]
    (if (instance? clojure.lang.IDeref a) @a (or a (default-state id)))))

(defn set-cooldown!
  "Define o cooldown `cooldown-key` isolado para o agente `id` em `(:agent-states @ctx)` (CA-101)."
  ([ctx id cooldown-key]
   (set-cooldown! ctx id cooldown-key (System/currentTimeMillis)))
  ([ctx id cooldown-key until]
   (let [a (agent-atom ctx id)]
     (swap! a update :cooldowns (fn [m] (assoc (or m {}) cooldown-key until)))
     until))
  ([ctx id cooldown-key duration now]
   (set-cooldown! ctx id cooldown-key (+ (long now) (long duration)))))

(defn cooldown-active?
  "Verifica se o cooldown `cooldown-key` do agente `id` esta ativo em `(:agent-states @ctx)` (CA-101)."
  ([ctx id cooldown-key]
   (let [v (get-in (read-agent-state ctx id) [:cooldowns cooldown-key])]
     (cond
       (nil? v)     false
       (boolean? v) v
       (number? v)  (pos? v)
       (map? v)     (pos? (or (:until v) (:expires-at v) 0))
       :else        (boolean v))))
  ([ctx id cooldown-key now]
   (let [v (get-in (read-agent-state ctx id) [:cooldowns cooldown-key])]
     (cond
       (nil? v)     false
       (boolean? v) v
       (number? v)  (> (double v) (double now))
       (map? v)     (> (double (or (:until v) (:expires-at v) 0)) (double now))
       :else        false)))
  ([ctx id cooldown-key now span]
   (let [v (get-in (read-agent-state ctx id) [:cooldowns cooldown-key])]
     (boolean (and (number? v) (< (- (double now) (double v)) (double span)))))))

(defn record-agent-event!
  "Registra um evento isolado em `:events` do agente `id` dentro de `(:agent-states @ctx)` (CA-101)."
  ([ctx id evt]
   (let [a     (agent-atom ctx id)
         base  (if (map? evt) evt {:text (str evt)})
         entry (merge {:timestamp (System/currentTimeMillis)
                       :agent-id  id}
                      base
                      {:agent-id (or (:agent-id base) id)})]
     (:events
      (swap! a update :events
             (fn [evts]
               (vec (take-last max-agent-events (conj (vec (or evts [])) entry))))))))
  ([ctx id evt-type text]
   (record-agent-event! ctx id {:type evt-type :text text}))
  ([ctx id evt-type source text]
   (record-agent-event! ctx id {:type evt-type :source source :text text})))

(defn agent-events
  "Retorna o vetor de eventos isolados do agente `id` (CA-101)."
  [ctx id]
  (vec (or (:events (read-agent-state ctx id)) [])))

(defn agent-leases
  "Retorna (aridade 2) ou atualiza (aridade 3) o mapa `:leases` isolado do agente `id` em `(:agent-states @ctx)` (CA-101)."
  ([ctx id]
   (merge default-leases (:leases (read-agent-state ctx id))))
  ([ctx id leases-map]
   (let [a (agent-atom ctx id)]
     (:leases
      (swap! a update :leases
             (fn [cur]
               (merge default-leases cur leases-map)))))))


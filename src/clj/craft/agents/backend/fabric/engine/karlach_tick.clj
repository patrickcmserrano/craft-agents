(ns craft.agents.backend.fabric.engine.karlach-tick
  "Tick (20Hz) da Guardia Karlach: roteia pelo modo (danca, follow/bodyguard com
   barco, mineracao, abrigo de chuva, patrulha/cercados) e publica o estado no
   engine-ctx do Agent Studio. As decisoes puras vivem em follow e patrol e sao
   reexportadas aqui."
  (:require [craft.agents.backend.fabric.actions :as actions :refer [karlach-say!]]
            [craft.agents.backend.fabric.engine.follow :as follow]
            [craft.agents.backend.fabric.engine.mining :as mining]
            [craft.agents.backend.fabric.engine.patrol :as patrol]
            [craft.agents.backend.fabric.engine.shelter
             :refer [rain-shelter-decision shelter-route-steps stand-karlach!]]
            [craft.agents.backend.fabric.sanctuary :refer [sanctuary-route]]
            [craft.agents.backend.fabric.sentry :refer [proactive-sentry-scan!]]
            [craft.agents.backend.fabric.transport :as transport :refer [send-bridge-cmd! uformat]]
            [craft.agents.npc.perception-tick :as perception-tick]))

;; Decisoes puras do tick da Karlach (definidas em follow/patrol).
(def follow-tick-decision follow/follow-tick-decision)
(def boat-transition follow/boat-transition)
(def patrol-step-decision patrol/patrol-step-decision)

(defn- with-cards
  "Acrescenta ao `env` os cartoes do Agent Studio do tick atual:
   :picard-card, :make-karlach-card e :make-agents-map (status pos -> mapa de agentes)."
  [{:keys [engine-ctx-atom picard-state coco-state richeh-state] :as env}]
  (let [picard-card (let [[px py pz] (:pos @picard-state)]
                      {:status (:status @picard-state)
                       :pos [(/ (Math/round (* px 10.0)) 10.0)
                             (double py)
                             (/ (Math/round (* pz 10.0)) 10.0)]})
        coco-card   {:status (:status @coco-state) :pos (:pos @coco-state)}
        richeh-card {:status (:status @richeh-state) :pos (:pos @richeh-state)}
        k-skills (vec (or (get-in @engine-ctx-atom [:agents :karlach :skills]) []))
        make-karlach-card (fn [status pos]
                            (cond-> {:status status :pos pos}
                              (seq k-skills) (assoc :skills k-skills)))]
    (assoc env
           :picard-card picard-card
           :make-karlach-card make-karlach-card
           :make-agents-map (fn [k-status k-pos]
                              {:karlach (make-karlach-card k-status k-pos)
                               :picard  picard-card
                               :coco    coco-card
                               :richeh  richeh-card}))))

(defn- shelter-decision [st]
  (rain-shelter-decision {:raining?    (:raining? (:last-weather st))
                          :thundering? (:thundering? (:last-weather st))
                          :mode        (:mode st)
                          :sheltering? (:sheltering? st false)}))

(defn- tick-dance!
  "1. Dance override."
  [{:keys [nav-state engine-ctx-atom tick make-agents-map]} st]
  (let [[cx cy cz] (:pos st)
        nyaw (float (mod (+ (:yaw st) 32.0) 360.0))]
    (send-bridge-cmd!
     (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz nyaw))
    (when (zero? (mod tick 5))
      (send-bridge-cmd! "SWING|Karlach")
      (send-bridge-cmd!
       (uformat "CMD|particle minecraft:note %.2f %.2f %.2f 0.3 0.3 0.3 1.0 2 normal"
                cx (+ cy 2.0) cz)))
    (swap! nav-state #(-> %
                          (update :dance-ticks dec)
                          (assoc :yaw nyaw)))
    (swap! engine-ctx-atom assoc
           :tick tick
           :leases {:legs {:owner :dance} :hands {:owner :dance} :gaze {:owner :dance}}
           :agents (make-agents-map "dancing 🔥" [cx cy cz]))))

(defn- tick-shelter!
  "CA-060: nas BORDAS (comeco/fim da chuva) interrompe a patrulha e enfileira o
   trajeto ate o Quarto da Karlach (:shelter) ou devolve a ronda (:resume).
   Enquanto chove (:wait) o ramo e falso de proposito, para a patrulha consumir
   os micro-steps com a mesma maquina de dead reckoning."
  [{:keys [nav-state engine-ctx-atom tick make-agents-map]} st decision]
  (let [[cx cy cz] (:pos st)]
    (case (:action decision)
      :shelter
      ;; Chovendo e na rua: aborta a patrulha e enfileira o trajeto ao quarto.
      (do
        (when (:speak? decision)
          (karlach-say! "Chovendo aqui fora, soldado! Vou me recolher ao quarto até o tempo abrir. 🔥🌧️"))
        (let [quarters (some #(when (= (:type %) :quarters) %) sanctuary-route)
              steps    (vec (or (shelter-route-steps quarters [cx cy cz]) []))]
          (swap! nav-state assoc :sheltering? true :steps steps))
        (swap! engine-ctx-atom assoc :tick tick
               :leases {:legs {:owner :rain-shelter} :hands nil :gaze {:owner :path-look}}
               :agents (make-agents-map "Abrigada da chuva 🌧️" [cx cy cz])))

      ;; :resume — tempo abriu: levanta do banco, limpa o abrigo e devolve a rota normal.
      (do
        (when (:speak? decision)
          (karlach-say! "O tempo abriu! Voltando à ronda do santuário, soldado! ☀️"))
        (when (:seated? st false)
          (stand-karlach!))
        (swap! nav-state assoc :sheltering? false :seated? false :steps [])
        (swap! engine-ctx-atom assoc :tick tick
               :leases nil
               :agents (make-agents-map "Retomando a patrulha ☀️" [cx cy cz]))))))

(defn- tick-other-followers!
  "CA-092: qualquer agente (fora a Karlach) com `:mode :follow` no seu estado segue o
   lider (a meta :follow-player da CA-091 liga isso). Erro de um agente nao derruba o tick."
  [{:keys [engine-ctx-atom] :as env}]
  (doseq [[id st-atom] (:agent-states @engine-ctx-atom)
          :when (and (not= id :karlach)
                     (contains? #{:follow :bodyguard} (:mode @st-atom)))]
    (try (follow/tick-follow! (with-cards env) id)
         (catch Throwable t
           (println "[follow-ERROR]" id (.getMessage t))))))

(defn tick-karlach!
  "Um tick da Karlach. `env`: :engine-ctx-atom :nav-state :picard-state :coco-state
   :richeh-state :tick."
  [{:keys [engine-ctx-atom nav-state tick] :as env0}]
  (let [t0-ns     (System/nanoTime)
        cmd-count (atom 0)
        orig-send transport/send-bridge-cmd!
        orig-qop  actions/query-online-players]
    (with-redefs [transport/send-bridge-cmd!
                  (fn [line]
                    (swap! cmd-count inc)
                    (orig-send line))
                  actions/query-online-players
                  (fn []
                    (let [snap (perception-tick/capture-snapshot!
                                engine-ctx-atom
                                {:tick     (or tick (when engine-ctx-atom (:tick @engine-ctx-atom)) 0)
                                 :agent-id :karlach
                                 :query-players-fn
                                 (when-not (identical? orig-qop perception-tick/default-query-online-players)
                                   orig-qop)})]
                      (:players snap)))]
      (when (zero? (mod tick 25))
        (let [[kx ky kz] (:pos @nav-state)]
          (send-bridge-cmd!
           (uformat "CMD|particle minecraft:flame %.2f %.2f %.2f 0.15 0.2 0.15 0.01 3 normal"
                    kx (+ ky 1.1) kz))
          (send-bridge-cmd!
           (uformat "CMD|particle minecraft:smoke %.2f %.2f %.2f 0.15 0.25 0.15 0.01 2 normal"
                    kx (+ ky 1.2) kz))))
      (tick-other-followers! env0)
      (let [env (with-cards env0)
            st  @nav-state]
        ;; CA-060: expoe o modo e o clima da Karlach para observabilidade
        ;; (nav-state e let local, ilegivel do REPL; o ctx e legivel).
        (swap! engine-ctx-atom assoc
               :karlach-mode (:mode st)
               :karlach-nav-pos (:pos st)
               :weather (:last-weather st)
               :sheltering? (:sheltering? st false)
               :shelter-steps-left (count (:steps st))
               :shelter-step-head (first (:steps st)))
        (let [patrolling? (= (:mode st) :sanctuary-patrol)
              shelter     (when patrolling? (shelter-decision st))]
          (cond
            (pos? (:dance-ticks st 0))                       (tick-dance! env st)
            (contains? #{:follow :bodyguard} (:mode st))     (follow/tick-follow! env :karlach)
            (= (:mode st) :mining)                           (mining/tick-mining! env st)
            (contains? #{:shelter :resume} (:action shelter)) (tick-shelter! env st shelter)

            ;; Patrulha & cuidado dos cercados (desligado durante o abrigo: nao sai
            ;; para resgatar animal na chuva)
            (and patrolling?
                 (not (:sheltering? st false))
                 (proactive-sentry-scan! engine-ctx-atom nav-state sanctuary-route))
            nil

            :else (patrol/tick-patrol! env st)))))
    (when engine-ctx-atom
      (let [elapsed-ms (/ (double (- (System/nanoTime) t0-ns)) 1000000.0)]
        (perception-tick/record-tick-metrics! engine-ctx-atom
                                              {:tick          (or tick (:tick @engine-ctx-atom) 0)
                                               :tick-ms       elapsed-ms
                                               :cmds-per-tick @cmd-count})))
    nil))

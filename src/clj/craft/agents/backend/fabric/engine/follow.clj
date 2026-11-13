(ns craft.agents.backend.fabric.engine.follow
  "Follow / bodyguard (Navegacao Universal via A* 3D): decisoes puras (barco,
   teleporte de aproximacao, debounce de perda, `follow-tick`) e o tick com combate
   de escolta, voo de elytra e watchdog (CA-049, CA-065, CA-066).

   CA-092: o tick e parametrizado pelo agente: `(tick-follow! env agent-id)`. Qualquer
   agente com `:mode :follow` no seu estado segue o lider; a Karlach mantem o combate de
   escolta e o cartao do Studio. Limitacao: os ops Java PATH/GROUND ainda nao recebem o
   nome do agente (dependem da CA-095) e continuam no mundo da Karlach."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :refer [attack-hostile-target! karlach-say!
                                                         query-online-players
                                                         query-sitting-status
                                                         scan-nearby-hostiles]]
            [craft.agents.backend.fabric.engine.combat :as combat
             :refer [battle-cry-ready? evaluate-combat-preemption strike-ready?]]
            [craft.agents.backend.fabric.engine.watchdog :as ewd]
            [craft.agents.backend.fabric.locomotion :as loco]
            [craft.agents.backend.fabric.navigation :as nav
             :refer [compute-yaw request-path3d]]
            [craft.agents.backend.fabric.sanctuary :refer [close-portal-at-coord! open-portal-at-coord!]]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd! uformat]]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.state :as npc-state]
            [craft.agents.npc.speech :as speech]))

(def ^:private teleport-distance-m 48.0)
(def ^:private hold-distance-m 2.6)
(def ^:private lost-debounce-ticks 60)

(defn- ride-boat-cmd [agent-name]
  (str "CMD|execute at " agent-name " run ride " agent-name
       " mount @e[type=boat,distance=..4,sort=nearest,limit=1]"))

(defn follow-tick-decision
  "Decisao pura do follow. Entrada: {:pos [x y z] :leader [x y z] :in-vehicle? bool
   :hostiles [...]}. Saida: {:dist (horizontal) :dist3 :yaw :teleport? (> 48m 3D, outro
   bioma) :combat (evaluate-combat-preemption)}."
  [{:keys [pos leader in-vehicle? hostiles]}]
  (let [[cx cy cz] pos
        [tx ty tz] leader
        dx (- tx cx)
        dy (- (double ty) (double cy))
        dz (- tz cz)
        dist3 (Math/sqrt (+ (* dx dx) (* dz dz) (* dy dy)))]
    {:dist (Math/sqrt (+ (* dx dx) (* dz dz)))
     :dist3 dist3
     :yaw (compute-yaw dx dz)
     :teleport? (> dist3 teleport-distance-m)
     :combat (evaluate-combat-preemption pos leader hostiles in-vehicle?)}))

(defn follow-hold?
  "true se ela ja esta colada no lider (<= 2.6m) e nao em recall de tether."
  [dist action]
  (and (<= dist hold-distance-m) (not= action :tether-snap-recall)))

(defn follow-tick
  "Passo puro de locomocao do follow (CA-092). Entrada: {:agent-name :pos :leader :path
   :speed :sprint :sneak :hold? (default: dist <= 2.6m)}. Saida: {:commands [\"MOVE|<nome>|...\"]
   :pos :yaw :path :holding?}. Colado no lider: so gira e espelha o agachar; senao avanca
   `path` (NUNCA atravessa paredes: sem caminho, gira e espera)."
  [{:keys [agent-name pos leader path speed sprint sneak hold?]}]
  (let [[cx cy cz] pos
        [tx _ tz] leader
        dx (- tx cx)
        dz (- tz cz)
        dist (Math/sqrt (+ (* dx dx) (* dz dz)))
        yaw (compute-yaw dx dz)
        sn (str (boolean sneak))
        hold (if (some? hold?) hold? (follow-hold? dist nil))
        stand (fn [] {:commands [(uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|0.0|false|%s"
                                          agent-name (double cx) (double cy) (double cz) yaw sn)]
                      :pos [cx cy cz] :yaw yaw :path [] :holding? hold})]
    (if (or hold (empty? path))
      (stand)
      (let [r (loco/advance-path {:pos [cx cy cz] :path path :speed speed :yaw yaw})
            [sx sy sz] (:pos r)]
        {:commands [(uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|0.0|%s|%s"
                             agent-name sx sy sz (:yaw r) (str (boolean sprint)) sn)]
         :pos (:pos r) :yaw (:yaw r) :path (:path r) :holding? false}))))

(defn boat-transition
  "Sincronia Terra-Barco (2Hz). Entrada: {:agent-name (default \"Karlach\") :actual-sitting?
   :target-sitting? :dist} (sitting = montado no barco; nil = leitura ausente). Saida:
   {:commands [..] :in-vehicle? bool}, sem :in-vehicle? quando nada muda. Lider desembarcou ->
   STAND; lider embarcou e ela esta a <= 3.5m fora do barco -> monta."
  [{:keys [agent-name actual-sitting? target-sitting? dist] :or {agent-name "Karlach"}}]
  (cond
    (nil? actual-sitting?)
    {:commands []}

    (and actual-sitting? (false? target-sitting?))
    {:commands [(str "STAND|" agent-name)] :in-vehicle? false}

    (and (false? actual-sitting?) target-sitting? (<= dist 3.5))
    {:commands [(ride-boat-cmd agent-name)] :in-vehicle? true}

    :else
    {:commands [] :in-vehicle? actual-sitting?}))

(defn follow-lost-decision
  "Debounce contra oscilacao do socket quando o lider some da lista de jogadores:
   :wait (conta mais um tick) ou :give-up apos 60 ticks, voltando a patrulha."
  [st]
  (let [lost-cnt (inc (:follow-lost-ticks st 0))]
    (if (>= lost-cnt lost-debounce-ticks)
      {:action :give-up :follow-lost-ticks 0}
      {:action :wait :follow-lost-ticks lost-cnt})))

(defn- sync-boat! [nav-state agent-name target-name dist]
  (try
    (let [r (boat-transition {:agent-name agent-name
                              :actual-sitting? (query-sitting-status agent-name)
                              :target-sitting? (query-sitting-status target-name)
                              :dist dist})]
      (run! send-bridge-cmd! (:commands r))
      (when (contains? r :in-vehicle?)
        (swap! nav-state assoc :in-vehicle? (:in-vehicle? r))))
    (catch Throwable _ nil)))

(defn- karlach? [{:keys [agent-id]}] (= :karlach (or agent-id :karlach)))

(defn- publish!
  "Publica no engine-ctx. Karlach: :tick/:leases/:agents (como antes). Outros agentes
   so atualizam a propria entrada em :agents (nao pisam nos leases globais)."
  [{:keys [engine-ctx-atom tick agent-id] :as env} leases karlach-agents status pos]
  (if (karlach? env)
    (swap! engine-ctx-atom assoc :tick tick :leases leases :agents karlach-agents)
    (swap! engine-ctx-atom update :agents assoc agent-id {:status status :pos pos})))

(defn- follow-glide!
  "Voo de elytra espelhando o lider: tem prioridade sobre combate/A*."
  [{:keys [nav-state agent-name make-agents-map] :as env} st target-p dist3]
  (let [{tx :x ty :y tz :z} target-p
        phase (loco/glide-phase target-p (:gliding? @nav-state) dist3)]
    (case phase
      (:fly :land)
      (let [r (loco/flight-step {:pos (:pos @nav-state) :leader [tx ty tz]
                                 :trail (if (= phase :fly) 3.0 1.5) :max-step 2.5})
            [fx fy fz] (:pos r)]
        (when-not (:gliding? @nav-state)
          (send-bridge-cmd! (str "CMD|item replace entity " agent-name " armor.chest with minecraft:elytra"))
          (send-bridge-cmd! (str "CMD|tag " agent-name " add craft_glide")))
        (send-bridge-cmd! (uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|%.1f|true|false"
                                   agent-name fx fy fz (double (:yaw r)) (double (:pitch r))))
        (swap! nav-state assoc :gliding? true :pos [fx fy fz] :yaw (:yaw r) :follow-path []
               :watchdog nil)
        (publish! env {:legs {:owner :elytra-follow} :hands nil :gaze {:owner :elytra-follow}}
                  (when (karlach? env) (make-agents-map "🪽 voando com você" [fx fy fz]))
                  "🪽 voando com você" [fx fy fz]))
      :touchdown
      (let [[px py pz] (:pos @nav-state)
            gy (or (nav/query-ground-y [px py pz]) py)]
        (send-bridge-cmd! (str "CMD|tag " agent-name " remove craft_glide"))
        (send-bridge-cmd! (uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                                   agent-name px (double gy) pz (double (:yaw st 0.0))))
        (swap! nav-state assoc :gliding? false :pos [px (double gy) pz] :follow-path []))
      nil)))

(defn- follow-combat!
  "Varredura tatica e preempcao de combate reativo (CA-049) + locomocao de escolta."
  [{:keys [nav-state tick agent-name make-agents-map make-karlach-card picard-card] :as env}
   st target-p]
  (let [[cx cy cz] (:pos st)
        {tx :x ty :y tz :z} target-p
        yaw (compute-yaw (- tx cx) (- tz cz))]
    (let [hostiles (if (zero? (mod tick 4))
                     (try (let [h (scan-nearby-hostiles agent-name 14.0)]
                            (swap! nav-state assoc :last-hostiles h)
                            h)
                          (catch Throwable _ (:last-hostiles @nav-state [])))
                     (:last-hostiles @nav-state []))
          in-veh?  (:in-vehicle? st false)
          decision (:combat (follow-tick-decision {:pos [cx cy cz] :leader [tx ty tz]
                                                   :in-vehicle? in-veh? :hostiles hostiles}))]
      (case (:action decision)
        :combat-strike
        (let [[mx my mz] (:target-pos decision)
              target-id  (:target-id decision)
              hdx        (- (double mx) (double cx))
              hdz        (- (double mz) (double cz))
              hyaw       (compute-yaw hdx hdz)]
          (send-bridge-cmd! (uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|0.0|false|false" agent-name cx cy cz hyaw))
          (when (strike-ready? (:last-strike-tick @nav-state) tick)
            (swap! nav-state assoc :last-strike-tick tick)
            (when target-id (attack-hostile-target! agent-name target-id))
            (send-bridge-cmd! (str "SWING|" agent-name))
            (send-bridge-cmd! (uformat "CMD|particle minecraft:crit %.2f %.2f %.2f 0.3 0.3 0.3 0.1 5 normal"
                                      (double mx) (+ (double my) 1.0) (double mz))))
          (when (battle-cry-ready? (:last-battle-cry-tick @nav-state) tick)
            (swap! nav-state assoc :last-battle-cry-tick tick)
            (if (karlach? env)
              (karlach-say! "Toma essa! Não mexe com meu esquadrão! 🔥 🛡️")
              (speech/say! (:agent-id env) "Toma essa! Não mexe com meu esquadrão! 🛡️")))
          (publish! env {:legs {:owner :combat-strike} :hands {:owner :combat-strike} :gaze {:owner :combat-strike}}
                    (when (karlach? env) (make-agents-map "⚔️ em combate defensivo" (:pos @nav-state)))
                    "⚔️ em combate defensivo" (:pos @nav-state)))

        :combat-intercept
        (let [[mx my mz] (:target-pos decision)
              path-queue (:follow-path st [])
              last-target (:follow-last-target st)
              active-path (if (loco/needs-replan? {:path path-queue :pos [cx cy cz]
                                                   :target [mx my mz] :last-target last-target})
                            (request-path3d [cx cy cz] [mx my mz] 2.0)
                            path-queue)]
          (if (seq active-path)
            (let [r (loco/advance-path {:pos [cx cy cz] :path active-path
                                        :speed 0.28 :yaw yaw})
                  [step-x step-y step-z] (:pos r)]
              (send-bridge-cmd!
               (uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|0.0|true|false" agent-name step-x step-y step-z (:yaw r)))
              (swap! nav-state assoc :pos (:pos r) :yaw (:yaw r) :follow-path (:path r) :follow-last-target [mx my mz]))
            (let [hdx (- (double mx) (double cx))
                  hdz (- (double mz) (double cz))
                  hyaw (compute-yaw hdx hdz)]
              (send-bridge-cmd!
               (uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|0.0|false|false" agent-name cx cy cz hyaw))
              (swap! nav-state assoc :yaw hyaw)))
          (publish! env {:legs {:owner :combat-intercept} :hands nil :gaze {:owner :combat-intercept}}
                    (when (karlach? env) (make-agents-map "🏹 interceptando hostil" (:pos @nav-state)))
                    "🏹 interceptando hostil" (:pos @nav-state)))

        :remain-mounted-passive
        (do
          (send-bridge-cmd!
           (uformat "MOVE|%s|%.3f|%.3f|%.3f|%.1f|0.0|false|false" agent-name cx cy cz yaw))
          (publish! env {:legs nil :hands nil :gaze {:owner :passenger-look}}
                    (when (karlach? env) (make-agents-map "⛵ navegando embarcada com você" (:pos @nav-state)))
                    "⛵ navegando embarcada com você" (:pos @nav-state)))

        ;; Default: :tether-snap-recall ou :follow-player
        (let [;; CA-066: watchdog de progresso (so aqui: combate/barco
              ;; parados de proposito nao contam como travamento)
              st   (ewd/follow-watchdog-step! nav-state st (:name target-p) [tx ty tz] tick agent-name)
              [cx cy cz] (:pos st)
              dx   (- tx cx)
              dz   (- tz cz)
              dist (Math/sqrt (+ (* dx dx) (* dz dz)))
              gait (loco/mirror-gait target-p dist)
              hold? (follow-hold? dist (:action decision))
              path-queue (:follow-path st [])
              active-path (if (or hold?
                                  (not (loco/needs-replan? {:path path-queue :pos [cx cy cz]
                                                            :target [tx ty tz]
                                                            :last-target (:follow-last-target st)})))
                            path-queue
                            (request-path3d [cx cy cz] [tx ty tz] 2.2))
              next-node (first active-path)
              _    (when (and (not hold?) (= (:action next-node) :TRAVERSE_PORTAL))
                     (let [pcoord [(:x next-node) (:y next-node) (:z next-node)]]
                       (open-portal-at-coord! pcoord)
                       (swap! nav-state assoc :opened-portal pcoord)))
              r    (follow-tick {:agent-name agent-name :pos [cx cy cz] :leader [tx ty tz]
                                 :path active-path :speed (:speed gait)
                                 :sprint (:sprint gait) :sneak (:sneak gait) :hold? hold?})]
          (run! send-bridge-cmd! (:commands r))
          (if (seq (:path r))
            (swap! nav-state assoc :pos (:pos r) :yaw (:yaw r) :follow-path (:path r)
                   :follow-last-target [tx ty tz])
            (swap! nav-state assoc :yaw (:yaw r)
                   :follow-path []
                   :pos (if next-node (:pos r) (:pos @nav-state))))
          (when-let [op-portal (:opened-portal @nav-state)]
            (let [[ox _ oz] op-portal
                  pdist (Math/sqrt (+ (Math/pow (- cx (+ (double ox) 0.5)) 2)
                                      (Math/pow (- cz (+ (double oz) 0.5)) 2)))]
              (when (> pdist 2.2)
                (close-portal-at-coord! op-portal)
                (swap! nav-state assoc :opened-portal nil))))
          (let [status (if (= (:action decision) :tether-snap-recall)
                         "⚡ retornando ao líder (tether snap)"
                         (if (= (:mode st) :bodyguard)
                           (str "🛡️ guarda-costas de " (:name target-p))
                           (str "escoltando " (:name target-p))))]
            (publish! env {:legs {:owner :escort-player} :hands nil :gaze {:owner :social-gaze}}
                      {:karlach (when (karlach? env) (make-karlach-card status (:pos @nav-state)))
                       :picard  picard-card}
                      status (:pos @nav-state))))))))

(defn- find-player [players name]
  (first (filter #(= (str/lower-case (:name %)) (str/lower-case name)) players)))

(defn- agent-name-of [agent-id]
  (or (:agent/name (registry/agent-by-id agent-id))
      (str/capitalize (name agent-id))))

(defn tick-follow!
  "Um tick do modo :follow / :bodyguard do agente `agent-id` (default :karlach, CA-092).
   `env` traz os atoms e os construtores de cartao do tick (ver karlach-tick/tick-karlach!).
   O estado do agente e `:nav-state` (Karlach) ou `(npc-state/agent-atom ctx id)`."
  ([env] (tick-follow! env :karlach))
  ([env agent-id]
   (let [nav-state  (if (= agent-id :karlach)
                      (:nav-state env)
                      (npc-state/agent-atom (:engine-ctx-atom env) agent-id))
         agent-name (agent-name-of agent-id)
         env        (assoc env :nav-state nav-state :agent-id agent-id :agent-name agent-name)
         st         @nav-state
         tick       (:tick env)
         players  (query-online-players)
         target-p (find-player players (or (:follow-target st) "pace"))
         ;; CA-065: re-ancora nav-state :pos pela posicao real (mesma lista,
         ;; sem chamada extra). Se divergiu, forca replanejamento.
         real-pos (loco/agent-pos-from-players players agent-name)
         rec-pos  (loco/reconcile-pos (:pos st) real-pos 0.75)
         resync?  (not= rec-pos (:pos st))
         _        (when resync?
                    (swap! nav-state assoc :pos rec-pos :follow-path []))
         st       (if resync? (assoc st :pos rec-pos :follow-path []) st)]
     (if target-p
       (let [leader [(:x target-p) (:y target-p) (:z target-p)]
             d (follow-tick-decision {:pos (:pos st) :leader leader
                                      :in-vehicle? (:in-vehicle? st false)
                                      :hostiles (:last-hostiles @nav-state [])})]
         (swap! nav-state assoc :follow-lost-ticks 0)
         ;; Teleporte de aproximação se o jogador estiver muito distante (> 48m / outro bioma)
         (when (:teleport? d)
           (ewd/teleport-agent-to-leader! nav-state agent-name (:name target-p) leader))
         ;; Sincronização e Composição Multimodal Terra-Barco (2Hz)
         (when (zero? (mod tick 10))
           (sync-boat! nav-state agent-name (:name target-p) (:dist d)))
         (follow-glide! env st target-p (:dist3 d))
         (when-not (:gliding? @nav-state)
           (follow-combat! env st target-p)))
       ;; Debounce para evitar cancelamento por oscilação momentânea do socket
       (let [r (follow-lost-decision st)]
         (if (= :give-up (:action r))
           (do
             (swap! nav-state assoc :mode (if (= agent-id :karlach) :sanctuary-patrol :idle)
                    :steps [] :route-idx 0 :follow-lost-ticks 0)
             (if (= agent-id :karlach)
               (karlach-say! "Perdi você de vista, soldado! Vou voltar pra patrulha dos cercados.")
               (speech/say! agent-id "Perdi você de vista! Vou ficar por aqui.")))
           (swap! nav-state assoc :follow-lost-ticks (:follow-lost-ticks r))))))))

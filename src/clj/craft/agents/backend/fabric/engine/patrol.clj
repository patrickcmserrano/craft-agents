(ns craft.agents.backend.fabric.engine.patrol
  "Patrulha do santuario e cuidado dos cercados: consome os micro-steps de
   `(:steps nav-state)` com dead reckoning (CA-065, CA-066). A decisao do passo
   :walk e pura (`patrol-step-decision`); os demais ops animais vivem em patrol-ops."
  (:require [clojure.string]
            [craft.agents.backend.fabric.actions :refer [karlach-say! query-leash-status
                                                         query-online-players]]
            [craft.agents.backend.fabric.engine.patrol-ops :as ops]
            [craft.agents.backend.fabric.engine.shelter :refer [sit-karlach-at-quarters!]]
            [craft.agents.backend.fabric.engine.watchdog :refer [patrol-watchdog-fired!]]
            [craft.agents.backend.fabric.locomotion :as loco]
            [craft.agents.backend.fabric.navigation :refer [compute-yaw expand-route-microsteps
                                                            generate-walk-steps]]
            [craft.agents.backend.fabric.sanctuary :refer [barks-for-current-position
                                                           close-portal-at-coord!
                                                           current-enclosure-id
                                                           feed-enclosure-animals!
                                                           open-portal-at-coord! patrol-route
                                                           sanctuary-route set-double-gate!]]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd!]]
            [craft.agents.cognition.critic :as critic]
            [craft.agents.npc.body :as body]))

(defn- corner-pause?
  "true se a proxima perna :walk dobra mais de 30 graus em relacao a atual: o
   animal na guia precisa de uma pausa para contornar a curva."
  [dx dz next-step tx tz]
  (let [[ntx _ ntz] (:target next-step)
        ndx (- ntx tx)
        ndz (- ntz tz)
        ndist (Math/sqrt (+ (* ndx ndx) (* ndz ndz)))]
    (when (> ndist 0.1)
      (let [y1 (compute-yaw dx dz)
            y2 (compute-yaw ndx ndz)
            diff (Math/abs (- y1 y2))
            ydiff (if (> diff 180.0) (- 360.0 diff) diff)]
        (> ydiff 30.0)))))

(defn patrol-step-decision
  "Decisao pura do passo :walk. Entrada: {:cur-step :pos :leashed? :leash-info
   :next-step}. Saida (prioridade nesta ordem):
     {:action :leash-broken}               guia arrebentou durante o trajeto
     {:action :arrive :corner-pause? b}    chegou no alvo (<= walk-arrive-radius)
     {:action :advance :speed v}           caminha; 0.245 solta, 0.165 com guia,
                                           0.090 se o animal ficou a > 7.5m."
  [{:keys [cur-step pos leashed? leash-info next-step]}]
  (let [[cx _ cz] pos
        [tx _ tz] (:target cur-step)
        dx (- tx cx)
        dz (- tz cz)
        dist (Math/sqrt (+ (* dx dx) (* dz dz)))
        is-leashed? (or (boolean leashed?) (boolean (:species cur-step)))]
    (cond
      (and is-leashed? leashed? (false? (:leashed? leash-info)))
      {:action :leash-broken}

      (<= dist loco/walk-arrive-radius)
      {:action :arrive
       :corner-pause? (when (and is-leashed? (= (:op next-step) :walk) (:target next-step))
                        (corner-pause? dx dz next-step tx tz))}

      :else
      {:action :advance
       :speed (cond
                (and is-leashed? (> (:distance leash-info 0.0) 7.5)) 0.090
                is-leashed? 0.165
                :else 0.245)})))

(defn- leash-broken!
  "A guia arrebentou: alerta, localiza o animal e enfileira o resgate."
  [{:keys [nav-state engine-ctx-atom]} st cur-step]
  (let [[cx cy cz] (:pos st)]
    (let [species (or (:species cur-step) (:species (first (filter :species (:steps st)))) "animal")
          norm-sp (critic/normalize-species species)]
      (record-cognitive-event! engine-ctx-atom :perception-warn "Tactile Sensory Feedback (Nativo 20Hz)"
                               (str "ALERTA TATIL IMEDIATO: A guia arrebentou! O animal (" norm-sp ") soltou-se da corda."))
      (karlach-say! (str "Eita, a corda soltou! Espera ai, " norm-sp ", to voltando pra te pegar!"))
      (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.leash_knot.break master @a ~ ~ ~ 1.0 1.0")
      (let [mob-resp (send-bridge-cmd! (str "LOCATE_MOB|Karlach|" norm-sp))
            mob-pos (when (and mob-resp (clojure.string/starts-with? mob-resp "OK|"))
                      (let [parts (clojure.string/split mob-resp #"\|")]
                        [(Double/parseDouble (nth parts 1))
                         (Double/parseDouble (nth parts 2))
                         (Double/parseDouble (nth parts 3))]))
            rescue-pos (or mob-pos [cx cy cz])
            to-mob-steps (generate-walk-steps [cx cy cz] rescue-pos (str "Retornando para resgatar " norm-sp) {:radius 1.2})
            rem-steps (vec (:steps st))
            recovery-seq (vec
                          (concat
                           [{:op :wait :ticks 5 :label "Parando imediatamente apos guia arrebentar"}]
                           to-mob-steps
                           [{:op :attach-lead :species species :label (str "Reatando guia no " norm-sp)}
                            {:op :wait :ticks 10 :label "Fixando corda"}]
                           rem-steps))]
        (swap! nav-state #(-> %
                              (assoc :leashed? false)
                              (assoc :steps recovery-seq)))))
))

(defn- walk-step!
  "Op :walk: guia quebrada > watchdog (CA-066) > chegada > passo de caminhada."
  [{:keys [nav-state engine-ctx-atom tick make-agents-map] :as env} st cur-step]
  (let [[cx cy cz] (:pos st)
        [tx ty tz] (:target cur-step)
        leashed?   (:leashed? st false)
        is-leashed? (or leashed? (boolean (:species cur-step)))
        leash-info (when is-leashed? (query-leash-status "Karlach"))
        rem-steps  (vec (rest (:steps st)))
        d (patrol-step-decision {:cur-step cur-step :pos [cx cy cz] :leashed? leashed?
                                 :leash-info leash-info :next-step (first rem-steps)})]
    (cond
      ;; 1. Alerta Critico Tatil: A guia arrebentou durante o trajeto!
      (= :leash-broken (:action d))
      (leash-broken! env st cur-step)

      ;; 2. CA-066: passo :walk sem progresso por 20 s => descarta e segue
      (patrol-watchdog-fired! nav-state st cur-step [cx cy cz] tick)
      (swap! nav-state update :steps #(vec (rest %)))

      ;; 3a. Chegou: crava no alvo e passa ao proximo passo
      (= :arrive (:action d))
      (do
        (body/move! {:agent :karlach :agent-name "Karlach" :x tx :y ty :z tz
                     :yaw (:yaw st) :pitch 0.0 :priority :bt})
        (swap! nav-state #(-> %
                              (assoc :pos [(double tx) (double ty) (double tz)])
                              (assoc :steps (if (:corner-pause? d)
                                              (into [{:op :wait :ticks 5 :label "Aguardando animal contornar curva"}] rem-steps)
                                              rem-steps)))))

      ;; 3b. Caminhada normal (conducao segura ou patrulha)
      :else
      (let [stepped (loco/walk-step {:pos [cx cy cz] :target [tx ty tz]
                                     :speed (:speed d) :yaw (:yaw st)})
            [nx ny' nz] (:pos stepped)
            yaw (:yaw stepped)]
        (body/move! {:agent :karlach :agent-name "Karlach" :x nx :y ny' :z nz
                     :yaw yaw :pitch 0.0 :priority :bt})
        (swap! nav-state assoc :pos [nx (double ny') nz] :yaw yaw)
        (swap! engine-ctx-atom assoc
               :tick tick
               :leases {:legs {:owner :patrol-waypoints} :hands nil :gaze {:owner :path-look}}
               :agents (make-agents-map (:label cur-step) [(/ (Math/round (* nx 10.0)) 10.0) (double ny') (/ (Math/round (* nz 10.0)) 10.0)]))))))

(defn- gate!
  "Op :gate: abre/fecha portal ou portao duplo."
  [{:keys [nav-state engine-ctx-atom tick make-agents-map]} st cur-step]
  (let [[cx cy cz] (:pos st)]
    (if-let [p (:pos cur-step)]
      (if (:open? cur-step)
        (open-portal-at-coord! p)
        (close-portal-at-coord! p))
      (set-double-gate! (:gates cur-step) (:block cur-step) (:facing cur-step) (:open? cur-step)))
    (swap! nav-state update :steps #(vec (rest %)))
    (swap! engine-ctx-atom assoc
           :tick tick
           :leases {:legs {:owner :portal-traversal}
                    :hands {:owner :toggle-double-gate}
                    :gaze {:owner :portal-traversal}}
           :agents (make-agents-map (:label cur-step) [cx cy cz]))))

(defn- feed!
  "Op :feed: alimenta os animais do cercado e fala a frase do cercado atual."
  [{:keys [nav-state engine-ctx-atom tick make-agents-map]} st cur-step]
  (let [[cx cy cz] (:pos st)]
    (feed-enclosure-animals! (:pos cur-step) (:food cur-step) (:species cur-step))
    ;; Seleciona fala EXCLUSIVAMENTE do cercado onde Karlach está fisicamente
    (let [enc-barks (barks-for-current-position [cx cy cz])
          chosen-bark (if (seq enc-barks)
                        (rand-nth enc-barks)
                        (:bark cur-step))]
      (when chosen-bark
        (when (> (- (System/currentTimeMillis) (:last-bark-ms st 0)) 20000)
          (karlach-say! chosen-bark)
          (swap! nav-state assoc :last-bark-ms (System/currentTimeMillis)))))
    (swap! nav-state update :steps #(vec (rest %)))
    (swap! engine-ctx-atom assoc
           :tick tick
           :leases {:legs {:owner :care-for-enclosure}
                    :hands {:owner :feed-animals}
                    :gaze {:owner :care-for-enclosure}}
           :agents (make-agents-map (:label cur-step) [cx cy cz]))))

(defn- bark!
  "Op :BARK: so fala frases de praca/corredor fora de um cercado (35s de intervalo)."
  [{:keys [nav-state]} st cur-step]
  (let [[cx cy cz] (:pos st)]
    (when-let [b (:text cur-step)]
      (when (and (nil? (current-enclosure-id [cx cy cz]))
                 (> (- (System/currentTimeMillis) (:last-bark-ms st 0)) 35000))
        (karlach-say! b)
        (swap! nav-state assoc :last-bark-ms (System/currentTimeMillis))))
    (swap! nav-state update :steps #(vec (rest %)))))

(defn- wait!
  "Op :wait: olha para o jogador mais proximo (< 12m) e conta os ticks."
  [{:keys [nav-state engine-ctx-atom tick make-agents-map]} st cur-step]
  (let [[cx cy cz] (:pos st)]
    (let [rem-ticks (if (pos? (:wait-left st 0))
                      (dec (:wait-left st))
                      (dec (:ticks cur-step 30)))]
      (when (zero? (mod tick 10))
        (let [players (remove #(#{"Karlach" "Picard"} (:name %)) (query-online-players))
              nearest (first (sort-by #(let [dx (- (:x %) cx) dz (- (:z %) cz)]
                                         (+ (* dx dx) (* dz dz)))
                                      players))]
          (when nearest
            (let [dx (- (:x nearest) cx)
                  dz (- (:z nearest) cz)
                  dist (Math/sqrt (+ (* dx dx) (* dz dz)))]
              (when (< dist 12.0)
                (let [gyaw (compute-yaw dx dz)]
                  (body/move! {:agent :karlach :agent-name "Karlach" :x cx :y cy :z cz
                               :yaw gyaw :pitch 0.0 :priority :idle})))))))
      (if (<= rem-ticks 0)
        (swap! nav-state #(-> %
                              (assoc :wait-left 0)
                              (update :steps (fn [s] (vec (rest s))))))
        (swap! nav-state assoc :wait-left rem-ticks))
      (swap! engine-ctx-atom assoc
             :tick tick
             :leases {:legs nil :hands nil :gaze {:owner :social-gaze}}
             :agents (make-agents-map (:label cur-step) [(/ (Math/round (* cx 10.0)) 10.0) cy (/ (Math/round (* cz 10.0)) 10.0)])))
))

(defn- idle!
  "Sem steps: abrigada senta no banco do quarto (UMA vez); senao expande a proxima parada da ronda."
  [{:keys [nav-state engine-ctx-atom tick make-agents-map]} st]
  (let [[cx cy cz] (:pos st)]
    (if (:sheltering? st false)
      ;; Abrigada e sem steps: ja esta no quarto. Senta no
      ;; banco UMA vez (flag :seated?) e espera o tempo abrir.
      (do
        (when-not (:seated? st false)
          (let [quarters (some #(when (= (:type %) :quarters) %) sanctuary-route)]
            (when (sit-karlach-at-quarters! (:seat-pos quarters))
              (karlach-say! "Ah, um banquinho pra descansar as canelas... melhor que ficar de pé na chuva! 🔥🪑"))
            (swap! nav-state assoc :seated? true)))
        (swap! engine-ctx-atom assoc
               :tick tick
               :leases {:legs {:owner :rain-shelter} :hands nil :gaze {:owner :social-gaze}}
               :agents (make-agents-map "Sentada no banco do quarto 🌧️🪑" [cx cy cz])))
      (let [next-idx (mod (inc (:route-idx st 0)) (count patrol-route))
            next-stop (nth patrol-route next-idx)]
        (swap! nav-state assoc
               :route-idx next-idx
               :steps (vec (expand-route-microsteps next-stop [cx cy cz])))))
))

(defn- early-abort!
  "Invariante runtime violada (Environmental Critic): registra, fala e zera os steps."
  [{:keys [nav-state engine-ctx-atom]} cur-step inv-check]
  (let [reflection (critic/generate-early-abort-reflection inv-check cur-step)]
    (try
      (critic/record-early-abort-event! reflection
                                        (or (:skill-id cur-step) :patrol-sanctuary)
                                        "state/abort-ledger.edn")
      (catch Throwable _ nil))
    (record-cognitive-event! engine-ctx-atom :critic-fail "Environmental Critic"
                             (str "EARLY ABORT DETECTADO: " (:diagnostic inv-check)))
    (record-cognitive-event! engine-ctx-atom :reflexion "System 2 Reflexion"
                             (str "Ação corretiva: " (name (:reflection/suggested-action reflection))))
    (karlach-say! (:reflection/voice-line reflection))
    (swap! nav-state assoc :steps [] :mode :sanctuary-patrol)))

(defn- exec-step!
  "Checa as invariantes do passo corrente e despacha pelo :op."
  [{:keys [nav-state engine-ctx-atom] :as env} st cur-step]
  (let [[cx cy cz] (:pos st)
        invariants (:invariants cur-step)
        inv-check  (when (seq invariants)
                     (critic/check-runtime-invariants
                      invariants
                      {:bot-pos    [cx cy cz]
                       :target-pos (or (:target cur-step) [cx cy cz])
                       :health     (get-in @engine-ctx-atom [:agents :karlach :health] 20.0)
                       :leashed?   (:leashed? st false)}))]
    (if (and inv-check (:violated? inv-check))
      (early-abort! env cur-step inv-check)
      (case (:op cur-step)
        :equip
        (do
          (when (:item cur-step)
            (send-bridge-cmd! (str "CMD|item replace entity Karlach weapon.mainhand with " (:item cur-step))))
          (swap! nav-state update :steps #(vec (rest %))))
        :walk            (walk-step! env st cur-step)
        :gate            (gate! env st cur-step)
        :feed            (feed! env st cur-step)
        :attach-lead     (ops/attach-lead! env st cur-step)
        :detach-lead     (ops/detach-lead! env)
        :await-contained (ops/await-contained! env st cur-step)
        :critic          (ops/critic! env st cur-step)
        :BARK            (bark! env st cur-step)
        :wait            (wait! env st cur-step)))))

(defn tick-patrol!
  "Um tick da patrulha. `st` e o snapshot de nav-state; `tick` vem de `env`."
  [{:keys [nav-state tick] :as env} st]
  (let [;; CA-065: a cada 20 ticks na patrulha, re-ancora :pos pela posicao
        ;; real (limiar 1,5). O passo :walk corrente e recalculado a cada tick
        ;; a partir de :pos, entao ja segue da posicao real.
        rec-pos (when (and (= (:mode st) :sanctuary-patrol) (zero? (mod tick 20)))
                  (let [real (try (loco/agent-pos-from-players (query-online-players) "Karlach")
                                  (catch Throwable _ nil))
                        r (loco/reconcile-pos (:pos st) real 1.5)]
                    (when (not= r (:pos st)) r)))
        _  (when rec-pos (swap! nav-state assoc :pos rec-pos))
        st (if rec-pos (assoc st :pos rec-pos) st)
        steps (:steps st)]
    (if (empty? steps)
      (idle! env st)
      (exec-step! env st (first steps)))))

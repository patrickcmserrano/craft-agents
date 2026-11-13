(ns craft.agents.backend.fabric.engine.combat
  "Decisoes puras de combate da Karlach: cooldowns de golpe/grito e preempcao reativa
   com tether anti-kiting de escolta (CA-049).")

(def strike-cooldown-ticks
  "Ticks (20Hz) entre golpes melee da Karlach: ~1s, cadencia de machado."
  20)

(defn strike-ready?
  "true se ja passou o cooldown desde o ultimo golpe (`last-tick` nil = nunca golpeou)."
  [last-tick tick]
  (or (nil? last-tick) (>= (- tick last-tick) strike-cooldown-ticks)))

(def battle-cry-cooldown-ticks
  "Ticks (20Hz) minimos entre gritos de guerra no chat: 30s."
  600)

(defn battle-cry-ready?
  "true se pode gritar de novo (`last-tick` nil = nunca gritou)."
  [last-tick tick]
  (or (nil? last-tick) (>= (- tick last-tick) battle-cry-cooldown-ticks)))

(def ^:private escort-tether-m 12.0)
(def ^:private escort-guard-radius-m 8.0)
(def ^:private self-defense-radius-m 4.0)

(defn- dist3 [[ax ay az] [bx by bz]]
  (Math/sqrt (+ (Math/pow (- (double ax) (double bx)) 2)
                (Math/pow (- (double ay) (double by)) 2)
                (Math/pow (- (double az) (double bz)) 2))))

(defn evaluate-combat-preemption
  "Avalia preempção de combate reativo, tether anti-kiting de escolta e proteção de embarque (CA-049).
   Retorna mapa de decisão:
     - :action (:combat-strike, :combat-intercept, :tether-snap-recall, :remain-mounted-passive, :follow-player)
     - :target-pos [x y z]
     - :target-id int
     - :clear-follow-path? boolean"
  [bot-pos leader-pos hostiles in-vehicle?]
  (if in-vehicle?
    ;; 1. Proteção de Regressão com o Barco (CA-048 + CA-049):
    ;; Não desembarca sozinha para caçar na margem do rio.
    (if-let [melee-mob (first (filter #(<= (:distance %) 3.5) hostiles))]
      {:action :combat-strike
       :target-pos [(:x melee-mob) (:y melee-mob) (:z melee-mob)]
       :target-id (:id melee-mob)
       :clear-follow-path? true}
      {:action :remain-mounted-passive
       :target-pos nil
       :clear-follow-path? false})

    ;; 2. Modo Terrestre (fora do barco):
    (let [[bx by bz] (or bot-pos [0.0 64.0 0.0])
          [lx ly lz] (or leader-pos bot-pos)
          dist-to-leader (if leader-pos
                           (Math/sqrt (+ (Math/pow (- (double lx) (double bx)) 2)
                                         (Math/pow (- (double ly) (double by)) 2)
                                         (Math/pow (- (double lz) (double bz)) 2)))
                           0.0)
          ;; Escolta: so engaja mob que ameaca o lider (<= 8m dele) ou colado nela (<= 4m).
          ;; Mob que ficou pra tras e ignorado, senao ela abandona o follow.
          target-mob (first (filter #(or (<= (double (:distance %)) self-defense-radius-m)
                                         (and leader-pos
                                              (<= (dist3 [(:x %) (:y %) (:z %)] leader-pos) escort-guard-radius-m)))
                                    hostiles))]
      (cond
        ;; A) Tether Tático Anti-Kiting: líder além de 12m -> recall de emergência
        (> dist-to-leader escort-tether-m)
        {:action :tether-snap-recall
         :target-pos leader-pos
         :clear-follow-path? false}

        ;; B) Hostil próximo engajável dentro do tether (<= 18m do líder e <= 14m de Karlach)
        (and target-mob (<= (:distance target-mob) 14.0))
        (let [mdist (:distance target-mob)]
          (if (<= mdist 3.8)
            {:action :combat-strike
             :target-pos [(:x target-mob) (:y target-mob) (:z target-mob)]
             :target-id (:id target-mob)
             :clear-follow-path? true}
            {:action :combat-intercept
             :target-pos [(:x target-mob) (:y target-mob) (:z target-mob)]
             :target-id (:id target-mob)
             :clear-follow-path? false}))

        ;; C) Sem hostis engajáveis: locomoção normal para o líder
        :else
        {:action :follow-player
         :target-pos leader-pos
         :clear-follow-path? false}))))

(def ^:private flee-threat-radius-m
  "Raio (m) em que um hostil ja conta como ameaca para quem foge."
  10.0)

(defn- flee-ally-ids
  "Aliados candidatos: `:ally-ids` da percepcao ou, na falta, o `:flee-target` dos traits."
  [agent ally-ids]
  (vec (or (seq ally-ids)
           (some-> (get-in agent [:agent/traits :flee-target]) vector))))

(defn combat-decision
  "Decisao pura de combate por `:agent/combat-policy` do agente (CA-093).
   `perception`: {:bot-pos :leader-pos :hostiles :in-vehicle? :allies {id [x y z]} :ally-ids [id]}.
     :fight -> `evaluate-combat-preemption` (mesmo mapa de decisao)
     :flee  -> {:action :evade-towards-ally :ally-id id :target-pos [x y z]} rumo ao aliado
               mais proximo com posicao conhecida, se ha hostil a <= 10 m; senao {:action :none}
     :none (ou ausente) -> {:action :none}"
  [agent {:keys [bot-pos leader-pos hostiles in-vehicle? allies ally-ids]}]
  (case (:agent/combat-policy agent)
    :fight (evaluate-combat-preemption bot-pos leader-pos hostiles in-vehicle?)
    :flee  (let [threat? (some #(<= (double (:distance %)) flee-threat-radius-m) hostiles)
                 origin  (or bot-pos [0.0 64.0 0.0])
                 nearest (when threat?
                           (->> (flee-ally-ids agent ally-ids)
                                (keep (fn [id] (when-let [pos (get allies id)] [id pos])))
                                (sort-by (fn [[_ pos]] (dist3 origin pos)))
                                first))]
             (if-let [[id pos] nearest]
               {:action :evade-towards-ally :ally-id id :target-pos pos}
               {:action :none}))
    {:action :none}))

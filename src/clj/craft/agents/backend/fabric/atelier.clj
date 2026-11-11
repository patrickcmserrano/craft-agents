(ns craft.agents.backend.fabric.atelier
  "Comportamento de 20 TPS, navegação e interação canônica do Ateliê (Coco & Richeh) no Castelo dos Touros Negros."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :as transport :refer [send-bridge-cmd! uformat]]))

;; =============================================================================
;; Waypoints e Configuração de Castelo
;; =============================================================================

(def castle-waypoints
  [[-30.0 66.0 190.0]
   [-25.0 66.0 194.0]
   [-20.0 66.0 190.0]
   [-24.0 66.0 185.0]
   [-28.0 66.0 186.0]])

(defn load-agent-edn [agent-id]
  (try
    (let [f (io/file (str "agents/" (name agent-id) ".edn"))]
      (when (.exists f)
        (edn/read-string (slurp f))))
    (catch Throwable _ nil)))

(defonce ^:private coco-data (delay (load-agent-edn :coco)))
(defonce ^:private richeh-data (delay (load-agent-edn :richeh)))

;; =============================================================================
;; Falas Formatadas Tellraw no Servidor
;; =============================================================================

(defn coco-say! [msg]
  (let [safe-msg (str/replace msg "\"" "\\\"")
        json-cmd (str "execute at Coco run tellraw @a[distance=..16] [{\"text\":\"<\",\"color\":\"dark_gray\"},"
                      "{\"text\":\"Coco\",\"color\":\"yellow\",\"bold\":true},"
                      "{\"text\":\"> \",\"color\":\"dark_gray\"},"
                      "{\"text\":\"" safe-msg "\",\"color\":\"yellow\"}]")]
    (send-bridge-cmd! (str "CMD|" json-cmd))
    (send-bridge-cmd! "CMD|execute at Coco run playsound minecraft:item.book.page_turn master @a[distance=..24] ~ ~ ~ 0.9 1.1")
    (send-bridge-cmd! "SWING|Coco")))

(defn richeh-say! [msg]
  (let [safe-msg (str/replace msg "\"" "\\\"")
        json-cmd (str "execute at Richeh run tellraw @a[distance=..16] [{\"text\":\"<\",\"color\":\"dark_gray\"},"
                      "{\"text\":\"Richeh\",\"color\":\"aqua\",\"bold\":true},"
                      "{\"text\":\"> \",\"color\":\"dark_gray\"},"
                      "{\"text\":\"" safe-msg "\",\"color\":\"aqua\"}]")]
    (send-bridge-cmd! (str "CMD|" json-cmd))
    (send-bridge-cmd! "CMD|execute at Richeh run playsound minecraft:block.amethyst_block.hit master @a[distance=..24] ~ ~ ~ 0.8 1.6")
    (send-bridge-cmd! "SWING|Richeh")))

;; =============================================================================
;; Magias de Ataque do Ateliê (Glifos de Coco & Cristais de Richeh)
;; =============================================================================
;; Assinatura visual + dano real a hostis. A varredura e o golpe usam os
;; opcodes de combate ja existentes na bridge (TARGET_HOSTILE/ATTACK_HOSTILE);
;; o visual sai por CMD|particle. Cooldown de 5s evita spam de combinacao.

(def ^:private spell-cooldown-ms 5000)
(def ^:private spell-burst-ms 8000)
(def ^:private spell-scan-radius 20.0)

(defn- parse-hostile-response
  "Extrai o alvo mais proximo da resposta do SyntheticHostileScanner.
   Formato: OK|<count>|<id>,<tipo>,<x>,<y>,<z>,<dist>,<hp>;<id>,... (ja ordenado).
   Devolve {:id int :type string :x :y :z :dist :hp} ou nil."
  [resp]
  (when (and resp (str/starts-with? resp "OK|"))
    (let [segments (str/split resp #"\|" 3)
          payload (nth segments 2 "")]
      (when-not (str/blank? payload)
        (let [first-target (first (str/split payload #";"))
              fields (str/split first-target #",")]
          (when (>= (count fields) 7)
            (try
              {:id (Integer/parseInt (first fields))
               :type (nth fields 1)
               :x (Double/parseDouble (nth fields 2))
               :y (Double/parseDouble (nth fields 3))
               :z (Double/parseDouble (nth fields 4))
               :dist (Double/parseDouble (nth fields 5))
               :hp (Double/parseDouble (nth fields 6))}
              (catch Throwable _ nil))))))))

(defn should-say-spell?
  "True se a bruxa pode soltar o grito de guerra neste surto (o primeiro
   lancamento apos a janela de silencio)."
  [st now]
  (> (- now (:last-spell-said-ms @st 0)) spell-burst-ms))

(defn- spell-ready?
  "True se o cooldown de conjuracao ja passou."
  [st now]
  (> (- now (:last-spell-ms @st 0)) spell-cooldown-ms))

(defn- projectile-cmd
  "Comando que lanca o projetil real (fireball) da bruxa RUMO AO ALVO.
   A direcao sai da posicao da bruxa (`:pos` do estado) para as coordenadas do
   alvo, normalizada; `speed` fixa a velocidade. `ExplosionPower:0` para o fogo
   nao quebrar blocos — o dano real continua a ser o do ATTACK_HOSTILE."
  [st agent-name {:keys [x y z]}]
  (let [[wx wy wz] (:pos @st)
        dx (- (double x) (double wx))
        dy (- (double y) (double wy))
        dz (- (double z) (double wz))
        len (max 0.001 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))
        speed 1.1
        mx (* speed (/ dx len))
        my (* speed (/ dy len))
        mz (* speed (/ dz len))]
    (uformat "CMD|execute at %s run summon minecraft:fireball ^ ^1.2 ^1 {ExplosionPower:0b,Motion:[%.3f,%.3f,%.3f]}"
             agent-name mx my mz)))

(defn- burst-cmds
  "Golpe visual ancorado nas COORDENADAS do alvo — a magia detona nele, nao
   so na bruxa. `x/y/z` vem do scanner de hostis."
  [{:keys [x y z]} particles]
  (mapv (fn [p] (uformat "CMD|execute positioned %.2f %.2f %.2f run %s" x y z p))
        particles))

(def ^:private spell-hitkill-damage 100)
(def ^:private spell-hitkill-radius 2.0)

(defn- hitkill-cmd
  "Comando de dano que mata o alvo num golpe. Usa `damage` (comando vanilla
  1.21) com `spell-hitkill-damage` — 100 mata mobs comuns de uma vez e deixa
  bosses (wither, dragao) vivos. O alvo e preso por COORDENADAS + raio curto
  (2 blocos) + `limit=1`, o mesmo que se mediu a funcionar pela ponte."
  [{:keys [x y z]}]
  (uformat "CMD|damage @e[x=%.2f,y=%.2f,z=%.2f,distance=..%.1f,limit=1,sort=nearest] %d minecraft:magic"
           x y z spell-hitkill-radius spell-hitkill-damage))

(defn- cast-spell!
  "Nucleo comum: varre hostis, aplica dano ao mais proximo e emite o visual
   assinado por `particles` (glifo na bruxa), `sound` e `burst-particles`
   (detonacao nas coordenadas do alvo).
   Devolve true se conjurou (havia alvo), false caso contrario."
  [st agent-name send-cmd! now particles sound burst-particles]
  (if-not (spell-ready? st now)
    false
    (let [resp (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" agent-name spell-scan-radius))
          target (parse-hostile-response resp)]
      (swap! st assoc :last-spell-ms now)
      (when (should-say-spell? st now)
        (swap! st assoc :last-spell-said-ms now :spell-said? true))
      (doseq [p particles] (send-cmd! p))
      (when sound (send-cmd! sound))
      (when target
        (send-cmd! (str "SWING|" agent-name))
        ;; HIT KILL: dano 100 num golpe (mobs comuns morrem, bosses resistem).
        (send-cmd! (hitkill-cmd target))
        ;; Projetil real a voar ate o alvo + detonacao visual NAS COORDENADAS dele.
        (send-cmd! (projectile-cmd st agent-name target))
        (doseq [b (burst-cmds target burst-particles)] (send-cmd! b)))
      (some? target))))

(defn cast-coco-spell!
  "Coco - Glifo de Tinta Viva: desenha um glifo que morde o hostil mais proximo.
   `tick` fica na assinatura por simetria com o motor, mas o timing vem de `now`."
  [coco-st send-cmd! _tick now]
  (cast-spell!
   coco-st "Coco" send-cmd! now
   ["CMD|execute at Coco run particle minecraft:enchant ~ ~1.0 ~ 0.6 0.6 0.6 0.05 25 normal"
    "CMD|execute at Coco run particle minecraft:witch ~ ~1.2 ~ 0.4 0.4 0.4 0.02 15 normal"
    "CMD|execute at Coco run particle minecraft:crit ~ ~1.0 ~ 0.3 0.3 0.3 0.1 10 normal"]
   "CMD|execute at Coco run playsound minecraft:item.book.page_turn master @a[distance=..24] ~ ~ ~ 1.2 0.8"
   ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
    "particle minecraft:soul_fire_flame ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"
    "particle minecraft:enchant ~ ~ ~ 0.8 0.8 0.8 0.1 40 normal"]))

(defn cast-richeh-spell!
  "Richeh - Estilhaco de Cristal: inscreve uma matriz e a estilhaca no alvo.
   `tick` fica na assinatura por simetria com o motor, mas o timing vem de `now`."
  [richeh-st send-cmd! _tick now]
  (cast-spell!
   richeh-st "Richeh" send-cmd! now
   ["CMD|execute at Richeh run particle minecraft:end_rod ~ ~1.0 ~ 0.6 0.6 0.6 0.08 25 normal"
    "CMD|execute at Richeh run particle minecraft:glow_squid_ink ~ ~0.8 ~ 0.4 0.4 0.4 0.02 20 normal"
    "CMD|execute at Richeh run particle minecraft:block_marker minecraft:amethyst_block ~ ~1.0 ~ 0.3 0.3 0.3 0.05 12 normal"]
   "CMD|execute at Richeh run playsound minecraft:block.amethyst_block.break master @a[distance=..24] ~ ~ ~ 1.1 0.9"
   ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
    "particle minecraft:end_rod ~ ~ ~ 0.6 0.6 0.6 0.08 40 normal"
    "particle minecraft:block_marker minecraft:amethyst_block ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"]))

(defn spell-auto-defense!
  "Reacao automatica: se houver hostil no raio, a bruxa conjura a sua magia.
   Devolve true se conjurou."
  [st agent-name send-cmd! now]
  (let [cast-fn (if (= agent-name "Coco") cast-coco-spell! cast-richeh-spell!)]
    (cast-fn st send-cmd! 0 now)))

;; =============================================================================
;; Fábrica de Estado
;; =============================================================================

(defn create-coco-state
  ([] (create-coco-state [-30.0 66.0 190.0]))
  ([pos]
   (atom {:pos (vec pos)
          :yaw 0.0
          :pitch 0.0
          :mode :patrol
          :follow-target nil
          :waypoint-idx 1
          :phase :walk
          :wait-left 0
          :last-bark-ms 0
          :last-duo-ms 0
          :last-spell-ms 0
          :last-spell-said-ms 0
          :spell-said? false
          :status "Estudando glifos no castelo"})))

(defn create-richeh-state
  ([] (create-richeh-state [-30.0 66.0 192.0]))
  ([pos]
   (atom {:pos (vec pos)
          :yaw 0.0
          :pitch 0.0
          :mode :tether-orbit
          :follow-target nil
          :phase :orbit
          :wait-left 0
          :last-bark-ms 0
          :last-spell-ms 0
          :last-spell-said-ms 0
          :spell-said? false
          :status "Artesã de cristal & guarda-costas"})))

;; =============================================================================
;; Motor de 20 TPS: Coco (Curiosa, Observadora e Desenhista)
;; =============================================================================

(defn step-coco-tick!
  "Executa 1 tick motora e comportamental da Coco."
  [coco-st send-cmd! tick online-players]
  (let [st @coco-st
        [cx cy cz] (:pos st)
        mode (:mode st)]
    (cond
      ;; 1. Modo Follow (Jogador solicitou acompanhamento)
      (= mode :follow)
      (let [target-name (or (:follow-target st) "pace")
            target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case target-name)) online-players))]
        (if target-p
          (let [dx (- (:x target-p) cx)
                dz (- (:z target-p) cz)
                dist (Math/sqrt (+ (* dx dx) (* dz dz)))
                yaw (nav/compute-yaw dx dz)]
            (if (> dist 2.4)
              (let [step-d (min 0.20 dist)
                    nx (+ cx (* (/ dx dist) step-d))
                    nz (+ cz (* (/ dz dist) step-d))]
                (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx cy nz yaw))
                (swap! coco-st assoc :pos [nx cy nz] :yaw yaw :status (str "Acompanhando " (:name target-p))))
              (do
                (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz yaw))
                (swap! coco-st assoc :yaw yaw :status (str "Conversando com " (:name target-p))))))
          (swap! coco-st assoc :mode :patrol)))

      ;; 2. Modo Patrulha / Estudo no Castelo
      :else
      (let [wp-idx (:waypoint-idx st 0)
            target-wp (nth castle-waypoints wp-idx (first castle-waypoints))
            [tx ty tz] target-wp
            phase (:phase st :walk)]
        (if (= phase :walk)
          (let [dx (- tx cx)
                dz (- tz cz)
                dist (Math/sqrt (+ (* dx dx) (* dz dz)))
                yaw (nav/compute-yaw dx dz)]
            (if (<= dist 0.35)
              ;; Chegou ao waypoint -> inicia sessão de anotações no caderno
              (do
                (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" tx ty tz yaw))
                (send-cmd! "SWING|Coco")
                (send-cmd! "CMD|execute at Coco run particle minecraft:enchant ~ ~1.0 ~ 0.3 0.2 0.3 0.05 8 normal")
                (swap! coco-st assoc
                       :pos [(double tx) (double ty) (double tz)]
                       :yaw yaw
                       :phase :sketch
                       :wait-left 70
                       :status "Desenhando glifo no caderno"))
              ;; Continua caminhando
              (let [step-d (min 0.18 dist)
                    nx (+ cx (* (/ dx dist) step-d))
                    nz (+ cz (* (/ dz dist) step-d))]
                (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ty nz yaw))
                (swap! coco-st assoc :pos [nx ty nz] :yaw yaw :status "Explorando os salões"))))

          ;; Fase :sketch (Folheando o caderno e estudando)
          (let [rem (dec (:wait-left st 0))]
            (when (zero? (mod rem 25))
              (send-cmd! "SWING|Coco")
              (send-cmd! "CMD|execute at Coco run particle minecraft:witch ~ ~1.1 ~ 0.2 0.2 0.2 0.02 5 normal"))
            ;; Social gaze: se jogador estiver a menos de 7m, vira na direção dele
            (when-let [nearest (first (filter #(let [dx (- (:x %) cx) dz (- (:z %) cz)]
                                                 (< (+ (* dx dx) (* dz dz)) 49.0))
                                              online-players))]
              (let [pdx (- (:x nearest) cx)
                    pdz (- (:z nearest) cz)
                    gyaw (nav/compute-yaw pdx pdz)]
                (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz gyaw))
                (swap! coco-st assoc :yaw gyaw)))
            (if (<= rem 0)
              (swap! coco-st assoc
                     :waypoint-idx (mod (inc wp-idx) (count castle-waypoints))
                     :phase :walk
                     :wait-left 0)
              (swap! coco-st assoc :wait-left rem))))))))

;; =============================================================================
;; Motor de 20 TPS: Richeh (Artesã de Cristal & Protetora Silenciosa)
;; =============================================================================

(defn step-richeh-tick!
  "Executa 1 tick motora e comportamental da Richeh."
  [richeh-st coco-st send-cmd! tick online-players]
  (let [st @richeh-st
        [rx ry rz] (:pos st)
        mode (:mode st)]
    (cond
      ;; 1. Modo Follow (Jogador solicitou acompanhamento)
      (= mode :follow)
      (let [target-name (or (:follow-target st) "pace")
            target-p (first (filter #(= (str/lower-case (:name %)) (str/lower-case target-name)) online-players))]
        (if target-p
          (let [dx (- (:x target-p) rx)
                dz (- (:z target-p) rz)
                dist (Math/sqrt (+ (* dx dx) (* dz dz)))
                yaw (nav/compute-yaw dx dz)]
            (if (> dist 2.4)
              (let [step-d (min 0.20 dist)
                    nx (+ rx (* (/ dx dist) step-d))
                    nz (+ rz (* (/ dz dist) step-d))]
                (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ry nz yaw))
                (swap! richeh-st assoc :pos [nx ry nz] :yaw yaw :status (str "Seguindo " (:name target-p))))
              (do
                (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" rx ry rz yaw))
                (swap! richeh-st assoc :yaw yaw :status (str "Vigiando " (:name target-p))))))
          (swap! richeh-st assoc :mode :tether-orbit)))

      ;; 2. Modo Tether Orbit em torno da Coco
      :else
      (let [[cx cy cz] (:pos @coco-st)
            dx (- cx rx)
            dz (- cz rz)
            dist (Math/sqrt (+ (* dx dx) (* dz dz)))
            yaw (nav/compute-yaw dx dz)]
        (cond
          ;; Coco afastou-se demais (> 5.5m): Richeh acelera para alcançá-la
          (> dist 5.5)
          (let [step-d (min 0.22 dist)
                nx (+ rx (* (/ dx dist) step-d))
                nz (+ rz (* (/ dz dist) step-d))]
            (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx cy nz yaw))
            (swap! richeh-st assoc :pos [nx cy nz] :yaw yaw :status "Orbitando Coco"))

          ;; Distância ideal (2.0m a 5.5m): Permanece atenta ou inscreve micro-glifos de cristal
          (>= dist 2.0)
          (do
            (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" rx ry rz yaw))
            (when (zero? (mod tick 60))
              (send-cmd! "SWING|Richeh")
              (send-cmd! "CMD|execute at Richeh run particle minecraft:glow_squid_ink ~ ~0.2 ~ 0.2 0.05 0.2 0.02 4 normal")
              (send-cmd! "CMD|execute at Richeh run playsound minecraft:block.amethyst_block.hit master @a[distance=..16] ~ ~ ~ 0.5 1.8"))
            (swap! richeh-st assoc :yaw yaw :status "Inscrevendo micro-glifos"))

          ;; Muito perto (< 2.0m): dá um passo sutil para o lado
          :else
          (let [side-dx (- dz)
                side-dz dx
                sdist (max 0.01 (Math/sqrt (+ (* side-dx side-dx) (* side-dz side-dz))))
                nx (+ rx (* (/ side-dx sdist) 0.08))
                nz (+ rz (* (/ side-dz sdist) 0.08))]
            (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ry nz yaw))
            (swap! richeh-st assoc :pos [nx ry nz] :yaw yaw :status "Guardando distância")))))))

;; =============================================================================
;; Sinergia de Dupla (Duo Synchrony)
;; =============================================================================

(defn check-duo-synchrony!
  "Dispara interação temática entre Coco e Richeh quando estão próximas."
  [coco-st richeh-st send-cmd! say-fn]
  (let [now (System/currentTimeMillis)
        [cx cy cz] (:pos @coco-st)
        [rx ry rz] (:pos @richeh-st)
        dx (- rx cx)
        dz (- rz cz)
        dist (Math/sqrt (+ (* dx dx) (* dz dz)))]
    (when (and (< dist 3.5)
               (> (- now (:last-duo-ms @coco-st 0)) 45000))
      (swap! coco-st assoc :last-duo-ms now)
      (let [cyaw (nav/compute-yaw dx dz)
            ryaw (nav/compute-yaw (- dx) (- dz))]
        (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz cyaw))
        (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|0.0|false|false" rx ry rz ryaw))
        (send-cmd! "SWING|Coco")
        (send-cmd! "SWING|Richeh")
        (send-cmd! "CMD|execute at Coco run particle minecraft:enchant ~ ~1.2 ~ 0.3 0.2 0.3 0.05 10 normal")
        (send-cmd! "CMD|execute at Richeh run particle minecraft:block_marker minecraft:amethyst_block ~ ~0.5 ~ 0.2 0.2 0.2 0.05 8 normal")
        (if (zero? (rand-int 2))
          (say-fn :coco (rand-nth (get-in @coco-data [:agent/dialogues :reactions :near-richeh]
                                          ["Richeh! Olha só esse novo arranjo de sinais que tentei rascunhar!"])))
          (say-fn :richeh (rand-nth (get-in @richeh-data [:agent/dialogues :reactions :near-coco]
                                            ["Hmph. Seu traço melhorou um pouquinho hoje, Coco... mas só um pouquinho."]))))))))

(defn check-celestial-reaction!
  "Reage a eventos celestes raros (eclipses, auroras ou alinhamento do gigante gasoso) (CA-052)."
  [coco-st richeh-st celestial-snapshot send-cmd! say-fn]
  (let [phenomenon (:phenomenon celestial-snapshot :nominal)
        now (System/currentTimeMillis)]
    (when (and (not= phenomenon :nominal)
               (> (- now (:last-celestial-ms @coco-st 0)) 45000))
      (swap! coco-st assoc :last-celestial-ms now)
      (let [[cx cy cz] (:pos @coco-st)
            [rx ry rz] (:pos @richeh-st)
            [gx gy gz] (get-in celestial-snapshot [:gas-giant :vector] [0.0 0.8 0.6])
            pitch (float (* (Math/asin (- gy)) (/ 180.0 Math/PI)))
            yaw   (float (* (Math/atan2 (- gx) gz) (/ 180.0 Math/PI)))]
        (send-cmd! (uformat "MOVE|Coco|%.3f|%.3f|%.3f|%.1f|%.1f|false|false" cx cy cz yaw pitch))
        (send-cmd! (uformat "MOVE|Richeh|%.3f|%.3f|%.3f|%.1f|%.1f|false|false" rx ry rz yaw pitch))
        (send-cmd! "SWING|Coco")
        (send-cmd! "SWING|Richeh")
        (case phenomenon
          :solar-eclipse
          (do
            (send-cmd! "CMD|execute at Coco run particle minecraft:witch ~ ~1.8 ~ 0.4 0.4 0.4 0.05 15 normal")
            (say-fn :coco (rand-nth (get-in @coco-data [:agent/dialogues :celestial]
                                            ["O gigante com anéis cobriu o sol! As sombras ficaram duplas! 🪐✨"]))))
          :crystal-aurora
          (do
            (send-cmd! "CMD|execute at Richeh run particle minecraft:end_rod ~ ~1.8 ~ 0.5 0.5 0.5 0.08 20 normal")
            (say-fn :richeh (rand-nth (get-in @richeh-data [:agent/dialogues :celestial]
                                              ["Olha a refração naquelas cortinas de luz no céu, Coco! 🔮"]))))
          nil)))))

;; =============================================================================
;; Manipulação de Comandos de Chat para o Ateliê
;; =============================================================================

(defn handle-atelier-chat!
  "Processa falas direcionadas a Coco e Richeh. Retorna true se consumido."
  [speaker msg coco-st richeh-st say-fn]
  (let [lower (str/lower-case (or msg ""))]
    (cond
      ;; 1. Comandos para Coco
      (str/includes? lower "coco")
      (cond
        (or (str/includes? lower "ataca") (str/includes? lower "atacar")
            (str/includes? lower "magia") (str/includes? lower "glifo")
            (str/includes? lower "feitiço") (str/includes? lower "feiti"))
        (do
          (let [hit? (cast-coco-spell! coco-st send-bridge-cmd! 0 (System/currentTimeMillis))]
            (if hit?
              (say-fn :coco (rand-nth ["Glifo de Tinta Viva! Segura ele pra mim! 🖋️✨"
                                       "A tinta morde! Não se mexe! 📖"]))
              (say-fn :coco "Não tem nada pra desenhar por aqui... o castelo tá tranquilo! 🖋️")))
          true)

        (or (str/includes? lower "segue") (str/includes? lower "vem"))
        (do
          (swap! coco-st assoc :mode :follow :follow-target speaker)
          (say-fn :coco (str "Tô indo, " speaker "! Deixa só eu guardar a pena de prata no caderno! 📖✨"))
          true)

        (or (str/includes? lower "fica") (str/includes? lower "patrulha")
            (str/includes? lower "ateliê") (str/includes? lower "castelo") (str/includes? lower "volta"))
        (do
          (swap! coco-st assoc :mode :patrol :follow-target nil)
          (say-fn :coco "Entendido! Vou continuar mapeando os fluxos e runas aqui pelo castelo! 🖋️")
          true)

        (or (str/includes? lower "céu") (str/includes? lower "ceu")
            (str/includes? lower "estrela") (str/includes? lower "eclipse")
            (str/includes? lower "planeta") (str/includes? lower "aurora"))
        (do
          (let [dialogues (get-in @coco-data [:agent/dialogues :celestial] [])
                chosen (if (seq dialogues) (rand-nth dialogues) "A geometria dos céus desafia qualquer caderno de desenho! 🌌")]
            (say-fn :coco chosen))
          true)

        :else
        (do
          (let [dialogues (concat (get-in @coco-data [:agent/dialogues :social] [])
                                  (get-in @coco-data [:agent/dialogues :castles] [])
                                  (get-in @coco-data [:agent/dialogues :celestial] []))
                chosen (if (seq dialogues) (rand-nth dialogues) "A magia nasce do desejo de ajudar os outros! ✨")]
            (say-fn :coco chosen))
          true))

      ;; 2. Comandos para Richeh
      (str/includes? lower "richeh")
      (cond
        (or (str/includes? lower "ataca") (str/includes? lower "atacar")
            (str/includes? lower "magia") (str/includes? lower "cristal")
            (str/includes? lower "estilhaça") (str/includes? lower "feitiço")
            (str/includes? lower "feiti") (str/includes? lower "lança"))
        (do
          (let [hit? (cast-richeh-spell! richeh-st send-bridge-cmd! 0 (System/currentTimeMillis))]
            (if hit?
              (say-fn :richeh (rand-nth ["Estilhaço de Cristal. Não desperdiça meu tempo. 🔮"
                                         "A matriz tá inscrita. Fica parado e ele quebra. 💠"]))
              (say-fn :richeh "Hmph. Não vejo nada digno dos meus cristais por aqui.")))
          true)

        (or (str/includes? lower "segue") (str/includes? lower "vem"))
        (do
          (swap! richeh-st assoc :mode :follow :follow-target speaker)
          (say-fn :richeh (str "Hmph... Se você insiste tanto, " speaker ". Mas não fica no meu caminho!"))
          true)

        (or (str/includes? lower "fica") (str/includes? lower "protege")
            (str/includes? lower "ateliê") (str/includes? lower "volta"))
        (do
          (swap! richeh-st assoc :mode :tether-orbit :follow-target nil)
          (say-fn :richeh "Melhor mesmo. Preciso terminar de polir as matrizes de cristal ao redor do ateliê.")
          true)

        (or (str/includes? lower "céu") (str/includes? lower "ceu")
            (str/includes? lower "estrela") (str/includes? lower "eclipse")
            (str/includes? lower "planeta") (str/includes? lower "aurora"))
        (do
          (let [dialogues (get-in @richeh-data [:agent/dialogues :celestial] [])
                chosen (if (seq dialogues) (rand-nth dialogues) "Se a refração daquelas luzes no céu alinhar com o cristal, o feitiço não dissipa nunca. 🔮")]
            (say-fn :richeh chosen))
          true)

        :else
        (do
          (let [dialogues (concat (get-in @richeh-data [:agent/dialogues :social] [])
                                  (get-in @richeh-data [:agent/dialogues :castles] [])
                                  (get-in @richeh-data [:agent/dialogues :celestial] []))
                chosen (if (seq dialogues) (rand-nth dialogues) "Eu só desenho a magia que tem o meu próprio traço.")]
            (say-fn :richeh chosen))
          true))

      :else false)))

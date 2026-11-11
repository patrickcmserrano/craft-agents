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
  "Comando que lanca o projetil real (fireball) do CONJURADOR rumo ao alvo.
   A direcao sai da posicao da ancora (`anchor-name`) para as coordenadas do
   alvo, normalizada; `speed` fixa a velocidade. A ancora e a posicao de quem
   conjura: o JOGADOR quando comanda (`anchor-name` = nome dele, o `execute at
   <nome>` fica na posicao real dele), ou a bruxa na auto-defesa. Quando a
   ancora e a bruxa, usa-se o `:pos` do estado (o nome do NPC nao e resolvivel
   pelo `execute at` do servidor). `ExplosionPower:0` para o fogo nao quebrar
   blocos — o dano real continua a ser o do comando de dano."
  ([st agent-name target] (projectile-cmd st agent-name nil target))
  ([st anchor-name send-cmd! {:keys [x y z]}]
   (let [[wx wy wz] (if send-cmd!
                      ;; Jogador: le a posicao real dele pela ponte.
                      (let [resp (send-cmd! "PLAYERS")
                            entry (some (fn [e] (let [f (str/split e #",")]
                                                  (when (= anchor-name (first f)) f)))
                                        (-> resp (str/replace #"^OK\|" "") (str/split #";")))]
                        (if (and entry (>= (count entry) 4))
                          [(Double/parseDouble (nth entry 1))
                           (Double/parseDouble (nth entry 2))
                           (Double/parseDouble (nth entry 3))]
                          (:pos @st)))
                      (:pos @st))
         dx (- (double x) (double wx))
         dy (- (double y) (double wy))
         dz (- (double z) (double wz))
         len (max 0.001 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))
         speed 1.1
         mx (* speed (/ dx len))
         my (* speed (/ dy len))
         mz (* speed (/ dz len))
         at-expr (if send-cmd!
                   (uformat "at %s" anchor-name)
                   (uformat "positioned %.2f %.2f %.2f" wx wy wz))]
     (uformat "CMD|execute %s run summon minecraft:fireball ^ ^1.2 ^1 {ExplosionPower:0b,Motion:[%.3f,%.3f,%.3f]}"
              at-expr mx my mz))))

(defn- burst-cmds
  "Golpe visual ancorado nas COORDENADAS do alvo — a magia detona nele, nao
   so na bruxa. `x/y/z` vem do scanner de hostis."
  [{:keys [x y z]} particles]
  (mapv (fn [p] (uformat "CMD|execute positioned %.2f %.2f %.2f run %s" x y z p))
        particles))

(def ^:private spell-hitkill-damage 100)
(def ^:private spell-hitkill-radius 2.0)

;; O grimorio: para o jogador conjurar, tem de LEVAR um livro-e-pena consigo
;; (no inventario). O item e o poder; sem ele, a bruxa recusa.
(def ^:private grimoire-item "minecraft:writable_book")

(defn- hitkill-cmd
  "Comando de dano que mata o alvo num golpe. Usa `damage` (comando vanilla
  1.21) com `spell-hitkill-damage` — 100 mata mobs comuns de uma vez e deixa
  bosses (wither, dragao) vivos. O alvo e preso por COORDENADAS + raio curto
  (2 blocos) + `limit=1`, o mesmo que se mediu a funcionar pela ponte."
  [{:keys [x y z]}]
  (uformat "CMD|damage @e[x=%.2f,y=%.2f,z=%.2f,distance=..%.1f,limit=1,sort=nearest] %d minecraft:magic"
           x y z spell-hitkill-radius spell-hitkill-damage))

(defn- grimoire-carried?
  "True se o jogador `caster` leva o grimorio NO INVENTARIO (qualquer slot).
   `INSPECT_INVENTORY|<jogador>` devolve OK|<count>|<id>,<qtd>;<id>,<qtd>;...
   (o motor so sabe ler o inventario, nao a mao — nao ha op de slot na ponte)."
  [send-cmd! caster]
  (let [resp (send-cmd! (uformat "INSPECT_INVENTORY|%s" caster))]
    (boolean (and resp (str/starts-with? resp "OK|")
                  (str/includes? resp grimoire-item)))))

;; =============================================================================
;; Mira por Direcao (o jogador aponta, a magia acerta o que ele mira)
;; =============================================================================
;; O jogador NAO precisa de um op novo: a ponte ja sabe ler a direcao do olhar
;; (RAYCAST_BLOCK devolve o primeiro bloco solido na linha do olhar). Nao existe
;; um op de raycast de ENTIDADES, entao a mira cruza a LINHA do olhar com a
;; varredura de hostis (TARGET_HOSTILE) e escolhe o hostil mais proximo DESSA
;; linha — os hostis que nao estao no cone de mira sao ignorados.

(def ^:private aim-cone-deg
  "Meio-angulo do cone de mira, em graus. O hostil tem de estar dentro deste
   desvio da linha do olhar para contar como alvo da mira."
  20.0)

(defn player-aim-snapshot
  "Le a mira do jogador pela ponte. A ORIGEM sao os pes do jogador (op PLAYERS,
   que devolve nome,x,y,z) e a DIRECAO e o vetor normalizado origem->bloco
   avistado (RAYCAST_BLOCK devolve o primeiro bloco solido na linha do olhar).
   `send-cmd!` e injetado para os testes; devolve
   {:look-direction [dx dy dz] :ray-origin [x y z] :ray-max-dist d}
   ou nil se o jogador nao tem bloco em vista."
  [send-cmd! player-name max-dist]
  (let [resp (send-cmd! (uformat "RAYCAST_BLOCK|%s|%.1f" player-name (double max-dist)))
        pos-resp (send-cmd! "PLAYERS")]
    (when (and resp (str/starts-with? resp "OK|"))
      (let [parts (str/split resp #"\|")
            bx (try (Double/parseDouble (nth parts 2 "0")) (catch Throwable _ 0.0))
            by (try (Double/parseDouble (nth parts 3 "0")) (catch Throwable _ 0.0))
            bz (try (Double/parseDouble (nth parts 4 "0")) (catch Throwable _ 0.0))
            ;; A posicao real dos pes do jogador vem do op PLAYERS.
            player (fn []
                     (some (fn [entry]
                             (let [f (str/split entry #",")]
                               (when (= player-name (first f)) f)))
                           (-> pos-resp (str/replace #"^OK\|" "") (str/split #";"))))
            pfields (player)
            [ox oy oz] (if (>= (count pfields) 4)
                         [(Double/parseDouble (nth pfields 1))
                          (Double/parseDouble (nth pfields 2))
                          (Double/parseDouble (nth pfields 3))]
                         [bx (dec by) bz])
            ;; Os olhos ficam ~1.62m acima dos pes; o bloco avistado da a direcao.
            ey (+ oy 1.62)
            dx (- bx ox)
            dy (- by ey)
            dz (- bz oz)
            len (max 0.001 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
        {:look-direction [(/ dx len) (/ dy len) (/ dz len)]
         :ray-origin [ox oy oz]
         :ray-max-dist (double max-dist)}))))

(defn select-aim-target
  "Escolhe, entre os `hostiles`, o que esta na mira do jogador: aquele cuja
   direcao o jogador->hostil forma o menor angulo com a linha do olhar, desde
   que dentro de `aim-cone-deg`. Devolve o hostil escolhido ou nil."
  [snapshot hostiles]
  (when (and snapshot (seq hostiles))
    (let [[dx dy dz] (:look-direction snapshot)
          [ox oy oz] (:ray-origin snapshot)
          cos-limit (Math/cos (Math/toRadians aim-cone-deg))
          scored (keep (fn [{:keys [x y z] :as h}]
                         (let [vx (- (double x) (double ox))
                               vy (- (double y) (double oy))
                               vz (- (double z) (double oz))
                               vlen (max 0.001 (Math/sqrt (+ (* vx vx) (* vy vy) (* vz vz))))
                               cosang (/ (+ (* dx vx) (* dy vy) (* dz vz)) vlen)]
                           (when (>= cosang cos-limit)
                             [cosang h])))
                       hostiles)]
      (second (apply max-key first (cons [(- (double cos-limit) 1.0) nil] scored))))))

(defn- cast-spell!
  "Nucleo comum: varre hostis, aplica dano ao mais proximo e emite o visual
   assinado por `particles` (glifo na bruxa), `sound` e `burst-particles`
   (detonacao nas coordenadas do alvo).
   Quando `caster` e um nome de jogador, a magia NASCE DELE: a varredura de
   hostis e o projetil ancoram na posicao do JOGADOR (nao na bruxa), e ele
   precisa do GRIMORIO no inventario (o item e o poder). Sem `caster` =
   auto-defesa da bruxa: a magia nasce na propria bruxa, sem exigencia.
   `opts` pode trazer `:aim-target` — o hostil que o jogador mira; quando
   presente, E ELE o alvo (mesmo que outro esteja mais perto).
   Devolve true se conjurou (havia alvo), false caso contrario."
  [st agent-name send-cmd! caster now particles sound burst-particles opts]
  (cond
    (not (spell-ready? st now)) false
    (and caster (not (grimoire-carried? send-cmd! caster))) false
    :else
    ;; A magia nasce do CONJURADOR: o jogador quando comanda, a bruxa na auto-defesa.
    (let [anchor (or caster agent-name)
          resp (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" anchor spell-scan-radius))
          scanned (parse-hostile-response resp)
          ;; A mira do jogador tem prioridade; sem mira, cai no mais proximo.
          target (or (:aim-target opts) scanned)]
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
        (send-cmd! (projectile-cmd st anchor send-cmd! target))
        (doseq [b (burst-cmds target burst-particles)] (send-cmd! b)))
      (some? target))))

(defn- parse-all-hostiles
  "Extrai TODOS os hostis da resposta do SyntheticHostileScanner
   (OK|<count>|<id>,<tipo>,<x>,<y>,<z>,<dist>,<hp>;...) para uma lista de mapas.
   Usado pela mira por direcao, que precisa comparar todos contra a linha do olhar."
  [resp]
  (when (and resp (str/starts-with? resp "OK|"))
    (let [payload (nth (str/split resp #"\|" 3) 2 "")]
      (when-not (str/blank? payload)
        (keep (fn [seg]
                (let [fields (str/split seg #",")]
                  (when (>= (count fields) 7)
                    (try
                      {:id (Integer/parseInt (first fields))
                       :type (nth fields 1)
                       :x (Double/parseDouble (nth fields 2))
                       :y (Double/parseDouble (nth fields 3))
                       :z (Double/parseDouble (nth fields 4))
                       :dist (Double/parseDouble (nth fields 5))
                       :hp (Double/parseDouble (nth fields 6))}
                      (catch Throwable _ nil)))))
              (str/split payload #";"))))))

(defn cast-spell-with-aim!
  "Conjura resolvendo a MIRA do jogador: le a mira (`player-aim-snapshot`),
   varre os hostis EM VOLTA DO JOGADOR (`TARGET_HOSTILE|<caster>`) e escolhe,
   entre eles, o que esta na linha do olhar (`select-aim-target`). Sem hostil na
   mira, a magia nao sai — mirar para o vazio nao gasta conjuracao.
   `caster` = nome do jogador (exige o grimorio); nil = auto-defesa, que ignora
   a mira e usa o mais proximo."
  [st agent-name send-cmd! caster now particles sound burst-particles]
  (if (nil? caster)
    (cast-spell! st agent-name send-cmd! nil now particles sound burst-particles nil)
    (let [snapshot (player-aim-snapshot send-cmd! caster spell-scan-radius)
          resp (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" caster spell-scan-radius))
          aimed (select-aim-target snapshot (parse-all-hostiles resp))]
      (cast-spell! st agent-name send-cmd! caster now particles sound burst-particles
                   {:aim-target aimed}))))

(defn cast-coco-spell!
  "Coco - Glifo de Tinta Viva: desenha um glifo que morde o hostil mais proximo.
   `caster` = nome do jogador que comanda (exige o grimorio); nil = auto-defesa.
   `tick` fica na assinatura por simetria com o motor, mas o timing vem de `now`.
   Com `opts` `{:aim-target hostil}`, acerta o hostil apontado pela mira."
  ([coco-st send-cmd! caster _tick now]
   (cast-coco-spell! coco-st send-cmd! caster _tick now nil))
  ([coco-st send-cmd! caster _tick now opts]
   (cast-spell!
    coco-st "Coco" send-cmd! caster now
    ["CMD|execute at Coco run particle minecraft:enchant ~ ~1.0 ~ 0.6 0.6 0.6 0.05 25 normal"
     "CMD|execute at Coco run particle minecraft:witch ~ ~1.2 ~ 0.4 0.4 0.4 0.02 15 normal"
     "CMD|execute at Coco run particle minecraft:crit ~ ~1.0 ~ 0.3 0.3 0.3 0.1 10 normal"]
    "CMD|execute at Coco run playsound minecraft:item.book.page_turn master @a[distance=..24] ~ ~ ~ 1.2 0.8"
    ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
     "particle minecraft:soul_fire_flame ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"
     "particle minecraft:enchant ~ ~ ~ 0.8 0.8 0.8 0.1 40 normal"]
    opts)))

(defn cast-richeh-spell!
  "Richeh - Estilhaco de Cristal: inscreve uma matriz e a estilhaca no alvo.
   `caster` = nome do jogador que comanda (exige o grimorio); nil = auto-defesa.
   `tick` fica na assinatura por simetria com o motor, mas o timing vem de `now`.
   Com `opts` `{:aim-target hostil}`, acerta o hostil apontado pela mira."
  ([richeh-st send-cmd! caster _tick now]
   (cast-richeh-spell! richeh-st send-cmd! caster _tick now nil))
  ([richeh-st send-cmd! caster _tick now opts]
   (cast-spell!
    richeh-st "Richeh" send-cmd! caster now
    ["CMD|execute at Richeh run particle minecraft:end_rod ~ ~1.0 ~ 0.6 0.6 0.6 0.08 25 normal"
     "CMD|execute at Richeh run particle minecraft:glow_squid_ink ~ ~0.8 ~ 0.4 0.4 0.4 0.02 20 normal"
     "CMD|execute at Richeh run particle minecraft:block_marker minecraft:amethyst_block ~ ~1.0 ~ 0.3 0.3 0.3 0.05 12 normal"]
    "CMD|execute at Richeh run playsound minecraft:block.amethyst_block.break master @a[distance=..24] ~ ~ ~ 1.1 0.9"
    ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
     "particle minecraft:end_rod ~ ~ ~ 0.6 0.6 0.6 0.08 40 normal"
     "particle minecraft:block_marker minecraft:amethyst_block ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"]
    opts)))

(defn spell-auto-defense!
  "Reacao automatica: se houver hostil no raio, a bruxa conjura a sua magia.
   Sem `caster` — e a bruxa a defender-se, nao obedece ao grimorio de ninguem.
   Devolve true se conjurou."
  [st agent-name send-cmd! now]
  (let [cast-fn (if (= agent-name "Coco") cast-coco-spell! cast-richeh-spell!)]
    (cast-fn st send-cmd! nil 0 now)))

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
          (if-not (grimoire-carried? send-bridge-cmd! speaker)
            (say-fn :coco "Sem o teu grimório comigo eu não consigo desenhar! Traz o livro-e-pena! 📖🎒")
            (let [hit? (cast-spell-with-aim! coco-st "Coco" send-bridge-cmd! speaker (System/currentTimeMillis)
                                             ["CMD|execute at Coco run particle minecraft:enchant ~ ~1.0 ~ 0.6 0.6 0.6 0.05 25 normal"
                                              "CMD|execute at Coco run particle minecraft:witch ~ ~1.2 ~ 0.4 0.4 0.4 0.02 15 normal"
                                              "CMD|execute at Coco run particle minecraft:crit ~ ~1.0 ~ 0.3 0.3 0.3 0.1 10 normal"]
                                             "CMD|execute at Coco run playsound minecraft:item.book.page_turn master @a[distance=..24] ~ ~ ~ 1.2 0.8"
                                             ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
                                              "particle minecraft:soul_fire_flame ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"
                                              "particle minecraft:enchant ~ ~ ~ 0.8 0.8 0.8 0.1 40 normal"])]
              (if hit?
                (say-fn :coco (rand-nth ["Glifo de Tinta Viva! Segura ele pra mim! 🖋️✨"
                                         "A tinta morde! Não se mexe! 📖"]))
                (say-fn :coco "Mira num bicho, não no vazio! O glifo precisa de um alvo pra morder! 🖋️"))))
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
          (if-not (grimoire-carried? send-bridge-cmd! speaker)
            (say-fn :richeh "Hmph. Sem o teu grimório, não desperdiço meus cristais. 📖")
            (let [hit? (cast-spell-with-aim! richeh-st "Richeh" send-bridge-cmd! speaker (System/currentTimeMillis)
                                             ["CMD|execute at Richeh run particle minecraft:end_rod ~ ~1.0 ~ 0.6 0.6 0.6 0.08 25 normal"
                                              "CMD|execute at Richeh run particle minecraft:glow_squid_ink ~ ~0.8 ~ 0.4 0.4 0.4 0.02 20 normal"
                                              "CMD|execute at Richeh run particle minecraft:block_marker minecraft:amethyst_block ~ ~1.0 ~ 0.3 0.3 0.3 0.05 12 normal"]
                                             "CMD|execute at Richeh run playsound minecraft:block.amethyst_block.break master @a[distance=..24] ~ ~ ~ 1.1 0.9"
                                             ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
                                              "particle minecraft:end_rod ~ ~ ~ 0.6 0.6 0.6 0.08 40 normal"
                                              "particle minecraft:block_marker minecraft:amethyst_block ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"])]
              (if hit?
                (say-fn :richeh (rand-nth ["Estilhaço de Cristal. Não desperdiça meu tempo. 🔮"
                                           "A matriz tá inscrita. Fica parado e ele quebra. 💠"]))
                (say-fn :richeh "Hmph. Mira num alvo de verdade. Meus cristais não se gastam no vazio."))))
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

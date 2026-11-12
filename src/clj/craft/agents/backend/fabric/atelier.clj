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

(defn caster-say!
  "Resposta DA CONJURACAO, falada da posicao do JOGADOR que conjurou.
   As falas das bruxas (`coco-say!`/`richeh-say!`) ancoram na posicao delas e
   so alcancam quem esta a 16 blocos — o Atelie fica a ~200 blocos da area de
   jogo, entao quem conjura nunca ouvia a resposta (o \"mandei mas nada\").
   `caster` = nome do jogador que conjurou: o tellraw nasce DELE e vai PARA ele."
  [send-cmd! caster msg]
  (let [safe-msg (str/replace (or msg "") "\"" "\\\"")
        json-cmd (str "execute at " caster " run tellraw @a[distance=..32] [{\"text\":\"<\",\"color\":\"dark_gray\"},"
                      "{\"text\":\"Coco\",\"color\":\"yellow\",\"bold\":true},"
                      "{\"text\":\"> \",\"color\":\"dark_gray\"},"
                      "{\"text\":\"" safe-msg "\",\"color\":\"yellow\"}]")]
    (send-cmd! (str "CMD|" json-cmd))
    (send-cmd! (str "CMD|execute at " caster " run playsound minecraft:item.book.page_turn master @a[distance=..32] ~ ~ ~ 0.9 1.1"))))

;; =============================================================================
;; Magias de Ataque do Ateliê (Glifos de Coco & Cristais de Richeh)
;; =============================================================================
;; Assinatura visual + dano real a hostis. A varredura e o golpe usam os
;; opcodes de combate ja existentes na bridge (TARGET_HOSTILE/ATTACK_HOSTILE);
;; o visual sai por CMD|particle. Cooldown de 5s evita spam de combinacao.

(def ^:private spell-cooldown-ms 5000)
(def ^:private wand-cooldown-ms 1000)
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
  "True se o cooldown de conjuracao ja passou.
   Com `caster` presente (conjuracao do jogador/varinha), usa wand-cooldown-ms (1000ms).
   Sem `caster` (auto-defesa autonoma das bruxas), usa spell-cooldown-ms (5000ms)."
  ([st now] (spell-ready? st nil now))
  ([st caster now]
   (let [cd (if caster wand-cooldown-ms spell-cooldown-ms)]
     (> (- now (:last-spell-ms @st 0)) cd))))

(def fireball-item-id "minecraft:blaze_rod")
(declare spell-registry)

(defn- projectile-spell-cmd
  "Comando que lanca o projetil configurado pelo feitico na direcao do alvo ou do olhar."
  [st anchor-name send-cmd! target snapshot spell-def]
  (let [{:keys [entity entity-nbt speed] :or {entity "minecraft:fireball"
                                              entity-nbt "{ExplosionPower:1b,Motion:[%.3f,%.3f,%.3f]}"
                                              speed 1.1}} spell-def
        {:keys [x y z]} target
        [wx wy wz] (if send-cmd!
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
        [mx my mz] (if target
                     (let [dx (- (double x) (double wx))
                           dy (- (double y) (double wy))
                           dz (- (double z) (double wz))
                           len (max 0.001 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
                       [(* speed (/ dx len)) (* speed (/ dy len)) (* speed (/ dz len))])
                     (let [[lx ly lz] (or (:look-direction snapshot) [1.0 0.0 0.0])
                           len (max 0.001 (Math/sqrt (+ (* lx lx) (* ly ly) (* lz lz))))]
                       [(* speed (/ lx len)) (* speed (/ ly len)) (* speed (/ lz len))]))
        at-expr (if send-cmd!
                  (uformat "at %s" anchor-name)
                  (uformat "positioned %.2f %.2f %.2f" wx wy wz))
        nbt (uformat entity-nbt mx my mz)]
    (uformat "CMD|execute %s run summon %s ^ ^1.2 ^1 %s" at-expr entity nbt)))

(defn- projectile-cmd
  "Comando que lanca o projetil real (fireball) do CONJURADOR rumo ao alvo.
   A direcao sai da posicao da ancora (`anchor-name`) para as coordenadas do
   alvo, normalizada; `speed` fixa a velocidade. A ancora e a posicao de quem
   conjura: o JOGADOR quando comanda (`anchor-name` = nome dele, o `execute at
   <nome>` fica na posicao real dele), ou a bruxa na auto-defesa. Quando a
   ancora e a bruxa, usa-se o `:pos` do estado (o nome do NPC nao e resolvivel
   pelo `execute at` do servidor). `ExplosionPower:1b` da DANO DE AREA REAL no
   ponto de impacto — e o dano agora vem da propria explosao, nao de um comando
   de dano (apontar a qualquer alvo passa a acerta-lo: a fireball voa ate la e
   detona). O servidor corre com `mobGriefing=false` (MEDIDO e desligado por
   isso), senao esta explosao quebraria blocos.
   SEM alvo (`target` nil), a direcao vem do vetor do olhar (`:look-direction`
   do snapshot): a varinha atira na mira mesmo sem mob nenhum a frente."
  ([st agent-name target] (projectile-cmd st agent-name nil target))
  ([st anchor-name send-cmd! target]
   (projectile-cmd st anchor-name send-cmd! target nil))
  ([st anchor-name send-cmd! target snapshot]
   (projectile-spell-cmd st anchor-name send-cmd! target snapshot
                         (get spell-registry fireball-item-id))))

(defn- burst-cmds
  "Golpe visual ancorado nas COORDENADAS do alvo — a magia detona nele, nao
   so na bruxa. `x/y/z` vem do scanner de hostis."
  [{:keys [x y z]} particles]
  (mapv (fn [p] (uformat "CMD|execute positioned %.2f %.2f %.2f run %s" x y z p))
        particles))

(def ^:private spell-hitkill-damage 100)
(def ^:private spell-hitkill-radius 2.0)

;; O item e o poder; sem ele, a bruxa recusa. Catalogo unificado de feiticos:
;; cada item magico mapeia para a assinatura do seu feitico (entidade, som, particulas).
(def ^:private grimoire-item fireball-item-id)

(def spell-registry
  {"minecraft:blaze_rod"
   {:id :fireball
    :name "Bola de Fogo"
    :entity "minecraft:fireball"
    :entity-nbt "{ExplosionPower:1b,Motion:[%.3f,%.3f,%.3f]}"
    :sound "minecraft:item.firecharge.use"
    :speed 1.1
    :damage-type "minecraft:explosion"
    :caster-particles
    ["CMD|execute at %s run particle minecraft:enchant ~ ~1.0 ~ 0.6 0.6 0.6 0.05 25 normal"
     "CMD|execute at %s run particle minecraft:witch ~ ~1.2 ~ 0.4 0.4 0.4 0.02 15 normal"
     "CMD|execute at %s run particle minecraft:crit ~ ~1.0 ~ 0.3 0.3 0.3 0.1 10 normal"]
    :burst-particles
    ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
     "particle minecraft:soul_fire_flame ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"
     "particle minecraft:enchant ~ ~ ~ 0.8 0.8 0.8 0.1 40 normal"]}

   "minecraft:blaze_powder"
   {:id :fireball
    :name "Bola de Fogo (Legado)"
    :entity "minecraft:fireball"
    :entity-nbt "{ExplosionPower:1b,Motion:[%.3f,%.3f,%.3f]}"
    :sound "minecraft:item.firecharge.use"
    :speed 1.1
    :damage-type "minecraft:explosion"
    :caster-particles
    ["CMD|execute at %s run particle minecraft:enchant ~ ~1.0 ~ 0.6 0.6 0.6 0.05 25 normal"
     "CMD|execute at %s run particle minecraft:witch ~ ~1.2 ~ 0.4 0.4 0.4 0.02 15 normal"
     "CMD|execute at %s run particle minecraft:crit ~ ~1.0 ~ 0.3 0.3 0.3 0.1 10 normal"]
    :burst-particles
    ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
     "particle minecraft:soul_fire_flame ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"
     "particle minecraft:enchant ~ ~ ~ 0.8 0.8 0.8 0.1 40 normal"]}

   "minecraft:breeze_rod"
   {:id :wind-charge
    :name "Onda de Choque"
    :cooldown-ticks 20
    :entity "minecraft:breeze_wind_charge"
    :entity-nbt "{Motion:[%.3f,%.3f,%.3f]}"
    :sound "entity.breeze.shoot"
    :speed 1.5
    :caster-particles
    ["CMD|execute at %s run particle minecraft:gust_emitter_large ~ ~1.2 ~ 0.2 0.2 0.2 0.05 1 normal"
     "CMD|execute at %s run particle minecraft:cloud ~ ~1.0 ~ 0.4 0.4 0.4 0.02 10 normal"]
    :burst-particles
    ["particle minecraft:gust_emitter_small ~ ~ ~ 0.5 0.5 0.5 0.1 5 normal"
     "particle minecraft:cloud ~ ~ ~ 0.5 0.5 0.5 0.05 15 normal"]}

   "minecraft:lightning_rod"
   {:id :lightning-smite
    :name "Relâmpago do Julgamento"
    :cooldown-ticks 60
    :sound "entity.lightning_bolt.thunder"
    :caster-particles
    ["CMD|execute at %s run particle minecraft:electric_spark ~ ~1.2 ~ 0.4 0.4 0.4 0.1 20 normal"
     "CMD|execute at %s run particle minecraft:enchant ~ ~1.0 ~ 0.5 0.5 0.5 0.05 15 normal"]
    :burst-particles
    ["particle minecraft:electric_spark ~ ~ ~ 1.0 1.0 1.0 0.1 35 normal"
     "particle minecraft:flash ~ ~ ~ 0.0 0.0 0.0 0.0 1 normal"]}

   "minecraft:trial_key"
   {:id :lightning-smite
    :name "Relâmpago do Julgamento"
    :cooldown-ticks 60
    :sound "entity.lightning_bolt.thunder"
    :caster-particles
    ["CMD|execute at %s run particle minecraft:electric_spark ~ ~1.2 ~ 0.4 0.4 0.4 0.1 20 normal"
     "CMD|execute at %s run particle minecraft:enchant ~ ~1.0 ~ 0.5 0.5 0.5 0.05 15 normal"]
    :burst-particles
    ["particle minecraft:electric_spark ~ ~ ~ 1.0 1.0 1.0 0.1 35 normal"
     "particle minecraft:flash ~ ~ ~ 0.0 0.0 0.0 0.0 1 normal"]}

   "minecraft:amethyst_shard"
   {:id :frost-beam
    :name "Raio Congelante"
    :cooldown-ticks 20
    :sound "block.glass.break"
    :caster-particles
    ["CMD|execute at %s run particle minecraft:snowflake ~ ~1.2 ~ 0.3 0.3 0.3 0.05 20 normal"
     "CMD|execute at %s run particle minecraft:item amethyst_shard ~ ~1.0 ~ 0.4 0.4 0.4 0.05 15 normal"]
    :burst-particles
    ["particle minecraft:snowflake ~ ~ ~ 1.2 1.2 1.2 0.1 40 normal"
     "particle minecraft:item packed_ice ~ ~ ~ 1.0 1.0 1.0 0.1 25 normal"
     "particle minecraft:dust 0.6 0.85 1.0 1.5 ~ ~ ~ 1.0 1.0 1.0 0.1 20 normal"]}

   "minecraft:echo_shard"
   {:id :shadow-step
    :name "Passo das Sombras"
    :cooldown-ticks 20
    :sound "entity.enderman.teleport"
    :caster-particles
    ["CMD|execute at %s run particle minecraft:portal ~ ~1.0 ~ 0.5 0.5 0.5 0.1 25 normal"
     "CMD|execute at %s run particle minecraft:reverse_portal ~ ~1.0 ~ 0.5 0.5 0.5 0.1 25 normal"]
    :burst-particles
    ["particle minecraft:portal ~ ~1.0 ~ 0.8 1.0 0.8 0.5 60 normal"
     "particle minecraft:reverse_portal ~ ~1.0 ~ 0.6 0.8 0.6 0.2 40 normal"]}

   "minecraft:heart_of_the_sea"
   {:id :tidal-surge
    :name "Orbe das Marés"
    :cooldown-ticks 20
    :sound "entity.generic.splash"
    :caster-particles
    ["CMD|execute at %s run particle minecraft:splash ~ ~1.2 ~ 0.3 0.3 0.3 0.08 20 normal"
     "CMD|execute at %s run particle minecraft:bubble_pop ~ ~1.0 ~ 0.4 0.4 0.4 0.05 15 normal"
     "CMD|execute at %s run particle minecraft:falling_dripstone_water ~ ~1.2 ~ 0.2 0.2 0.2 0.05 10 normal"]
    :burst-particles
    ["particle minecraft:splash ~ ~ ~ 1.5 1.5 1.5 0.2 80 normal"
     "particle minecraft:bubble_pop ~ ~ ~ 1.2 1.2 1.2 0.1 50 normal"
     "particle minecraft:dripping_dripstone_water ~ ~ ~ 1.0 1.0 1.0 0.1 30 normal"]}})

(defn- hitkill-cmd
  "Comando de dano que mata o alvo num golpe. Usa `damage` (comando vanilla
  1.21) com `spell-hitkill-damage` e tipo `minecraft:explosion` — o dano da
  FIREBALL em si (a fisica nao fere: MEDIDO que ExplosionPower 1 e 4 deixam o
  zumbi a 20.0 hp; o comando com tipo explosion leva 20.0 -> 14.9). 100 mata
  mobs comuns de uma vez e deixa bosses (wither, dragao) vivos. O alvo e preso
  por COORDENADAS + raio curto (2 blocos) + `limit=1`, o mesmo que se mediu a
  funcionar pela ponte."
  [{:keys [x y z]}]
  (uformat "CMD|damage @e[x=%.2f,y=%.2f,z=%.2f,distance=..%.1f,limit=1,sort=nearest] %d minecraft:explosion"
           x y z spell-hitkill-radius spell-hitkill-damage))

(defn- grimoire-carried?
  "True se o jogador `caster` leva QUALQUER ITEM DO CATALOGO NO INVENTARIO.
   `INSPECT_INVENTORY|<jogador>` devolve OK|<count>|<id>,<qtd>;<id>,<qtd>;..."
  [send-cmd! caster]
  (let [resp (send-cmd! (uformat "INSPECT_INVENTORY|%s" caster))]
    (boolean (and resp (str/starts-with? resp "OK|")
                  (some #(str/includes? resp %) (keys spell-registry))))))

;; =============================================================================
;; Mira por Direcao (o jogador aponta, a magia acerta o que ele mira)
;; =============================================================================
;; O jogador NAO precisa de um op novo: a ponte ja sabe ler a direcao do olhar
;; (RAYCAST_BLOCK devolve o primeiro bloco solido na linha do olhar). Nao existe
;; um op de raycast de ENTIDADES, entao a mira cruza a LINHA do olhar com a
;; varredura de hostis (TARGET_HOSTILE) e escolhe o hostil mais proximo DESSA
;; linha — os hostis que nao estao no cone de mira sao ignorados.

(def ^:private aim-cone-deg
  "Meio-angulo do cone de mira, em graus. HISTORICO: era o limite dentro do qual
   um hostil contava como alvo da mira — e por isso apontar para fora do cone
   deixava a magia sem alvo. Passou a estar SEM LIMITE (nil): conta o hostil mais
   alinhado com a linha do olhar, desde que esteja a FRENTE (cos > 0). Mantida
   como constante documental."
  nil)

(defn- player-pes
  "Le a posicao dos pes do jogador (op PLAYERS -> nome,x,y,z). Devolve [x y z] ou nil."
  [send-cmd! player-name]
  (let [pos-resp (send-cmd! "PLAYERS")]
    (some (fn [entry]
            (let [f (str/split entry #",")]
              (when (= player-name (first f))
                [(Double/parseDouble (nth f 1))
                 (Double/parseDouble (nth f 2))
                 (Double/parseDouble (nth f 3))])))
          (-> pos-resp (str/replace #"^OK\|" "") (str/split #";")))))

(defn- look-direction-op
  "Le a DIRECAO REAL do olhar pelo op LOOK (vetor unitario + yaw/pitch).
   `LOOK|<jogador>` -> OK|dx|dy|dz|yaw|pitch. Devolve [dx dy dz] normalizado
   ou nil se o op nao existe (JVM antiga) ou o jogador nao esta online."
  [send-cmd! player-name]
  (let [resp (send-cmd! (uformat "LOOK|%s" player-name))]
    (when (and resp (str/starts-with? resp "OK|"))
      (let [parts (str/split resp #"\|")]
        (when (>= (count parts) 5)
          (let [dx (try (Double/parseDouble (nth parts 1)) (catch Throwable _ 0.0))
                dy (try (Double/parseDouble (nth parts 2)) (catch Throwable _ 0.0))
                dz (try (Double/parseDouble (nth parts 3)) (catch Throwable _ 0.0))
                len (max 0.001 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
            [(/ dx len) (/ dy len) (/ dz len)]))))))

(defn player-aim-snapshot
  "Le a mira do jogador pela ponte. A ORIGEM sao os pes do jogador (op PLAYERS,
   que devolve nome,x,y,z) e a DIRECAO e a direcao REAL do olhar pelo op LOOK
   (vetor do olhar, nao depende de haver bloco a frente).
   FALLBACK: se o op LOOK nao existir (JVM antiga, sem reinicio), a direcao cai
   no antigo vetor origem->bloco avistado (RAYCAST_BLOCK) — que e fragil (acerta
   o chao), mas mantem o caminho vivo.
   `send-cmd!` e injetado para os testes; devolve
   {:look-direction [dx dy dz] :ray-origin [x y z] :ray-max-dist d}
   ou nil se o jogador nao existe."
  [send-cmd! player-name max-dist]
  (let [look (look-direction-op send-cmd! player-name)
        pes  (player-pes send-cmd! player-name)]
    (when pes
      (if look
        {:look-direction look
         :ray-origin pes
         :ray-max-dist (double max-dist)}
        ;; Fallback: derivar a direcao do bloco avistado (caminho antigo).
        (let [resp (send-cmd! (uformat "RAYCAST_BLOCK|%s|%.1f" player-name (double max-dist)))]
          (when (and resp (str/starts-with? resp "OK|"))
            (let [parts (str/split resp #"\|")
                  bx (try (Double/parseDouble (nth parts 2 "0")) (catch Throwable _ 0.0))
                  by (try (Double/parseDouble (nth parts 3 "0")) (catch Throwable _ 0.0))
                  bz (try (Double/parseDouble (nth parts 4 "0")) (catch Throwable _ 0.0))
                  [ox oy oz] pes
                  ey (+ oy 1.62)
                  dx (- bx ox)
                  dy (- by ey)
                  dz (- bz oz)
                  len (max 0.001 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
              {:look-direction [(/ dx len) (/ dy len) (/ dz len)]
               :ray-origin [ox oy oz]
               :ray-max-dist (double max-dist)})))))))

(defn select-aim-target
  "Escolhe, entre os `hostiles`, o que esta na mira do jogador: aquele cuja
   direcao o jogador->hostil forma o MENOR angulo com a linha do olhar. Ja NAO
   ha cone maximo: apontar para qualquer hostil a frente conta (o mais alinhado
   ganha); quem nao esta a frente (cos <= 0) e ignorado — nao se acerta pelas
   costas. Devolve o hostil escolhido ou nil."
  [snapshot hostiles]
  (when (and snapshot (seq hostiles))
    (let [[dx dy dz] (:look-direction snapshot)
          [ox oy oz] (:ray-origin snapshot)
          scored (keep (fn [{:keys [x y z] :as h}]
                         (let [vx (- (double x) (double ox))
                               vy (- (double y) (double oy))
                               vz (- (double z) (double oz))
                               vlen (max 0.001 (Math/sqrt (+ (* vx vx) (* vy vy) (* vz vz))))
                               cosang (/ (+ (* dx vx) (* dy vy) (* dz vz)) vlen)]
                           ;; Só conta o que está A FRENTE do olhar (cos > 0).
                           (when (pos? cosang)
                             [cosang h])))
                       hostiles)]
      (second (apply max-key first (cons [-1.0 nil] scored))))))

(defn select-aim-target-cone
  "Escolhe o hostil na mira dentro de um cone restrito (min-cos, ex: 0.94 ~ 20°).
   Evita que mobs laterais roubem a mira quando o jogador esta apontando para outro ponto."
  [snapshot hostiles min-cos]
  (when (and snapshot (seq hostiles))
    (let [[dx dy dz] (:look-direction snapshot)
          [ox oy oz] (:ray-origin snapshot)
          scored (keep (fn [{:keys [x y z] :as h}]
                         (let [vx (- (double x) (double ox))
                               vy (- (double y) (double oy))
                               vz (- (double z) (double oz))
                               vlen (max 0.001 (Math/sqrt (+ (* vx vx) (* vy vy) (* vz vz))))
                               cosang (/ (+ (* dx vx) (* dy vy) (* dz vz)) vlen)]
                           (when (>= cosang min-cos)
                             [cosang h])))
                       hostiles)]
      (second (apply max-key first (cons [-1.0 nil] scored))))))

(defn find-safe-ground-y
  "Garante que o destino [tx ty tz] nao deixe o jogador enterrado em blocos
   solidos. Se o ponto estiver dentro de terra/pedra/bloco solido, sobe y ate
   encontrar espaco livre de 2 blocos (ar nos pes e ar na cabeca).
   Se ja estiver no ar (dash aereo), mantem ty.
   Se nao encontrar ar seguro ate 24 blocos, recua para fallback-oy (altura dos pes)."
  [send-cmd! tx ty tz fallback-oy]
  (let [bx (int (Math/floor tx))
        bz (int (Math/floor tz))
        init-by (int (Math/floor ty))]
    (loop [y init-by
           climbed 0]
      (if (>= climbed 24)
        (double fallback-oy)
        (let [resp (send-cmd! (uformat "BLOCK|%d|%d|%d" bx y bz))
              block-id (when (and resp (str/starts-with? resp "OK|"))
                         (second (str/split resp #"\|")))
              solid? (and block-id (not (str/includes? block-id ":air")))]
          (if solid?
            (recur (inc y) (inc climbed))
            ;; Bloco y e passavel (ar). Verifica se ha espaco para a cabeca em y+1:
            (let [resp-head (send-cmd! (uformat "BLOCK|%d|%d|%d" bx (inc y) bz))
                  head-id (when (and resp-head (str/starts-with? resp-head "OK|"))
                            (second (str/split resp-head #"\|")))
                  head-solid? (and head-id (not (str/includes? head-id ":air")))]
              (if head-solid?
                (recur (+ y 2) (+ climbed 2))
                (double y)))))))))

(defn- cast-spell!
  "Nucleo comum: varre hostis, aplica dano ao mais proximo e emite o visual
   assinado por `particles` (glifo na bruxa), `sound` e `burst-particles`
   (detonacao nas coordenadas do alvo).
   Quando `caster` e um nome de jogador, a magia NASCE DELE: a varredura de
   hostis e o projetil ancoram na posicao do JOGADOR (nao na bruxa), e ele
   precisa do GRIMORIO no inventario (o item e o poder). Sem `caster` =
   auto-defesa da bruxa: a magia nasce na propria bruxa, sem exigencia.
   `opts` pode trazer `:aim-target` — o hostil que o jogador mira; quando
   presente, E ELE o alvo (mesmo que outro esteja mais perto) — e
   `:look-snapshot` — a mira lida, que da a direcao do projetil quando NAO ha
   alvo. O projetil VOA SEMPRE: com alvo vai ate ele, sem alvo vai na direcao do
   olhar. O DANO sai por comando `damage ... minecraft:explosion` no alvo
   (MEDIDO: a fireball fisica NAO faz dano — so empurra; o comando, sim). O alvo
   e o hostil mais alinhado com a mira, SEM cone maximo: apontar a qualquer mob a
   frente acerta-o.
   Devolve true se havia alvo, false se atirou sem alvo."
  [st agent-name send-cmd! caster now particles sound burst-particles opts]
  (cond
    (not (spell-ready? st caster now)) false
    (and caster (not (grimoire-carried? send-cmd! caster))) false
    :else
    ;; A magia nasce do CONJURADOR: o jogador quando comanda, a bruxa na auto-defesa.
    (let [anchor (or caster agent-name)
          resp (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" anchor spell-scan-radius))
          scanned (parse-hostile-response resp)
          ;; A mira do jogador tem prioridade; sem mira, cai no mais proximo.
          target (or (:aim-target opts) scanned)
          snapshot (:look-snapshot opts)]
      (swap! st assoc :last-spell-ms now)
      (when (should-say-spell? st now)
        (swap! st assoc :last-spell-said-ms now :spell-said? true))
      (doseq [p particles] (send-cmd! p))
      (when sound (send-cmd! sound))
      ;; O PROJETIL VOA SEMPRE — com alvo vai ate ele, sem alvo vai na mira.
      (send-cmd! (projectile-cmd st anchor send-cmd! target snapshot))
      (when target
        (send-cmd! (str "SWING|" agent-name))
        ;; DANO DE EXPLOSAO por comando (a fireball fisica nao fere — MEDIDO).
        (send-cmd! (hitkill-cmd target))
        ;; Fogo ao impacto no alvo (chao e pes do mob)
        (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run setblock ~ ~ ~ minecraft:fire keep"
                            (:x target) (:y target) (:z target)))
        (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run setblock ~ ~1 ~ minecraft:fire keep"
                            (:x target) (:y target) (:z target)))
        ;; Detonacao visual NAS COORDENADAS do alvo.
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
   entre eles, o que esta na linha do olhar (`select-aim-target`).
   A mira DIRIGE o tiro, nao o autoriza: o projetil voa sempre — com hostil na
   mira vai ate ele (dano + detonacao), sem hostil voa na direcao do olhar.
   `caster` = nome do jogador (exige o grimorio); nil = auto-defesa, que ignora
   a mira e usa o mais proximo."
  [st agent-name send-cmd! caster now particles sound burst-particles]
  (if (nil? caster)
    (cast-spell! st agent-name send-cmd! nil now particles sound burst-particles nil)
    (let [snapshot (player-aim-snapshot send-cmd! caster spell-scan-radius)
          resp (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" caster spell-scan-radius))
          aimed (select-aim-target snapshot (parse-all-hostiles resp))]
      (cast-spell! st agent-name send-cmd! caster now particles sound burst-particles
                   {:aim-target aimed :look-snapshot snapshot}))))

(def ^:private casting-words
  "Palavras de CASTING puro — escritas no chat SEM nome de bruxa, disparam a
   magia a partir do proprio jogador. Nao colidem com as ordens das bruxas."
  ["fireball" "fogo" "bola de fogo" "fire"])

(defn casting-spell?
  "True se a mensagem e um CASTING puro (a palavra de magia, sem nome de bruxa).
   Compara sobre a mensagem em minusculas: as palavras de `casting-words` como
   substring. Vazia ou nil = false."
  [msg]
  (let [lower (str/lower-case (or msg ""))]
    (boolean (some #(str/includes? lower %) casting-words))))

(defn atelier-command?
  "True se a mensagem e para o ATELIE: ou um comando com o nome de uma bruxa
   (`coco`/`richeh`), ou um CASTING puro (`fireball`/`fogo`, sem nome de bruxa).
   O dispatcher usa isto para deixar passar ao `handle-atelier-chat!` — sem ele,
   um casting puro era capturado e DESCARTADO antes de chegar ao handler."
  [msg]
  (let [lower (str/lower-case (or msg ""))]
    (boolean (or (str/includes? lower "coco")
                 (str/includes? lower "richeh")
                 (casting-spell? msg)))))

(defn cast-casting!
  "Casting puro do JOGADOR: ele escreve so a palavra de magia no chat (sem nome
   de bruxa) e a magia nasca DELE, na direcao da mira dele. Usa a assinatura da
   Coco (glifo + fireball), que o shader emite. Exige o grimorio no inventario.
   Devolve true se conjurou (havia alvo na mira)."
  [st send-cmd! caster now]
  (cast-spell-with-aim!
   st "Coco" send-cmd! caster now
   [(uformat "CMD|execute at %s run particle minecraft:enchant ~ ~1.0 ~ 0.6 0.6 0.6 0.05 25 normal" caster)
    (uformat "CMD|execute at %s run particle minecraft:witch ~ ~1.2 ~ 0.4 0.4 0.4 0.02 15 normal" caster)
    (uformat "CMD|execute at %s run particle minecraft:crit ~ ~1.0 ~ 0.3 0.3 0.3 0.1 10 normal" caster)]
   (uformat "CMD|execute at %s run playsound minecraft:item.book.page_turn master @a[distance=..24] ~ ~ ~ 1.2 0.8" caster)
   ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
    "particle minecraft:soul_fire_flame ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"
    "particle minecraft:enchant ~ ~ ~ 0.8 0.8 0.8 0.1 40 normal"]))

(defn held-spell-item
  "Le o item na mao principal do jogador via op HELD_ITEM.
   Devolve um mapa {:item-id ... :damage ... :max-damage ... :spell ...}
   se o item estiver no catalogo de feiticos; nil caso contrario."
  [send-cmd! player-name]
  (let [resp (send-cmd! (uformat "HELD_ITEM|%s" player-name))]
    (when (and resp (str/starts-with? resp "OK|"))
      (let [parts (str/split resp #"\|")
            id (nth parts 1 nil)
            dmg (when (>= (count parts) 4)
                  (try (Long/parseLong (nth parts 3)) (catch Throwable _ nil)))
            max-dmg (when (>= (count parts) 5)
                      (try (Long/parseLong (nth parts 4)) (catch Throwable _ nil)))
            spell (get spell-registry id)]
        (when (and spell (some? dmg))
          {:item-id id
           :damage dmg
           :max-damage (or max-dmg 64)
           :spell spell})))))

(defn held-item-damage
  "Le o item na mao do jogador (op HELD_ITEM) e devolve o DANO (carga gasta)
   se for a varinha de fogo; nil se nao tem o item na mao.
   Mantido por retrocompatibilidade: delega para held-spell-item."
  [send-cmd! player-name]
  (when-let [info (held-spell-item send-cmd! player-name)]
    (when (= (:item-id info) fireball-item-id)
      (:damage info))))

(defn spell-used?
  "True se a carga do feitico foi gasta entre duas leituras consecutivas.
   Exige que seja o mesmo item-id e que o dano agora seja MAIOR que o anterior."
  [prev-info now-info]
  (boolean
   (and (map? prev-info)
        (map? now-info)
        (= (:item-id prev-info) (:item-id now-info))
        (> (long (:damage now-info)) (long (:damage prev-info))))))

(defn fireball-used?
  "Decide se houve um USO da varinha desde a ultima leitura.
   Compara o dano (carga) de `prev` com `now-dmg`; uso = o dano AUMENTOU.
   Se `prev` e nil (primeira leitura), nao dispara — so memoriza a base.
   Devolve true (usou) ou false (nada/a primeira leitura)."
  [prev now-dmg]
  (boolean (and (some? prev) (some? now-dmg) (> (long now-dmg) (long prev)))))

(defn dispatch-held-spell!
  "Despacha a conjuracao de um feitico a partir do item na mao principal."
  [st send-cmd! player-name spell-info now]
  (let [spell (or (:spell spell-info) (get spell-registry (:item-id spell-info)))]
    (when spell
      (if (= (:id spell) :shadow-step)
        ;; Passo das Sombras (Duplo Dash): permite 2 cliques seguidos sem cooldown completo
        (let [dash-st (get-in @st [:dash-state player-name])
              second-dash? (and dash-st
                                (= (:count dash-st) 1)
                                (< (- now (or (:last-ms dash-st) 0)) 3000))
              cooldown-ticks (if second-dash?
                               (or (:cooldown-ticks spell) 25)
                               3)]
          (swap! st assoc-in [:dash-state player-name]
                 {:count (if second-dash? 0 1)
                  :last-ms now})
          (send-cmd! (uformat "ITEM_COOLDOWN|%s|%s|%d" player-name (:item-id spell-info) cooldown-ticks)))
        (send-cmd! (uformat "ITEM_COOLDOWN|%s|%s|%d" player-name (:item-id spell-info) (or (:cooldown-ticks spell) 20))))
      (case (:id spell)
        :fireball
        (let [snapshot (player-aim-snapshot send-cmd! player-name 48.0)
              resp-hostiles (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" player-name 48.0))
              hostiles (vec (or (parse-all-hostiles resp-hostiles) []))
              aim-mob (select-aim-target snapshot hostiles)
              [ox oy oz] (or (:ray-origin snapshot) (:pos @st) [0.0 64.0 0.0])
              [lx ly lz] (or (:look-direction snapshot) [1.0 0.0 0.0])
              ;; Ponto central do impacto da bola de fogo (mob, bloco solido ou linha do olhar):
              [ix iy iz] (cond
                           aim-mob
                           [(:x aim-mob) (:y aim-mob) (:z aim-mob)]

                           :else
                           (let [rc-resp (send-cmd! (uformat "RAYCAST_BLOCK|%s|%.1f" player-name 48.0))]
                             (if (and rc-resp (str/starts-with? rc-resp "OK|"))
                               (let [parts (str/split rc-resp #"\|")]
                                 [(try (+ (Double/parseDouble (nth parts 2)) 0.5) (catch Throwable _ (+ ox (* 24.0 lx))))
                                  (try (+ (Double/parseDouble (nth parts 3)) 1.0) (catch Throwable _ (+ oy (* 24.0 ly))))
                                  (try (+ (Double/parseDouble (nth parts 4)) 0.5) (catch Throwable _ (+ oz (* 24.0 lz))))])
                               [(+ ox (* 24.0 lx))
                                (+ oy (* 24.0 ly))
                                (+ oz (* 24.0 lz))])))
              target aim-mob]
          (swap! st assoc :last-spell-ms now)
          (doseq [p (:caster-particles spell)]
            (send-cmd! (uformat p player-name)))
          (when-let [snd (:sound spell)]
            (send-cmd! (uformat "CMD|execute at %s run playsound %s master @a[distance=..24] ~ ~ ~ 1.5 1.0" player-name snd)))
          ;; Lanca o projetil fisico de bola de fogo
          (send-cmd! (projectile-spell-cmd st player-name send-cmd! target snapshot spell))
          ;; FOGO NO IMPACTO:
          ;; 1. Coloca fogo no bloco de impacto e no bloco logo acima (se for ar), preservando blocos solidos
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run setblock ~ ~ ~ minecraft:fire keep"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run setblock ~ ~1 ~ minecraft:fire keep"
                              ix iy iz))
          ;; 2. Area de fogo ao redor (fill em raio de 1 bloco sobre ar)
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run fill ~-1 ~ ~-1 ~1 ~1 ~1 minecraft:fire keep"
                              ix iy iz))
          ;; 3. Dano de impacto em area: 50 de dano de explosao em mobs ao redor (protege jogador, itens e XP)
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:experience_orb,distance=..4.0] run damage @s 50 minecraft:explosion"
                              ix iy iz))
          ;; 4. Incendeia mobs atingidos (setblock fire nos pes se for ar) e dano continuo de fogo
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:experience_orb,distance=..4.0] at @s run setblock ~ ~ ~ minecraft:fire keep"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:experience_orb,distance=..4.0] run damage @s 10 minecraft:in_fire"
                              ix iy iz))
          ;; 5. Efeitos visuais e sonoros de impacto (explosao, labaredas e estalo de fogo)
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run playsound minecraft:entity.generic.explode master @a ~ ~ ~ 2.0 1.0"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run playsound minecraft:item.firecharge.use master @a ~ ~ ~ 2.0 0.9"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:explosion ~ ~0.5 ~ 0.5 0.5 0.5 0.0 2 normal"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:flame ~ ~0.5 ~ 1.2 0.8 1.2 0.1 40 normal"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:lava ~ ~0.5 ~ 0.8 0.5 0.8 0.1 15 normal"
                              ix iy iz))
          true)

        :wind-charge
        (let [snapshot (player-aim-snapshot send-cmd! player-name 24.0)
              resp-hostiles (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" player-name spell-scan-radius))
              hostiles (vec (or (parse-all-hostiles resp-hostiles) []))
              aim-mob (select-aim-target snapshot hostiles)
              [ox oy oz] (or (:ray-origin snapshot) (:pos @st) [0.0 64.0 0.0])
              [lx ly lz] (or (:look-direction snapshot) [1.0 0.0 0.0])
              ;; Ponto central do impacto do vento (mob, bloco solido ou linha do olhar):
              [ix iy iz] (cond
                           aim-mob
                           [(:x aim-mob) (:y aim-mob) (:z aim-mob)]

                           :else
                           (let [rc-resp (send-cmd! (uformat "RAYCAST_BLOCK|%s|%.1f" player-name 24.0))]
                             (if (and rc-resp (str/starts-with? rc-resp "OK|"))
                               (let [parts (str/split rc-resp #"\|")]
                                 [(try (Double/parseDouble (nth parts 2)) (catch Throwable _ (+ ox (* 12.0 lx))))
                                  (try (Double/parseDouble (nth parts 3)) (catch Throwable _ (+ oy (* 12.0 ly))))
                                  (try (Double/parseDouble (nth parts 4)) (catch Throwable _ (+ oz (* 12.0 lz))))])
                               [(+ ox (* 12.0 lx))
                                (+ oy (* 12.0 ly))
                                (+ oz (* 12.0 lz))])))
              target aim-mob]
          (swap! st assoc :last-spell-ms now)
          (doseq [p (:caster-particles spell)]
            (send-cmd! (uformat p player-name)))
          (when-let [snd (:sound spell)]
            (send-cmd! (uformat "CMD|execute at %s run playsound %s master @a[distance=..24] ~ ~ ~ 1.5 1.0" player-name snd)))
          ;; Lanca o projetil fisico de vento
          (send-cmd! (projectile-spell-cmd st player-name send-cmd! target snapshot spell))
          ;; Dano e efeitos em area (AOE): 40 de dano em raio de 6.5 blocos em todos os hostis/mobs (poupa jogador, itens e XP)
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:experience_orb,distance=..6.5] run damage @s 40 minecraft:player_attack"
                              ix iy iz))
          ;; Efeitos de detonacao e choque sonoro no ponto de impacto
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run playsound entity.breeze.wind_burst master @a ~ ~ ~ 2.0 0.9"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:gust_emitter_large ~ ~ ~ 1.0 0.5 1.0 0.1 3 normal"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:cloud ~ ~ ~ 1.5 1.0 1.5 0.1 30 normal"
                              ix iy iz))
          true)

        :lightning-smite
        (let [last-lightning (get @st :last-lightning-ms 0)]
          (if (< (- now last-lightning) 3000)
            false
            (let [thunder-range 80.0
                  snapshot (player-aim-snapshot send-cmd! player-name thunder-range)
                  resp-hostiles (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" player-name thunder-range))
                  hostiles (vec (or (parse-all-hostiles resp-hostiles) []))
                  ;; Cone restrito de 20° (cos >= 0.94) para só focar em mob se estiver realmente na mira
                  aim-mob (select-aim-target-cone snapshot hostiles 0.94)
                  [ox oy oz] (or (:ray-origin snapshot) (:pos @st) [0.0 64.0 0.0])
                  [lx ly lz] (or (:look-direction snapshot) [1.0 0.0 0.0])
                  ;; Ponto exato do impacto (mob mirado, ou bloco mirado centralizado no topo):
                  [ix iy iz] (cond
                               aim-mob
                               [(:x aim-mob) (:y aim-mob) (:z aim-mob)]

                               :else
                               (let [rc-resp (send-cmd! (uformat "RAYCAST_BLOCK|%s|%.1f" player-name thunder-range))]
                                 (if (and rc-resp (str/starts-with? rc-resp "OK|"))
                                   (let [parts (str/split rc-resp #"\|")]
                                     [(try (+ (Double/parseDouble (nth parts 2)) 0.5) (catch Throwable _ (+ ox (* 48.0 lx))))
                                      (try (+ (Double/parseDouble (nth parts 3)) 1.0) (catch Throwable _ (+ oy (* 48.0 ly))))
                                      (try (+ (Double/parseDouble (nth parts 4)) 0.5) (catch Throwable _ (+ oz (* 48.0 lz))))])
                                   [(+ ox (* 48.0 lx))
                                    (+ oy (* 48.0 ly))
                                    (+ oz (* 48.0 lz))])))
                  ]
              (swap! st assoc :last-spell-ms now :last-lightning-ms now)
              (doseq [p (:caster-particles spell)]
                (send-cmd! (uformat p player-name)))
              ;; Imunidade total (100% dano reduzido + fogo) ao conjurador
              (send-cmd! (uformat "CMD|effect give %s minecraft:resistance 2 4 true" player-name))
              (send-cmd! (uformat "CMD|effect give %s minecraft:fire_resistance 3 1 true" player-name))
              ;; Invocacao do raio exatamente no ponto mirado (a qualquer distancia)
              (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run summon minecraft:lightning_bolt ~ ~ ~"
                                  ix iy iz))
              ;; Dano concentrado (AOE reduzido para 3.5 blocos ao redor do raio, poupando jogadores)
              (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:experience_orb,distance=..3.5] run damage @s 50 minecraft:lightning_bolt"
                                  ix iy iz))
              ;; Fagulhas eletricas e trovao
              (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:electric_spark ~ ~1.0 ~ 0.8 1.0 0.8 0.15 35 normal"
                                  ix iy iz))
              (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run playsound entity.lightning_bolt.thunder master @a ~ ~ ~ 3.5 1.0"
                                  ix iy iz))
              true)))

        :frost-beam
        (let [frost-range 48.0
              snapshot (player-aim-snapshot send-cmd! player-name frost-range)
              resp-hostiles (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" player-name frost-range))
              hostiles (vec (or (parse-all-hostiles resp-hostiles) []))
              aim-mob (select-aim-target-cone snapshot hostiles 0.94)
              [ox oy oz] (or (:ray-origin snapshot) (:pos @st) [0.0 64.0 0.0])
              [lx ly lz] (or (:look-direction snapshot) [1.0 0.0 0.0])
              [ix iy iz] (cond
                           aim-mob
                           [(:x aim-mob) (:y aim-mob) (:z aim-mob)]

                           :else
                           (let [rc-resp (send-cmd! (uformat "RAYCAST_BLOCK|%s|%.1f" player-name frost-range))]
                             (if (and rc-resp (str/starts-with? rc-resp "OK|"))
                               (let [parts (str/split rc-resp #"\|")]
                                 [(try (+ (Double/parseDouble (nth parts 2)) 0.5) (catch Throwable _ (+ ox (* 24.0 lx))))
                                  (try (+ (Double/parseDouble (nth parts 3)) 1.0) (catch Throwable _ (+ oy (* 24.0 ly))))
                                  (try (+ (Double/parseDouble (nth parts 4)) 0.5) (catch Throwable _ (+ oz (* 24.0 lz))))])
                               [(+ ox (* 24.0 lx))
                                (+ oy (* 24.0 ly))
                                (+ oz (* 24.0 lz))])))
              speed 2.4
              vx (* lx speed)
              vy (* ly speed)
              vz (* lz speed)]
          (swap! st assoc :last-spell-ms now)
          (doseq [p (:caster-particles spell)]
            (send-cmd! (uformat p player-name)))
          ;; Saraivada de 3 projeteis de gelo (snowball) na direcao visada
          (doseq [offset [0.0 -0.12 0.12]]
            (send-cmd! (uformat "CMD|execute at %s positioned ~ ~1.5 ~ run summon minecraft:snowball ~ ~ ~ {Item:{id:\"minecraft:snowball\"},Motion:[%.3f,%.3f,%.3f]}"
                                player-name
                                (+ vx (* offset (- lz)))
                                vy
                                (+ vz (* offset lx)))))
          ;; Dano de congelamento (35 de dano de gelo/freeze) em area de 4.5 blocos ao redor do impacto
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:experience_orb,distance=..4.5] run damage @s 35 minecraft:freeze"
                              ix iy iz))
          ;; Lentidao extrema (Slowness IV por 6s) nos inimigos na area
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:experience_orb,distance=..4.5] run effect give @s minecraft:slowness 6 3 true"
                              ix iy iz))
          ;; Efeitos sonoros de estilhacamento de gelo / vidro e ametista
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run playsound block.glass.break master @a ~ ~ ~ 2.5 1.2"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run playsound block.amethyst_block.break master @a ~ ~ ~ 2.0 0.8"
                              ix iy iz))
          ;; Particulas de nevasca / snowflake e gelo compacto
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:snowflake ~ ~0.5 ~ 1.5 0.8 1.5 0.1 50 normal"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:item packed_ice ~ ~0.5 ~ 1.2 0.8 1.2 0.08 25 normal"
                              ix iy iz))
          true)

        :shadow-step
        (let [max-dash 32.0
              snapshot (player-aim-snapshot send-cmd! player-name max-dash)
              [ox oy oz] (or (:ray-origin snapshot) (:pos @st) [0.0 64.0 0.0])
              [lx ly lz] (or (:look-direction snapshot) [1.0 0.0 0.0])
              rc-resp (send-cmd! (uformat "RAYCAST_BLOCK|%s|%.1f" player-name max-dash))
              dist (if (and rc-resp (str/starts-with? rc-resp "OK|"))
                     (let [parts (str/split rc-resp #"\|")
                           d (try (Double/parseDouble (nth parts 5)) (catch Throwable _ max-dash))]
                       (max 0.0 (- d 1.2)))
                     max-dash)
              raw-tx (+ ox (* dist lx))
              raw-ty (+ oy (* dist ly))
              raw-tz (+ oz (* dist lz))
              safe-ty (find-safe-ground-y send-cmd! raw-tx raw-ty raw-tz oy)
              tx raw-tx
              ty safe-ty
              tz raw-tz]
          (swap! st assoc :last-spell-ms now)
          ;; 1. Origem: som e nuvem densa de portal e reverse_portal
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run playsound entity.enderman.teleport master @a ~ ~ ~ 1.5 1.2"
                              ox oy oz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:portal ~ ~1.0 ~ 0.8 1.0 0.8 0.5 60 normal"
                              ox oy oz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:reverse_portal ~ ~1.0 ~ 0.6 0.8 0.6 0.2 40 normal"
                              ox oy oz))
          ;; 2. Teleporte instantaneo mantendo a rotacao atual (~ ~)
          (send-cmd! (uformat "CMD|tp %s %.2f %.2f %.2f ~ ~"
                              player-name tx ty tz))
          ;; 3. Efeitos pos-teleporte: 2s de resistance e velocidade para reposicionamento (sem sumir a skin)
          (send-cmd! (uformat "CMD|effect give %s minecraft:resistance 2 4 true" player-name))
          (send-cmd! (uformat "CMD|effect give %s minecraft:speed 2 1 true" player-name))
          ;; 4. Destino: som e nuvem densa de portal e reverse_portal
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run playsound entity.enderman.teleport master @a ~ ~ ~ 1.5 1.0"
                              tx ty tz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:portal ~ ~1.0 ~ 0.8 1.0 0.8 0.5 60 normal"
                              tx ty tz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:reverse_portal ~ ~1.0 ~ 0.6 0.8 0.6 0.2 40 normal"
                              tx ty tz))
          true)

        :tidal-surge
        (let [max-reach 48.0
              snapshot (player-aim-snapshot send-cmd! player-name max-reach)
              resp-hostiles (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" player-name max-reach))
              hostiles (vec (or (parse-all-hostiles resp-hostiles) []))
              [ox oy oz] (or (:ray-origin snapshot) (:pos @st) [0.0 64.0 0.0])
              [lx ly lz] (or (:look-direction snapshot) [1.0 0.0 0.0])
              ;; A Torrente mira o bloco, nao o mob: assim clicar numa lagoa
              ;; de lava neutraliza exatamente a area apontada.
              [ix iy iz] (let [rc-resp (send-cmd! (uformat "RAYCAST_BLOCK|%s|%.1f" player-name max-reach))]
                           (if (and rc-resp (str/starts-with? rc-resp "OK|"))
                             (let [parts (str/split rc-resp #"\|")]
                               [(try (+ (Double/parseDouble (nth parts 2)) 0.5) (catch Throwable _ (+ ox (* 24.0 lx))))
                                (try (+ (Double/parseDouble (nth parts 3)) 1.0) (catch Throwable _ (+ oy (* 24.0 ly))))
                                (try (+ (Double/parseDouble (nth parts 4)) 0.5) (catch Throwable _ (+ oz (* 24.0 lz))))])
                             [(+ ox (* 24.0 lx))
                              (+ oy (* 24.0 ly))
                              (+ oz (* 24.0 lz))]))
              spd 1.8
              [vx vy vz] [(* spd lx) (* spd ly) (* spd lz)]]
          (swap! st assoc :last-spell-ms now)
          (send-cmd! (uformat "ITEM_COOLDOWN|%s|%s|%d" player-name (:item-id spell-info) (:cooldown-ticks spell)))
          (send-cmd! (uformat "CMD|execute at %s run playsound %s master @a ~ ~ ~ 1.2 1.0"
                              player-name (:sound spell)))
          (doseq [p (:caster-particles spell)]
            (send-cmd! (uformat p player-name)))
          ;; 1. Saraivada de 3 projeteis aquaticos (splash potion de agua)
          (doseq [offset [0.0 -0.10 0.10]]
            (send-cmd! (uformat "CMD|execute at %s positioned ~ ~1.5 ~ run summon minecraft:potion ~ ~ ~ {Item:{id:\"minecraft:splash_potion\",components:{\"minecraft:potion_contents\":{potion:\"minecraft:water\"}}},Motion:[%.3f,%.3f,%.3f]}"
                                player-name
                                (+ vx (* offset (- lz)))
                                vy
                                (+ vz (* offset lx)))))
          ;; 2. Deixa uma fonte no ponto de impacto. No Nether, agua solta
          ;; evapora como no balde vanilla; uma cauldron cheia preserva a magia.
          (send-cmd! (uformat "CMD|execute if dimension minecraft:the_nether positioned %.2f %.2f %.2f run setblock ~ ~ ~ minecraft:water_cauldron[level=3] keep"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute unless dimension minecraft:the_nether positioned %.2f %.2f %.2f run setblock ~ ~ ~ minecraft:water keep"
                              ix iy iz))
          ;; 3. Neutraliza lava diretamente: no Nether a agua evapora, entao
          ;; a conversao explicita para obsidiana garante o efeito do balde.
          (send-cmd! (uformat "CMD|execute at %s run fill ~-4 ~-2 ~-4 ~4 ~2 ~4 minecraft:obsidian replace minecraft:lava"
                              player-name))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run fill ~-4 ~-2 ~-4 ~4 ~2 ~4 minecraft:obsidian replace minecraft:lava"
                              ix iy iz))
          ;; 4. Apaga fogo no cenario (raio de 3 blocos ao redor do impacto)
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run fill ~-3 ~-1 ~-3 ~3 ~2 ~3 minecraft:air replace minecraft:fire"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run fill ~-3 ~-1 ~-3 ~3 ~2 ~3 minecraft:air replace minecraft:soul_fire"
                              ix iy iz))
          ;; 5. Molha entidades / apaga chamas (raio de 5 blocos)
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[distance=..5.0] run data modify entity @s Fire set value 0s"
                              ix iy iz))
          ;; 6. Dano de afogamento (35 de dano drown) em area de 4.5 blocos ao redor do impacto
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:experience_orb,distance=..4.5] run damage @s 35 minecraft:drown"
                              ix iy iz))
          ;; Dano critico extra de 50 contra Blazes (elementais de fogo tomam choque termico)
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[type=minecraft:blaze,distance=..6.0] run damage @s 50 minecraft:drown"
                              ix iy iz))
          ;; 7. Efeitos sonoros aquaticos
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run playsound entity.generic.splash master @a ~ ~ ~ 2.5 1.0"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run playsound ambient.underwater.enter master @a ~ ~ ~ 2.0 1.2"
                              ix iy iz))
          ;; 8. Particulas de torrente aquatica e chuva
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:splash ~ ~0.5 ~ 1.8 1.0 1.8 0.2 100 normal"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:bubble_pop ~ ~0.5 ~ 1.5 1.0 1.5 0.15 60 normal"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:rain ~ ~1.0 ~ 1.5 1.0 1.5 0.1 50 normal"
                              ix iy iz))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f run particle minecraft:falling_dripstone_water ~ ~1.5 ~ 1.2 0.8 1.2 0.1 40 normal"
                              ix iy iz))
          true)

        (do
          (println (format "[craft-agents-spell] feitico nao implementado: %s" (:id spell)))
          false)))))

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
   Devolve true se conjurou.

   SEM hostil nao ha magia: sem `caster` o projetil nao tem alvo nem mira, e o
   `cast-spell!` atiraria uma fireball para o vazio. A varredura aqui e o gate
   que impede esse disparo orfao (o jogador, que tem `caster` e mira, continua a
   poder atirar sem alvo)."
  [st agent-name send-cmd! now]
  (let [resp (send-cmd! (uformat "TARGET_HOSTILE|%s|%.1f" agent-name spell-scan-radius))]
    (if (parse-hostile-response resp)
      (let [cast-fn (if (= agent-name "Coco") cast-coco-spell! cast-richeh-spell!)]
        (cast-fn st send-cmd! nil 0 now))
      false)))

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
  "Processa falas direcionadas a Coco e Richeh. Retorna true se consumido.
   Aceita tambem o CASTING puro do jogador (so a palavra de magia, sem nome de
   bruxa): a magia nasce DELE, na direcao da mira dele."
  [speaker msg coco-st richeh-st say-fn]
  (let [lower (str/lower-case (or msg ""))]
    (cond
      ;; 0. CASTING puro do jogador: so a palavra de magia, sem nome de bruxa.
      ;; A resposta sai da posicao DELE (`caster-say!`), nao da Coco — o Atelie
      ;; esta a ~200 blocos e as falas das bruxas nao o alcancariam.
      (casting-spell? msg)
      (do
        (if-not (grimoire-carried? send-bridge-cmd! speaker)
          (caster-say! send-bridge-cmd! speaker "Sem o teu grimório eu não conjuro nada. Traz o livro-e-pena! 📖🎒")
          (let [hit? (cast-casting! coco-st send-bridge-cmd! speaker (System/currentTimeMillis))]
            (caster-say! send-bridge-cmd! speaker
                         (if hit?
                           (rand-nth ["Glifo de Tinta Viva! Bela mordida! 🖋️✨"
                                      "A tinta obedece a quem desenha! 📖"])
                           "Mira num bicho, não no vazio! O glifo precisa de um alvo pra morder! 🖋️"))))
        true)

      ;; 1. Comandos para Coco
      (str/includes? lower "coco")
      (cond
        (or (str/includes? lower "ataca") (str/includes? lower "atacar")
            (str/includes? lower "magia") (str/includes? lower "glifo")
            (str/includes? lower "feitiço") (str/includes? lower "feiti"))
        (do
          (if-not (grimoire-carried? send-bridge-cmd! speaker)
            (say-fn :coco "Sem o teu grimório, não há magia. Traz o livro-e-pena! 📖🎒")
            (let [hit? (cast-spell-with-aim! coco-st "Coco" send-bridge-cmd! speaker (System/currentTimeMillis)
                                             ["CMD|execute at Coco run particle minecraft:enchant ~ ~1.0 ~ 0.6 0.6 0.6 0.05 25 normal"
                                              "CMD|execute at Coco run particle minecraft:witch ~ ~1.2 ~ 0.4 0.4 0.4 0.02 15 normal"
                                              "CMD|execute at Coco run particle minecraft:crit ~ ~1.0 ~ 0.3 0.3 0.3 0.1 10 normal"]
                                             "CMD|execute at Coco run playsound minecraft:item.book.page_turn master @a[distance=..24] ~ ~ ~ 1.2 0.8"
                                             ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
                                              "particle minecraft:soul_fire_flame ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"
                                              "particle minecraft:enchant ~ ~ ~ 0.8 0.8 0.8 0.1 40 normal"])]
              (if hit?
                (say-fn :coco (rand-nth ["Isso! O Glifo de Tinta Viva é teu agora! 🖋️✨"
                                         "A tinta obedece a quem desenha. Bela mordida! 📖"]))
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
            (say-fn :richeh "Hmph. Sem o teu grimório, não há cristal pra traçar. 📖")
            (let [hit? (cast-spell-with-aim! richeh-st "Richeh" send-bridge-cmd! speaker (System/currentTimeMillis)
                                             ["CMD|execute at Richeh run particle minecraft:end_rod ~ ~1.0 ~ 0.6 0.6 0.6 0.08 25 normal"
                                              "CMD|execute at Richeh run particle minecraft:glow_squid_ink ~ ~0.8 ~ 0.4 0.4 0.4 0.02 20 normal"
                                              "CMD|execute at Richeh run particle minecraft:block_marker minecraft:amethyst_block ~ ~1.0 ~ 0.3 0.3 0.3 0.05 12 normal"]
                                             "CMD|execute at Richeh run playsound minecraft:block.amethyst_block.break master @a[distance=..24] ~ ~ ~ 1.1 0.9"
                                             ["particle minecraft:explosion ~ ~ ~ 0.4 0.4 0.4 0.0 1 normal"
                                              "particle minecraft:end_rod ~ ~ ~ 0.6 0.6 0.6 0.08 40 normal"
                                              "particle minecraft:block_marker minecraft:amethyst_block ~ ~ ~ 0.5 0.5 0.5 0.05 30 normal"])]
              (if hit?
                (say-fn :richeh (rand-nth ["Traça a matriz e estilhaça. O cristal é teu. 🔮"
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

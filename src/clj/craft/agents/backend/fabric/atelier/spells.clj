(ns craft.agents.backend.fabric.atelier.spells
  "Nucleo de conjuracao: cooldowns, projetil, dano, casting e auto-defesa das bruxas."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.transport :refer [uformat]]
            [craft.agents.backend.fabric.atelier.aim :refer [parse-all-hostiles parse-hostile-response player-aim-snapshot select-aim-target]]
            [craft.agents.backend.fabric.atelier.registry :refer [fireball-item-id spell-registry]]))

;; =============================================================================
;; Magias de Ataque do Ateliê (Glifos de Coco & Cristais de Richeh)
;; =============================================================================
;; Assinatura visual + dano real a hostis. A varredura e o golpe usam os
;; opcodes de combate ja existentes na bridge (TARGET_HOSTILE/ATTACK_HOSTILE);
;; o visual sai por CMD|particle. Cooldown de 5s evita spam de combinacao.

(def ^:private spell-cooldown-ms 5000)

(def ^:private wand-cooldown-ms 1000)

(def ^:private spell-burst-ms 8000)

(def spell-scan-radius 20.0)

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

(defn projectile-spell-cmd
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

(defn grimoire-carried?
  "True se o jogador `caster` leva QUALQUER ITEM DO CATALOGO NO INVENTARIO.
   `INSPECT_INVENTORY|<jogador>` devolve OK|<count>|<id>,<qtd>;<id>,<qtd>;..."
  [send-cmd! caster]
  (let [resp (send-cmd! (uformat "INSPECT_INVENTORY|%s" caster))]
    (boolean (and resp (str/starts-with? resp "OK|")
                  (some #(str/includes? resp %) (keys spell-registry))))))

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

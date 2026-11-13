(ns craft.agents.backend.fabric.atelier.held
  "Feiticos disparados pelo item na mao principal do jogador (HELD_ITEM)."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.transport :refer [uformat]]
            [craft.agents.backend.fabric.atelier.aim :refer [find-safe-ground-y parse-all-hostiles player-aim-snapshot select-aim-target select-aim-target-cone]]
            [craft.agents.backend.fabric.atelier.registry :refer [fireball-item-id spell-registry]]
            [craft.agents.backend.fabric.atelier.spells :refer [projectile-spell-cmd spell-scan-radius]]))

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

(def ^:private non-victim-selector
  "Seletor de entidades que o AOE das magias fere: tudo menos jogador, item e XP."
  "type=!minecraft:player,type=!minecraft:item,type=!minecraft:experience_orb")

(defn- impact-point
  "Ponto de impacto de uma magia mirada: o hostil mirado (`aim-mob`), senao o
   bloco avistado (RAYCAST_BLOCK) e, sem bloco, o ponto a `fallback-dist` na linha
   do olhar. `center?` centra o bloco avistado (+0.5/+1.0/+0.5, topo do bloco)."
  [st send-cmd! player-name snapshot aim-mob reach fallback-dist center?]
  (if aim-mob
    [(:x aim-mob) (:y aim-mob) (:z aim-mob)]
    (let [[ox oy oz] (or (:ray-origin snapshot) (:pos @st) [0.0 64.0 0.0])
          [lx ly lz] (or (:look-direction snapshot) [1.0 0.0 0.0])
          [cx cy cz] (if center? [0.5 1.0 0.5] [0.0 0.0 0.0])
          rc-resp (send-cmd! (uformat "RAYCAST_BLOCK|%s|%.1f" player-name reach))
          along (fn [o l] (+ o (* fallback-dist l)))
          coord (fn [parts i off fb]
                  (try (+ (Double/parseDouble (nth parts i)) off) (catch Throwable _ fb)))]
      (if (and rc-resp (str/starts-with? rc-resp "OK|"))
        (let [parts (str/split rc-resp #"\|")]
          [(coord parts 2 cx (along ox lx))
           (coord parts 3 cy (along oy ly))
           (coord parts 4 cz (along oz lz))])
        [(along ox lx) (along oy ly) (along oz lz)]))))

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
              ;; Ponto central do impacto da bola de fogo (mob, bloco solido ou linha do olhar):
              [ix iy iz] (impact-point st send-cmd! player-name snapshot aim-mob 48.0 24.0 true)
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
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[%s,distance=..4.0] run damage @s 50 minecraft:explosion"
                              ix iy iz non-victim-selector))
          ;; 4. Incendeia mobs atingidos (setblock fire nos pes se for ar) e dano continuo de fogo
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[%s,distance=..4.0] at @s run setblock ~ ~ ~ minecraft:fire keep"
                              ix iy iz non-victim-selector))
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[%s,distance=..4.0] run damage @s 10 minecraft:in_fire"
                              ix iy iz non-victim-selector))
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
              ;; Ponto central do impacto do vento (mob, bloco solido ou linha do olhar):
              [ix iy iz] (impact-point st send-cmd! player-name snapshot aim-mob 24.0 12.0 false)
              target aim-mob]
          (swap! st assoc :last-spell-ms now)
          (doseq [p (:caster-particles spell)]
            (send-cmd! (uformat p player-name)))
          (when-let [snd (:sound spell)]
            (send-cmd! (uformat "CMD|execute at %s run playsound %s master @a[distance=..24] ~ ~ ~ 1.5 1.0" player-name snd)))
          ;; Lanca o projetil fisico de vento
          (send-cmd! (projectile-spell-cmd st player-name send-cmd! target snapshot spell))
          ;; Dano e efeitos em area (AOE): 40 de dano em raio de 6.5 blocos em todos os hostis/mobs (poupa jogador, itens e XP)
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[%s,distance=..6.5] run damage @s 40 minecraft:player_attack"
                              ix iy iz non-victim-selector))
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
                  ;; Ponto exato do impacto (mob mirado, ou bloco mirado centralizado no topo):
                  [ix iy iz] (impact-point st send-cmd! player-name snapshot aim-mob thunder-range 48.0 true)
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
              (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[%s,distance=..3.5] run damage @s 50 minecraft:lightning_bolt"
                                  ix iy iz non-victim-selector))
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
              [lx ly lz] (or (:look-direction snapshot) [1.0 0.0 0.0])
              [ix iy iz] (impact-point st send-cmd! player-name snapshot aim-mob frost-range 24.0 true)
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
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[%s,distance=..4.5] run damage @s 35 minecraft:freeze"
                              ix iy iz non-victim-selector))
          ;; Lentidao extrema (Slowness IV por 6s) nos inimigos na area
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[%s,distance=..4.5] run effect give @s minecraft:slowness 6 3 true"
                              ix iy iz non-victim-selector))
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
              [lx ly lz] (or (:look-direction snapshot) [1.0 0.0 0.0])
              ;; A Torrente mira o bloco, nao o mob: assim clicar numa lagoa
              ;; de lava neutraliza exatamente a area apontada.
              [ix iy iz] (impact-point st send-cmd! player-name snapshot nil max-reach 24.0 true)
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
          (send-cmd! (uformat "CMD|execute positioned %.2f %.2f %.2f as @e[%s,distance=..4.5] run damage @s 35 minecraft:drown"
                              ix iy iz non-victim-selector))
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

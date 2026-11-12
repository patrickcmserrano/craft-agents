(ns craft.agents.backend.fabric.atelier.registry
  "Catalogo unificado de feiticos: item magico -> assinatura do feitico.")

(def fireball-item-id "minecraft:blaze_rod")

;; O item e o poder; sem ele, a bruxa recusa. Catalogo unificado de feiticos:
;; cada item magico mapeia para a assinatura do seu feitico (entidade, som, particulas).
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

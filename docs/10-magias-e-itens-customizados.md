# 10 — Magias e Itens Customizados (Clojure + Fabric 1.21.1)

Este guia documenta a arquitetura, os componentes e a metodologia completa para criar magias e itens customizados consumíveis no ecossistema Minecraft Fabric 1.21.1 integrado ao engine cognitivo Clojure (`craft-agents`) e ao dashboard web (`craft-repl` :8090).

---

## 1. Visão Geral da Arquitetura

O sistema combina quatro camadas complementares para viabilizar armas e feitiços mágicos responsivos sem a necessidade de mods client-side proprietários:

1. **Cliente Minecraft Vanilla (ou SKLauncher / Fabric Client):**
   * Interage via botão direito usando itens dotados de **Data Components** do 1.21.1.
   * Dispara pacotes nativos de rede (`ServerboundUseItemPacket`) mesmo ao mirar no ar aberto.
2. **Servidor Fabric 1.21.1 + Data Pack (`fireball_item`):**
   * Detecta o início do uso via advancement (`minecraft:using_item`).
   * Executa a função `usou.mcfunction`:
     - Aplica guarda rigorosa de cooldown (`execute if entity @s[scores={fb_cooldown=1..}] run return 0`).
     - Aplica modificador de durabilidade (`set_damage`) que interrompe imediatamente a animação de uso no cliente (clique instantâneo).
     - Remove o item com `clear` condicional quando o dano atinge o limite (`damage=64`).
3. **Ponte Java in-server (`SyntheticPlayerFactory.java` na porta 25595):**
   * Op `HELD_ITEM|<jogador>`: inspeciona a mão ativa via `method_7391` (`getStackInHand`) do jogador.
   * Op `LOOK|<jogador>`: lê o vetor unitário exato do olhar (yaw/pitch) do jogador.
   * Op `ITEM_COOLDOWN|<jogador>|<item_id>|<ticks>`: aciona `player.getItemCooldownManager().set(...)`, enviando o pacote `CooldownUpdateS2CPacket` nativo para renderizar a máscara cinza de cooldown no cliente.
4. **Engine Cognitivo Clojure (`craft-agents` / `atelier.clj`):**
   * Loop `item-poll` de alta frequência (4 Hz): compara o damage anterior com o atual.
   * Consulta o catálogo unificado `spell-registry` e despacha projéteis, partículas, comandos de dano cirúrgico e áudio.
5. **Dashboard Web (:8090 em `craft-repl`):**
   * Permite entregar qualquer kit de feitiço com 64 cargas no Arsenal e no AirDrop do parceiro.

```
[Jogador: Clique Direito no Mundo ou no Ar]
          │
          ▼
[Cliente envia ServerboundUseItemPacket (via componente food)]
          │
          ▼
[Data Pack: advancement using_item aciona usou.mcfunction]
   ├── 1. Revoga advancement para rearmar
   ├── 2. Guarda de cooldown: se fb_cooldown >= 1, sai sem tocar som nem gastar carga
   ├── 3. Emite som e partículas específicos do feitiço
   ├── 4. item modify gastar.json (incrementa damage na durabilidade)
   │        └── Força cliente a cancelar pose de uso (clique seco no Tick 0)
   ├── 5. Clear condicional se damage=64 (item esgotado)
   └── 6. Seta fb_cooldown (20 ticks para fogo/vento, 60 ticks para trovão)
          │
          ▼ (Polling a 4Hz via HELD_ITEM na porta 25595)
[Engine Clojure: atelier/dispatch-held-spell!]
   ├── 1. Le direcao real do olhar via op LOOK|<jogador>
   ├── 2. Trava mira com cone cirúrgico de 20° (select-aim-target-cone)
   ├── 3. Aciona ITEM_COOLDOWN nativo na ponte Java
   └── 4. Invocação de feitiço, partículas e dano calibrado
```

---

## 2. Regra de Ouro dos Itens Mágicos: Nunca Usar Itens-Bloco

> **Regra Crítica (Descoberta em 2026-11-11):**
> **NUNCA utilize itens que sejam blocos no Minecraft (`BlockItem`), como `minecraft:lightning_rod`, para varinhas ou armas mágicas.**

* **O Problema:** No Minecraft vanilla, quando um jogador clica com o botão direito segurando um bloco apontado para o chão ou parede, o jogo prioriza a mecânica de **colocar o bloco no mundo** ("fica apoiado como uma lanterna").
* **Perda Total de Magia:** Ao ser colocado como bloco, todos os Data Components (`custom_data`, `food`, `max_damage`) são descartados pelo motor vanilla. Ao quebrar e pegar o item de volta, o jogador recebe um item comum sem magia.
* **A Solução:** Use **exclusivamente itens puros (não-blocos)**:
  - 🔥 **Varinha de Fogo:** `minecraft:blaze_rod`
  - 🌪️ **Cajado dos Ventos:** `minecraft:breeze_rod`
  - ⚡ **Cetro do Trovão:** `minecraft:trial_key` (Chave de Provação de cobre da 1.21.1)
  - ❄️ **Raio Congelante:** `minecraft:amethyst_shard`
  - 🌑 **Passo das Sombras:** `minecraft:echo_shard`
  - 🌊 **Orbe das Marés:** `minecraft:heart_of_the_sea` (Coração do Mar / Torrente Aquática)

Itens puros nunca podem ser colocados no chão: o clique direito sempre dispara a magia sem risco de virar bloco.

---

## 3. Catálogo Unificado de Magias (`spell-registry`)

Localizado em `craft-agents/src/clj/craft/agents/backend/fabric/atelier.clj`:

| Item Base | Feitiço (`:id`) | Nome | Cooldown | Alcance | Efeito Ingame |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `minecraft:blaze_rod` | `:fireball` | Bola de Fogo | 1.0s (20 ticks) | Mira Livre | Projétil de bola de fogo física + dano `/damage ... 50 explosion` |
| `minecraft:breeze_rod` | `:wind-charge` | Onda de Choque | 1.0s (20 ticks) | 24 metros | Projétil `breeze_wind_charge` + dano AOE de 40 (`player_attack`) em raio de 6.5m (poupa conjurador, permite Rocket Jump) |
| `minecraft:trial_key` | `:lightning-smite` | Relâmpago do Julgamento | 3.0s (60 ticks) | 80 metros | Raio nativo vanilla (`lightning_bolt`) + dano elétrico de 50 em AOE de 3.5m (hitkill em Enderman e Creepers) + imunidade `Resistance V` à queima-roupa |
| `minecraft:amethyst_shard` | `:frost-beam` | Raio Congelante | 1.0s (20 ticks) | 48 metros | Saraivada de 3 projéteis de gelo (`snowball`) + 35 dano de congelamento (`freeze`) em AOE de 4.5m + Lentidão extrema (Slowness IV por 6s) + estilhaçamento sonoro de vidro/ametista |
| `minecraft:echo_shard` | `:shadow-step` | Passo das Sombras (Teleporte Tático) | 1.0s (20 ticks) | 32 metros | Teleporte instantâneo na direção do `LOOK` (parando 1 bloco antes de obstáculo sólido via raycast) sem dano de impacto + `Resistance V` (2s) e `Invisibility` (2s) + nuvem densa de portal/reverse_portal e som de enderman |
| `minecraft:heart_of_the_sea` | `:tidal-surge` | Orbe das Marés (Torrente Aquática) | 1.0s (20 ticks) | 48 metros | Saraivada de 3 projéteis aquáticos (`splash_potion` com água) + extingue fogo no cenário (raio de 3m) + apaga chamas de entidades molhadas (`Fire:0s` em 5m) + dano de afogamento (`drown` 35) em AOE de 4.5m e dano crítico em Blazes (50) + partículas de splash, bolhas e chuva |

---

## 4. Como Criar Itens Customizados com Clique Direito (1.21.1)

### Componente `food` com Interrupção no 1º Tick
No Minecraft Vanilla, o cliente descarta cliques no ar de itens sem ação de uso nativa. Adicionando o componente `food` com `can_always_eat: true` e `eat_seconds: 1000000`:
1. O cliente aceita o uso a qualquer momento e despacha o pacote de rede no **Tick 0**.
2. O advancement `fireball_item:usou_varinha` detecta o uso.
3. O modificador `item modify` altera o dano do item, interrompendo instantaneamente a pose de alimentação no cliente (sem som de mastigação).

### Sintaxe Completa do Item (64 Cargas)
```
minecraft:trial_key[
  custom_data={varinha_magica:1b},
  food={nutrition:0,saturation:0,can_always_eat:true,eat_seconds:1000000},
  max_damage=64,
  damage=0,
  max_stack_size=1
]
```

> **Atenção:** Se houver `max_damage`, **obrigatoriamente** declare `max_stack_size=1`. Caso contrário o servidor descartará o `/give` silenciosamente.

---

## 5. Ciclo do Data Pack (`usou.mcfunction`)

Localizado em `server/world/datapacks/fireball_item/data/fireball_item/function/usou.mcfunction`:

```mcfunction
# 1. Revoga advancement para permitir rearmar
advancement revoke @s only fireball_item:usou_varinha

# 2. Guarda de cooldown estrito: se ja estiver em cooldown, sai imediatamente (sem som nem gasto)
execute if entity @s[scores={fb_cooldown=1..}] run return 0

# 3. Feedback sensorial especifico (apenas quando fora de cooldown)
execute if items entity @s weapon.mainhand minecraft:trial_key at @s run playsound entity.lightning_bolt.thunder master @s ~ ~ ~ 0.8 1.4
execute if items entity @s weapon.mainhand minecraft:trial_key at @s run particle minecraft:electric_spark ~ ~1.2 ~ 0.3 0.3 0.3 0.05 15 normal

# 4. Consome 1 carga (set_damage -0.015625 para 64 cargas)
item modify entity @s weapon.mainhand fireball_item:gastar

# 5. Clear condicional se atingir o max_damage de 64
execute if items entity @s weapon.mainhand minecraft:trial_key[minecraft:damage=64] run clear @s minecraft:trial_key[minecraft:damage=64] 1

# 6. Seta cooldown condicional por feitiço
scoreboard players set @s fb_fire 1
scoreboard players set @s fb_cooldown 20
execute if items entity @s weapon.mainhand minecraft:trial_key run scoreboard players set @s fb_cooldown 60
```

---

## 6. Mira Cirúrgica e Resolução de Impacto no Clojure

Para evitar desvios causados por mobs na visão periférica ou colisões no chão:

1. **Cone de Visada Restrito (20° / `cos >= 0.94`):**
   A função `select-aim-target-cone` só considera um mob como alvo mirado se ele estiver a menos de 20° do centro do crosshair. Mobs fora dessa janela não desviam a mira.
2. **Centralização do Topo do Bloco:**
   Ao mirar no chão ou parede, o raycast calcula:
   $$x = \text{bx} + 0.5, \quad y = \text{by} + 1.0, \quad z = \text{bz} + 0.5$$
   O relâmpago atinge exatamente o topo do bloco visado.
3. **Imunidade à Queima-Roupa:**
   Ao conjurar o relâmpago, o jogador recebe instantaneamente:
   - `Resistance V` (`resistance 2 4 true`): 100% de absorção de dano por 2 segundos.
   - `Fire Resistance` (`fire_resistance 3 1 true`): anula qualquer fogo residual do chão.
   Isso permite acertar inimigos a 1 metro de distância sem sofrer dano.

---

## 7. Integração no Dashboard Web (:8090)

Localizado em `craft-repl/resources/public/`:

* **`index.html`:**
  ```html
  <button class="btn btn-amber grid-full" onclick="giveWeaponKit('lightning_wand')">⚡ Cetro do Trovão (64 Cargas — Clique Direito)</button>
  <button class="btn btn-amber" onclick="giveWeaponKit('lightning_wand', 'partner')">⚡ AirDrop Cetro do Trovão</button>
  ```
* **`js/actions.js`:**
  ```javascript
  case 'lightning_wand':
      title = 'Cetro do Trovão (64 Cargas — Relâmpago do Julgamento)';
      cmds = [
          `give ${nick} minecraft:trial_key[custom_data={varinha_magica:1b},food={nutrition:0,saturation:0,can_always_eat:true,eat_seconds:1000000},max_damage=64,damage=0,max_stack_size=1] 1`
      ];
      break;
  ```

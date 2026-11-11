# 10 — Magias e Itens Customizados (Clojure + Fabric 1.21.1)

Este guia documenta a arquitetura, os componentes e a metodologia completa para criar magias e itens customizados consumíveis no ecossistema Minecraft Fabric 1.21.1 integrado ao engine cognitivo Clojure (`craft-agents`).

---

## 1. Visão Geral da Arquitetura

O sistema combina três camadas complementares para viabilizar itens mágicos responsivos sem a necessidade de mods client-side proprietários:

1. **Cliente Minecraft Vanilla (ou SKLauncher / Fabric Client):**
   * Interage via botão direito usando itens dotados de **Data Components** do 1.21.1.
   * Dispara pacotes nativos de rede (`ServerboundUseItemPacket`) mesmo ao mirar no ar aberto.
2. **Servidor Fabric 1.21.1 + Data Pack (`fireball_item`):**
   * Detecta o início do uso via advancement (`minecraft:using_item`).
   * Executa a função `usou.mcfunction`, aplicando um modificador de durabilidade (`set_damage`) que interrompe imediatamente a animação de uso no cliente.
   * Controla o cooldown lógico via scoreboards (`fb_cooldown`).
3. **Engine Cognitivo Clojure (`craft-agents`):**
   * Monitora o item na mão principal via ponte TCP (`HELD_ITEM|<jogador>`).
   * Detecta a transição de carga/dano e calcula o vetor de mira real do jogador através do op `LOOK|<jogador>`.
   * Invoca a conjuração física do projétil (`minecraft:fireball`) e as partículas associadas (`atelier.clj`).

```
[Jogador: Clique Direito]
          │
          ▼
[Cliente envia ServerboundUseItemPacket]
          │
          ▼
[Data Pack: advancement using_item aciona usou.mcfunction]
   ├── 1. Revoga advancement para rearmar
   ├── 2. Emite som/partículas de uso
   ├── 3. item modify gastar.json (incrementa damage na durabilidade)
   │        └── Força cliente a cancelar pose de uso (clique seco)
   └── 4. Seta fb_cooldown (1s) e fb_fire=1
          │
          ▼ (Polling a 4Hz via HELD_ITEM na porta 25595)
[Engine Clojure: atelier/fireball-used?]
   ├── 1. Verifica wand-cooldown-ms (1000ms)
   ├── 2. Le direcao real do olhar via op LOOK|<jogador>
   └── 3. Dispara summon de fireball com vetor direcional normalizado
```

---

## 2. Como Criar Itens Customizados com Clique Direito (Minecraft 1.21.1)

### O Problema da Inércia do Cliente Vanilla
No Minecraft Vanilla, quando o jogador clica com o botão direito no ar aberto (ou mapa aberto) segurando itens comuns (`blaze_rod`, `stick`, etc.), o método `Item.use` retorna `PASS`. O cliente descarta a interação localmente e **nunca envia o pacote de rede ao servidor**. O servidor fica completamente cego ao clique.

### A Solução: Componente `food` com Tempo Longo e Interrupção no 1º Tick
Para tornar qualquer item clicável no ar sem sons incômodos de mastigação e sem consumir o item instantaneamente:

1. Adiciona-se o componente `food` com `can_always_eat: true` e `eat_seconds: 1000000`.
2. O cliente vê o item como comestível a qualquer momento, envia o pacote de uso no **Tick 0** e inicia o gatilho de advancement.
3. Ao detectar o uso no servidor, o modificador `item modify` altera a durabilidade (`damage`) do item na mão.
4. Qualquer alteração de componente no item ativo interrompe imediatamente a ação de alimentação no cliente antes que qualquer som de mastigação ou arroto seja emitido.

### Sintaxe do Item (1.21.1)
```
minecraft:blaze_rod[
  custom_data={varinha_fogo:1b},
  food={nutrition:0,saturation:0,can_always_eat:true,eat_seconds:1000000},
  max_damage=64,
  damage=0,
  max_stack_size=1
]
```

> **Atenção (Regra Dura do 1.21.1):**
> Se um item possui `max_damage`, ele se torna *damageable*. Itens não podem ser simultaneamente *damageable* e *stackable*. Portanto, **sempre inclua `max_stack_size=1`**, caso contrário o servidor descartará o `/give` silenciosamente com o erro:
> `Malformed item: 'Item cannot be both damageable and stackable'`.

---

## 3. O Ciclo de Consumo no Data Pack

### 1. Advancement de Detecção (`usou_varinha.json`)
Localizado em `server/world/datapacks/fireball_item/data/fireball_item/advancement/usou_varinha.json`:
```json
{
  "criteria": {
    "usou": {
      "trigger": "minecraft:using_item",
      "conditions": {
        "item": {
          "items": ["minecraft:blaze_rod"],
          "predicates": {
            "minecraft:custom_data": "{varinha_fogo:1b}"
          }
        }
      }
    }
  },
  "requirements": [["usou"]],
  "rewards": {
    "function": "fireball_item:usou"
  }
}
```

### 2. Modificador de Durabilidade (`gastar.json`)
Localizado em `server/world/datapacks/fireball_item/data/fireball_item/item_modifier/gastar.json`:
```json
{
  "function": "minecraft:set_damage",
  "add": true,
  "damage": -0.015625
}
```

#### Fórmula para Fração de Carga:
No Minecraft, o parâmetro `damage` em `set_damage` representa a fração de **vida restante** (`1.0` = cheio, `0.0` = quebrado). Com `add: true`, valores negativos consomem vida.
Para um item com $N$ cargas:
$$\text{damage} = -\frac{1.0}{N}$$
* Para 8 cargas: $-0.125$
* Para 16 cargas: $-0.0625$
* Para 32 cargas: $-0.03125$
* Para 64 cargas: $-0.015625$

### 3. Função de Execução (`usou.mcfunction`)
Localizada em `server/world/datapacks/fireball_item/data/fireball_item/function/usou.mcfunction`:
```mcfunction
# 1. Revoga advancement imediatamente para permitir re-disparo
advancement revoke @s only fireball_item:usou_varinha

# 2. Feedback sensorial (som e partículas de acionamento)
execute at @s run playsound minecraft:item.firecharge.use master @s ~ ~ ~ 1.0 1.0
execute at @s run particle minecraft:flame ~ ~1.2 ~ 0.25 0.25 0.25 0.02 8 normal
execute at @s run particle minecraft:soul_fire_flame ~ ~1.2 ~ 0.2 0.2 0.2 0.02 5 normal

# 3. Guarda de cooldown do data pack (1s = 20 ticks)
execute if entity @s[scores={fb_cooldown=1..}] run return 0

# 4. Gasta 1 carga (soma dano a durabilidade)
item modify entity @s weapon.mainhand fireball_item:gastar

# 5. Remove o item caso esgote todas as cargas (ex: 64)
execute if entity @s[nbt={SelectedItem:{components:{"minecraft:damage":64}}}] run clear @s minecraft:blaze_rod[minecraft:custom_data={varinha_fogo:1b},minecraft:damage=64] 1

# 6. Seta cooldown de 20 ticks
scoreboard players set @s fb_fire 1
scoreboard players set @s fb_cooldown 20
```

---

## 4. Implementação de Magias no Clojure (`atelier.clj`)

As magias residem em `craft-agents/src/clj/craft/agents/backend/fabric/atelier.clj`.

### A Leitura da Mira com o op `LOOK`
Para evitar que o projétil mire no chão por colisão de blocos próximos, a mira do jogador é consultada diretamente na ponte via `LOOK|<jogador>`:
```clojure
(defn- parse-look-response
  "Decodifica a resposta de LOOK|<jogador>:
   `OK|<dx>|<dy>|<dz>|<yaw>|<pitch>`"
  [resp]
  (when (and resp (str/starts-with? resp "OK|"))
    (let [parts (str/split (subs resp 3) #"\|")]
      (when (>= (count parts) 5)
        {:dir [(Double/parseDouble (nth parts 0))
               (Double/parseDouble (nth parts 1))
               (Double/parseDouble (nth parts 2))]
         :yaw (Double/parseDouble (nth parts 3))
         :pitch (Double/parseDouble (nth parts 4))}))))
```

### Projétil Real com `ExplosionPower`
A fireball é invocada à frente dos olhos do conjurador com um vetor normalizado multiplicado pela velocidade desejada:
```clojure
(defn- projectile-cmd
  [anchor-name target opts]
  (let [[dx dy dz] (or (:dir (:look-snapshot opts)) [0.0 0.0 1.0])
        speed 1.5
        vx (* dx speed)
        vy (* dy speed)
        vz (* dz speed)]
    (format "CMD|execute at %s run summon minecraft:fireball ^ ^1.2 ^1 {ExplosionPower:1b,Motion:[%.3f,%.3f,%.3f]}"
            anchor-name vx vy vz)))
```

### Separação de Cooldowns
Para evitar que o jogador fique travado pelo cooldown tático das bruxas autônomas (5 segundos):
```clojure
(def ^:private spell-cooldown-ms 5000) ;; Auto-defesa das bruxas
(def ^:private wand-cooldown-ms 1000)  ;; Cadencia do jogador com varinha

(defn- spell-ready?
  ([st now] (spell-ready? st nil now))
  ([st caster now]
   (let [cd (if caster wand-cooldown-ms spell-cooldown-ms)]
     (> (- now (:last-spell-ms @st 0)) cd))))
```

---

## 5. Scripts e Operações Úteis

### Entregar a Varinha a um Jogador
O script reexecutável `scripts/varinha_fogo.clj` entrega a varinha configurada ao alvo desejado:
```bash
# Entregar para o jogador default (pace)
clojure -Sdeps '{:paths ["scripts"]}' -M -m varinha-fogo

# Entregar para outro jogador
clojure -Sdeps '{:paths ["scripts"]}' -M -m varinha-fogo --target Lizzie
```

### Testes Automatizados
Para rodar os testes específicos de item e magia:
```bash
clojure -M:test -e "(require '[clojure.test :refer [run-tests]] '[craft.agents.atelier-test]) (run-tests 'craft.agents.atelier-test)"
```
Testes de integridade do getter de inventário:
```bash
clojure -M:test -e "(require '[clojure.test :refer [run-tests]] '[craft.agents.fabric.inventory-manager-property-test]) (run-tests 'craft.agents.fabric.inventory-manager-property-test)"
```

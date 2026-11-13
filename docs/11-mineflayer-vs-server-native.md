# 11 — Mineflayer × Server-Native: diferenças, gap e como estamos resolvendo

> **Data:** 2026-11-12
> **Escopo:** locomoção e corpo da Karlach (e, por tabela, Picard, Coco e Richeh) depois da migração do bot mineflayer (`craft-repl/bot/npc_karlach.js`) para o agente server-native do `craft-agents`.
> **Backlog:** [`12-backlog-paridade-mineflayer.md`](12-backlog-paridade-mineflayer.md) · **Plano de execução:** [`plans/sprint-karlach-nav-01.tasks.edn`](plans/sprint-karlach-nav-01.tasks.edn)

---

## 1. O sintoma

Com a Karlach server-native rodando (`craft-agents.service`), ela segue mal: fica parada longe do jogador, aparece dentro do terreno e tem dificuldade para interagir com o mundo. O bot mineflayer antigo, com menos código de comportamento, seguia direito.

## 2. Evidências medidas ao vivo (2026-11-12, ~20:55)

Medições feitas pelo nREPL do engine (`127.0.0.1:7891`) e pelos ops da ponte in-server (`PLAYERS`, `PATH`, `BLOCK`, `CMD|data get`).

| # | Medição | Resultado |
|---|---|---|
| M1 | Taxa do loop de 20 Hz | ~20 ticks/s (5 ticks a cada 250 ms). O loop **não** está lento. |
| M2 | Posição estimada (`nav-state :pos`) × real (`PLAYERS`) por 10 s em modo `follow` | Iguais e **paradas** em `[-583.04, 67.0, -546.65]` com o jogador a ~20 m. |
| M3 | Coluna da Karlach em `(-597, -593)` | y=66 `stone`, y=67 `dirt`, y=68 `grass_block`, y=69 `air`. Os pés (y=67) e a cabeça (y=68) estão **dentro de blocos**. O chão real fica em y=69. |
| M4 | `PATH` da posição dela até um ponto a 5 m | De y=67 → `ERR\|NO_PATH`. De y=69 → rota OK (`DIAGONAL; DROP_DOWN; DIAGONAL`). |
| M5 | NBT da Karlach (`data get entity Karlach`) | `Motion = [2.24, 0.10, 1.16]`, igual em duas leituras com 1,5 s de intervalo. Num corpo com física essa velocidade cairia a quase zero em poucos ticks: **o servidor não aplica física a ela**. |
| M6 | Latência da ponte (`PLAYERS`) | 0,15–0,46 ms. Transporte não é gargalo. |

## 3. O que o mineflayer dava de graça

O `npc_karlach.js` tem ~560 linhas e quase nenhuma lógica de locomoção, porque as bibliotecas faziam o trabalho pesado:

- **`prismarine-physics`** — o bot era um cliente de verdade: gravidade, colisão AABB, degrau de 0,6, pulo, nado, escada, knockback, atrito e `onGround`. Era fisicamente impossível ficar dentro de um bloco.
- **Posição autoritativa** — `bot.entity.position` vinha dos pacotes do servidor. O `patrol.js` ainda tinha `reconcileAfterTeleport` para o único caso em que ela divergia (o `tp` de fallback).
- **Executor do `mineflayer-pathfinder`** (`monitorMovement`) — liga forward/jump/sprint por tick, considera o nó alcançado com tolerância vertical, replaneja quando o bot sai da rota, detecta travamento, aceita rota parcial (`thinkTimeout`) e tem `GoalFollow` dinâmico (raio 2,5).
- **`Movements`** — alturas reais de blocos parciais (laje, escada, carpete, neve), escadas de mão, custo de líquido e limite de queda.
- **Watchdog de patrulha** (`createPatrolWatchdog`, `patrol.js`) — sem progresso em 20 s, rearma a patrulha.

## 4. Como o server-native funciona hoje

- A Karlach é um `ServerPlayerEntity` falso (`SyntheticPlayerFactory`, conexão `EmbeddedChannel`). O servidor **não** roda física de movimento para jogadores: isso é trabalho do cliente, e ela não tem cliente.
- O loop de 20 Hz em `engine.clj` calcula a próxima posição sozinho e manda `MOVE|Karlach|x|y|z|yaw|pitch|sprint|sneak`. A ponte chama `refreshPositionAndAngles`: é um **teleporte por tick**.
- A posição usada no cálculo é `nav-state :pos`, uma **estimativa local** (dead-reckoning). Nos modos follow e patrulha ela nunca é relida do servidor.
- O A* (`PathSolver3D`) é um porte do mineflayer-pathfinder e **supõe um executor com física**. O executor real é uma interpolação de 0,24 bloco/tick que troca o `y` para o do próximo nó no mesmo instante.
- `SyntheticMotorController` (o "controlador físico") é um **stub** que mexe num atom Clojure. Nenhum código o chama.

## 5. O gap — por que ela se enterra e trava

Cadeia causal, confirmada por M2–M4:

1. **Descida antecipada** (`engine.clj`, follow, ~linhas 633–668; o mesmo padrão aparece na patrulha, ~1010–1016). Num nó `DROP_DOWN` de 2–3 blocos, o passo põe `y = ny` enquanto x/z ainda estão sobre a coluna antiga. Ela fica dentro do bloco.
2. **Sem correção pela física.** Nada a empurra para fora: o servidor não tica física nela (M5).
3. **Sem relitura da posição.** O replanejamento parte de `nav-state :pos`, que agora está dentro do sólido.
4. **Sem recuperar o ponto de partida no A\*.** Partida em bloco sólido → nenhum vizinho válido → `NO_PATH`.
5. **Sem watchdog.** Com `NO_PATH`, o código só gira para o jogador e espera. Ela fica presa até o jogador passar de 48 m (teleporte de emergência) ou sair do servidor.

Agravantes:

- **Velocidade efetiva de ~3,8 m/s.** São 0,24 por tick, menos um tick ocioso a cada nó consumido. O jogador anda a 4,3 m/s e corre a 5,6 m/s, então ela sempre fica para trás.
- **`target-moved?` ignora o y.** Se o jogador desce para uma caverna, ela não replaneja. O tether de 18 m usa distância 3D, então com o jogador 30 blocos abaixo ela fica em `tether snap` permanente com um A* de 3000 nós que não chega lá.
- **3000 nós sem rota parcial.** Se estoura o orçamento, vira `NO_PATH` e ela fica parada.
- **Alturas parciais.** O `LiveWorldVoxelAccessor` só distingue SOLID/EMPTY: laje e carpete contam como bloco cheio, e ela flutua ou afunda meio bloco.
- **Knockback não aplicado.** O `Motion` acumula (M5) e nunca vira deslocamento nem é zerado.

## 6. Mapa de paridade por camada

| # | Camada | Mineflayer | Server-native hoje | Situação | Tarefas |
|---|---|---|---|---|---|
| 1 | Física do corpo | `prismarine-physics` | nenhuma; `MOVE` é teleporte | **ausente** | CA-068, CA-069, CA-070 |
| 2 | Posição autoritativa | pacotes do servidor | `nav-state :pos` estimado | **ausente** | CA-065 |
| 3 | Executor de rota | `monitorMovement` | lerp com y antecipado e tick ocioso | **com defeito** | CA-064, CA-067 |
| 4 | Rota parcial | `thinkTimeout` | `NO_PATH` | **ausente** | CA-073 |
| 5 | Partida dentro de sólido | impossível (física) | `NO_PATH` permanente | **ausente** | CA-062, CA-063 |
| 6 | Regras de movimento | `Movements` | SOLID/EMPTY binário | **parcial** | CA-074, CA-075 |
| 7 | Watchdog | `createPatrolWatchdog` | só o tp de >48 m e "perdi você" | **ausente** | CA-066 |
| 8 | Olhar | `lookAt` com pitch, olha jogador próximo | yaw no `MOVE`, pitch 0 | parcial | CA-076 |
| 9 | Combate | `bot.attack` (cooldown do servidor) | `ATTACK_HOSTILE`; cooldown depende do tick do player | não verificado | CA-071 |
| 10 | Knockback recebido | física | `Motion` acumula | **ausente** | CA-069 |
| 11 | Portões, itens, alimentação | `setblock` / `item replace` / `InLove` | os mesmos comandos | portado | — |
| 12 | Percepção de entidades | `nearestEntity` | `PLAYERS`, `SCAN`, `LOCATE_*` | portado | — |
| 13 | Chat | `bot.chat` (chat real) | `tellraw @a[distance=..16]` | diferente | CA-077 |
| 14 | Comandos e LLM | palavras-chave + `agy` | intents + cognição | portado e ampliado | — |
| 15 | Setup do corpo | resistance 255, regen, saturation, armadura tingida, machado | resistance 4, fire_res, picareta | diferente | CA-078 |
| 16 | Reconexão | `on('end')` | `bridge-alive?` + `ensure-live-bridge-and-agents!` | portado | — |

## 7. Como estamos resolvendo

A estratégia é em três fases. A primeira não depende de física e devolve o "me segue direito" rapidamente. A segunda substitui o teleporte por um corpo de verdade.

### Fase 1 — Sprint `karlach-nav-01`: parar de enterrar e destravar sozinha (CA-062 … CA-067)

| ID | O que muda | Camada |
|---|---|---|
| CA-062 | `PathSolver3D` recupera a partida dentro de sólido (sobe até 3 blocos, depois testa as 8 colunas vizinhas). | 5 |
| CA-063 | `GroundSnap` (Java puro) + op de ponte `GROUND` + `navigation/query-ground-y`: acha o y em pé mais próximo numa coluna. | 5 |
| CA-064 | Executor puro `locomotion/advance-path`: descida só ao chegar no nó, subida imediata, sem tick ocioso, velocidade de sprint; `needs-replan?` passa a contar o y. Ligado ao follow e ao intercept. | 3 |
| CA-065 | `nav-state :pos` re-ancorado pela posição real (`PLAYERS`) no follow e periodicamente na patrulha. Versão nativa do `reconcileAfterTeleport`. | 2 |
| CA-066 | Watchdog de progresso (porte do `createPatrolWatchdog`): replaneja em 2 s, desenterra em 3 s, teleporta para perto do líder em 8 s; na patrulha pula o passo após 20 s. | 7 |
| CA-067 | Patrulha do santuário usa o mesmo executor (sem atravessar terreno). | 3 |

A sprint é despachada pelo `sprint_runner` (swe + CGE + small-router, Claude Sonnet 5.5): uma worktree encadeada por tarefa, envelope do perfil CGE `locomotion`, testes primeiro (commit Red só com testes) e gate com `make test` + `repo-index verify`.

### Fase 2 — Corpo com física (CA-068 … CA-072)

- **Op `STEP`**: o Clojure manda **intenção** (direção, pulo, sprint) e não posição. Na thread do servidor, a ponte monta a velocidade, aplica gravidade, atrito e o knockback acumulado e chama o `Entity.move(MovementType.SELF, vel)` do vanilla, que já resolve colisão, degrau e `onGround` (o mesmo que o Carpet faz com fake players). Devolve `x|y|z|onGround`, que vira a posição autoritativa.
- O `MOVE` fica só para assentos e cenas. O stub `SyntheticMotorController` vira o código real ou é removido.
- Verificar se o servidor chama o `playerTick` dela: isso decide o cooldown de ataque e a fome.

### Fase 3 — Refinamento (CA-073 … CA-079)

Rota parcial, alturas de blocos parciais, escadas de mão, olhar com pitch, chat real ou `tellraw`, paridade do setup do corpo e um classloader reutilizado no op `PATH`.

## 8. Implantação e verificação ao vivo

Os testes da sprint são unitários (mundo em memória `world_fixture`, ponte com `with-redefs`). O que só existe no servidor vivo é verificado depois do merge:

1. Antes de reiniciar, ver se há jogadores (`server/logs/latest.log`). Depois `systemctl --user restart craft-agents`.
2. Repetir M3/M4: `PATH` de uma posição enterrada deve devolver rota.
3. `GROUND|x|y|z` na coluna medida em M3 deve devolver `69`.
4. Em follow: sem posição enterrada por 5 min de caminhada em terreno acidentado; distância ao jogador ≤ 6 m andando e ≤ 10 m correndo.

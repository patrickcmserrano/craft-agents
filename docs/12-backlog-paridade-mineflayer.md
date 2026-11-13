# 12 — Backlog: paridade Mineflayer → Server-Native (locomoção e corpo)

> **Status:** Sprint `karlach-nav-01` concluída e em `main` (`f12b328`, 2026-11-12) · Sprint `karlach-nav-02` planejada — nenhuma tarefa iniciada
> **Criado em:** 2026-11-12
> **Origem:** diagnóstico ao vivo da Karlach server-native, documentado em [`11-mineflayer-vs-server-native.md`](11-mineflayer-vs-server-native.md) (evidências M1–M6).
> **Objetivo da sprint:** a Karlach segue o jogador sem se enterrar e, se travar, sai sozinha em poucos segundos.

Cada tarefa segue o pipeline do `AGENTS.md`: worktree `feat/CA-0xx`, testes escritos e falhando primeiro (Red, commit só com testes), testes travados durante a implementação e `make test` + `repo-index verify` verdes antes do commit de implementação.

**Execução automática:** [`plans/sprint-karlach-nav-01.tasks.edn`](plans/sprint-karlach-nav-01.tasks.edn) + `/home/pace/dev/scripts/sprint_runner.clj`. As tarefas CA-062…CA-067 rodam em série com Claude Code (Sonnet 5.5) via small-router, uma worktree encadeada por tarefa (a primeira parte de `sprint/karlach-nav-01`), envelope do perfil CGE `locomotion` e gate do runner (árvore limpa, teste primeiro, `make test`, `repo-index verify`). A série para na primeira falha.

---

## 1. Backlog completo

| ID | Tarefa | Fase | Prior. | Depende de | Camada (doc 11 §6) |
|---|---|---|---|---|---|
| [CA-062](#ca-062) | A* recupera partida dentro de sólido | 1 | P0 | — | 5 |
| [CA-063](#ca-063) | `GroundSnap` + op `GROUND` + `query-ground-y` | 1 | P0 | CA-062 | 5 |
| [CA-064](#ca-064) | Executor de locomoção puro (`advance-path`, `needs-replan?`) | 1 | P0 | — | 3 |
| [CA-065](#ca-065) | Posição autoritativa: re-ancorar `nav-state :pos` | 1 | P0 | CA-064 | 2 |
| [CA-066](#ca-066) | Watchdog de progresso (follow e patrulha) | 1 | P0 | CA-063, CA-065 | 7 |
| [CA-067](#ca-067) | Patrulha do santuário sem atravessar terreno | 1 | P1 | CA-064 | 3 |
| CA-068 | Op `STEP` com física vanilla (`Entity.move(SELF)`, gravidade, atrito, pulo) | 2 | P0 | Sprint 1 | 1 |
| CA-069 | Knockback: aplicar e zerar o `Motion` acumulado no `STEP` | 2 | P1 | CA-068 | 10 |
| CA-070 | Migrar follow, patrulha e combate de `MOVE` para `STEP`; `MOVE` só para cenas e assentos | 2 | P0 | CA-068 | 1 |
| CA-071 | Verificar se o servidor chama o `playerTick` dela; cooldown de ataque e fome | 2 | P1 | — | 9 |
| CA-072 | Remover ou implementar stubs mortos (`SyntheticMotorController`, `SyntheticInteraction`, `SyntheticHostileDetector`), depois de conferir usos em `test/` | 2 | P2 | CA-070 | — |
| CA-073 | Rota parcial: quando o orçamento de nós estoura, devolver `PARTIAL` até o melhor nó | 3 | P1 | — | 4 |
| CA-074 | Alturas de blocos parciais (laje, escada, carpete, neve, terra arada, caminho) no voxel accessor e no A* | 3 | P1 | — | 6 |
| CA-075 | Escada de mão e trepadeira (`allowClimbing`) | 3 | P2 | CA-074 | 6 |
| CA-076 | Olhar: pitch real, olhar o jogador próximo quando parada, mirar no mob | 3 | P2 | — | 8 |
| CA-077 | Chat: decidir entre chat real do player sintético e `tellraw` por distância | 3 | P2 | — | 13 |
| CA-078 | Setup do corpo: armadura tingida e machado na mão secundária como no mineflayer | 3 | P2 | — | 15 |
| CA-079 | Op `PATH`: reutilizar o `URLClassLoader` em vez de criar um por chamada | 3 | P2 | — | — |
| [CA-080](#ca-080) | Classes de navegação frescas nos ops `PATH`/`GROUND` (classloader *child-first* com cache) | 1 | P0 | — | 5 |
| [CA-081](#ca-081) | Modo mineração sem descida antecipada (`locomotion/approach-step`) | 1 | P1 | — | 3 |
| [CA-082](#ca-082) | Implantação e verificação ao vivo da sprint 1 (manual) | 1 | P0 | CA-080 | — |
| [CA-083](#ca-083) | Picard: passo `:walk` da ponte com `locomotion/walk-step` | 1 | P2 | — | 3 |

Ordem da sprint 1: CA-062 → CA-063 → CA-064 → CA-065 → CA-066 → CA-067.

Ordem da sprint 2 (`karlach-nav-02`): CA-080 → CA-081 → CA-083 (automáticas) e, depois do merge, CA-082 (manual). Com a CA-080 implantada, a CA-079 fica coberta (o loader passa a ser reutilizado).

**Fora da execução automática:** o deploy (`systemctl --user restart craft-agents`, com checagem de jogadores) e as verificações ao vivo do doc 11 §8. Fazem parte do fechamento da sprint, feito à mão.

### Convenções comuns a todas as tarefas da sprint

- **Testes novos** ficam no namespace `craft.agents.karlach-locomotion-test`, arquivo `test/craft/agents/karlach_locomotion_test.clj`. A primeira tarefa (CA-062) cria o arquivo e o registra em `test/craft/agents/test_runner.clj`: entra no `:require` e em `epic-suites` como `{:epic 17 :title "Epico VIII (Locomocao) — Paridade Mineflayer → Server-Native (CA-062..CA-067)" :ns 'craft.agents.karlach-locomotion-test}`. As tarefas seguintes acrescentam `deftest`s ao mesmo arquivo.
- **Mundo de teste:** `test/craft/agents/fixtures/world_fixture.clj` (`make-flat-world-state`, `set-block!`, `->voxel-accessor`). Com `(make-flat-world-state 64)`, todo y ≤ 64 é sólido (`grass_block`) e o jogador em pé tem os pés em y = 65.
- **Ponte nos testes:** nunca abrir socket. Use `with-redefs [craft.agents.backend.fabric.transport/send-bridge-cmd! (fn [cmd] ...)]`, como em `test/craft/agents/karlach_rain_shelter_test.clj`.
- **Java** em `src/java/**` é compilado pelo `make test` (alvo `compile-java`) e testado por interop a partir do Clojure.
- **Nós de rota** no Clojure têm o formato devolvido por `navigation/raw-request-path3d`: `{:x int :y int :z int :action kw}`, com x/z de bloco. O centro do bloco é `x + 0.5`, `z + 0.5`.
- **O que precisa do servidor vivo** (ops novos da ponte) é implementado e coberto até a fronteira testável. A verificação ao vivo vai para `docs/plans/sprint-karlach-nav-01-pendencias.md`, sem inventar resultado.

---

## 2. Tarefas da sprint `karlach-nav-01`

### CA-062
**A\* recupera partida dentro de sólido** — P0 · camada 5

- **Problema (doc 11, M3/M4):** a Karlach fica com pés e cabeça dentro do terreno. `PathSolver3D.solve` parte do bloco sólido, nenhum vizinho é válido e o resultado é `NO_PATH`. Ela fica parada para sempre.
- **Desenho:**
  - Novo método público e estático em `com.craft.agents.nav.PathSolver3D`: `long recoverStart(VoxelAccessor world, MovementCapabilities caps, int x, int y, int z)`. Devolve a posição empacotada (`PackedPos.pack`) de uma partida válida ou `-1L` se não houver.
  - Partida **válida** = pés e cabeça não `SOLID` e (`caps.canStandOn(world, x, y-1, z)` ou pés `LIQUID`).
  - Ordem de busca: (1) a própria posição; (2) mesma coluna, y+1 … y+3; (3) as 8 colunas vizinhas (cardinais antes das diagonais), cada uma com dy em `0, +1, -1, +2, +3`. Devolve a primeira válida.
  - `solve(...)`: se a partida não for válida, chama `recoverStart`. Se achar outra, faz a busca a partir dela e **prefixa** a rota com um `PathNodeStep` nessa posição (`JUMP_UP` se mais alta que a partida original, senão `WALK`), para o executor sair do bloco primeiro. Se não achar, `NO_PATH` como hoje.
  - Partida já válida: comportamento **idêntico** ao atual (mesmos passos).
- **Arquivos:** `src/java/com/craft/agents/nav/PathSolver3D.java`; novo `test/craft/agents/karlach_locomotion_test.clj`; `test/craft/agents/test_runner.clj` (registro, ver convenções).
- **Testes (Red):**
  - caso real reproduzido: mundo plano em 64 com sólidos extras em (0,65,0), (0,66,0), (0,67,0) (morro) e partida em (0,66,0) → `SUCCESS`; o primeiro passo está em (0,68,0) ou numa coluna vizinha em pé;
  - `recoverStart` com partida livre devolve a própria posição;
  - partida enterrada com a coluna e as 8 vizinhas cheias até y+3 → `NO_PATH` e `recoverStart` = `-1`;
  - partida livre: a rota é igual à de antes da mudança (compare com um cenário de `epic3_navigation_3d_test.clj`).
- **Aceite:** `make test` verde (a suíte existente continua passando).

### CA-063
**`GroundSnap` + op de ponte `GROUND` + `navigation/query-ground-y`** — P0 · camada 5

- **Objetivo:** dar ao executor e ao watchdog um jeito de achar o y em pé correto de uma coluna, para desenterrar a Karlach sem teleportá-la para longe.
- **Desenho:**
  - Nova classe Java pura `com.craft.agents.nav.GroundSnap` com `static int findStandableY(VoxelAccessor world, int x, int y, int z, int up, int down)`. Testa os offsets na ordem `0, +1, -1, +2, -2, …` até `+up` e `-down` e devolve o primeiro y válido (mesma regra de "partida válida" da CA-062; reutilize, por exemplo, um helper estático de `PathSolver3D`). Se não houver, devolve `Integer.MIN_VALUE`.
  - Op novo na ponte, em `SyntheticPlayerFactory.processCommand`: `GROUND|x|y|z` → `OK|<y>` ou `ERR|NO_GROUND`. Usa `LiveWorldVoxelAccessor` + `GroundSnap.findStandableY(..., 4, 6)`, carregados do mesmo jeito que o op `PATH` (mesmo `pathLoader`). Coloque o bloco logo depois do op `PATH`.
  - Em `craft.agents.backend.fabric.navigation`: `(defn query-ground-y [[x y z]] ...)` manda `GROUND|<floor x>|<floor y>|<floor z>` via `transport/send-bridge-cmd!` e devolve o y como `double`, ou `nil` em erro, `ERR` ou resposta malformada.
- **Arquivos:** `src/java/com/craft/agents/nav/GroundSnap.java` (novo), `src/java/com/craft/agents/fabric/SyntheticPlayerFactory.java`, `src/clj/craft/agents/backend/fabric/navigation.clj`, `test/craft/agents/karlach_locomotion_test.clj`.
- **Testes (Red):**
  - `findStandableY` no mundo plano (chão 64) a partir de y = 65 → 65; de y = 62 (enterrado) → 65 com `up` ≥ 3; de y = 70 (no ar) → 65 com `down` ≥ 5;
  - morro: sólidos extras em (0,65..67,0) a partir de y = 66 → 68;
  - sem solução dentro dos limites → `Integer/MIN_VALUE`;
  - `query-ground-y` com a ponte redefinida: `"OK|69"` → `69.0`; `"ERR|NO_GROUND"` → `nil`; `nil` → `nil`; e o comando enviado é exatamente `GROUND|-597|67|-593` para `[-596.3 67.0 -592.8]`.
- **Aceite:** `make test` verde. A verificação ao vivo do op `GROUND` (doc 11 §8, item 3) vai para o arquivo de pendências.

### CA-064
**Executor de locomoção puro (`advance-path`, `needs-replan?`)** — P0 · camada 3

- **Problema (doc 11 §5):** o passo do follow em `engine.clj` (ramo default `:tether-snap-recall`/`:follow-player`, ~linhas 626–690, e o ramo `:combat-intercept`, ~575–610) troca o y para o do próximo nó antes de chegar nele (descida antecipada → enterra). Também gasta um tick ocioso a cada nó, anda 0,24/tick e o replanejamento ignora o y do líder.
- **Desenho:** novo namespace `craft.agents.backend.fabric.locomotion` (`src/clj/craft/agents/backend/fabric/locomotion.clj`), só com funções puras:
  - `(advance-path {:keys [pos path speed]})` → `{:pos [x y z] :yaw float :path [...] :arrived? bool :node node-or-nil}`.
    - Consome **no mesmo tick** todos os nós iniciais cujo centro esteja a ≤ 0,35 na horizontal. Ao consumir um nó, o y passa a ser o y dele.
    - Depois avança no máximo `speed` na horizontal em direção ao centro do próximo nó (sem passar dele).
    - **Regra de y:** nó mais alto que o y atual (`JUMP_UP`) → y do nó já neste tick. Nó mais baixo (`DROP_DOWN`) → **mantém o y atual** até chegar a ≤ 0,35 do nó; só então adota o y dele. `SWIM` → y do nó + 0,2 (comportamento atual).
    - `:arrived?` = rota vazia depois de consumir. `:yaw` via `navigation/compute-yaw`, mantido o valor anterior se não houve deslocamento (passe `:yaw` na entrada).
  - `(needs-replan? {:keys [path pos target last-target]})` → `true` se a rota está vazia; ou se o alvo se moveu mais de 3,0 **em 3D** desde `last-target`; ou se `pos` está a mais de 2,5 na horizontal do centro do próximo nó (saiu da rota).
  - `(follow-speed dist-to-leader)` → 0,28 se a distância for maior que 6,0, senão 0,22.
- **Integração:** nos dois ramos de `engine.clj` citados, troque o cálculo inline (`target-moved?`, `step-d`, `safe-ny`, `ndist <= 0.4`) por `needs-replan?` + `advance-path` + `follow-speed`. Mantenha o formato do comando `MOVE`, a abertura e o fechamento de portões (`:TRAVERSE_PORTAL`, `:opened-portal`), os leases e os cards. O ramo "sem rota" (gira e espera) continua igual.
- **Arquivos:** `src/clj/craft/agents/backend/fabric/locomotion.clj` (novo), `src/clj/craft/agents/backend/fabric/engine.clj`, `test/craft/agents/karlach_locomotion_test.clj`.
- **Testes (Red):**
  - descida: pos `[0.5 69.0 0.5]`, rota `[{:x 1 :y 66 :z 0 :action :DROP_DOWN}]`, speed 0.24 → depois de 1 tick y = 69.0 e x avançou; repetindo até chegar, o y vira 66.0 só no tick em que a distância horizontal fica ≤ 0,35;
  - subida: nó `{:x 1 :y 70 :z 0 :action :JUMP_UP}` → y = 70.0 já no primeiro tick;
  - sem tick ocioso: pos sobre o centro do nó 1, rota com os nós 1 e 2 → em um tick consome o 1 e se desloca em direção ao 2;
  - nunca ultrapassa o nó: speed 5.0 com o nó a 1,0 → termina no centro do nó;
  - `needs-replan?`: líder subiu 4 blocos com x/z iguais → `true`; líder andou 1 bloco → `false`; rota vazia → `true`; pos a 3 blocos do próximo nó → `true`;
  - `follow-speed` 10.0 → 0.28; 3.0 → 0.22.
- **Aceite:** `make test` verde; `engine.clj` não tem mais `safe-ny` nem `(<= ndist 0.4)` nos dois ramos.

### CA-065
**Posição autoritativa: re-ancorar `nav-state :pos` pela posição real** — P0 · camada 2

- **Problema:** `nav-state :pos` é estimado localmente e nunca é relido nos modos follow e patrulha (doc 11 §4). Qualquer divergência (tp por comando, empurrão, chunk) vira posição fantasma. O `patrol.js` tinha `reconcileAfterTeleport` exatamente para isso.
- **Desenho** (funções puras em `locomotion`):
  - `(agent-pos-from-players players name)` → `[x y z]` do jogador com esse nome (sem diferenciar maiúsculas) na lista de `actions/query-online-players`, ou `nil`.
  - `(reconcile-pos nav-pos real-pos threshold)` → `real-pos` se a distância 3D entre os dois for maior que `threshold`, senão `nav-pos`; `real-pos` nil → `nav-pos`.
- **Integração em `engine.clj`:**
  - follow/bodyguard: o ramo já chama `query-online-players` a cada tick. Pegue a posição real da Karlach dessa mesma lista (sem chamada extra à ponte) e, antes de calcular qualquer passo, faça `(reconcile-pos (:pos st) real 0.75)`. Se mudou, grave em `nav-state :pos` e limpe `:follow-path` (força replanejamento a partir da posição real).
  - patrulha (`:sanctuary-patrol`): a cada 20 ticks, `query-online-players` → `reconcile-pos` com limiar 1.5. Se mudou, grave a posição e regenere o passo `:walk` corrente a partir dela.
- **Arquivos:** `src/clj/craft/agents/backend/fabric/locomotion.clj`, `src/clj/craft/agents/backend/fabric/engine.clj`, `test/craft/agents/karlach_locomotion_test.clj`.
- **Testes (Red):**
  - `agent-pos-from-players` acha `"Karlach"` em uma lista com `"karlach"` minúsculo; ausente → `nil`;
  - `reconcile-pos`: divergência de 0,5 com limiar 0,75 → mantém a estimativa; divergência só vertical de 2,0 (enterrada) → adota a real; real nil → mantém.
- **Aceite:** `make test` verde.

### CA-066
**Watchdog de progresso (follow e patrulha)** — P0 · camada 7

- **Problema:** sem watchdog, `NO_PATH` ou travamento deixam a Karlach parada indefinidamente (doc 11 §5, passo 5). O mineflayer tinha `createPatrolWatchdog` (`craft-repl/bot/patrol.js`): sem aproximação de pelo menos 0,5 em 20 s, rearma.
- **Desenho:** novo namespace puro `craft.agents.backend.fabric.watchdog` (`src/clj/craft/agents/backend/fabric/watchdog.clj`), medido em ticks e não em relógio, para ser determinístico:
  - `(arm goal tick)` → estado `{:goal [x y z] :best-dist ##Inf :last-progress-tick tick}`.
  - `(progress wd pos tick)` → se a distância horizontal de `pos` ao `:goal` caiu pelo menos 0,5 abaixo de `:best-dist`, atualiza `:best-dist` e `:last-progress-tick`. Se a distância for ≤ `:near` (parâmetro, padrão 3.0), conta como progresso.
  - `(retarget wd goal)` → troca o `:goal` mantendo o relógio (o líder anda; uma nova `:best-dist` é medida a partir do próximo `progress`).
  - `(verdict wd tick cfg)` → `:ok`, `:replan`, `:unstick` ou `:teleport`, conforme os ticks sem progresso passarem `(:replan cfg)`, `(:unstick cfg)` e `(:teleport cfg)`. `cfg` de follow = `{:replan 40 :unstick 60 :teleport 160}`; de patrulha = `{:replan 400 :unstick 400 :teleport ##Inf}`.
- **Integração em `engine.clj`:**
  - follow: arma ao entrar no modo (ou quando não há estado), `retarget` para a posição do líder a cada tick e `progress` com a posição da Karlach.
    - `:replan` → limpa `:follow-path`.
    - `:unstick` → `navigation/query-ground-y` na coluna atual; se vier um y diferente do atual, manda `MOVE` para `[x y-do-chão z]`, grava a posição e limpa a rota.
    - `:teleport` → mesmo bloco do teleporte de >48 m já existente (tp + som + partícula + `:pos` do líder), depois rearma.
  - patrulha: arma no início de cada passo `:walk` com o alvo do passo. `:replan`/`:unstick` (400 ticks = 20 s) → descarta o passo `:walk` corrente e segue para o próximo.
  - Cada disparo deixa uma linha `println` com o prefixo `[karlach-watchdog]`.
- **Arquivos:** `src/clj/craft/agents/backend/fabric/watchdog.clj` (novo), `src/clj/craft/agents/backend/fabric/engine.clj`, `test/craft/agents/karlach_locomotion_test.clj`.
- **Testes (Red):**
  - aproximação contínua por 200 ticks → sempre `:ok`;
  - parada a 10 blocos: 39 ticks → `:ok`, 40 → `:replan`, 60 → `:unstick`, 160 → `:teleport`;
  - dentro de `:near` (2,0 do alvo) parada por 300 ticks → `:ok`;
  - oscilação abaixo de 0,5 não reinicia o relógio;
  - `retarget` não zera o relógio;
  - cfg de patrulha: 399 → `:ok`, 400 → `:replan`; nunca `:teleport`.
- **Aceite:** `make test` verde.

### CA-067
**Patrulha do santuário sem atravessar terreno** — P1 · camada 3

- **Problema:** o executor de passos `:walk` da patrulha (`engine.clj`, ~linhas 1005–1016) anda em linha reta usando o y do **alvo** (`[nx (double ty) nz]`). Isso tem o mesmo defeito de descida antecipada e também sobe antes da hora.
- **Desenho:** função pura `(locomotion/walk-step {:keys [pos target speed yaw]})` → `{:pos :yaw :arrived?}`. Usa as mesmas regras de y de `advance-path`, tratando `target` como um nó único (`target` já vem centrado, sem somar 0,5). Chegada a ≤ 0,22 do alvo (limiar atual do executor). O ramo de passo `:walk` do engine passa a usá-la e mantém a velocidade guiada pela guia (0,165/0,090 com animal; 0,245 sem), as micro-pausas de curva e o envio do `MOVE`.
- **Arquivos:** `src/clj/craft/agents/backend/fabric/locomotion.clj`, `src/clj/craft/agents/backend/fabric/engine.clj`, `test/craft/agents/karlach_locomotion_test.clj`.
- **Testes (Red):**
  - alvo 2 blocos abaixo e a 3 de distância: o y se mantém até a chegada;
  - alvo 1 bloco acima: o y sobe já no primeiro tick;
  - alvo a 0,1: `:arrived?` true e pos = alvo.
- **Aceite:** `make test` verde. Picard (`picard-state`, mesma linha reta) fica fora: ele anda na ponte plana da Enterprise. Registre isso no arquivo de pendências.

---

## 3. Tarefas da sprint `karlach-nav-02` (pendências da sprint 1)

**Origem:** implantação da sprint 1 em 2026-11-12, ~21:29. Depois do `make attach`, a parte em Clojure entrou no ar, mas a parte em Java não:

- `PATH` com partida dentro de bloco → `ERR|NO_PATH` (o `PATH` normal funciona);
- `GROUND|x|y|z` → `ERR|null` em qualquer coluna.

Causa, confirmada em `server/logs/latest.log`: `Erro ao definir/redefinir PathSolver3D.class: class redefinition failed: attempted to add a method`. A JVM não aceita redefinição que adiciona método (`recoverStart`), então o servidor mantém o `PathSolver3D` antigo. Os ops `PATH` e `GROUND` criam um `URLClassLoader` sobre `target/classes`, mas ele delega primeiro ao pai (o Knot), que devolve a classe velha. O `GroundSnap` novo chama um helper que não existe na classe velha → `NoSuchMethodError` embrulhado em `InvocationTargetException` (mensagem nula, daí o `ERR|null`). Efeito prático: o passo `:unstick` do watchdog não faz nada e ela vai direto ao teleporte de 8 s.

As convenções da seção 1 continuam valendo. Os testes novos entram em `test/craft/agents/karlach_locomotion_test.clj`. O título do épico em `test_runner.clj` passa a ser `"Epico VIII (Locomocao) — Paridade Mineflayer → Server-Native (CA-062..CA-083)"`.

### CA-080
**Classes de navegação frescas nos ops `PATH`/`GROUND`** — P0 · camada 5

- **Problema:** ver o início desta seção. Toda mudança futura que adicionar método a uma classe de `com.craft.agents.nav`/`api` repetiria o defeito.
- **Desenho:**
  - Nova classe Java `com.craft.agents.fabric.FreshNavClassLoader extends java.net.URLClassLoader`, **child-first** só para nomes que começam com `com.craft.agents.nav.`, `com.craft.agents.api.` ou são exatamente `com.craft.agents.fabric.LiveWorldVoxelAccessor`. Para esses, tenta `findClass` no próprio diretório antes do pai (reutilizando a já carregada via `findLoadedClass`). Todo o resto delega ao pai (classes do Minecraft vêm do Knot).
  - Fábrica estática com cache: `static synchronized FreshNavClassLoader forDir(java.io.File classesDir, ClassLoader parent)`. Reutiliza a instância enquanto a "versão" do diretório não mudar e o `parent` for o mesmo. Versão = maior `lastModified` entre os `.class` de `nav/`, `api/` e `fabric/LiveWorldVoxelAccessor.class`. Se mudar, cria um loader novo e descarta a referência ao antigo.
  - Em `SyntheticPlayerFactory.processCommand`, os dois `new java.net.URLClassLoader(...)` (ops `PATH` e `GROUND`) passam a usar `FreshNavClassLoader.forDir(new java.io.File("/home/pace/dev/minecraft/craft-agents/target/classes/"), cl)`. O resto do op continua igual.
  - **Restrição de hot-reload:** o `SyntheticPlayerFactory` precisa continuar redefinível a quente pelo `BridgeReloadAgent`. Mude só o **corpo** de `processCommand`: nenhum método, campo ou classe interna novos em `SyntheticPlayerFactory`. A classe `FreshNavClassLoader` é nova e o agente a define no Knot (é "definir", não "redefinir").
  - No `catch` dos ops `PATH` e `GROUND`, se a mensagem da exceção for nula, devolva o nome da classe da causa raiz (ex.: `ERR|java.lang.NoSuchMethodError: ...`) para não esconder o próximo defeito como `ERR|null`. Continua só no corpo de `processCommand`.
- **Arquivos:** `src/java/com/craft/agents/fabric/FreshNavClassLoader.java` (novo), `src/java/com/craft/agents/fabric/SyntheticPlayerFactory.java`, `test/craft/agents/karlach_locomotion_test.clj`, `test/craft/agents/test_runner.clj` (título do épico).
- **Testes (Red):**
  - com `parent` = o classloader do teste (que já tem `com.craft.agents.nav.PathSolver3D`) e diretório `target/classes`: `(.loadClass loader "com.craft.agents.nav.PathSolver3D")` devolve uma classe cujo `getClassLoader` é o próprio `FreshNavClassLoader` (child-first), diferente da classe do pai;
  - o mesmo vale para `com.craft.agents.api.VoxelAccessor` e `com.craft.agents.fabric.LiveWorldVoxelAccessor`;
  - `java.lang.String`, `clojure.lang.RT` e `com.craft.agents.fabric.SyntheticPlayerFactory` vêm do pai;
  - `forDir` duas vezes seguidas, sem mudança no disco → mesma instância;
  - depois de `setLastModified` (mais recente) num `.class` de `nav/` → instância nova. Use uma **cópia** de `target/classes/com/craft/agents/{nav,api}` num diretório temporário (`java.nio.file.Files/createTempDirectory`) para não tocar no build;
  - a classe carregada pelo loader fresco expõe `recoverStart` (`getMethod` com a assinatura da CA-062) — é o que falhava ao vivo.
- **Aceite:** `make test` verde. A verificação ao vivo é a CA-082.

### CA-081
**Modo mineração sem descida antecipada** — P1 · camada 3

- **Problema:** o ramo de aproximação do minério no modo `:mining` (`engine.clj`, ~linhas 800–825, o único `(<= ndist 0.4)` que restou) tem o mesmo defeito que a CA-064 corrigiu no follow: troca o y para o do nó antes de chegar, perde um tick por nó e anda 0,24 fixo. Em túnel, isso a enterra no teto ou no chão.
- **Desenho:**
  - Nova função pura `(locomotion/approach-step {:keys [pos path speed yaw]})` → o mapa de `advance-path` mais `:pitch`. O `:pitch` vem de `navigation/compute-pitch`, do olho (y + 1,62) até o centro do próximo nó da rota resultante; sem próximo nó, 0.0.
  - O ramo de mineração passa a usar `approach-step` com speed 0.24 e grava em `[:active-mining :path]` a rota devolvida. Mantém o comando `MOVE` com pitch, o descarte de minério inalcançável e o resto do modo.
- **Arquivos:** `src/clj/craft/agents/backend/fabric/locomotion.clj`, `src/clj/craft/agents/backend/fabric/engine.clj`, `test/craft/agents/karlach_locomotion_test.clj`.
- **Testes (Red):**
  - nó 2 blocos abaixo: y mantido até chegar (mesma regra de `advance-path`);
  - `:pitch` positivo (olhando para baixo, convenção do Minecraft) quando o próximo nó está abaixo do olho, e próximo de 0 quando está na altura do olho;
  - rota vazia → `:arrived?` true e `:pitch` 0.0.
- **Aceite:** `make test` verde; nenhum `(<= ndist 0.4)` em `engine.clj`.

### CA-082
**Implantação e verificação ao vivo da sprint 1** — P0 · **manual** (fora do sprint-runner)

Checklist, depois do merge da CA-080 em `main`:

1. `server/logs/latest.log`: confirmar que não há jogadores online.
2. Reiniciar o servidor Minecraft **ou** rodar `make attach` (com a CA-080, o `make attach` basta: o `FreshNavClassLoader` é definido no Knot e o `SyntheticPlayerFactory` é só redefinido). Depois, `systemctl --user restart craft-agents`.
3. `GROUND|-182|62|171` (coluna do santuário com chão em 64) → `OK|65`; `GROUND|-182|69|171` → `OK|65`.
4. `PATH|-181.5,62.0,171.5|-176.5,65.0,175.5|1.5` (partida enterrada) → `OK|…` com o primeiro passo em pé.
5. Follow por 5 min em terreno acidentado: nenhuma posição enterrada (`BLOCK` nos pés = ar); distância ao jogador ≤ 6 m andando e ≤ 10 m correndo; linhas `[karlach-watchdog]` raras.
6. Registrar os resultados neste documento (status da sprint 2) e fazer o push de `main` para `origin` (estava 33+ commits à frente em 2026-11-12).

### CA-083
**Picard: passo `:walk` da ponte com `locomotion/walk-step`** — P2 · camada 3

- **Problema:** o passo de caminhada entre estações do Picard (`engine.clj`, ~linhas 455–480, `speed 0.16` com `[nx (double ty) nz]`) usa o y do alvo de imediato. Hoje não tem efeito porque a ponte da Enterprise é plana, mas é o mesmo defeito da CA-067.
- **Desenho:** trocar o avanço inline por `locomotion/walk-step` com `:speed 0.16`, mantendo a chegada (com o `MOVE` final com yaw/pitch da estação), a fase `:wait` e o status.
- **Arquivos:** `src/clj/craft/agents/backend/fabric/engine.clj`, `test/craft/agents/karlach_locomotion_test.clj`.
- **Testes (Red):** extrair o cálculo para uma função pura `(engine/picard-walk-tick pst station)` que devolve `{:pos :yaw :arrived?}` e testá-la: estação 1 bloco acima → y sobe já no primeiro tick; estação a 0,1 → `:arrived?` true.
- **Aceite:** `make test` verde.

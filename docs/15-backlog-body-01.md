# 15 — Backlog: Sprint `body-01` ("Um dono por atuador e um tick mensurável")

> **Status:** Sprint `body-01` planejada para execução noturna/madrugada nas janelas ociosas AGY (`CA-098..CA-101`) + Claude (`CA-102..CA-105`).
> **Criado em:** 2026-11-14 (baseado no relatório arquitetural [`docs/reports/arch-review-01.md`](reports/arch-review-01.md) — `CA-097`).
> **Base:** branch `sprint/body-01` a partir de `feat/CA-097` (`main` `8678fc8` + relatório arquitetural). Base verde: 349 tests / 3244 assertions (`make test`).
> **Objetivo da sprint:** Garantir invariante "1 dono por canal motor (`:legs`, `:gaze`, `:hands`, `:pose`), no máximo 1 comando `MOVE|` por agente por tick", captura única de percepção por tick (`PLAYERS`), isolamento de estado por agente (`[:agent-states id]`), roteamento correto de metas endereçadas e higiene do registro/Studio (`SUG-01`, `SUG-02`, `SUG-05`, `SUG-08`, `SUG-16`).

### Convenções da Sprint

- **Worktree por tarefa:** cada tarefa parte da branch anterior (`sprint/body-01` → `feat/CA-098` → ... → `feat/CA-105`). Nunca altere o checkout principal `/home/pace/dev/minecraft/craft-agents`.
- **TDD estrito (gate do runner):** 1º commit na branch **somente** com arquivos de teste (`test/...`, incluindo registro em `test/craft/agents/test_runner.clj` no `:require` e em `epic-suites` se criar arquivo novo); 2º commit com implementação em `src/...` (e `docs/...` quando aplicável).
- **Verificação:** rode `make test` e garanta `0 failures, 0 errors` antes do commit final de implementação. Não abra sockets nos testes; mocke `craft.agents.backend.fabric.transport/send-bridge-cmd!`.
- **Registros em pendências:** tudo que exigir servidor Minecraft vivo ou validação manual vai para `docs/plans/sprint-body-01-pendencias.md`.

## 1. Mapa das Tarefas

| ID | Título Curto | Sugestões | Harness Sugerido |
|---|---|---|---|
| `CA-098` | Higiene de `AgentSpec` (`:bt/root`), `POST /api/reload` com `registry/reload!` e caminhos configuráveis | `SUG-16` | AGY (`gemini-3.8-flash-high`) |
| `CA-099` | Roteamento de metas endereçadas em `sentry` → `goals/execute-goal!` | `SUG-05` | AGY (`gemini-3.8-flash-high`) |
| `CA-100` | Snapshot único de percepção por tick (`npc.perception-tick`) e métricas `:tick-ms`/`:cmds-per-tick` | `SUG-02` | AGY (`gemini-4-argon-high`) |
| `CA-101` | Isolamento de estado por agente (`:leases`, `:events`, `:cooldowns`) e Studio dinâmico | `SUG-08` | AGY (`gemini-4-argon-high`) |
| `CA-102` | Árbitro de corpo `craft.agents.npc.body` com canais, prioridades e `flush!` | `SUG-01` (parte 1) | Claude (`claude-sonnet-5-5`) |
| `CA-103` | Migrar emissores diretos de `"MOVE\|"` (Karlach, follow, watchdog, patrol, mining, companion, work, sentry, actions) para `npc.body` | `SUG-01` (parte 2a) | Claude (`claude-sonnet-5-5`) |
| `CA-104` | Migrar `picard_tick`, `atelier/behavior` e `bt_actions` para `npc.body`, esvaziar `self-driven` e ativar guarda estrutural | `SUG-01` (parte 2b) | Claude (`claude-sonnet-5-5`) |
| `CA-105` | Testes integrados multi-agente da sprint `body-01` e atualização de documentação | Fechamento `body-01` | Claude (`claude-sonnet-5-5`) |

## 2. Especificação das Tarefas

### CA-098
**Higiene de registro, validação de `:bt/root` no `AgentSpec`, recarga no Studio e caminhos configuráveis (`SUG-16`).**

- **Arquivos:**
  - `src/clj/craft/agents/npc/registry.clj`
  - `src/clj/craft/agents/studio/server.clj`
  - `src/clj/craft/agents/skills/composer.clj` (leitura de `validate-bt-ast` ou referência, sem quebrar callers)
  - `test/craft/agents/npc/registry_test.clj`
  - `test/craft/agents/epic5_cognition_mcp_studio_test.clj`
- **Contexto:** Hoje `AgentSpec` em `src/clj/craft/agents/npc/registry.clj` aceita `[:bt/root {:optional true} vector?]`, permitindo árvores malformadas silenciosas, e `POST /api/reload` em `src/clj/craft/agents/studio/server.clj` recarrega apenas as rotas MCP/HTTP sem chamar `registry/reload!`. Além disso, há caminhos default rígidos que devem respeitar variáveis de ambiente/propriedades mantendo compatibilidade com os defaults atuais.
- **Red (1º commit — só testes):**
  - Em `test/craft/agents/npc/registry_test.clj`: adicionar teste garantindo que `:bt/root` em `AgentSpec` aceita todas as árvores declaradas em `agents/*.edn` (e formas suportadas por `goals.bt/execute-tree-node!`, como `:selector`, `:sequence`, `:fallback`, `:parallel`, `:action`, `:condition`, `:guard`, `:cooldown`, `:repeat`, `:invert`), mas rejeita vetor malformado (ex.: `[]`, `[123]`, `[:op-inexistente-xyz {:x 1}]`).
  - Em `test/craft/agents/epic5_cognition_mcp_studio_test.clj` (ou novo teste no bloco Studio de `registry_test.clj`): verificar que chamar `POST /api/reload` no handler do Studio também invoca `craft.agents.npc.registry/reload!`.
- **Green (2º commit — implementação):**
  - Atualizar `AgentSpec` em `src/clj/craft/agents/npc/registry.clj` para validar `:bt/root` com predicado estrutural real (verificando antes todos os arquivos em `agents/*.edn` para não quebrar nenhum agente existente).
  - Atualizar o endpoint `POST /api/reload` em `src/clj/craft/agents/studio/server.clj` para invocar `(registry/reload!)` e incluir contagem de agentes recarregados na resposta sem quebrar as asserções existentes do Studio.
  - Parametrizar `default-dir` em `npc/registry.clj` via `(or (System/getenv "CRAFT_AGENTS_DIR") "agents")` mantendo `"agents"` como fallback.
- **Accept:** `make test` verde com 0 falhas.

### CA-099
**Roteamento de metas e comandos de chat para o agente endereçado em `sentry` → `goals/execute-goal!` (`SUG-05`).**

- **Arquivos:**
  - `src/clj/craft/agents/backend/fabric/sentry.clj`
  - `src/clj/craft/agents/backend/fabric/goals/dispatch.clj`
  - `src/clj/craft/agents/backend/fabric/goals/companion.clj`
  - `src/clj/craft/agents/backend/fabric/goals/work.clj`
  - `src/clj/craft/agents/backend/fabric/goals/furniture.clj`
  - `test/craft/agents/sentry_test.clj`
  - `test/craft/agents/npc_goals_test.clj`
- **Contexto:** Quando um comando é endereçado a outro agente (ex.: `"picard me segue"` ou `"coco vem"`), o roteamento em `sentry/handle-player-command!` e em partes de `goals/execute-goal!` ainda assume Karlach ou não propaga de ponta a ponta o `:agent/id` alvo na construção do `ctx` e nas falas de resposta.
- **Red (1º commit — só testes):**
  - Em `test/craft/agents/sentry_test.clj` e `test/craft/agents/npc_goals_test.clj`: adicionar testes provando que:
    1. Um comando endereçado a `:picard` ou `:coco` em `sentry/handle-player-command!` constrói/propaga o contexto de meta com `:agent-id` igual ao agente endereçado (não `:karlach`).
    2. `goals/execute-goal!` para metas comuns (`:follow-player`, `:teleport-to-player`, `:stand-up`, etc.) usa o `:agent-id` e `:agent` do contexto recebido (e fala com `npc.speech/say!` para aquele agente, sem falar em nome da Karlach quando `:agent-id` for `:picard` ou `:coco`).
- **Green (2º commit — implementação):**
  - Ajustar `sentry/handle-player-command!` (e helper de contexto por agente) para resolver o agente alvo via `npc.addressing` / `npc.registry`, montar o `ctx` com `:agent-id`, `:agent`, `:nav-state` (`npc.state/agent-atom` quando não for Karlach/Picard com átomo legado, ou mapa compatível) e chamar `goals/execute-goal!`.
  - Ajustar os `defmethod execute-goal!` que ainda têm string fixa `"Karlach"` ou usam apenas `nav-state` sem ler `(:agent-id ctx)` para respeitar o agente do contexto mantendo 100% de retrocompatibilidade para a Karlach.
- **Accept:** `make test` verde com 0 falhas.

### CA-100
**Snapshot único de percepção por tick (`craft.agents.npc.perception-tick`) e métricas de tick `:tick-ms` / `:cmds-per-tick` (`SUG-02`).**

- **Arquivos:**
  - `src/clj/craft/agents/npc/perception_tick.clj` (novo)
  - `src/clj/craft/agents/backend/fabric/engine/bt_tick.clj`
  - `src/clj/craft/agents/backend/fabric/engine/karlach_tick.clj`
  - `src/clj/craft/agents/studio/server.clj`
  - `test/craft/agents/npc_perception_tick_test.clj` (novo)
  - `test/craft/agents/test_runner.clj`
- **Contexto:** Conforme `arch-review-01.md` (`SUG-02`), em ciclos com múltiplos agentes e BTs ativas, consultar a ponte (`PLAYERS` / vitais / hostis) individualmente por agente multiplica o tráfego na ponte TCP e deixa `[:perception]` dessincronizado ou vazio para os guards da BT. Além disso, o loop carece de telemetria de latência de tick (`:tick-ms`) e volume de comandos (`:cmds-per-tick`).
- **Red (1º commit — só testes):**
  - Criar `test/craft/agents/npc_perception_tick_test.clj` e registrá-lo em `test/craft/agents/test_runner.clj` (`:require` e `epic-suites`), verificando que:
    1. `perception-tick/capture-snapshot!` realiza no máximo 1 chamada `PLAYERS` por invocação mesmo quando há vários agentes ativos (Karlach, Picard, Coco, Richeh, Antigravity) e preenche um snapshot estruturado `{:tick N :captured-at-ms T :players ... :by-agent {id {:self ... :leader ... :hostiles ...}}}`.
    2. `perception-tick/agent-perception` / `perception-tick/inject-perception!` atualiza `@engine-ctx-atom` (`:perception` e `:perception-by-agent`) de modo consumível por `bt_actions/guard-holds?`.
    3. `perception-tick/record-tick-metrics!` grava `{:tick-ms ... :cmds-per-tick ... :tick-p95-ms ...}` em `[:telemetry :tick]` (ou `[:metrics]`) no `engine-ctx-atom` e o payload de estado do Studio (`/api/state`) expõe essas métricas.
- **Green (2º commit — implementação):**
  - Criar `src/clj/craft/agents/npc/perception_tick.clj` com funções puras de parse/indexação de snapshot de jogadores/vitais/hostis por agente + efeito único via `transport/send-bridge-cmd!` (1 `PLAYERS` por tick no máximo, com cache no mesmo tick).
  - Integrar em `bt_tick.clj` / `karlach_tick.clj` (e expor `:tick-ms` e `:cmds-per-tick` em `studio/server.clj` sem quebrar contratos antigos de `/api/state`).
- **Accept:** `make test` verde com 0 falhas.

### CA-101
**Isolamento de estado por agente em `[:agent-states id]` e cartões dinâmicos no Studio (`SUG-08`).**

- **Arquivos:**
  - `src/clj/craft/agents/npc/state.clj`
  - `src/clj/craft/agents/leases.clj`
  - `src/clj/craft/agents/studio/server.clj`
  - `test/craft/agents/npc_state_test.clj`
  - `test/craft/agents/epic4_behavior_leases_store_test.clj`
- **Contexto:** Chaves globais no root do contexto/estado (`:cooldowns`, `:events`, `leases` globais de 1 agente só) e o mapa fixo de 4 agentes em `:agents` misturam ou limitam a observabilidade multi-agente. Sem quebrar os leitores legados de `:karlach` e os 4 cartões existentes, cada agente precisa ter seu próprio namespace de leases/cooldowns/eventos e aparecer dinamicamente no Studio a partir do registro.
- **Red (1º commit — só testes):**
  - Em `test/craft/agents/npc_state_test.clj`: testar que `npc.state/set-cooldown!`, `npc.state/cooldown-active?`, `npc.state/record-agent-event!` e `npc.state/agent-leases` isolam estado por `agent-id` em `(:agent-states @ctx)` (um cooldown ou evento de `:coco` não interfere com `:richeh` nem com `:karlach`).
  - Em `test/craft/agents/epic4_behavior_leases_store_test.clj`: testar novas aridades/funções em `leases.clj` para leases **por agente** com `:ttl-ticks` (ex.: `acquire-agent-leases!`, `active-agent-leases`, `tick-expire-leases!`) onde a expiração por TTL libera automaticamente o canal após N ticks e onde `:karlach` e `:coco` nunca colidem no mesmo canal `:legs`.
  - Em `test/craft/agents/npc_state_test.clj`: testar que a função que monta os cartões de agentes para o Studio inclui dinamicamente qualquer agente registrado em `registry/all-agents` (ex.: `:antigravity` além de `:karlach`, `:picard`, `:coco`, `:richeh`).
- **Green (2º commit — implementação):**
  - Estender `src/clj/craft/agents/npc/state.clj` com `:cooldowns`, `:events` e `:leases` por agente dentro do átomo em `[:agent-states id]` (mantendo 100% de compatibilidade com `agent-atom` existente e sem remover chaves legadas usadas por `karlach_tick`).
  - Estender `src/clj/craft/agents/leases.clj` com suporte a mapa particionado por `agent-id` e expiração por `:ttl-ticks`, preservando integralmente `create-lease-manager`, `acquire-leases!`, `release-leases!` e `MultiagentArbiter`.
  - Atualizar `src/clj/craft/agents/studio/server.clj` para mesclar dinamicamente os agentes de `registry/all-agents` e `[:agent-states]` nos dados de agentes retornados pela UI/API.
- **Accept:** `make test` verde com 0 falhas.

### CA-102
**Criar árbitro único de atuadores corporais `craft.agents.npc.body` com canais, prioridades e compositor de comando único por tick (`SUG-01` parte 1).**

- **Arquivos:**
  - `src/clj/craft/agents/npc/body.clj` (novo)
  - `test/craft/agents/npc_body_test.clj` (novo)
  - `test/craft/agents/test_runner.clj`
- **Contexto:** Hoje `13` arquivos sob `src/clj/craft/agents/` constroem e enviam `"MOVE|..."` diretamente via `transport/send-bridge-cmd!`, causando corrida de cabeça/rotação entre BT, ateliê, follow e watchdog. Precisamos de um único namespace dono de `"MOVE|"`: `craft.agents.npc.body`.
- **Red (1º commit — só testes):**
  - Criar `test/craft/agents/npc_body_test.clj` (registrado em `test_runner.clj`) testando:
    1. Canais suportados `#{:legs :gaze :hands :pose}` e prioridades explícitas (`:survival` > `:combat` > `:player-goal` > `:bt` > `:atelier` > `:idle`).
    2. `body/request!` enfileira intenções no buffer do tick por `[agent-id channel]`; quando dois subsistemas competem pelo mesmo canal no mesmo agente/tick, apenas o de maior prioridade vence (empate determinístico documentado).
    3. Quando um subsistema pede `:legs` (andar para `[x y z]`) e outro pede `:gaze` (olhar para `[yaw pitch]` ou alvo) no mesmo tick para o mesmo agente, `body/flush!` arbitra/compõe deterministicamente e emite **no máximo 1 comando `MOVE|`** por agente por tick via `transport/send-bridge-cmd!`, incrementando a métrica `:cmds-per-tick` (ou retornando os comandos emitidos).
    4. `body/move!` oferece API direta/compatível (ex.: para teleporte/passo único em callers migrados) aceitando mapa ou parâmetros claros (`:agent`, `:x`, `:y`, `:z`, `:yaw`, `:pitch`, `:jump`, `:sprint`, `:sneak`, `:use`, `:attack` ou string/mapa estruturado sem que nenhum caller externo precise conhecer o formato wire `"MOVE|..."`), e respeitando locks/leases com `:ttl-ticks` quando aplicável.
- **Green (2º commit — implementação):**
  - Implementar `src/clj/craft/agents/npc/body.clj` de forma limpa, coesa (< 250 linhas), separando arbitragem/composição pura dos efeitos colaterais de envio via `transport/send-bridge-cmd!`. Este namespace é o único lugar que deve serializar o prefixo wire `"MOVE|"`.
- **Accept:** `make test` verde com 0 falhas.

### CA-103
**Migrar todas as emissões diretas de `"MOVE|"` nos subsistemas da Karlach, Locomoção e Metas de Trabalho/Companhia para `npc.body` (`SUG-01` parte 2a).**

- **Arquivos:**
  - `src/clj/craft/agents/backend/fabric/actions.clj`
  - `src/clj/craft/agents/backend/fabric/engine/karlach_tick.clj`
  - `src/clj/craft/agents/backend/fabric/engine/follow.clj`
  - `src/clj/craft/agents/backend/fabric/engine/watchdog.clj`
  - `src/clj/craft/agents/backend/fabric/engine/patrol.clj`
  - `src/clj/craft/agents/backend/fabric/engine/patrol_ops.clj`
  - `src/clj/craft/agents/backend/fabric/engine/mining.clj`
  - `src/clj/craft/agents/backend/fabric/goals/companion.clj`
  - `src/clj/craft/agents/backend/fabric/goals/work.clj`
  - `src/clj/craft/agents/backend/fabric/sentry.clj`
  - `test/craft/agents/npc_body_test.clj`
- **Contexto:** Estes 10 arquivos emitem `"MOVE|"` diretamente pela ponte em vez de passar por `craft.agents.npc.body`. Note que testes existentes (ex.: `karlach_rain_shelter_test`, `karlach_gait_mirror_test`, `karlach_teleport_test`, `npc_locomotion_test`) mockam `transport/send-bridge-cmd!` e inspecionam os strings `"MOVE|..."` enviados! Portanto `body/move!` e `body/flush!` DEVEM continuar enviando exatamente a mesma sintaxe `"MOVE|..."` via `transport/send-bridge-cmd!` para que nenhum teste de caracterização ou locomotor existente quebre!
- **Red (1º commit — só testes):**
  - Em `test/craft/agents/npc_body_test.clj`: adicionar teste estrutural verificando que nenhum desses 10 arquivos em `src/clj/craft/agents/backend/fabric/` contém o literal `"MOVE|` no código-fonte.
- **Green (2º commit — implementação):**
  - Substituir cada construção/envio de `"MOVE|..."` nesses 10 namespaces por chamadas a `craft.agents.npc.body` (passando `:priority`, `:channel`, `:agent` quando aplicável, usando `body/move!` ou função dedicada em `npc.body` para preservar exata equivalência de formato wire nos testes mockados em `transport/send-bridge-cmd!`).
  - Se algum caller construir o string `"MOVE|"` só para retorno em teste unitário ou log sem enviar, mover a serialização para `npc.body/format-move` sem deixar o literal `"MOVE|` fora de `npc/body.clj`.
- **Accept:** `make test` completo verde (`0 failures, 0 errors`) — incluindo todos os testes de locomoção, teleporte, gait mirror e sentry!

### CA-104
**Migrar `picard_tick.clj`, `atelier/behavior.clj` e `goals/bt_actions.clj` para `npc.body`, esvaziar `bt_tick/self-driven` e ativar o guarda estrutural global contra `"MOVE|"` (`SUG-01` parte 2b).**

- **Arquivos:**
  - `src/clj/craft/agents/backend/fabric/engine/picard_tick.clj`
  - `src/clj/craft/agents/backend/fabric/atelier/behavior.clj`
  - `src/clj/craft/agents/backend/fabric/goals/bt_actions.clj`
  - `src/clj/craft/agents/backend/fabric/engine/bt_tick.clj`
  - `src/clj/craft/agents/npc/body.clj` (se precisar de helper adicional para olhar/girar mantendo lease de `:legs`)
  - `test/craft/agents/npc_body_guard_test.clj` (novo)
  - `test/craft/agents/npc_bt_test.clj`
  - `test/craft/agents/test_runner.clj`
- **Contexto:** Após a `CA-103`, restam apenas 3 arquivos emitindo `"MOVE|"`: `picard_tick.clj`, `atelier/behavior.clj` e `bt_actions.clj`. Além disso, `bt_tick/self-driven` ainda exclui `#{:karlach :picard :richeh :coco}` por medo de conflito de cabeça/corpo (o "giro de cabeça da Coco" corrigido provisoriamente em `8678fc8`). Com o árbitro de canais `:legs` vs `:gaze` em `npc.body`, podemos zerar `self-driven` (`#{}`) para que todos os agentes rodem suas BTs sob arbitragem única!
- **Atenção sobre `test/craft/agents/npc_bt_test.clj`:** a linha 47 atualmente asserta `(= #{:karlach :picard :richeh :coco} bt-tick/self-driven)` — nesta tarefa (`CA-104`) é explicitamente parte da especificação atualizar essa asserção em `npc_bt_test.clj` para `(= #{} bt-tick/self-driven)` na fase Red!
- **Red (1º commit — só testes):**
  - Criar `test/craft/agents/npc_body_guard_test.clj` (registrado em `test_runner.clj`) varrendo todos os `.clj` sob `src/clj/` e falhando se a string literal `"MOVE|` aparecer em qualquer arquivo diferente de `src/clj/craft/agents/npc/body.clj`.
  - Atualizar `test/craft/agents/npc_bt_test.clj` para esperar `(= #{} bt-tick/self-driven)` e adicionar cenário provando que quando Coco (`:coco`) executa a rotina do ateliê (`:legs`, prioridade `:atelier` ou superior) e a BT (`:gaze`) no mesmo tick, o árbitro `npc.body` não permite que o comando de baixa prioridade sobrescreva `:legs` nem emita 2 `MOVE|` para `:coco` no mesmo tick.
- **Green (2º commit — implementação):**
  - Migrar todas as emissões de `"MOVE|` em `picard_tick.clj`, `atelier/behavior.clj` e `bt_actions.clj` para `npc.body`.
  - Esvaziar `self-driven` em `bt_tick.clj` (`(def self-driven #{})`), garantindo que `tick-karlach!`, `tick-picard!`, `atelier` e `bt_tick` passem pelo árbitro `npc.body` (fazendo `body/flush!` ao final do ciclo de tick ou preservando emissões consistentes quando chamado isoladamente em testes existentes).
- **Accept:** `make test` completo verde (`0 failures, 0 errors`).

### CA-105
**Testes integrados multi-agente da sprint `body-01`, registro de pendências ao vivo e atualização documental.**

- **Arquivos:**
  - `test/craft/agents/npc_body_integration_test.clj` (novo)
  - `test/craft/agents/test_runner.clj`
  - `docs/plans/sprint-body-01-pendencias.md`
  - `docs/13-backlog-npc-core.md`
- **Red (1º commit — só testes):**
  - Criar `test/craft/agents/npc_body_integration_test.clj` (registrado em `test_runner.clj`) cobrindo um cenário integrado de tick multi-agente (`:karlach`, `:picard`, `:coco`, `:richeh`):
    1. Competição simultânea entre subsistemas (`:survival`, `:bt`, `:atelier`) no mesmo agente resulta em no máximo 1 comando `MOVE|` por agente naquele tick após `body/flush!`.
    2. Snapshot único de percepção por tick (`perception-tick/capture-snapshot!`) executa exatamente 1 chamada `PLAYERS` para os 4 agentes.
    3. Estados `:cooldowns`, `:events` e `:leases` permanecem isolados por agente em `[:agent-states id]`.
    4. Comando de chat endereçado a `:picard` roteia corretamente até `goals/execute-goal!` com `:agent-id :picard`.
- **Green (2º commit — docs e eventuais ajustes finais):**
  - Atualizar `docs/plans/sprint-body-01-pendencias.md` listando exatamente o que está coberto por testes automatizados vs o que depende de validação ao vivo no servidor Minecraft (observação de Coco/Richeh + latência em tick real).
  - Atualizar `docs/13-backlog-npc-core.md` (ou notas cruzadas) refletindo o fechamento de `body-01` e a contagem atual de testes sem inventar números.
- **Accept:** `make test` completo verde (`0 failures, 0 errors`).

# 16 — Backlog: Sprint `atelier-core-01` ("Coco e Richeh no NPC Core")

> **Status:** Sprint `atelier-core-01` enfileirada para iniciar automaticamente logo após `feat/CA-105` (`body-01`).
> **Criado em:** 2026-11-14 (baseado no relatório arquitetural [`docs/reports/arch-review-01.md`](reports/arch-review-01.md) — `CA-097`).
> **Base:** branch `feat/CA-105` (término da Sprint `body-01`, com árbitro `npc.body`, snapshot único de percepção e isolamento `[:agent-states id]`).
> **Objetivo da sprint:** Unificar os interpretadores de Behavior Tree (`bt/engine.clj` + `goals/bt.clj` + `bt_actions.clj`) com estado `:running`, memória por nó, `:cooldown`/`:repeat`/timeout real (`SUG-03`); consolidar o registro declarativo de ações com guards fail-closed (`SUG-04`); enriquecer o schema EDN de persona/rotina/lugares (`SUG-11`); e migrar Coco e Richeh integralmente para o NPC Core, aposentando lógica hardcoded específica em `atelier/behavior.clj` (`SUG-06`).

### Convenções da Sprint

- **Worktree por tarefa:** cada tarefa parte da branch anterior (`feat/CA-105` → `feat/CA-106` → ... → `feat/CA-113`).
- **TDD estrito:** 1º commit só com arquivos de teste (`test/...`, incluindo `test/craft/agents/test_runner.clj` ao criar arquivo novo); 2º commit com implementação em `src/...` e/ou `resources/` / `agents/` / `docs/`.
- **Verificação:** rode `make test` (`0 failures, 0 errors`) antes de concluir cada tarefa. Mocke sempre `craft.agents.backend.fabric.transport/send-bridge-cmd!`.

## 1. Mapa das Tarefas

| ID | Título Curto | Sugestões |
|---|---|---|
| `CA-106` | Interpretador único de BT com estado `:running`, memória por nó e `:cooldown`/`:repeat` reais | `SUG-03` (parte 1) |
| `CA-107` | Timeout real de ações assíncronas na BT e unificação `bt/engine.clj` + `goals/bt.clj` | `SUG-03` (parte 2) |
| `CA-108` | Registro declarativo único de ações `{:run :channels :params :doc}` e multimethod de guards fail-closed | `SUG-04` |
| `CA-109` | Schema declarativo de persona (`:lines`, `:relations`, `:routine`, `:places`) em `agents/*.edn` | `SUG-11` (parte 1) |
| `CA-110` | Primitivas genéricas de rotina, lugares e interação social no NPC Core | `SUG-06` (parte 1) |
| `CA-111` | Migrar Coco e Richeh integralmente para BTs/EDN no NPC Core e limpar `atelier/behavior.clj` | `SUG-06` (parte 2) |
| `CA-112` | Testes estruturais de distinção de persona e proibição de nomes de personagens em `src/` | `SUG-11` (parte 2) |
| `CA-113` | Fechamento integrado da sprint `atelier-core-01`, atualização do Studio e documentação | Fechamento |

## 2. Especificação das Tarefas

### CA-106
**Interpretador único de BT com estado `:running`, memória por nó e `:cooldown` / `:repeat` reais (`SUG-03` parte 1).**

- **Arquivos:**
  - `src/clj/craft/agents/backend/fabric/goals/bt.clj`
  - `src/clj/craft/agents/npc/state.clj`
  - `test/craft/agents/npc_bt_test.clj`
- **Contexto:** Hoje o executor de BT retorna no máximo `:ok`/`:fail` síncrono sem memória por nó nem propagação consistente de `:running`, e decoradores como `:cooldown` e `:repeat` não controlam cadência real multi-tick por `[agent-id node-path]`.
- **Red (1º commit — só testes):**
  - Em `test/craft/agents/npc_bt_test.clj`: adicionar testes cobrindo:
    1. Uma `:sequence` onde o 2º filho retorna `:running` retoma diretamente do 2º filho no tick seguinte (sem re-executar o 1º filho enquanto estiver `:running`), e só avança ou reinicia quando concluir com `:ok` ou `:fail`.
    2. Nó decorador `[:cooldown {:ticks N} child]` bloqueia reexecução de `child` durante `N` ticks por `[agent-id node-path]`.
    3. Nó decorador `[:repeat {:times K} child]` repete `child` `K` vezes retornando `:running` até completar `K` sucessos (ou encerrar em falha conforme contrato explícito).
- **Green (2º commit — implementação):**
  - Armazenar memória de nós de BT por agente e caminho de nó dentro de `[:agent-states agent-id :bt-memory]` em `npc.state`.
  - Implementar suporte a `:running`, retomada de `:sequence`/`:selector` (ou `:mem-sequence`), `:cooldown` e `:repeat` no interpretador mantendo 100% de compatibilidade com os testes antigos de `goals/bt` e `npc_bt_test`.
- **Accept:** `make test` verde com 0 falhas.

### CA-107
**Timeout de ações assíncronas na BT e unificação dos interpretadores `bt/engine.clj` e `goals/bt.clj` (`SUG-03` parte 2).**

- **Arquivos:**
  - `src/clj/craft/agents/bt/engine.clj`
  - `src/clj/craft/agents/backend/fabric/goals/bt.clj`
  - `src/clj/craft/agents/bt/builtins.clj`
  - `test/craft/agents/npc_bt_test.clj`
  - `test/craft/agents/epic4_behavior_leases_store_test.clj`
- **Contexto:** Existem duas árvores/motores de BT (`bt/engine.clj` dos Épicos anteriores e `backend/fabric/goals/bt.clj` do runtime live). Unificá-los sob um núcleo comum garante que evolução de decoradores (`:timeout`, `:running`, memória) valha para todo o projeto sem duplicação.
- **Red (1º commit — só testes):**
  - Em `test/craft/agents/npc_bt_test.clj`: testar nó `[:timeout {:ticks N} child]` (ou `:timeout-ticks` na ação assíncrona) que aborta um filho preso em `:running` após `N` ticks e libera seus leases pendentes em `npc.body`/`leases.clj`, retornando `:fail`.
  - Verificar também compatibilidade entre o ponto de entrada de `bt/engine.clj` e o executor unificado.
- **Green (2º commit — implementação):**
  - Implementar timeout por ticks na BT + limpeza de lease associado em caso de expiração.
  - Fazer `bt/engine.clj` e `goals/bt.clj` compartilharem o mesmo núcleo de interpretação (preservando todas as assinaturas públicas esperadas por `epic4_behavior_leases_store_test`, `npc_bt_test` e `goals.clj`).
- **Accept:** `make test` verde com 0 falhas.

### CA-108
**Registro único declarativo de ações `{:run :channels :params :doc}` e multimethod de guards fail-closed (`SUG-04`).**

- **Arquivos:**
  - `src/clj/craft/agents/backend/fabric/goals/bt_actions.clj`
  - `src/clj/craft/agents/backend/fabric/goals/bt.clj`
  - `src/clj/craft/agents/npc/registry.clj`
  - `test/craft/agents/npc_bt_test.clj`
  - `test/craft/agents/npc/registry_test.clj`
- **Contexto:** Conforme `SUG-04`, hoje ações vivem parte no multimethod `bt-action`, parte em `case` hardcoded dentro de `execute-tree-node!`, e guards desconhecidos ou sem dados falham silenciosamente ou com fallback inconsistente.
- **Red (1º commit — só testes):**
  - Em `test/craft/agents/npc_bt_test.clj` e `test/craft/agents/npc/registry_test.clj`: testar que:
    1. Toda ação de BT é declarada em um mapa/registro único `{action-key {:run fn :channels #{...} :params schema :doc string}}` com introspecção (`registered-actions`), sem `case` paralelo escondido em `bt.clj`.
    2. `guard-holds?` usa multimethod extensível por condição e opera em modo **fail-closed**: condição desconhecida ou estado sem snapshot exigido retorna `false` deterministicamente.
    3. Validação de `AgentSpec` verifica se todas as `:action` referenciadas em `:bt/root` existem no registro de ações.
- **Green (2º commit — implementação):**
  - Consolidar ações de BT em `bt_actions.clj` num registro único `{:run :channels :params :doc}` conectado ao árbitro `npc.body` pelos `:channels` declarados; remover ramos `case` duplicados de `bt.clj`.
  - Converter guards para multimethod `eval-guard` fail-closed.
- **Accept:** `make test` verde com 0 falhas.

### CA-109
**Schema declarativo de persona (`:lines`, `:relations`, `:routine`, `:places`) nos EDNs de agentes (`SUG-11` parte 1).**

- **Arquivos:**
  - `src/clj/craft/agents/npc/registry.clj`
  - `src/clj/craft/agents/npc/speech.clj`
  - `agents/karlach.edn`
  - `agents/picard.edn`
  - `agents/coco.edn`
  - `agents/richeh.edn`
  - `agents/antigravity.edn`
  - `test/craft/agents/npc/registry_test.clj`
  - `test/craft/agents/npc_speech_test.clj`
- **Contexto:** Hoje falas, relações sociais, agenda diária e lugares favoritos de Coco e Richeh ficam espalhados em namespaces Clojure específicos do ateliê em vez de dados declarativos nos `.edn`.
- **Red (1º commit — só testes):**
  - Em `test/craft/agents/npc/registry_test.clj`: testar que `AgentSpec` valida opcionalmente `:agent/persona` (ou chaves declarativas `:lines`, `:relations`, `:routine`, `:places` no `.edn` do agente) e que os 5 arquivos `agents/*.edn` carregam sem erros e declaram `:places`, `:routine` e `:lines` coerentes.
  - Em `test/craft/agents/npc_speech_test.clj`: testar que `npc.speech` consegue sortear falas por evento/tópico diretamente a partir das `:lines` declaradas no `.edn` da persona, sem consultar nomes hardcoded em código Clojure.
- **Green (2º commit — implementação):**
  - Estender `AgentSpec` em `npc/registry.clj` com `:agent/lines`, `:agent/relations`, `:agent/routine`, `:agent/places` (ou `:agent/persona` agrupado, mantendo compatibilidade com `:agent/dialogues`).
  - Popular os metadados declarativos nos EDNs em `agents/*.edn` (especialmente Coco e Richeh com suas falas, lugares do ateliê, relação entre si/com jogador e rotina).
  - Atualizar `npc/speech.clj` com helper `persona-line` guiado por registro.
- **Accept:** `make test` verde com 0 falhas.

### CA-110
**Primitivas genéricas de rotina, lugares nomeados e interação social no NPC Core (`SUG-06` parte 1).**

- **Arquivos:**
  - `src/clj/craft/agents/backend/fabric/goals/bt_actions.clj`
  - `src/clj/craft/agents/npc/routine.clj` (novo)
  - `test/craft/agents/npc_routine_test.clj` (novo)
  - `test/craft/agents/test_runner.clj`
- **Contexto:** Para que Coco, Richeh e futuros NPCs rodem exclusivamente via `.edn` + BT sem lógica específica no engine, o catálogo de primitivas do NPC Core precisa incluir ações/guards genéricos orientados a dados do registro: ir a um lugar nomeado de `:agent/places`, executar bloco horário de `:agent/routine`, cumprimentar/interagir com outro agente conforme `:agent/relations`, usar bloco de trabalho (`workstation`) e lançar feitiço/efeito declarado.
- **Red (1º commit — só testes):**
  - Criar `test/craft/agents/npc_routine_test.clj` (registrado em `test_runner.clj`) testando as ações e guards declarativos genéricos (`:visit-place`, `:follow-routine-slot`, `:greet-companion`, `:workbench-study` / `:cast-declared-spell`) com dois agentes fictícios diferentes provando que o comportamento segue apenas o `.edn` de cada agente.
- **Green (2º commit — implementação):**
  - Criar `src/clj/craft/agents/npc/routine.clj` com funções puras de seleção de bloco de rotina por hora do mundo (`world-time`) e resolução de `:places`/`:relations`, registrando as ações e guards correspondentes no catálogo de `bt_actions.clj`.
- **Accept:** `make test` verde com 0 falhas.

### CA-111
**Migrar Coco e Richeh integralmente para BTs/EDN no NPC Core e limpar lógica hardcoded de `atelier/behavior.clj` (`SUG-06` parte 2).**

- **Arquivos:**
  - `agents/coco.edn`
  - `agents/richeh.edn`
  - `src/clj/craft/agents/backend/fabric/atelier/behavior.clj`
  - `src/clj/craft/agents/backend/fabric/atelier.clj`
  - `src/clj/craft/agents/backend/fabric/engine/atelier_tick.clj`
  - `test/craft/agents/atelier_test.clj`
  - `test/craft/agents/npc_routine_test.clj`
- **Contexto:** Aposentar o código imperativo específico de Coco e Richeh em `atelier/behavior.clj` (mantendo apenas fachada/shims mínimos se exigidos por testes legados em `atelier_test.clj` que não devam ser quebrados, delegando para o registro EDN e BT unificada).
- **Red (1º commit — só testes):**
  - Em `test/craft/agents/npc_routine_test.clj`: adicionar teste garantindo que tanto `:coco` quanto `:richeh` executam suas rotinas diárias, interação mútua, visita aos pontos do ateliê e reações exclusivamente via `bt_tick/tick-agent!` usando `:bt/root` + `.edn`, com emissão única pelo `npc.body`.
- **Green (2º commit — implementação):**
  - Completar `:bt/root` em `agents/coco.edn` e `agents/richeh.edn` usando as primitivas genéricas de `CA-110`.
  - Enxugar `atelier/behavior.clj` e `engine/atelier_tick.clj` delegando integralmente para o interpretador de BT e registro EDN (removendo decisões hardcoded duplicadas), garantindo que `atelier_test.clj` e toda a suíte continuem 100% verdes.
- **Accept:** `make test` verde com 0 falhas.

### CA-112
**Testes estruturais de distinção de persona e proibição de nomes de personagens em `src/` (`SUG-11` parte 2).**

- **Arquivos:**
  - `test/craft/agents/npc_persona_structural_test.clj` (novo)
  - `test/craft/agents/test_runner.clj`
  - Eventuais namespaces em `src/clj/craft/agents/` que ainda contenham strings/keywords hardcoded de nomes específicos fora de fachadas de compatibilidade legadas explicitamente anotadas.
- **Contexto:** Conforme `SUG-11`, precisa existir um guarda automático garantindo que duas personas distintas geram decisões/falas distintas usando o mesmo código e impedindo que novos `if (= id :coco)` ou strings de persona vazem novamente para `src/clj/craft/agents/npc/` e motores genéricos.
- **Red (1º commit — só testes):**
  - Criar `test/craft/agents/npc_persona_structural_test.clj` (registrado em `test_runner.clj`) verificando:
    1. **Teste de distinção de persona:** executar o mesmo ciclo de tick/resposta com a persona de `:coco` vs `:richeh` (ou duas personas sintéticas no registro) e comprovar que falas, destinos (`:places`) e ações escolhidas diferem de acordo com o EDN.
    2. **Teste estrutural anti-hardcoding:** varrer namespaces de núcleo (`src/clj/craft/agents/npc/*.clj`, `src/clj/craft/agents/bt/*.clj`, `src/clj/craft/agents/backend/fabric/goals/bt*.clj`, `engine/bt_tick.clj`, `engine/atelier_tick.clj`, `atelier/behavior.clj`) e falhar se houver referência hardcoded a `:coco`, `:richeh`, `"Coco"` ou `"Richeh"` fora de listas explícitas de fachada legada de Karlach/Picard documentadas.
- **Green (2º commit — implementação):**
  - Remover eventuais resquícios de condicionais hardcoded de personagem nos módulos varridos até que o teste estrutural passe limpo.
- **Accept:** `make test` verde com 0 falhas.

### CA-113
**Fechamento integrado da sprint `atelier-core-01`, atualização do Studio e documentação.**

- **Arquivos:**
  - `src/clj/craft/agents/studio/server.clj` (se necessário expor resumo de `:routine`/`:bt-memory` sem quebrar UI)
  - `test/craft/agents/npc_docs_test.clj`
  - `docs/plans/sprint-atelier-core-01-pendencias.md`
  - `docs/reports/arch-review-01.md` (opcional: nota de andamento em seção própria se não alterar testes, ou registrar em `sprint-atelier-core-01-pendencias.md`)
- **Red (1º commit — só testes):**
  - Atualizar/estender teste em `test/craft/agents/npc_routine_test.clj` (ou `npc_persona_structural_test.clj`) cobrindo ponta a ponta Studio `/api/state` + BT unificada + Coco/Richeh declarativos + guard fail-closed.
- **Green (2º commit — implementação/docs):**
  - Ajustar exposição no Studio se necessário e documentar em `docs/plans/sprint-atelier-core-01-pendencias.md` o estado verificado em testes vs validações pendentes ao vivo em jogo.
- **Accept:** `make test` completo verde (`0 failures, 0 errors`).

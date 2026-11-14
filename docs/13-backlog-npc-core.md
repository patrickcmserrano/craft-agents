# 13 — Backlog: NPC Core (capacidades da Karlach compartilhadas por todos os NPCs)

> **Status:** Sprint `npc-core-01` planejada · agendada para 2026-11-13 01:31 BRT (retomada automática de hora em hora até 12:31)
> **Continuação:** a sprint `body-01` (`CA-098`..`CA-105`, árbitro único de atuadores `npc.body`, snapshot único de percepção e estado isolado por agente) foi fechada em 2026-11-14. Cobertura automatizada e o que falta validar ao vivo: [`plans/sprint-body-01-pendencias.md`](plans/sprint-body-01-pendencias.md). Contagem atual do `make test` na entrega de `CA-105`: 377 testes, 3643 asserções, 0 failures, 0 errors (a "base verde" abaixo é a de `npc-core-01` e está desatualizada).
> **Criado em:** 2026-11-12
> **Base:** branch `sprint/npc-core-01` = `main` (`cf8e657`) + merge de `refactor/atelier-cleanup` (atelier em sub-namespaces com fachada). Base verde: 218 testes / 2388 asserções.
> **Objetivo da sprint:** o que hoje é "da Karlach" mas é capacidade geral de corpo, sobrevivência, fala, comando e cognição passa a funcionar para qualquer NPC declarado em `agents/*.edn` (Karlach, Picard, Coco, Richeh). Os namespaces gigantes (`engine.clj` com 1395 linhas, `goals.clj` com 773) são quebrados como no refactor do atelier.

Cada tarefa segue o pipeline do `AGENTS.md`: worktree `feat/CA-0xx`, testes primeiro (commit Red só com testes), testes travados durante a implementação e `make test` + `repo-index verify` verdes antes do commit de implementação.

**Execução automática:** [`plans/sprint-npc-core-01.tasks.edn`](plans/sprint-npc-core-01.tasks.edn) + `/home/pace/dev/scripts/sprint_runner.clj`, disparado por `/home/pace/dev/scripts/sprint_autoresume.clj` (timer `sprint-npc-core-01.timer`). A série é encadeada: cada tarefa parte da branch da anterior. Se uma tarefa falhar por limite de uso da assinatura, a próxima janela retoma a partir dela. Outras falhas podem ser retentadas até 3 vezes.

---

## 1. Backlog

| ID | Tarefa | Prior. | Depende de | Perfil CGE |
|---|---|---|---|---|
| [CA-084](#ca-084) | Caracterizar e quebrar `engine.clj` em sub-namespaces com fachada | P0 | — | backend-fabric |
| [CA-085](#ca-085) | Quebrar `goals.clj` em sub-namespaces com fachada | P0 | CA-084 | backend-fabric |
| [CA-086](#ca-086) | Registro de agentes (`npc.registry`) com schema Malli | P0 | — | architecture |
| [CA-087](#ca-087) | Fala unificada `npc.speech/say!` | P0 | CA-086 | backend-fabric |
| [CA-088](#ca-088) | Spawn e loadout guiados pelo `.edn` | P1 | CA-086 | backend-fabric |
| [CA-089](#ca-089) | Estado por agente (`npc.state`) e Studio genérico | P0 | CA-086 | architecture |
| [CA-090](#ca-090) | Endereçamento de comandos por nome de qualquer agente | P0 | CA-086 | cognition |
| [CA-091](#ca-091) | Despacho de metas por agente (`execute-goal!`) | P0 | CA-085, CA-089, CA-090 | cognition |
| [CA-092](#ca-092) | Locomoção compartilhada: follow, watchdog, teleporte, barco | P0 | CA-089, CA-091 | locomotion |
| [CA-093](#ca-093) | Sobrevivência compartilhada: comer, abrigo de chuva, política de combate | P1 | CA-092 | backend-fabric |
| [CA-094](#ca-094) | Behavior Tree para todo agente com `:bt/root` | P1 | CA-091 | cognition |
| [CA-095](#ca-095) | Java: mundo do agente por nome nos ops da ponte | P1 | — | backend-fabric |
| [CA-096](#ca-096) | Documentação de arquitetura, cobertura e pendências | P1 | todas | architecture |

Ordem da série: CA-084 → CA-085 → CA-086 → CA-087 → CA-088 → CA-089 → CA-090 → CA-091 → CA-092 → CA-093 → CA-094 → CA-095 → CA-096.

**Continua só da Karlach** (papel, não capacidade): resgate de animal fugido, cuidar dos cercados, patrulha do Santuário, falas por espécie, procriar animais e a Escolta Ativa (`sentry/proactive-sentry-scan!`). Picard continua com ponte/clima, e Coco/Richeh continuam com feitiços.

**Outras sprints pendentes (não entram aqui):** fases 2 e 3 da paridade Mineflayer (CA-068…CA-079, doc 12): op `STEP` com física, knockback, rota parcial, blocos parciais etc. Elas mexem no Java de locomoção e conflitariam com a CA-092/095; ficam para a sprint seguinte, já em cima do NPC Core, para valerem para todos os agentes. A verificação ao vivo de 5 min do follow (CA-082 item 5) continua manual.

**Fora da execução automática:** deploy (`make attach` / restart do `craft-agents.service` com checagem de jogadores), verificação ao vivo, merge em `main` e push.

### Convenções comuns

- **Compatibilidade:** todo nome público que hoje é consumido (`fabric/karlach-say!`, `execute-karlach-goal!`, `start-live-karlach-loop!`, `engine/evaluate-combat-preemption`, etc.) continua existindo como alias/fachada. Nenhum teste existente pode ser editado para "passar" (Locked-Test). Se um teste antigo depender de detalhe interno movido, mantenha o nome antigo na fachada.
- **Fachada:** copie o padrão de `src/clj/craft/agents/backend/fabric/atelier.clj` (macro `reexport` que delega pela var, para que redefinir via nREPL/re-attach valha na fachada).
- **Namespace novo de domínio:** `src/clj/craft/agents/npc/*.clj` (`craft.agents.npc.registry`, `.speech`, `.state`, ...). `backend/fabric/*` fica com o que fala com a ponte.
- **Testes novos:** um arquivo por tarefa, `test/craft/agents/npc_<area>_test.clj` (ns `craft.agents.npc-<area>-test`), registrado em `test/craft/agents/test_runner.clj` (no `:require` e em `epic-suites` com `{:epic 18 :title "Epico IX (NPC Core) — <área> (CA-0xx)" ...}`). Para refactors (CA-084/085) o arquivo é `test/craft/agents/engine_characterization_test.clj` / `goals_characterization_test.clj`.
- **Ponte nos testes:** nunca abrir socket. Use `with-redefs [craft.agents.backend.fabric.transport/send-bridge-cmd! (fn [cmd] ...)]` e capture os comandos (ver `test/craft/agents/karlach_rain_shelter_test.clj`).
- **Java:** `SyntheticPlayerFactory` aceita só mudanças em corpo de método (hot-swap recusa método novo). Classes novas de navegação vão pelo `FreshNavClassLoader`.
- **Nunca** use `CMD|tp <agente>`: teleporte de agente é `actions/teleport-agent-to!` (bridge `MOVE`). Teste `karlach-teleport-test` guarda isso.
- **Tamanho:** nenhum namespace novo ou alterado nesta sprint passa de ~400 linhas. Funções puras (decisões) separadas dos efeitos (ponte).
- O que precisa de servidor vivo é implementado até a fronteira testável. A verificação ao vivo vai para `docs/plans/sprint-npc-core-01-pendencias.md`, sem inventar resultado.

---

## 2. Tarefas

### CA-084

**Caracterizar e quebrar `engine.clj` em sub-namespaces com fachada.**

`src/clj/craft/agents/backend/fabric/engine.clj` tem 1395 linhas, e `start-live-karlach-loop!` (linha ~283) é uma função de mais de 1000 linhas com os ticks do Picard (A), do Ateliê (B) e da Karlach (C: dança, follow/bodyguard com barco, patrulha/cercados), além das threads de chat.

1. **Red/caracterização:** `test/craft/agents/engine_characterization_test.clj` fixa o comportamento atual das decisões puras que já existem (`evaluate-combat-preemption`, `rain-shelter-decision`, `weather-transition`, `shelter-route-steps`, `strike-ready?`, `battle-cry-ready?`, `picard-walk-tick`, `follow-watchdog-step!`, `patrol-watchdog-fired!`) e cobre cada função pura nova extraída do loop (por exemplo `follow-tick-decision`, `boat-transition`, `patrol-step-decision`): dado um estado e uma leitura do mundo, qual comando de ponte sai e qual estado novo resulta. Os testes devem chamar os nomes **novos** nos sub-namespaces, para que o Red falhe de verdade.
2. **Green:** crie `backend/fabric/engine/` com `combat.clj`, `shelter.clj`, `watchdog.clj` (as funções `*-watchdog-*` do engine; o puro `fabric/watchdog.clj` continua igual), `picard_tick.clj`, `atelier_tick.clj`, `karlach_tick.clj` (follow, barco, patrulha) e `chat.clj` (threads de log/chat). `start-live-karlach-loop!` vira um orquestrador curto que chama `(tick-picard! ...)`, `(tick-atelier! ...)` e `(tick-karlach! ...)`. `engine.clj` vira fachada reexportando todos os nomes públicos de antes.
3. Sem mudança de comportamento: todos os testes antigos passam sem edição.

**Aceite:** `engine.clj` < 150 linhas; nenhum sub-namespace > 400; `make test` verde; `clj-kondo --lint src` sem erro novo.

### CA-085

**Quebrar `goals.clj` em sub-namespaces com fachada.**

`goals.clj` (773 linhas) mistura o multimethod `execute-karlach-goal!` (resgate, abrigo, teleporte, follow, bodyguard, mineração, atenção espacial, dança, patrulha, inventário, drop, procriar, sentar/levantar, ensinar skill, skill dinâmica) com o interpretador de Behavior Tree `execute-tree-node!`.

1. **Red:** `test/craft/agents/goals_characterization_test.clj` cobre, para cada `:goal/type`, os comandos de ponte emitidos e as mudanças em `engine-ctx`/`nav-state` (com `with-redefs` na ponte e em `actions/*`), e cobre `execute-tree-node!` para `:selector`, `:sequence`, `:parallel`, `:guard`, `:cooldown` e `:action`, chamando os nomes nos sub-namespaces novos.
2. **Green:** `backend/fabric/goals/` com `rescue.clj` (resgate e procriar: papel da Karlach), `companion.clj` (teleporte, follow, bodyguard, atenção espacial, dança), `work.clj` (mineração, inventário, drop), `furniture.clj` (sentar/levantar, abrigo), `skills.clj` (ensinar e skill dinâmica) e `bt.clj` (interpretador). Os `defmethod` ficam nos sub-namespaces, sobre o mesmo multimethod definido num `goals/dispatch.clj`. `goals.clj` vira fachada e garante o `require` de todos (senão os `defmethod` não registram).

**Aceite:** `goals.clj` < 120 linhas, cada sub-namespace < 300, `make test` verde.

### CA-086

**Registro de agentes com schema Malli.**

Hoje cada agente é lido do seu `.edn` de um jeito (`sanctuary/karlach-dialogues` faz `slurp` direto, o spawn é fixo em `actions/ensure-live-bridge-and-agents!` e o Studio monta `:agents` à mão em `studio/server.clj`).

1. **Red:** `test/craft/agents/npc_registry_test.clj`: carrega todos os `agents/*.edn` de agentes (ignorando `regions/`, `routes/`, `skills/`), valida pelo schema, `agent-by-name` aceita qualquer caixa ("karlach", "Karlach"), `agent-names` devolve os nomes de jogador, e um `.edn` inválido (sem `:agent/id`) gera erro explicativo e não derruba os outros.
2. **Green:** `src/clj/craft/agents/npc/registry.clj` com `AgentSpec` em Malli: obrigatórios `:agent/id`, `:agent/name` e `:agent/spawn`; opcionais `:agent/role`, `:agent/loadout`, `:agent/quarters`, `:agent/dialogues`, `:agent/traits`, `:bt/root` e os campos **novos** `:agent/voice {:color str :sound str :radius num}`, `:agent/combat-policy (:fight | :flee | :none)`, `:agent/capabilities #{kw}` e `:agent/skin str`. Funções `load-agents`, `agent-by-id`, `agent-by-name`, `agent-names` e `reload!` (atom). Preencha `:agent/voice`/`:agent/combat-policy`/`:agent/skin` nos `.edn` existentes (Karlach: vermelho, `block.campfire.crackle`, `:fight`; Coco/Richeh: `:flee`; Picard: conforme `picard-say!` atual). Decida pelo que o código faz hoje se `antigravity.edn` é um agente ou outra coisa, e documente no docstring.

### CA-087

**Fala unificada.**

Existem `actions/karlach-say!`, `picard/picard-say!` e, no atelier, `speech/coco-say!`, `richeh-say!` e `caster-say!` (o atelier já tem `tellraw-cmd` único).

1. **Red:** `test/craft/agents/npc_speech_test.clj`: `(say! :karlach "oi")` emite o `tellraw` com `execute at Karlach`, raio, cor e som vindos de `:agent/voice`; escapa aspas e barra invertida; agente desconhecido não emite nada e devolve `nil`; os wrappers antigos produzem exatamente os mesmos comandos de antes (caracterização byte a byte).
2. **Green:** `npc/speech.clj` com `say-cmds` (puro, devolve os comandos) e `say!` (efeito). Os wrappers antigos passam a delegar para ela, mantendo nome e saída.

### CA-088

**Spawn e loadout guiados pelo `.edn`.**

`ensure-live-bridge-and-agents!` faz SPAWN, gamemode, efeitos e item de mão com comandos escritos à mão por agente.

1. **Red:** `test/craft/agents/npc_spawn_test.clj`: `spawn-cmds` (puro) para cada agente do registro gera `SPAWN|<nome>|x|y|z|<skin>`, gamemode, efeitos (de um novo `:agent/effects`, opcional) e `item replace` para cada slot de `:agent/loadout` (`:mainhand`, `:offhand`, `:hotbar.N`). Para a Karlach a lista é igual à de hoje (picareta de netherite, resistência 4, resistência a fogo); o `:agent/loadout` dela passa a declarar isso.
2. **Green:** `npc/spawn.clj`; `ensure-live-bridge-and-agents!` itera o registro. Agentes cujo spawn hoje é feito em outro lugar (atelier/Picard) migram só se o comportamento ficar idêntico; caso contrário, registre como pendência.

### CA-089

**Estado por agente e Studio genérico.**

`nav-state` é um átomo único da Karlach; Picard, Coco e Richeh têm átomos próprios com formatos diferentes; o Studio só mostra o inventário de `:karlach`.

1. **Red:** `test/craft/agents/npc_state_test.clj`: `(state/agent-atom ctx :picard)` cria sob demanda com os defaults (`:pos` do spawn, `:mode :idle`, `:path []`, `:watchdog nil`); estados de agentes diferentes são independentes; `studio-snapshot` inclui `:inventory` e `:pos` de **todos** os agentes registrados (com a consulta de inventário via `with-redefs`).
2. **Green:** `npc/state.clj` (mapa `agent-id -> atom` dentro do `engine-ctx`). O `nav-state` da Karlach passa a ser `(agent-atom ctx :karlach)`, sem mudar as chaves que o código já usa. `studio/server.clj` monta `:agents` e `:inventory` a partir do registro. Mantém `:karlach-mode`/`:karlach-nav-pos` no payload por compatibilidade com o front.

### CA-090

**Endereçamento de comandos por nome de qualquer agente.**

`sentry/handle-player-command!` só aciona o pipeline Router → Grounding → Goal se a mensagem contém "karlach" (`sentry.clj` ~187). `intent.clj` (~202) remove só `karlach|picard` do início, e `skills/composer.clj` (~455) gera aliases prefixados com "karlach".

1. **Red:** `test/craft/agents/npc_addressing_test.clj`: `addressed-agents` (pura) devolve `[:karlach]` para "karlach me segue", `[:coco]` para "!coco vem cá", `[:karlach :picard]` para "karlach e picard, venham", `[]` para mensagem sem nome, e reconhece "todos"/"pessoal" como todos os agentes com a capacidade pedida; `strip-addressing` remove qualquer nome registrado; `composer` gera aliases para o agente que aprendeu a skill. Os testes antigos de `sentry_test.clj` e de intent continuam verdes.
2. **Green:** `npc/addressing.clj`; `sentry` usa essa função e passa `:agent/id` no intent. Fala livre/papel específico (Coco/Richeh no atelier, Picard no clima) continua no fluxo de hoje; a mudança é que **metas genéricas** (CA-091) passam a valer para quem foi chamado.

### CA-091

**Despacho de metas por agente.**

1. **Red:** `test/craft/agents/npc_goals_test.clj`: `execute-goal!` recebe `{:agent agent-spec :state agent-atom ...}` e despacha por `:goal/type`; as metas genéricas (`:follow-player`, `:teleport-to-player`, `:bodyguard`, `:mining-cooperation`, `:spatial-attention`, `:inventory-inspection`, `:drop-inventory`, `:sit-furniture`, `:stand-up`) emitem comandos com o nome do agente certo (teste com `:picard` e `:coco`, não só `:karlach`); meta de papel (`:animal-rescue`, `:breed-animals`, `:patrol-sanctuary`) pedida a um agente sem a capacidade em `:agent/capabilities` responde com fala de recusa e não move ninguém; `skills/actions.clj` (`:mount-vehicle`, `:dismount-vehicle`, `:swim-follow`, entrar no barco) usa o `:agent-name` do contexto, sem "Karlach" como padrão escondido; skills aprendidas gravam em `[:agents <id> :skills]`.
2. **Green:** multimethod `execute-goal!` em `goals/dispatch.clj`; `execute-karlach-goal!` vira wrapper que chama com o contexto da Karlach.

### CA-092

**Locomoção compartilhada.**

Follow com A* 3D, `locomotion/advance-path`, `reconcile-pos`, `mirror-gait`, voo (`glide-phase`/`flight-step`), teleporte quando fica para trás (`teleport-agent-to!` via MOVE), barco e o watchdog (`follow-watchdog-step!`/`patrol-watchdog-fired!`) só funcionam para a Karlach.

1. **Red:** `test/craft/agents/npc_locomotion_test.clj`: `follow-tick` (puro, no sub-namespace extraído na CA-084) parametrizado por agente gera `MOVE|<nome>|...` para Picard e Coco seguindo um líder num mundo de `fixtures/world_fixture.clj`; o watchdog dispara por agente de forma independente; o teleporte de recuperação usa MOVE com o nome certo; a decisão de embarque/desembarque usa o nome do agente.
2. **Green:** o tick de follow vira `(tick-follow! ctx agent-id)`, e `tick-karlach!` o chama. Qualquer agente com `:mode :follow` no seu estado segue (a meta `:follow-player` da CA-091 liga isso). Os ops Java (`PATH`/`GROUND`) recebem o nome do agente se a CA-095 já existir; senão continuam no mundo da Karlach (registre a limitação).

### CA-093

**Sobrevivência compartilhada.**

1. **Red:** `test/craft/agents/npc_survival_test.clj`: `rain-shelter-decision` usa `:agent/quarters` de qualquer agente (sem quarters, ele não se abriga e continua o que fazia); `auto-eat-food!`/`feed-hungry-agents!` cobrem todos os agentes do registro; `combat-decision` (pura) por `:agent/combat-policy`: `:fight` reaproveita `evaluate-combat-preemption`, `:flee` devolve alvo de fuga para o aliado mais próximo entre `:agent/traits :flee-target`/`ally-ids` (a ação `evade-towards-ally` que a `coco.edn` já declara), e `:none` ignora.
2. **Green:** implemente a ação de BT `:evade-towards-ally` e generalize o abrigo de chuva. O alerta de chuva do Picard continua só dele.

### CA-094

**Behavior Tree para todo agente com `:bt/root`.**

A `coco.edn` já declara `:bt/root`, mas só a árvore da Karlach roda.

1. **Red:** `test/craft/agents/npc_bt_test.clj`: o engine roda um passo de BT por agente que tem `:bt/root` (com o tick do agente via `with-redefs`); uma ação desconhecida (`:emote-sketch-notes`, `:approach-and-sketch`) não lança, registra evento cognitivo "ação não implementada" uma vez por id e devolve `:failure`; os guards `:ally-within-range?`, `:interesting-block-nearby?` e `:cooldown-ready?` que a Coco usa existem e são puros; um agente sem `:bt/root` mantém o tick atual (Picard, Richeh).
2. **Green:** registro de ações de BT extensível (`defmulti bt-action`) no `goals/bt.clj`. Implemente `:face-entity` e `:social-gaze` como genéricas; as de desenho da Coco podem ficar como no-op registrado.

### CA-095

**Java: mundo do agente por nome nos ops da ponte.**

Em `SyntheticPlayerFactory.java` (~771, ~797, ~858) "o mundo real da Karlach" é resolvido com `"Karlach"` fixo para `PATH`/`GROUND`/`BLOCK`/`TOGGLE_PORTAL`.

1. **Red:** teste por interop (Clojure, em `test/craft/agents/npc_bridge_world_test.clj`) da função de parse/resolução: um campo opcional de agente no fim do comando (`PATH|...|agent=Picard`) é extraído; sem o campo, o padrão continua sendo "Karlach" (compatibilidade). Se a extração for feita em classe nova (helper estático), ela fica fora da `SyntheticPlayerFactory` e é carregada pelo `FreshNavClassLoader` ou é classe nova (que pode ser carregada sem redefinir a factory).
2. **Green:** **apenas corpo de método** na `SyntheticPlayerFactory` (sem método/campo novo). `navigation/raw-request-path3d` e `query-ground-y` aceitam `:agent-name` opcional e o anexam.
3. Pendência: verificação ao vivo com um agente no Nether.

### CA-096

**Documentação de arquitetura, cobertura e pendências.**

1. **Red:** `test/craft/agents/npc_docs_test.clj` checa invariantes estruturais baratos: nenhum `.clj` em `src/clj/craft/agents/backend/fabric/` ou `src/clj/craft/agents/npc/` passa de 400 linhas (a fachada do atelier e arquivos que esta sprint não tocou entram numa lista explícita de exceções com o tamanho atual, para não crescerem); todo `agents/*.edn` de agente valida no schema; todo `:goal/type` que o grounding produz tem `defmethod` em `execute-goal!`.
2. **Green:** atualize `docs/02-arquitetura-e-plano-craft-agents.md` com uma seção "NPC Core" (registro → estado → endereçamento → metas → ticks/BT → ponte) e o mapa de namespaces novo; atualize o status no topo deste documento; escreva `docs/plans/sprint-npc-core-01-pendencias.md` com tudo o que ficou para verificação ao vivo ou não foi migrado; atualize a contagem de testes no `AGENTS.md` do workspace **somente** se ele estiver dentro do repo (não está: anote na pendência).

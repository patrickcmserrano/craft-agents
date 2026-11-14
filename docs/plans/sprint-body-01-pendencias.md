# Pendências da Sprint `body-01` (`CA-098` .. `CA-105`)

> Planejada em `2026-11-14`; atualizada ao término de `CA-105` (`2026-11-14`). Contagem de testes na entrega: **377 testes, 3643 asserções, 0 failures, 0 errors** (saída de `make test` em `feat/CA-105`).

## Coberto por testes automatizados (sem servidor)

Tudo com a ponte stubada (`with-redefs` em `transport/send-bridge-cmd!`); nenhum socket real.

- **Arbitragem corporal única** (`npc-body-test`, CA-102): canais, prioridades (`survival > combat > player-goal > bt > atelier > idle`), desempate determinístico, composição `legs+gaze+pose` num único `MOVE|`, API `move!` com leases.
- **Guarda global** (`npc-body-guard-test`, CA-104): nenhum literal `"MOVE|` fora de `npc.body`; cenário da Coco com `:legs` do ateliê + `:gaze` da BT saindo como 1 `MOVE|`.
- **Percepção e métricas** (`npc-perception-tick-test`, CA-100): 1 `PLAYERS` por tick, `:tick-ms`/`:cmds-per-tick`/`:tick-p95-ms` em `[:telemetry :tick]` e em `/api/state`.
- **Estado por agente** (CA-101 e `npc-state-test`): `:cooldowns`, `:events`, `:leases` isolados em `[:agent-states id]`.
- **Integração multi-agente** (`npc-body-integration-test`, CA-105), com `:karlach`, `:picard`, `:coco`, `:richeh`:
  1. `:survival`, `:bt` e `:atelier` competindo no mesmo agente → no máximo 1 `MOVE|` por agente após `body/flush!`, e vence `:survival`;
  2. `perception-tick/capture-snapshot!` → exatamente 1 chamada `PLAYERS` para os 4 agentes;
  3. cooldown, evento e lease de um agente não vazam para os outros;
  4. chat endereçado a `picard` (`sentry/handle-player-command!`) chega a `goals/execute-goal!` com `:agent-id :picard`.

## Depende de validação ao vivo no servidor Minecraft (não verificado)

- [ ] Observar visualmente Coco e Richeh no Ateliê após a unificação via `npc.body` e o esvaziamento de `bt_tick/self-driven` (fim das micro-oscilações de cabeça durante movimento e postura).
- [ ] Medir latência em tick real de 20Hz (`50ms`): `:tick-ms`, `:tick-p95-ms` e `:cmds-per-tick` no Agent Studio (`/api/state`) com 4-5 agentes ativos. Os testes só provam que as métricas são gravadas e expostas, não os valores reais.
- [ ] Validar o roteamento por chat no jogo (`"picard me segue"`, `"coco vem"`, `"karlach fica"`). Os testes cobrem só o caminho `handle-player-command!` → `execute-goal!` com a ponte stubada.
- [ ] (CA-104) Com `self-driven` vazio, a BT de Karlach, Picard, Richeh e Coco roda a 5Hz junto dos ticks dedicados: confirmar que sai 1 `MOVE|` por agente por tick (o `engine/run-tick!` abre o tick com `body/begin-tick!` e emite tudo em `body/flush!`; `step!`/`move!` só enfileiram em tick aberto e enviam direto fora dele). Atenção a `:cmds-per-tick` e a jitter de posição da Karlach quando a BT dela pede `:gaze` no mesmo tick de um `move!` (a posição vem sempre do `:legs`).
- [ ] (CA-102) Confirmar no bridge real que os campos extras `|jump|use|attack` anexados por `npc.body/->wire` (só quando algum é verdadeiro) são aceitos/ignorados pelo parser do `MOVE`; o formato base de 9 campos segue o wire já usado por `follow.clj`.
- [ ] Deploy em produção (`/home/pace/dev/minecraft/craft-agents`: merge + `make attach` / restart `craft-agents.service` após confirmar jogadores ausentes). Fora do repo e da execução automática.

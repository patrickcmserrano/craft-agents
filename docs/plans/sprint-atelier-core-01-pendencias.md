# Pendências da Sprint `atelier-core-01` (`CA-106` .. `CA-113`)

> Gerado no planejamento inicial da sprint (`2026-11-14`). Será atualizado automaticamente ao término de `CA-113`.

## Verificações dependentes de servidor Minecraft vivo

- [ ] Acompanhar ciclo dia/noite em jogo com Coco e Richeh orientadas por `:agent/routine`, `:agent/places` e BT unificada (`:running` / `:cooldown` / `:timeout`).
- [ ] Conferir no Studio UI a visualização dinâmica de memória da BT, leases ativos e troca de dados por `POST /api/reload`.
- [ ] Deploy em produção após merge em `main`.

## CA-108 — registro de ações e guards fail-closed

- [ ] Ações declaradas em `agents/*.edn` sem implementação de BT (`:run-blackboard-directive`, `:engage-hostiles`, `:patrol-waypoints`, `:care-for-enclosure`, `:escort-player`) constam no registro como `PENDENTE:` (falham e registram evento, como antes); implementar ou remover das árvores.
- [ ] `reload!` ainda não passa `{:known-actions (bt-actions/known-action-ids)}` a `load-agents` (macros de `skills/*.edn` e `:composite-skills` são válidos em runtime e não entram em `known-action-ids`); ligar a validação no boot depois de decidir como expor macros.
- [ ] Guards agora falham fechado: `:in-quiet-corner?` exige `[:perception :players]`; `:within-mount-range?`, `:leader-vehicle-has-seat?` e `:ally-within-range?` (sem `:ally-id`) exigem snapshot do líder/aliado. Conferir em jogo que a percepção ao vivo fornece esses campos (senão a Richeh nunca agacha no canto quieto).

## CA-111 — Coco e Richeh integralmente em BT/EDN (`SUG-06` parte 2)

Feito no repo: `:bt/root` de `agents/coco.edn` e `agents/richeh.edn` cobre rotina (`:follow-routine-slot`), dupla (`:greet-companion`), pontos do ateliê (`:at-place?` + `:routine-activity?`), reação ao ambiente (`:evade-towards-ally`/`:ward-companion`) e ao céu (`:celestial-event?` + `:react-celestial`); `:tether-follow-ally` agora anda de verdade. `engine/atelier_tick.clj` só publica `:world-time`/`:celestial` no engine-ctx, mantém o driver de "seguir jogador" (`:mode :follow`) e a auto-defesa por magia. `bt-tick/tick-agent!` publica a posição real (PLAYERS) em `[:agents id :pos]`, exceto Karlach.

- [ ] **Validar em jogo (servidor vivo)** a nova cadência: a BT roda a 5 Hz com passos de 0.6–0.7 bloco (≈3–3,5 blocos/s) por `MOVE` absoluto, em vez dos 20 Hz × 0.18 do motor antigo; conferir que o andar não fica “travado” e que `:timeout {:ticks 5}` (reavalia prioridades a cada ~1 s) não gera soluços visíveis.
- [ ] **Relógio do mundo**: não há op de ponte que leia a hora real do servidor; `:world-time` é publicado pelo tick do ateliê como `(mod tick 24000)` (mesmo relógio das efemérides, 1 dia = 20 min de loop). Se um dia a ponte expuser a hora real (ex.: `TIME`), publicá-la em `:world-time` e remover o proxy em `atelier_tick.clj`.
- [ ] **Ordem de seguir (`:mode :follow`) ainda é imperativa**: `atelier.chat/handle-atelier-chat!` muda `:mode` nos atoms `coco-state`/`richeh-state` e `step-*-tick!` executa o seguir (fonte `:player-goal`). Migrar para `:has-active-directive?` → `:run-blackboard-directive` (hoje `PENDENTE:`) quando a diretiva por blackboard existir; aí `coco-state`/`richeh-state` só guardam o estado de feitiços.
- [ ] **Shims legados em `atelier/behavior.clj`** (`castle-waypoints`, patrulha por waypoints, órbita, `check-duo-synchrony!`, `check-celestial-reaction!`): nenhum caminho do engine os usa mais, mas continuam porque `atelier_test.clj` e `npc_bt_test.clj` (travados nesta tarefa) os chamam diretamente. Removê-los junto com a migração desses testes para `bt-tick/tick-agent!`.
- [ ] **Reações novas vs. comportamento antigo**: antes só uma das duas falava na interação de dupla (sorteio) e só Coco/Richeh reagiam, respectivamente, a eclipse/aurora; agora cada uma tem cooldown próprio (45 s) e ambas reagem a `:solar-eclipse` e `:crystal-aurora` com falas de `:agent/lines :celestial`. Fenômenos fora dessa lista não geram mais o olhar mudo. Conferir se o ritmo agrada em jogo.
- [ ] `schemas/valid-bt-node?` passou a aceitar `:timeout` (o núcleo `bt.core` já o interpretava, mas o `.edn` com `:timeout` era rejeitado pelo registro).

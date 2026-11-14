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

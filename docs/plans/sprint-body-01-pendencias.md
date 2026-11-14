# Pendências da Sprint `body-01` (`CA-098` .. `CA-105`)

> Gerado no planejamento inicial da sprint (`2026-11-14`). Será atualizado automaticamente ao término de `CA-105`.

## Verificações dependentes de servidor Minecraft vivo

- [ ] Observar comportamento visual da Coco e da Richeh no Ateliê após unificação via `npc.body` e esvaziamento de `bt_tick/self-driven` (confirmar fim definitivo de micro-oscilações de cabeça durante movimento e postura).
- [ ] Medir em tick real de 20Hz (`50ms`) os valores de `:tick-ms` e `:cmds-per-tick` no Agent Studio (`/api/state`) com 4-5 agentes ativos simultaneamente.
- [ ] Validar em servidor vivo o roteamento via chat do jogo (`"picard me segue"`, `"coco vem"`, `"karlach fica"`).
- [ ] Deploy em produção (`/home/pace/dev/minecraft/craft-agents`: merge + `make attach` / restart `craft-agents.service` após confirmar jogadores ausentes).

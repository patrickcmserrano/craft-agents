# Pendências da Sprint `body-01` (`CA-098` .. `CA-105`)

> Gerado no planejamento inicial da sprint (`2026-11-14`). Será atualizado automaticamente ao término de `CA-105`.

## Verificações dependentes de servidor Minecraft vivo

- [ ] Observar comportamento visual da Coco e da Richeh no Ateliê após unificação via `npc.body` e esvaziamento de `bt_tick/self-driven` (confirmar fim definitivo de micro-oscilações de cabeça durante movimento e postura).
- [ ] Medir em tick real de 20Hz (`50ms`) os valores de `:tick-ms` e `:cmds-per-tick` no Agent Studio (`/api/state`) com 4-5 agentes ativos simultaneamente.
- [ ] Validar em servidor vivo o roteamento via chat do jogo (`"picard me segue"`, `"coco vem"`, `"karlach fica"`).
- [ ] Deploy em produção (`/home/pace/dev/minecraft/craft-agents`: merge + `make attach` / restart `craft-agents.service` após confirmar jogadores ausentes).
- [ ] (CA-104) Com `self-driven` vazio, a BT de Karlach, Picard, Richeh e Coco roda a 5Hz junto dos ticks dedicados: confirmar em servidor vivo que sai 1 `MOVE|` por agente por tick (o `engine/run-tick!` abre o tick com `body/begin-tick!` e emite tudo em `body/flush!`; `step!`/`move!` só enfileiram em tick aberto e enviam direto fora dele). Atenção a `:cmds-per-tick` e a qualquer jitter de posição da Karlach quando a BT dela pede `:gaze` no mesmo tick de um `move!` (a posição vem sempre do `:legs`).
- [ ] (CA-102) Confirmar no bridge real que os campos extras `|jump|use|attack` anexados por `npc.body/->wire` (só quando algum é verdadeiro) são aceitos/ignorados pelo parser do `MOVE`; o formato base de 9 campos segue o wire já usado por `follow.clj`.

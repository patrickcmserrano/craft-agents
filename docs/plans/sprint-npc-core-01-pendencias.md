# sprint npc-core-01 — pendências

## CA-086 — registro de agentes

- Os consumidores ainda não usam `craft.agents.npc.registry`: `sanctuary/karlach-dialogues` (slurp direto),
  `actions/ensure-live-bridge-and-agents!` (spawn fixo) e `studio/server.clj` (`:agents` à mão) ficam para a migração
  seguinte; esta tarefa entrega só o registro, o schema e os `.edn` preenchidos.
- `:agent/voice`/`:agent/skin` de Picard (`gold`, `block.note_block.chime`, `:none`) são valores escolhidos sem
  conferir o `picard-say!` atual (o código não foi localizável a partir do envelope). Conferir e ajustar o `.edn`.
- `antigravity.edn` é tratado como agente (tem id/name/spawn/loadout/BT próprios); recebeu só `:combat-policy :fight`
  (usa `:engage-hostiles`), sem voz/skin por não haver referência no código.

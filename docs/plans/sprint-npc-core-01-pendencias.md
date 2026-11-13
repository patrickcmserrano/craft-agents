# sprint npc-core-01 — pendências

## CA-086 — registro de agentes

- Os consumidores ainda não usam `craft.agents.npc.registry`: `sanctuary/karlach-dialogues` (slurp direto),
  `actions/ensure-live-bridge-and-agents!` (spawn fixo) e `studio/server.clj` (`:agents` à mão) ficam para a migração
  seguinte; esta tarefa entrega só o registro, o schema e os `.edn` preenchidos.
- `:agent/voice`/`:agent/skin` de Picard (`gold`, `block.note_block.chime`, `:none`) são valores escolhidos sem
  conferir o `picard-say!` atual (o código não foi localizável a partir do envelope). Conferir e ajustar o `.edn`.
- `antigravity.edn` é tratado como agente (tem id/name/spawn/loadout/BT próprios); recebeu só `:combat-policy :fight`
  (usa `:engage-hostiles`), sem voz/skin por não haver referência no código.

## CA-087 — fala unificada

- `npc.speech/say-cmds`/`say!` leem `:agent/voice`, mas os wrappers antigos (`karlach-say!`, `picard-say!`,
  `coco-say!`, `richeh-say!`, `caster-say!`) passam `opts` que sobrepõem a voz para manter a saída byte a byte:
  as vozes dos `.edn` de Picard/Coco/Richeh (cor, raio) divergem das falas históricas. Quando as vozes forem
  alinhadas nos `.edn`, os `opts` podem sair.
- O escape agora cobre também barra invertida (antes só aspas); mensagens com `\` mudam de saída (era JSON inválido).
- `make test` falha em `karlach-rain-shelter-test` (CA-060o, `shelter-route-steps` com um único `:wait`): teste e
  código de rota não são tocados por esta tarefa; não foi possível confirmar na baseline sem checkout. Verificar.

## CA-088 — spawn guiado pelo registro

- `:agent/spawn-loadout` (novo) existe porque o `:agent/loadout` da Karlach (`:mainhand "minecraft:apple"`) é item de runtime usado na alimentação do santuário (CA-044, teste travado). O spec pedia declarar a picareta em `:agent/loadout`; isso quebraria o CA-044. Unificar exige mudar o consumo do loadout em runtime.
- `:agent/boot-spawn? false` mantém Antigravity fora do spawn de boot: o spawn dele é feito em outro lugar (não migrado, comportamento não verificável sem servidor vivo).
- Ordem de spawn mudou (id alfabético: Coco, Karlach, Picard, Richeh; antes Karlach, Picard, Coco, Richeh); o log por agente agora é genérico (sem emoji por agente).
- Armadura da Picard passou a constar em `:agent/loadout` do `picard.edn` (`:chest/:legs/:feet`); verificar se algum consumidor de runtime do loadout reage a isso.
- Spawn real em servidor vivo (bridge 25595) não foi exercitado: só `spawn-cmds` puro e `spawn-agents!` com stub.

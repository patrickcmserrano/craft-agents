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

## CA-091 — despacho de metas por agente

- `execute-goal!` (multimethod em `goals/dispatch.clj`) recebe `{:agent spec :state atom :engine-ctx atom :sanctuary-route route}`.
  `execute-karlach-goal!` continua **multimethod** (não `defn`): os testes travados de CA-085 fazem
  `(methods dispatch/execute-karlach-goal!)` e `identical?` com a fachada. A fachada registra um método-wrapper por tipo
  de meta de `execute-goal!` (`register-karlach-wrappers!`); metas registradas depois do load da fachada caem no `:default`, que também delega.
- Karlach ganhou `:agent/capabilities #{:animal-rescue :breed-animals :patrol-sanctuary}` em `agents/karlach.edn`;
  sem isso o wrapper recusaria as metas de papel dela. Picard/Coco/Richeh/Antigravity seguem sem capacidades (recusam).
- `skills/actions.clj`: `:mount-vehicle`, `:swim-follow` e `:detect-and-attack-dangerous-mobs` falham com `:NO_AGENT_NAME` sem `:agent-name`
  no contexto. Exceções mantidas por testes travados do epic5 (CA-047/CA-048): `:mount-boat` (alias legado) sem nome cai em
  "Karlach" e `:dismount-vehicle` aceita `:speaker "Karlach"`. Remover essas duas exceções exige alterar esses testes.
  Os padrões "Richeh"/"Coco" das ações do Ateliê (`:tether-follow-ally` etc.) não foram tocados (fora do escopo listado).
- `bt/execute-tree-node!` ganhou a aridade com `agent` (6º argumento); sem ele usa a Karlach. O contexto das ações modulares
  agora leva `:agent-name`/`:agent-id`/`:say!` (a chave `:karlach-say!` foi removida; nenhuma ação a consumia).
- Falas de agentes que não estão no registro (`npc.speech/say!` resolve a voz por id no registro) não emitem nada.
- Os consumidores do multimethod fora de `goals/` (engine, MCP, studio) continuam chamando `execute-karlach-goal!`; migrar
  para `execute-goal!` com o agente endereçado (CA-090) fica para a tarefa seguinte. Não foi exercitado em servidor vivo.

## CA-092 — locomoção compartilhada

- Os ops Java `PATH`/`GROUND` (via `nav/request-path3d` e `nav/query-ground-y`) **não recebem o nome do agente**: a CA-095 ainda
  não existe, então o A* de qualquer agente continua planejando no mundo/posição da Karlach no lado Java. Só os comandos
  `MOVE`/`STAND`/`SWING`/`CMD` carregam o nome certo.
- Para agentes que não são a Karlach, `tick-follow!` mantém o estado em `npc.state/agent-atom` e publica só a própria entrada
  em `(:agents @engine-ctx)` (não mexe em `:leases`/`:tick`); sem cartão do Studio com skills. Combate de escolta
  (`scan-nearby-hostiles`/`attack-hostile-target!`) usa o nome do agente, mas não foi exercitado em servidor vivo.
- Perdeu o líder por 60 ticks: a Karlach volta a `:sanctuary-patrol`; os demais voltam a `:idle`.
- `tick-other-followers!` (em `karlach-tick`) percorre `(:agent-states ctx)`; a meta `:follow-player` da CA-091 ainda precisa
  gravar `:mode :follow` no estado do agente (não verificado aqui). Teste de watchdog/teleporte usa ponte stubada.

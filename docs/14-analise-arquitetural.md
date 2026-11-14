# 14 — Análise arquitetural: escalabilidade, extensibilidade e individualidade dos agentes

*Data: 2026-11-14 · Sprint `arch-review-01` · Modo análise (só relatório)*

**Execução automática:** [`plans/sprint-arch-review-01.tasks.edn`](plans/sprint-arch-review-01.tasks.edn) + `/home/pace/dev/scripts/sprint_runner.clj` em `:mode :analysis`, Claude Fable 5.2, com envelopes do CGE (repo-index) dos perfis architecture, cognition, backend-fabric e locomotion. O único artefato é `docs/reports/arch-review-01.md`.

| ID | Tarefa | Perfis CGE |
| :--- | :--- | :--- |
| CA-097 | Relatório de sugestões arquiteturais | architecture, cognition, backend-fabric, locomotion |

---

### CA-097

**Relatório de sugestões arquiteturais do craft-agents à luz do objetivo "Voyager na JVM".**

#### Objetivo do projeto (o que a análise precisa entender primeiro)

O craft-agents nasceu como a **nova versão do Voyager (NVIDIA, 2023) na JVM**. Leia nesta ordem e resuma, no relatório, a tese e as promessas:

1. `README.md`: visão, tese e mapa de docs.
2. `docs/08-tese-arquitetura-cognitiva-e-capacidades.md`: tese fundacional, Sistema 1 (reflexo a 20 Hz) + Sistema 2 (cognição), as "três rupturas" (server-native em vez de Mineflayer; BT declarativa em EDN validada por Malli em vez de `eval` de JS; roteamento semântico).
3. `docs/03-comportamento-e-cognicao-llm.md`: a tríade Voyager (currículo automático, prompting iterativo com crítico de ambiente, skill library), `compose_and_save_skill`.
4. `docs/09-backlog-rsi-curriculum-agent.md`: currículo, curiosidade intrínseca, RSI seguro em malha fechada.
5. `docs/02-arquitetura-e-plano-craft-agents.md` (com a seção NPC Core), `docs/11-mineflayer-vs-server-native.md`, `docs/13-backlog-npc-core.md` e `docs/plans/sprint-npc-core-01-pendencias.md`: arquitetura atual e dívidas registradas.
6. `docs/05-roteiro-video-craft-agents.md`, ato IV: a promessa pública ("experimento Voyager em escala multiplayer: dezenas de agentes autônomos com currículo aberto").

#### Estado conhecido (ponto de partida, verifique no código)

- **NPC Core** (sprint npc-core-01, CA-084…096): `src/clj/craft/agents/npc/{registry,spawn,state,addressing,speech}.clj`, `goals/dispatch.clj` (`execute-goal!`), `engine/follow.clj` (`tick-follow!`), sobrevivência/`combat-decision` em `engine/combat.clj`, BT por agente em `engine/bt_tick.clj` + `goals/bt_actions.clj` (`defmulti bt-action`).
- **Agentes**: `agents/karlach.edn`, `picard.edn`, `coco.edn`, `richeh.edn`, `antigravity.edn`. Karlach e Picard têm ticks dedicados (`engine/karlach_tick.clj`, `picard_tick.clj`).
- **Ateliê (Coco e Richeh)**: controlador paralelo em `backend/fabric/atelier/behavior.clj` (máquinas de estado escritas à mão com o nome de cada uma, waypoints fixos, movimento em linha reta por `MOVE` sem A*). As árvores `:bt/root` dos `.edn` delas declaram ~9 ações e só `face-entity`/`social-gaze` existem; o resto cai no `:default`. Em 2026-11-14 (`8678fc8`), `:coco` entrou em `bt_tick/self-driven` para acabar com dois controladores mandando yaw (a cabeça girava em loop), e com isso a BT dela parou de rodar.
- **Corpo**: `ServerPlayerEntity` sintético movido por teleporte (`MOVE` em `src/java/.../SyntheticPlayerFactory.java`), sem física. A paridade de física (op `STEP`, CA-068…079, doc 12) está pendente.
- **Leases** `:legs/:hands/:gaze` existem (`leases.clj`, CA-029), mas os ticks dedicados mandam `MOVE` sem passar por eles.

#### Perguntas que o relatório tem de responder

1. **Aderência à tese:** o que do Voyager (currículo, crítico, skill library, auto-verificação) existe de fato no código, rodando, e o que é só doc? Monte uma tabela promessa → evidência (`arquivo:linha`) → status (rodando / implementado sem uso / só doc).
2. **Escalabilidade:** o que impede ir de 5 para dezenas de agentes? Considere: o loop único de 20 Hz e o custo por tick (consultas à ponte por agente, `query-online-players` repetido), a ponte texto/TCP (`transport`), o estado global em atoms, o Studio/broadcast, o custo de LLM por agente via small-router e o uso de memória. Aponte gargalos com evidência e estime ordem de grandeza quando der.
3. **Extensibilidade:** quanto custa criar um agente novo hoje (o que é só `.edn` e o que exige Clojure/Java)? Avalie `defmulti bt-action`/guards, `execute-goal!`, capabilities e o caminho para skills aprendidas (`skills/*.edn`, Malli gatekeeper) entrarem na BT de qualquer agente.
4. **Individualidade:** como personalidade, voz, traços, rotina, relações (dupla Coco/Richeh) e memória deveriam viver em dados, sem código por personagem? Proponha um modelo: o que fica no `.edn`, o que é comportamento compartilhado, o que é aprendido. Como garantir que dois agentes com o mesmo núcleo se comportem de forma reconhecivelmente diferente?
5. **Controle único do corpo:** proponha a arquitetura que torna impossível dois controladores no mesmo atuador (o bug da Coco): leases obrigatórios no envio de `MOVE`, um árbitro por agente, prioridades.
6. **Ateliê → NPC Core:** plano de migração de Coco e Richeh (implementar as ações declaradas como `bt-action` genéricos, follow/sobrevivência do núcleo, remover `step-*-tick!`) e o que isso habilita.
7. **Caminho até o Voyager multiplayer:** sequência de marcos para currículo aberto + skill library compartilhada entre agentes, com riscos (custo de LLM, segurança, determinismo, testes que dependem de servidor vivo).

#### Formato obrigatório de `docs/reports/arch-review-01.md`

1. **Resumo executivo** (≤ 15 linhas): diagnóstico e as 5 recomendações mais importantes.
2. **Objetivo e conceitos**: a tese Voyager-na-JVM em palavras próprias e os conceitos centrais (Sistema 1/2, BT-como-dado, skill library, server-native).
3. **Mapa da arquitetura atual**: camadas e fluxo tick → BT/metas → ponte → Java, com um diagrama mermaid.
4. **Aderência à tese** (pergunta 1, em tabela).
5. **Escalabilidade**, 6. **Extensibilidade**, 7. **Individualidade**, 8. **Controle do corpo** (perguntas 2–5): em cada uma, achados com evidência, riscos e sugestões.
9. **Sugestões priorizadas**: tabela ID (`SUG-01`…) · sugestão · impacto · esforço (P/M/G) · risco · dependências.
10. **Roadmap de sprints**: 3 a 5 sprints em ordem (inclui a migração do ateliê e a paridade de física CA-068…079), cada uma com objetivo, escopo e critério de pronto testável.
11. **Questões em aberto**: decisões que dependem do Patrick.

Regras: português do Brasil; identificadores e código sem tradução; nada inventado (o que não foi verificado vai marcado como inferência); sugestões compatíveis com o protocolo do repo (teste antes, testes travados, LLM só via small-router em `http://localhost:8081`).

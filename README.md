# ⚡ craft-agents

> **Next-Gen Server-Native Cognitive Architecture & Embodied AI Engine for Minecraft (Clojure 1.12 + Java 21 / Fabric 1.21.1).**

O **Craft Agents** é um framework e sistema operacional cognitivo para agentes virtuais autônomos dentro do Minecraft. Ele une a robustez funcional do **Clojure**, a alta performance do **Java 21**, uma arquitetura dual inspirada em Kahneman (Sistema 1 Reativo + Sistema 2 Evolutivo), persistência de habilidades estilo **NVIDIA Voyager** com segurança estática formal (**Malli Gatekeeper sem `eval`**) e observabilidade em tempo real com **Craft Agents Studio** e **Model Context Protocol (MCP)**.

---

## 🧭 A Tese Central

> *"A inteligência corporificada (Embodied AI) em mundos complexos não pode depender exclusivamente de LLMs em tempo de execução (muito lentos, caros e propensos a falhas de segurança via `eval`), nem de máquinas de estados rígidas (incapazes de adaptação semântica).*
> 
> *A verdadeira autonomia emerge de uma **Arquitetura Dual (Kahneman Sistema 1 + Sistema 2)** executada de forma **Server-Native (in-process a 20Hz)**, onde reflexos motores sub-milissegundo garantem sobrevivência e física contínua, enquanto a síntese declarativa de árvores de comportamento (Behavior Trees) validadas formalmente constrói uma biblioteca cumulativa de habilidades sem jamais executar código arbitrário inseguro."*

---

## 🏛️ Visão Geral da Arquitetura

```
+-------------------------------------------------------------------------+
|                        CRAFT AGENTS STUDIO (:8095)                      |
|       Radar 3D  •  Voyager Skill Library  •  Live Cognitive Feed        |
+------------------------------------+------------------------------------+
                                     ^ WebSockets
                                     |
+------------------------------------+------------------------------------+
|                SISTEMA 2: COGNIÇÃO EVOLUTIVA & SÍNTESE                  |
|  • Chat Skill Synthesizer: Linguagem Natural -> AST EDN                |
|  • Malli Gatekeeper: Validação Estrutural Fail-Closed (Zero eval)      |
|  • Voyager Skill Library: Repositório Reutilizável (skills/*.edn)       |
|  • Skill-RAG: Recuperação Vetorial Few-Shot                             |
|  • Environmental Critic: Auditoria de Ground Truth & Early Abort        |
|  • Model Context Protocol (MCP): Servidor JSON-RPC 2.0 Padrão           |
+------------------------------------+------------------------------------+
                                     | Compilação & Auto-Roteamento
                                     v
+-------------------------------------------------------------------------+
|                SISTEMA 1: MOTOR REATIVO SERVER-NATIVE (20Hz)            |
|  • Semantic Router: Similaridade Vetorial de Cosseno (< 1ms)            |
|  • Behavioral Leases: Arbitragem de Canais (Pernas, Mãos, Olhar)        |
|  • PathSolver3D: Navegação A* Tridimensional Contínua                   |
|  • Seat Manager: Raycast 3D e Montagem de Mobília sem Mods Extras       |
|  • Escolta Ativa & Sentinela: Detecção Proativa de Fugas no Santuário   |
+------------------------------------+------------------------------------+
                                     | Execução de Baixo Nível
                                     v
+-------------------------------------------------------------------------+
|              MINECRAFT FABRIC SERVER 1.21.1 (JAVA 21 / JVM)             |
|          ServerPlayerEntity  •  SilentEmbeddedChannel  •  20 TPS        |
+-------------------------------------------------------------------------+
```

---

## ✨ Principais Diferenciais

* **🚀 Server-Native (Superação do Mineflayer):** Opera dentro da JVM do servidor Minecraft sobre `ServerPlayerEntity` com `SilentEmbeddedChannel`. Sem latência TCP, sem limites artificiais de pacotes, consumo `< 500 KB/bot` e física precisa no tick rate de 20Hz.
* **🛡️ Malli Gatekeeper (Superação do NVIDIA Voyager):** Zero `eval()` ou geração de strings de script inseguras. Habilidades são geradas como árvores abstratas (ASTs) de Behavior Tree em EDN, validadas estaticamente por esquemas Malli, com verificação de ciclos infinitos (DFS) e sandbox mental de pré-condições.
* **💬 Chat Skill Synthesizer:** Ensine habilidades novas diretamente no chat do jogo em linguagem natural (`!karlach aprenda danca-e-senta: dance e sente no sofa`). O compilador valida e grava em `skills/<id>.edn` na hora.
* **🗣️ Roteamento Semântico em Linguagem Natural:** Roteamento por similaridade de cossenos em subwords (< 1ms) permitindo comandos livres, variações e gírias sem necessidade de `/` ou sintaxes pré-definidas.
* **🛋️ Seat Manager & Raycasting 3D:** Identificação espacial ativa de blocos de sofá e cadeiras, sentando e levantando sem demandar mods de terceiros no cliente.
* **📡 Craft Agents Studio (`:8095`):** Dashboard web responsivo em tempo real com Radar 3D interativo, feeds de decisão cognitiva e biblioteca visual de skills.
* **🔌 Suporte Nativo a MCP (Model Context Protocol):** Servidor JSON-RPC 2.0 padrão da indústria para controle e inspeção por ferramentas de IA externas (Claude Desktop, Cursor, Antigravity).

---

## 🤖 Agentes Operacionais Ativos

### Karlach — Guardiã Infernal do Santuário
* **Patrulha e Cuidados:** Ronda perimetral e alimentação contínua de animais (capivaras, cervos, elefantes, pandas, tatus e gatos).
* **Sentinela Proativa & Resgate:** Detecta ativamente animais fugitivos durante a patrulha e conduz de volta para seus cercados com iscas e laço.
* **Reprodução AFK de Gatos:** Gerencia peixes frescos, ativação de postura, namoro (`InLove`), aceleração de filhotes e trava populacional anti-lag.
* **Mineração Cooperativa de Diamantes:** Acompanha jogadores no subsolo, escava veios na mira ou detectados e ejeta o saque no inventário do jogador.
* **Interação Social:** Senta ao lado dos jogadores no sofá, celebra com danças de Avernus e interage com personalidade rica.

### Capitão Jean-Luc Picard — Oficial de Comando Orbital
* **Ponte da USS Enterprise-D:** Posto de comando orbital em altitude `Y=145`.
* **Matriz Meteorológica:** Diagnóstico de clima e horário solar, além de controle climático (*"Make it so!"*).
* **Away Team:** Acompanhamento de jogadores em expedições na superfície com retorno disciplinado ao posto.

---

## 🚀 Comandos Rápidos

```bash
# Compilar contratos e módulos nativos Java 21
make compile-java

# Executar toda a suíte de testes TDD (75 suítes / 1005 asserções)
make test

# Executar apenas um épico específico (ex: Épico 3 - Navegação 3D)
make test-epic EPIC=3

# Iniciar o Craft Agents Studio na porta 8095
make studio
```

---

## 📚 Documentação Completa

| Documento | Descrição |
| :--- | :--- |
| [01 — Análise Mineflayer e Mercado](docs/01-analise-mineflayer-e-mercado.md) | Diagnóstico de limitações do Mineflayer e oportunidade técnica. |
| [02 — Arquitetura e Plano `craft-agents`](docs/02-arquitetura-e-plano-craft-agents.md) | Arquitetura de microsserviços, JVM in-process e protocolos. |
| [03 — Comportamento e Cognição com LLM](docs/03-comportamento-e-cognicao-llm.md) | Integração da Tríade Voyager e decomposição de tarefas. |
| [04 — Backlog de Implementação (`CA-001` a `CA-052`)](docs/04-backlog-implementacao.md) | Especificação detalhada de cada requisito dos Épicos 0 a 7. |
| [05 — Roteiro de Vídeo Técnico](docs/05-roteiro-video-craft-agents.md) | Script de apresentação e demonstração pública do projeto. |
| [06 — Matriz TDD e Especificação de Testes](docs/06-matriz-tdd-e-especificacao-testes.md) | Mapeamento de asserções comportamentais e cobertura. |
| [07 — Primitivas Cinemáticas Mineflayer na JVM](docs/07-plano-primitivas-cinematicas-mineflayer-jvm.md) | Portabilidade e otimização das primitivas de controle cinemático. |
| [08 — Tese, Arquitetura Cognitiva e Capacidades](docs/08-tese-arquitetura-cognitiva-e-capacidades.md) | **Manifesto fundacional, capacidades atuais e visão científica.** |
| [09 — Backlog: Curriculum Agent & RSI](docs/09-backlog-rsi-curriculum-agent.md) | **Engenharia de autoaperfeiçoamento recursivo, curiosidade e Dreamer.** |

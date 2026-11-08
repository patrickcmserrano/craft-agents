# 08 — Tese Fundacional, Arquitetura Cognitiva e Capacidades Operacionais
*Data: Novembro de 2026*  
*Versão: 1.0 (Fabric 1.21.1 / Clojure 1.12 / Java 21)*  
*Status: Operacional em Produção com 1005 Asserções TDD GREEN*

---

## 1. A Tese do Craft Agents

> **"A inteligência corporificada (Embodied AI) em mundos simulados complexos não pode depender exclusivamente de LLMs em tempo de execução (muito lentos, caros e propensos a falhas de segurança via `eval`), nem de máquinas de estados rígidas (incapazes de adaptação semântica e generalização).**
>
> **A verdadeira autonomia emerge de uma Arquitetura Dual (Kahneman Sistema 1 + Sistema 2) executada de forma Server-Native (in-process a 20Hz), onde reflexos motores sub-milissegundo garantem sobrevivência e física contínua, enquanto a síntese declarativa de árvores de comportamento (Behavior Trees) validadas formalmente constrói uma biblioteca cumulativa de habilidades sem jamais executar código arbitrário inseguro."**

### As Três Rupturas com o Mercado
1. **Server-Native vs. Clientes TCP Externos (Superação do Mineflayer):**
   * *Abordagem Tradicional:* Bots externos conectando via socket de rede simulando jogadores, com latência TCP, desync físico constante e dependência do Node.js.
   * *Abordagem Craft Agents:* Execução direta dentro da JVM do servidor Minecraft (Fabric 1.21.1) sobre `ServerPlayerEntity` com `SilentEmbeddedChannel`. Os agentes operam no mesmo tick de 50ms (20Hz) do motor do jogo, com footprint `< 500 KB/bot`.
2. **Behavior Trees Declarativas vs. Insegurança do `eval` (Superação do NVIDIA Voyager):**
   * *Abordagem Tradicional (NVIDIA Voyager):* O LLM gera código JavaScript arbitrário executado em tempo de execução via `eval()`, expondo o ambiente a loops infinitos, exceções não tratadas e vulnerabilidades graves de segurança.
   * *Abordagem Craft Agents:* **Zero `eval`**. Todas as habilidades aprendidas são representadas como Árvores de Comportamento (Behavior Trees) declarativas em dados imutáveis EDN, submetidas ao **Malli Gatekeeper** com validação de esquema estático, verificação de ciclos (DFS) e sandbox mental de pré-condições.
3. **Roteamento Semântico Contínuo vs. Comandos Rígidos:**
   * Usuários interagem em linguagem natural coloquial. O roteador semântico calcula similaridade de cosseno em espaço esparso de subwords em menos de **1ms**, eliminando a necessidade de comandos sintáticos (`/sit`, `/follow`).

---

## 2. A Arquitetura Cognitiva Implementada

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

## 3. Capacidades Operacionais dos Agentes Ativos

### Karlach — Guardiã Infernal e Cuidadora do Santuário
* **Patrulha e Cuidado Territorial:** Ronda contínua com monitoramento perimetral de cercados com capivaras, cervos, elefantes, pandas, tatus e gatos.
* **Sentinela Proativa & Resgate:** Identificação autônoma de animais fora dos cercados, atração com alimento adequado, condução via laço e tranca de portão.
* **Reprodução AFK de Gatos & Animais:** Protocolo de alimentação com bacalhau cru (`minecraft:cod`), ativação de `InLove`, aceleração de filhotes e trava populacional anti-lag.
* **Mineração Cooperativa de Diamantes:** Acompanhamento no subsolo, escavação com picareta de Netherite guiada por mira (*crosshair*) ou varredura de veios, e entrega de saque via drop de inventário.
* **Interação Social & Mobília:** Raycasting 3D nativo para localização de sofás e bancos, repouso ao lado de jogadores e danças de Avernus.

### Capitão Jean-Luc Picard — Oficial de Comando Orbital
* **Posto de Comando na USS Enterprise-D:** Localizado nas coordenadas orbitais `[-65, 145, 101]`.
* **Matriz Meteorológica & Relógio Solar:** Diagnóstico de tempo e fase do dia, dispersão de tempestades com o comando estelar (*"Make it so!"*).
* **Away Team:** Acompanhamento de jogadores em expedições na superfície com protocolo de retorno à ponte.

---

## 4. O Sistema de Aprendizado via Chat (Chat Skill Synthesizer)

O jogador pode ensinar novas rotinas para o bot digitando no chat do jogo:

```text
!karlach aprenda danca-e-senta: dance e sente no sofa
```

### Ciclo de Vida da Habilidade Aprendida:
1. **Extração de Intenção:** O Semantic Router detecta o padrão de ensino (`teach-pattern`) e extrai a instrução sem disparar as ações antecipadamente.
2. **Decomposição Semântica:** A frase em linguagem natural é dividida em passos e mapeada para nós de ação válidos da AST (`[:sequence [:action :social-dance] [:action :sit-furniture]]`).
3. **Malli Gatekeeper:** A árvore é submetida a verificação estática:
   * Compatibilidade com `schemas/BTNode`.
   * Verificação de que todas as ações pertencem ao catálogo seguro.
   * Ausência de ciclos recursivos (DFS).
   * Verificação mental de pré-condições de itens e ambiente.
4. **Persistência em Disco:** O mapa da skill é gravado em `skills/<id>.edn`.
5. **Auto-Roteamento:** O Semantic Router compila novas âncoras vetoriais em tempo de execução e salva em `agents/routes/<id>.edn`.
6. **Telemetria:** O Agent Studio recebe a nova skill via WebSocket instantaneamente.
7. **Invocação:** O jogador fala `"karlach, danca e senta"` e ela executa os passos da AST sequencialmente.

---

## 5. Craft Agents Studio & MCP

* **Studio (`http://localhost:8095`):** Servidor HTTP-Kit integrado com WebSockets em broadcast contínuo transmitindo ticks, leases, inventários, clima, radar 3D e a Voyager Skill Library com visualização de árvores.
* **Model Context Protocol (MCP JSON-RPC 2.0):** Endpoints padronizados para ferramentas externas (`reply_and_gesture`, `set_directive`, `run_task_sequence`, `compose_and_save_skill`), permitindo integração com Claude Desktop, Cursor e outros agentes.

---

## 6. Métricas de Engenharia e Confiabilidade

* **Suíte TDD:** 75 suítes automatizadas cobrindo todos os épicos de infraestrutura, navegação, leases, cognição, homeostase e integração.
* **Asserções:** **1005 asserções 100% GREEN (0 falhas, 0 erros)**.
* **Estabilidade:** Execução contínua como serviço systemd (`craft-agents.service`) a 20 TPS estáveis no servidor Fabric 1.21.1.

---

## 7. Próxima Fronteira: O Curriculum Loop Fechado

O horizonte científico subsequente visa fechar a malha de autonomia total:
* **Mecanismo de Curiosidade Intrinseca:** Em períodos de ócio, o agente inspeciona seu inventário e o ambiente, gera hipóteses de objetivos inéditos no estilo MineDojo/Voyager, executa testes práticos no mundo, reflete com o Environmental Critic sobre eventuais falhas e enriquece a biblioteca com novas habilidades sem nenhuma supervisão humana.

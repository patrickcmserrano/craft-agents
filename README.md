# ⚡ craft-agents

> **Next-Gen Dual-Backend Autonomous Agent & Embodied AI Engine for Minecraft (Clojure 1.12 + Java 21/25).**

Projetado para substituir e superar o Mineflayer em memória (`< 500 KB/bot` no modo Server-Native), compatibilidade com servidores modded, navegação 3D (`PathSolver3D` zero-allocation com portais semânticos atômicos) e integração segura com IA via **MCP** (sem `eval()`).

## 🚀 Comandos TDD

```bash
# Compilar contratos e módulos Java 21
make compile-java

# Rodar toda a suíte TDD (Épicos 0 a 7 / CA-001 a CA-052)
make test

# Rodar apenas um épico específico (ex: Épico 3 — Navegação 3D)
make test-epic EPIC=3

# Ver matriz resumida de progresso TDD (GREEN vs RED-PENDING vs FAIL)
make tdd-status
```

## 📚 Documentação de Engenharia
- [01 — Análise Mineflayer e Mercado](docs/01-analise-mineflayer-e-mercado.md)
- [02 — Arquitetura e Plano `craft-agents`](docs/02-arquitetura-e-plano-craft-agents.md)
- [03 — Comportamento e Cognição com LLM](docs/03-comportamento-e-cognicao-llm.md)
- [04 — Backlog Detalhado (`CA-001` a `CA-052`)](docs/04-backlog-implementacao.md)
- [05 — Roteiro de Vídeo Técnico](docs/05-roteiro-video-craft-agents.md)
- [06 — Matriz TDD e Especificação de Testes](docs/06-matriz-tdd-e-especificacao-testes.md)

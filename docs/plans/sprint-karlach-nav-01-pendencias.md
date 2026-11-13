# Pendências da sprint karlach-nav-01

## CA-063 — verificação ao vivo do op `GROUND`
- Doc 11 §8, item 3: enviar `GROUND|x|y|z` à ponte in-server real (porta 25595) com o servidor Fabric no ar
  e conferir `OK|<y>` numa coluna enterrada/no ar e `ERR|NO_GROUND` quando não há chão em +4/-6.
- Não executado: exige servidor e tráfego reais, fora da worktree. O op ainda precisa ser carregado no
  servidor vivo (recompilar `target/classes` e reiniciar/`REDEFINE_CLASS` conforme o caso).

## CA-067 — Picard fora do escopo
- `picard-state` usa a mesma linha reta (y do alvo), mas Picard anda na ponte plana da Enterprise,
  sem desníveis; por isso não foi migrado para `locomotion/walk-step`. Reavaliar se a ponte ganhar degraus.

## Implantação (2026-11-12, ~21:29) — pendências levadas para a sprint `karlach-nav-02`
- Parte Clojure (CA-064..CA-067 e o watchdog) no ar após `systemctl --user restart craft-agents`.
- Parte Java (CA-062, CA-063) **inativa**: `PathSolver3D` não pôde ser redefinido a quente
  ("attempted to add a method"); `PATH` enterrado → `ERR|NO_PATH`, `GROUND` → `ERR|null`. → **CA-080**.
- Modo mineração ainda com descida antecipada (`engine.clj`, `(<= ndist 0.4)`). → **CA-081**.
- Verificação ao vivo (doc 11 §8) e push de `main`. → **CA-082** (manual).
- Picard fora do escopo da CA-067. → **CA-083**.

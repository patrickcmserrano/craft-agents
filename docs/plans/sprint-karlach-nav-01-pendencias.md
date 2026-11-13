# Pendências da sprint karlach-nav-01

## CA-063 — verificação ao vivo do op `GROUND`
- Doc 11 §8, item 3: enviar `GROUND|x|y|z` à ponte in-server real (porta 25595) com o servidor Fabric no ar
  e conferir `OK|<y>` numa coluna enterrada/no ar e `ERR|NO_GROUND` quando não há chão em +4/-6.
- Não executado: exige servidor e tráfego reais, fora da worktree. O op ainda precisa ser carregado no
  servidor vivo (recompilar `target/classes` e reiniciar/`REDEFINE_CLASS` conforme o caso).

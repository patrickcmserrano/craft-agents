#!/usr/bin/env python3
"""Le o estado VIVO de uma entidade do servidor pelo nREPL in-server (porta 7890).

Existe porque nem a ponte 25595/PLAYERS nem o nREPL do engine (7891) medem o
estado da entidade: a 7890 roda DENTRO da JVM do servidor, no KnotClassLoader, e
por isso alcanca `net.minecraft.server.MinecraftServer` e a entidade real.

Le `Pos`, `Motion`, `Pose`, `onGround` e `inWater`, e marca as amostras que
mudaram — que e o que distingue "anda" de "esta presa".

Uso:
    scripts/entity_state.py Karlach
    scripts/entity_state.py Karlach --n 6 --interval 1.0
    scripts/entity_state.py --all
"""
import argparse
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from nrepl import eval_code  # noqa: E402

# O ns `user` da sessao nao tem clojure.core referido. Em vez de qualificar tudo,
# carregamos o core na propria sessao (caminho curto e legivel).
PRELUDE = "(clojure.core/require 'clojure.core 'clojure.string)"

# Acha o servidor pelos threads (mesmo caminho do resolveServerInstance) e le a
# entidade pelo nome no player manager.
STATE_FORM = """
(let [srv (first (keep (fn [t]
              (when (re-find #"Server thread" (.getName t))
                (first (keep (fn [f]
                        (try (do (.setAccessible f true)
                                 (let [v (.get f t)]
                                   (when (instance? net.minecraft.server.MinecraftServer v) v)))
                             (catch Throwable _ nil)))
                      (seq (.getDeclaredFields (class t)))))))
            (keys (Thread/getAllStackTraces))))
      pm  (when srv (let [m (.getMethod (class srv) "method_3760" (into-array Class []))]
                      (.invoke m (into-array Object [srv]))))
      ps  (when pm (let [m (.getMethod (class pm) "method_14571" (into-array Class []))]
                     (seq (.invoke m (into-array Object [pm])))))
      nm  (fn [p] (let [m (.getMethod (class p) "method_7334" (into-array Class []))]
                    (.getName (.invoke m (into-array Object [p])))))
      e   (first (filter (fn [p] (= %s (nm p))) ps))]
  (if e
    (let [mm (fn [n] (.getMethod (class e) n (into-array Class [])))
          g  (fn [n] (.invoke (mm n) (into-array Object [e])))
          gs (fn [n] (str (g n)))]
      (str "pos=[" (gs "method_23317") "," (gs "method_23318") "," (gs "method_23321") "]"
           " motion=" (str (.getVelocity e))
           " pose=" (.getPose e)
           " onGround=" (gs "method_24828")
           " inWater=" (gs "method_5799")
           " sneaking=" (gs "method_5715")))
    "ENTITY_NOT_FOUND"))
"""


def probe(name, port, timeout):
    out, err, val = eval_code("127.0.0.1", port, STATE_FORM % ('"%s"' % name),
                              timeout, prelude=PRELUDE)
    if err.strip():
        return "ERR " + err.strip().splitlines()[0][:180]
    return out.strip() if out.strip() else str(val)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("entity", nargs="?", default="Karlach")
    ap.add_argument("--port", type=int, default=7890)
    ap.add_argument("--n", type=int, default=1)
    ap.add_argument("--interval", type=float, default=1.0)
    ap.add_argument("--timeout", type=float, default=20)
    ap.add_argument("--all", action="store_true", help="todos os agentes")
    a = ap.parse_args()

    names = ["Karlach", "Picard", "Coco", "Richeh"] if a.all else [a.entity]
    prev = {n: None for n in names}
    for i in range(a.n):
        for n in names:
            line = probe(n, a.port, a.timeout)
            mark = ""
            if prev[n] is not None and line != prev[n]:
                mark = "   <-- MUDOU"
            print("[%d] %-8s %s%s" % (i, n, line, mark))
            prev[n] = line
        if i < a.n - 1:
            time.sleep(a.interval)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Cliente nREPL minimo para o craft-agents (portas 7891 engine / 7890 in-server).

Medido em 2026-11-10 (nREPL 1.3.0):
- **NAO envie `id`.** Esta versao TRAVA a resposta quando a mensagem tem `id`
  (medido: `{"op":"clone","id":"c"}` -> timeout com zero bytes; o MESMO payload
  sem `id` responde na hora). Casamos as respostas pela `session`, nao por id.
- clone ANTES do eval e obrigatorio; mensagem para sessao inexistente e
  descartada em silencio pelo nREPL.
- bencode usa comprimento em BYTES.
- o ns `user` da sessao NAO tem clojure.core referenciado: qualifique
  (clojure.core/println) ou faca o require.
- o erro vem no campo `err` com status `eval-error`; um cliente que so olha
  value/done sai antes de ler e devolve vazio.

Uso:
    scripts/nrepl.py '(+ 1 2)'
    scripts/nrepl.py --port 7890 '(craft.agents.../foo)'
    scripts/nrepl.py --file sonda.clj
"""
import argparse
import socket
import sys
import time


def _s(v):
    """O nREPL bencode devolve strings como bytes UTF-8."""
    if isinstance(v, bytes):
        return v.decode("utf-8", "replace")
    return str(v)


def ben(o):
    if isinstance(o, bytes):
        return b"%d:" % len(o) + o
    if isinstance(o, str):
        return ben(o.encode())
    if isinstance(o, int):
        return b"i%de" % o
    if isinstance(o, list):
        return b"l" + b"".join(ben(x) for x in o) + b"e"
    if isinstance(o, dict):
        return b"d" + b"".join(ben(k) + ben(v) for k, v in o.items()) + b"e"
    raise TypeError(type(o))


def bdec(buf, i=0):
    c = buf[i : i + 1]
    if c == b"i":
        j = buf.index(b"e", i)
        return int(buf[i + 1 : j]), j + 1
    if c == b"l":
        i += 1
        out = []
        while buf[i : i + 1] != b"e":
            v, i = bdec(buf, i)
            out.append(v)
        return out, i + 1
    if c == b"d":
        i += 1
        out = {}
        while buf[i : i + 1] != b"e":
            k, i = bdec(buf, i)
            v, i = bdec(buf, i)
            out[k.decode() if isinstance(k, bytes) else k] = v
        return out, i + 1
    j = buf.index(b":", i)
    n = int(buf[i:j])
    return buf[j + 1 : j + 1 + n], j + 1 + n


def read_msgs(sock, timeout, where=None):
    """Le mensagens bencode. NAO filtra por id (o nREPL 1.3.0 trava com id).

    if where: para quando a resposta for da `session` em `where`.
    Retorna (out, err, val, msgs)."""
    out, err, val, buf, msgs = [], [], None, b"", []
    deadline = time.time() + timeout
    sock.settimeout(timeout)
    while time.time() < deadline:
        try:
            chunk = sock.recv(65536)
        except socket.timeout:
            break
        if not chunk:
            break
        buf += chunk
        while buf:
            try:
                msg, n = bdec(buf)
            except (ValueError, IndexError):
                break
            buf = buf[n:]
            msgs.append(msg)
            if "out" in msg:
                out.append(_s(msg["out"]))
            if "err" in msg:
                err.append(_s(msg["err"]))
            if "value" in msg:
                val = _s(msg["value"])
            if where is not None and _s(msg.get("session")) == where:
                st = msg.get("status")
                if st and ("done" in st or "eval-error" in st):
                    return "".join(out), "".join(err), val, msgs
    return "".join(out), "".join(err), val, msgs


def eval_code(host, port, code, timeout=25):
    s = socket.create_connection((host, port), timeout=timeout)
    # clone sem id — com id o nREPL 1.3.0 nao responde (medido 2026-11-10).
    s.sendall(ben({"op": "clone"}))
    buf, session = b"", None
    s.settimeout(timeout)
    deadline = time.time() + timeout
    while session is None:
        if time.time() > deadline:
            raise RuntimeError("timeout esperando o clone")
        chunk = s.recv(65536)
        if not chunk:
            raise RuntimeError("conexao fechada durante o clone")
        buf += chunk
        while buf:
            try:
                msg, n = bdec(buf)
            except (ValueError, IndexError):
                break
            buf = buf[n:]
            if "new-session" in msg:
                session = _s(msg["new-session"])
    s.sendall(ben({"op": "eval", "session": session, "code": code}))
    out, err, val, _ = read_msgs(s, timeout, where=session)
    s.close()
    return out, err, val


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=7891)
    ap.add_argument("--timeout", type=float, default=25)
    ap.add_argument("--file", help="le o codigo de um arquivo")
    ap.add_argument("code", nargs="*")
    a = ap.parse_args()

    code = open(a.file).read() if a.file else " ".join(a.code)
    if not code.strip():
        ap.print_help()
        return 1

    out, err, val = eval_code(a.host, a.port, code, a.timeout)
    if err.strip():
        print("--- ERR ---")
        print(err.rstrip())
    if out.strip():
        print(out.rstrip())
    if val is not None:
        print("=> %s" % val)
    return 0


if __name__ == "__main__":
    sys.exit(main())

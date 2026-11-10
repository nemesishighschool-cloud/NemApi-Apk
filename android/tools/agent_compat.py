#!/usr/bin/env python3
"""Compatibilité « agent de codage » avec l'API NemApi.

Reproduit ce que font Cline / Roo / Continue / Cursor quand ils pointent sur une
API OpenAI locale :

  1. GET  /v1/models                        → liste des modèles
  2. POST /v1/chat/completions  stream=true → conversation système + code
  3. POST /v1/chat/completions  avec `tools` + `tool_choice` (doit être toléré)
  4. POST /v1/chat/completions  long contexte (fichier source de ~4 000 c.)
  5. POST /v1/responses                     → variante « Responses API »

Usage : python3 agent_compat.py [--host 127.0.0.1] [--port 8090]
"""
import argparse
import json
import time
import urllib.error
import urllib.request

OK = "OK"
KO = "ÉCHEC"


def call(base, path, payload=None, timeout=300):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(base + path, data=data, headers={
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
    })
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        ctype = r.headers.get("Content-Type", "")
        if "event-stream" in ctype:
            text, chunks = "", 0
            for line in r:
                s = line.decode("utf-8", "replace").strip()
                if not s.startswith("data:"):
                    continue
                p = s[5:].strip()
                if p == "[DONE]":
                    break
                try:
                    j = json.loads(p)
                except Exception:
                    continue
                if j.get("error"):
                    raise RuntimeError(j["error"].get("code") or j["error"])
                d = (j.get("choices") or [{}])[0].get("delta") or {}
                if d.get("content"):
                    text += d["content"]
                    chunks += 1
            return {"kind": "sse", "text": text, "chunks": chunks, "secs": round(time.time() - t0, 2)}
        body = json.loads(r.read().decode())
        return {"kind": "json", "body": body, "secs": round(time.time() - t0, 2)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8090)
    args = ap.parse_args()
    base = f"http://{args.host}:{args.port}"
    results = []

    # 1. modèles
    try:
        with urllib.request.urlopen(base + "/v1/models", timeout=15) as r:
            models = [m["id"] for m in json.loads(r.read().decode()).get("data", [])]
        ok = "deepseek-chat" in models
        results.append(("GET /v1/models", ok, f"{models}"))
    except Exception as e:
        results.append(("GET /v1/models", False, str(e)))

    # 2. agent : consigne système + demande de code, en stream
    system = (
        "Tu es un agent de codage. Réponds uniquement par le code demandé, "
        "sans explication, dans un bloc de code."
    )
    user = "Écris une fonction Kotlin `safeDiv(a: Int, b: Int): Int?` qui renvoie null si b == 0."
    try:
        out = call(base, "/v1/chat/completions", {
            "model": "deepseek-chat", "stream": True, "temperature": 0.2, "max_tokens": 300,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        })
        ok = bool(out["text"].strip()) and ("safeDiv" in out["text"] or "fun" in out["text"])
        results.append(("POST chat stream + system", ok,
                        f"{out['chunks']} fragments, {len(out['text'])} c., {out['secs']} s"))
    except Exception as e:
        results.append(("POST chat stream + system", False, str(e)))

    # 3. champs d'outils : un agent envoie `tools`, l'API doit rester utilisable
    try:
        out = call(base, "/v1/chat/completions", {
            "model": "deepseek-chat", "stream": True,
            "tools": [{
                "type": "function",
                "function": {
                    "name": "read_file",
                    "description": "Lit un fichier du projet",
                    "parameters": {"type": "object", "properties": {"path": {"type": "string"}},
                                   "required": ["path"]},
                },
            }],
            "tool_choice": "auto",
            "messages": [{"role": "user", "content": "Quel fichier contient le point d'entrée d'une app Android ? Une phrase."}],
        })
        ok = bool(out["text"].strip())
        results.append(("requête avec `tools`", ok, f"{len(out['text'])} c., {out['secs']} s"))
    except urllib.error.HTTPError as e:
        results.append(("requête avec `tools`", False, f"HTTP {e.code} : {e.read()[:120]}"))
    except Exception as e:
        results.append(("requête avec `tools`", False, str(e)))

    # 4. long contexte (comme un fichier source collé par un agent)
    code = "\n".join(f"fun f{i}(x: Int) = x * {i}  // ligne {i}" for i in range(120))
    try:
        out = call(base, "/v1/chat/completions", {
            "model": "deepseek-chat", "stream": True,
            "messages": [
                {"role": "system", "content": "Tu analyses du code Kotlin."},
                {"role": "user", "content": f"Ce fichier contient combien de fonctions ? Réponds par un nombre.\n\n{code}"},
            ],
        })
        ok = bool(out["text"].strip())
        results.append((f"contexte long ({len(code)} c.)", ok,
                        f"{len(out['text'])} c. → « {out['text'].strip()[:40]} » ({out['secs']} s)"))
    except Exception as e:
        results.append((f"contexte long ({len(code)} c.)", False, str(e)))

    # 5. Responses API
    try:
        out = call(base, "/v1/responses", {
            "model": "deepseek-chat",
            "input": "Dis uniquement : RESPO AGENT",
        })
        txt = ""
        try:
            txt = out["body"]["output"][0]["content"][0]["text"]
        except Exception:
            txt = json.dumps(out["body"])[:80]
        results.append(("POST /v1/responses", bool(txt), f"« {txt[:40]} »"))
    except Exception as e:
        results.append(("POST /v1/responses", False, str(e)))

    print(f"{'test':32} {'état':6} détail")
    print("-" * 96)
    for name, ok, detail in results:
        print(f"{name:32} {OK if ok else KO:6} {detail}")
    ko = sum(1 for _, ok, _ in results if not ok)
    print(f"\n{len(results) - ko}/{len(results)} tests passés")
    return 1 if ko else 0


if __name__ == "__main__":
    raise SystemExit(main())

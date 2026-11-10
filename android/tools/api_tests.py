#!/usr/bin/env python3
"""Batterie de tests de l'API locale NemApi Android."""
import json
import threading
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8090"
ok = 0
ko = 0


def log(name, passed, detail=""):
    global ok, ko
    if passed:
        ok += 1
        print(f"OK   {name} {detail}")
    else:
        ko += 1
        print(f"FAIL {name} {detail}")


def post(path, payload, timeout=180):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode(),
        headers={"content-type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def stream_chunks(path, payload, stop_after=None, timeout=180):
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode(),
        headers={"content-type": "application/json"},
    )
    chunks = []
    with urllib.request.urlopen(req, timeout=timeout) as r:
        for line in r:
            s = line.decode("utf-8", "replace").strip()
            if not s.startswith("data: "):
                continue
            p = s[6:]
            if p == "[DONE]":
                break
            try:
                j = json.loads(p)
            except Exception:
                continue
            if "error" in j:
                chunks.append(("error", j["error"]))
                break
            d = (j.get("choices") or [{}])[0].get("delta") or {}
            if d.get("content"):
                chunks.append(("text", d["content"]))
            if stop_after and len(chunks) >= stop_after:
                break
    return chunks


# ---------------------------------------------------------------- 1. provider_busy
print("--- 1. concurrence (provider_busy) ---")
res = {}


def long_call():
    res["r"] = post("/v1/chat/completions", {"messages": [{"role": "user", "content": "Ecris 150 mots sur le desert."}]})


t = threading.Thread(target=long_call)
t.start()
time.sleep(1.2)
code, body = post("/v1/chat/completions", {"messages": [{"role": "user", "content": "ping"}]}, timeout=20)
try:
    j = json.loads(body)
    err = j.get("error", {}).get("code")
except Exception:
    err = None
log("2e requête refusée pendant une génération", err == "provider_busy", f"(http={code}, code={err})")
t.join(timeout=200)
first_ok = t.is_alive() is False and "choices" in (res.get("r") or (0, ""))[1]
log("1re requête aboutie", first_ok)

# ---------------------------------------------------------------- 2. annulation
print("--- 2. annulation (le client coupe) ---")
try:
    stream_chunks(
        "/v1/chat/completions",
        {"stream": True, "messages": [{"role": "user", "content": "Ecris 200 mots sur la montagne."}]},
        stop_after=1,
        timeout=60,
    )
    log("flux coupé par le client", True)
except Exception as e:
    log("flux coupé par le client", False, str(e))
time.sleep(3)
code, body = post("/v1/chat/completions", {"messages": [{"role": "user", "content": "Dis: libre"}]}, timeout=120)
try:
    txt = json.loads(body)["choices"][0]["message"]["content"]
    log("provider libéré après annulation", bool(txt), repr(txt[:40]))
except Exception:
    log("provider libéré après annulation", False, body[:200])

# ---------------------------------------------------------------- 3. long prompt
print("--- 3. prompt long (agent) ---")
big = "Contexte technique detaille. " * 220 + "\n\nResume ce contexte en une phrase."
code, body = post("/v1/chat/completions", {"messages": [{"role": "user", "content": big}]}, timeout=240)
try:
    txt = json.loads(body)["choices"][0]["message"]["content"]
    log(f"prompt de {len(big)} caractères accepté", bool(txt), repr(txt[:60]))
except Exception:
    log("prompt long", False, body[:300])

# ---------------------------------------------------------------- 4. /v1/responses
print("--- 4. /v1/responses ---")
code, body = post("/v1/responses", {"input": "Dis uniquement: RESPO"}, timeout=180)
try:
    j = json.loads(body)
    txt = j["output"][0]["content"][0]["text"]
    log("/v1/responses", bool(txt), repr(txt[:40]))
except Exception:
    log("/v1/responses", False, body[:200])

# ---------------------------------------------------------------- 5. erreurs
print("--- 5. erreurs ---")
code, body = post("/v1/chat/completions", {"messages": []}, timeout=30)
log("requête sans message -> erreur explicite", code >= 400 and "error" in body, f"(http={code})")

print(f"\n{ok} OK, {ko} FAIL")

#!/usr/bin/env python3
"""Statistiques de latence de l'API NemApi Android.

Mesure, pour chaque requête :
  - latence de soumission (temps avant le premier octet SSE utile)
  - temps jusqu'au premier token (TTFT)
  - durée totale
  - débit (caractères/s)
"""
import json
import statistics
import time
import urllib.request

BASE = "http://127.0.0.1:8090"

PROMPTS = [
    ("court", "Dis uniquement: bonjour"),
    ("court", "Quelle est la capitale du Togo ? Une phrase."),
    ("moyen", "Explique en 5 phrases ce qu'est un proxy inverse."),
    ("moyen", "Donne 3 avantages des WebView dans une application Android."),
    ("long", "Ecris un paragraphe de 150 mots sur la programmation en Kotlin."),
    ("code", "Ecris une fonction Kotlin qui inverse une chaine, avec un commentaire."),
]


def run(prompt):
    body = json.dumps({"stream": True, "messages": [{"role": "user", "content": prompt}]}).encode()
    req = urllib.request.Request(BASE + "/v1/chat/completions", data=body,
                                headers={"content-type": "application/json"})
    t0 = time.time()
    ttft = None
    chars = 0
    chunks = 0
    with urllib.request.urlopen(req, timeout=240) as r:
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
            d = (j.get("choices") or [{}])[0].get("delta") or {}
            c = d.get("content")
            if c:
                if ttft is None:
                    ttft = time.time() - t0
                chars += len(c)
                chunks += 1
    total = time.time() - t0
    return ttft or total, total, chars, chunks


rows = []
print(f"{'type':6} {'TTFT':>7} {'total':>7} {'chars':>6} {'chunks':>6} {'c/s':>7}  prompt")
for kind, prompt in PROMPTS:
    try:
        ttft, total, chars, chunks = run(prompt)
        cps = chars / total if total else 0
        rows.append((ttft, total, chars, chunks, cps))
        print(f"{kind:6} {ttft:6.2f}s {total:6.2f}s {chars:6d} {chunks:6d} {cps:7.1f}  {prompt[:48]}")
    except Exception as e:
        print(f"{kind:6} ERREUR {e}  {prompt[:40]}")

if rows:
    print("\n--- synthèse ---")
    print(f"requêtes           : {len(rows)}")
    print(f"TTFT   min/moy/max : {min(r[0] for r in rows):.2f}s / {statistics.mean(r[0] for r in rows):.2f}s / {max(r[0] for r in rows):.2f}s")
    print(f"total  min/moy/max : {min(r[1] for r in rows):.2f}s / {statistics.mean(r[1] for r in rows):.2f}s / {max(r[1] for r in rows):.2f}s")
    print(f"débit  moy         : {statistics.mean(r[4] for r in rows):.1f} caractères/s")
    print(f"tokens (approx.)   : {statistics.mean(r[2] for r in rows) / 4:.0f} tokens/réponse (4 c./token)")

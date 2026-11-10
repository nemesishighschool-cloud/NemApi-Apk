#!/usr/bin/env python3
"""Sonde SSE : horodate chaque delta reçu de l'API locale de NemApi."""
import json
import sys
import time
import urllib.request

prompt = sys.argv[1] if len(sys.argv) > 1 else "Ecris un paragraphe de 120 mots sur la ville de Lome au Togo."
url = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8090/v1/chat/completions"

body = json.dumps({"stream": True, "messages": [{"role": "user", "content": prompt}]}).encode()
req = urllib.request.Request(url, data=body, headers={"content-type": "application/json"})
t0 = time.time()
n = 0
try:
    with urllib.request.urlopen(req, timeout=240) as r:
        for line in r:
            s = line.decode("utf-8", "replace").strip()
            if not s.startswith("data: "):
                continue
            payload = s[6:]
            if payload == "[DONE]":
                print("%7.2fs  [DONE]" % (time.time() - t0))
                break
            try:
                j = json.loads(payload)
            except Exception:
                continue
            if "error" in j:
                print("%7.2fs  ERREUR %s" % (time.time() - t0, j["error"]))
                continue
            d = (j.get("choices") or [{}])[0].get("delta") or {}
            c = d.get("content")
            if c:
                n += 1
                print("%7.2fs  chunk#%d (%d c.) %r" % (time.time() - t0, n, len(c), c[:70]))
except Exception as e:
    print("exception:", e)
print("total %d chunks en %.1fs" % (n, time.time() - t0))

#!/usr/bin/env python3
"""Vérifie que le dashboard servi par l'app est bien celui de NemApi web.

Contrôle les pages HTML, les assets, puis chaque endpoint JSON que les pages
appellent (mêmes routes que le proxy FastAPI d'origine).
"""
import json
import sys
import urllib.request

BASE = "http://127.0.0.1:8090"
OK, KO = "OK ", "KO "


def get(path, raw=False):
    with urllib.request.urlopen(BASE + path, timeout=20) as r:
        data = r.read()
        return r.status, data if raw else data.decode("utf-8", "replace")


def post(path, payload=None, method="POST"):
    body = json.dumps(payload or {}).encode()
    req = urllib.request.Request(BASE + path, data=body, method=method,
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.status, r.read().decode()


fails = []


def check(label, cond, detail=""):
    print(f"{OK if cond else KO}{label}{(' — ' + detail) if detail else ''}")
    if not cond:
        fails.append(label)


def main():
    # ---- pages : ce sont les fichiers du dépôt (proxy/web), pas une copie
    pages = {
        "/": ("admin.html", ["Tableau de bord", "endpoint-url"]),
        "/index.html": ("admin.html", ["Tableau de bord"]),
        "/config": ("config.html", ["Configuration", "cfg-providers"]),
        "/integrations": ("integrations.html", ["Intégration agents", "agents"]),
        "/chat": ("chat.html", ["Chat test", "chat-model"]),
        "/analytics": ("analytics.html", ["Analytics", "total-requests"]),
        "/api-keys-page": ("api-keys.html", ["Clés API", "api-keys-list"]),
    }
    for path, (name, needles) in pages.items():
        code, html = get(path)
        ok = code == 200 and all(n in html for n in needles)
        check(f"page {path:16s} → {name}", ok, f"{len(html)} o")

    # ---- assets
    for path, kind in [("/admin.css", "css"), ("/admin.js", "js"),
                       ("/icons/logo-32.png", "png"),
                       ("/icons/providers/deepseek.png", "png")]:
        code, blob = get(path, raw=True)
        check(f"asset {path}", code == 200 and len(blob) > 200, f"{len(blob)} o")

    # ---- endpoints JSON du dashboard
    code, body = get("/health")
    h = json.loads(body)
    check("GET /health", h.get("ok") is True and "app_version" in h, str(h.get("app_version")))

    code, body = get("/status")
    st = json.loads(body)
    check("GET /status", st.get("status") in ("ready", "waiting_extension"), str(st.get("status")))

    code, body = get("/providers")
    prov = json.loads(body)["providers"]
    check("GET /providers", any(p["id"] == "deepseek" for p in prov), f"{len(prov)} provider(s)")

    code, body = get("/stats")
    s = json.loads(body)
    check("GET /stats", "total_requests" in s and "daily" in s,
          f"{s['total_requests']} req · {len(s['daily'])} jours")

    code, body = get("/settings")
    se = json.loads(body)
    check("GET /settings", set(se) >= {"stream_enabled", "auto_config", "fresh_chat", "premium_md"}, str(se))

    code, body = get("/extension/state")
    es = json.loads(body)
    check("GET /extension/state", "connected" in es and "tabs" in es and "logs" in es,
          f"{len(es.get('logs', []))} entrée(s) de journal")

    code, body = get("/agent-integrations")
    ai = json.loads(body)
    ids = [a["id"] for a in ai["agents"]]
    check("GET /agent-integrations", ids == ["qwen-code", "monkey-code", "nemesis"], ", ".join(ids))

    code, body = get("/api-keys")
    ak = json.loads(body)
    check("GET /api-keys", "api_keys" in ak and "require_auth" in ak, f"{len(ak['api_keys'])} clé(s)")

    # ---- écritures
    code, body = post("/settings", {"fresh_chat": True})
    check("POST /settings", json.loads(body)["fresh_chat"] is True)
    post("/settings", {"fresh_chat": False})

    code, body = post("/agent-integrations/qwen-code", {"enabled": True})
    a = json.loads(body)
    check("POST /agent-integrations/qwen-code", a.get("enabled") is True,
          f"{len(a.get('features', []))} fonction(s)")
    post("/agent-integrations/qwen-code", {"enabled": False})

    code, body = post("/api-keys", {"name": "test-ci"})
    k = json.loads(body)
    check("POST /api-keys", k.get("api_key", "").startswith("nemapi-"), k.get("id", ""))
    post(f"/api-keys/{k['id']}", {}, method="DELETE")

    # ---- le dashboard ne doit pas fuiter de contenu de conversation
    code, body = get("/extension/state")
    leaked = any(w in body.lower() for w in ["sel fond", "rayleigh", "capitale du togo"])
    check("aucun contenu de conversation dans l'état", not leaked)

    print()
    print(f"{len(fails)} échec(s)" if fails else "tous les contrôles du dashboard sont passés")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())

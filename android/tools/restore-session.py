#!/usr/bin/env python3
"""Restaure la session DeepSeek sauvegardée dans le WebView de l'app.

Usage : python3 restore-session.py [fichier.json]
Par défaut : /workspaces/.nemapi-android/session/deepseek-cookies.json
"""
import json
import sys
import urllib.request

path = sys.argv[1] if len(sys.argv) > 1 else "/workspaces/.nemapi-android/session/deepseek-cookies.json"
with open(path, encoding="utf-8") as f:
    cookie = json.load(f)["cookie"]

req = urllib.request.Request(
    "http://127.0.0.1:8090/api/debug/cookies",
    data=json.dumps({"cookie": cookie}).encode(),
    headers={"content-type": "application/json"},
    method="POST",
)
with urllib.request.urlopen(req, timeout=20) as r:
    print("restauration :", r.read().decode())

# vérification : la page doit être connectée
req = urllib.request.Request(
    "http://127.0.0.1:8090/api/debug/eval",
    data=b'location.href',
    method="POST",
)
with urllib.request.urlopen(req, timeout=20) as r:
    print("page courante :", r.read().decode())

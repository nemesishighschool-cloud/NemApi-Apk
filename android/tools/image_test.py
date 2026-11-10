#!/usr/bin/env python3
"""Test d'envoi d'image à l'API NemApi.

Fabrique une image PNG déterministe (quatre quadrants de couleurs connues),
l'envoie en `image_url` (data URI base64) et vérifie que la réponse décrit
bien l'image. Deux passes : stream et non-stream.

Usage : python3 image_test.py [--host 127.0.0.1] [--port 8090]
"""
import argparse
import base64
import io
import json
import struct
import sys
import time
import urllib.request
import zlib

QUADRANTS = {(0, 0): (220, 40, 40), (1, 0): (40, 170, 60), (0, 1): (40, 80, 220), (1, 1): (240, 200, 40)}


def make_png(size=384):
    """PNG RGB : rouge en haut-gauche, vert en haut-droite, bleu en bas-gauche, jaune en bas-droite."""
    half = size // 2
    raw = bytearray()
    for y in range(size):
        raw.append(0)  # filtre 0
        for x in range(size):
            raw.extend(QUADRANTS[(1 if x >= half else 0, 1 if y >= half else 0)])

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def request(base, payload, timeout=300):
    body = json.dumps(payload).encode()
    req = urllib.request.Request(
        base + "/chat/completions",
        data=body,
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
    )
    t0 = time.time()
    first = None
    text = ""
    with urllib.request.urlopen(req, timeout=timeout) as r:
        ctype = r.headers.get("Content-Type", "")
        if "event-stream" not in ctype:
            data = json.loads(r.read().decode())
            return data["choices"][0]["message"]["content"], None, time.time() - t0, data.get("usage")
        for line in r:
            line = line.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            chunk = line[5:].strip()
            if chunk == "[DONE]":
                break
            try:
                obj = json.loads(chunk)
            except Exception:
                continue
            if obj.get("error"):
                raise RuntimeError(obj["error"])
            delta = (obj.get("choices") or [{}])[0].get("delta", {}).get("content") or ""
            if delta:
                if first is None:
                    first = time.time() - t0
                text += delta
    return text, first, time.time() - t0, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8090)
    args = ap.parse_args()
    base = f"http://{args.host}:{args.port}/v1"

    png = make_png()
    b64 = base64.b64encode(png).decode()
    print(f"image PNG : {len(png)} o ({len(b64)} o en base64)")
    uri = "data:image/png;base64," + b64

    prompt = (
        "Cette image contient quatre quadrants de couleurs différentes. "
        "Réponds en une seule ligne, format exact : HAUT-GAUCHE=<couleur>; HAUT-DROITE=<couleur>; "
        "BAS-GAUCHE=<couleur>; BAS-DROITE=<couleur>."
    )
    content = [
        {"type": "text", "text": prompt},
        {"type": "image_url", "image_url": {"url": uri}},
    ]

    for stream in (True, False):
        label = "stream" if stream else "non-stream"
        print(f"\n--- {label} ---")
        payload = {
            "model": "deepseek-chat",
            "stream": stream,
            "messages": [{"role": "user", "content": content}],
        }
        try:
            text, first, total, usage = request(base, payload)
        except Exception as e:
            print(f"ÉCHEC {label} : {e}")
            sys.exit(1)
        print(f"1er fragment : {first if first is None else round(first, 2)} s — total {round(total, 2)} s")
        print(f"réponse : {text.strip()[:400]}")
        if usage:
            print(f"usage : {usage}")

    print("\nTerminé.")


if __name__ == "__main__":
    main()

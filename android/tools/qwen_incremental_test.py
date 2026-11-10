#!/usr/bin/env python3
"""Live regression test: Qwen Code UA, >64k first context, then two delta turns.

Requires a logged-in DeepSeek WebView and the local API on port 8090. The test
uses a synthetic 64,969-character long user-context message to reproduce the former hard
failure; it asks DeepSeek to retain a short marker and checks that subsequent
turns use the stored discussion with only the new user message typed.
"""
import json
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:8090"
UA = "QwenCode/0.2.0 (NemApi regression test)"
MARKER = "QWEN_MEMORY_MARKER_7D91C4"


def request(path, body=None, method=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method,
        headers={"User-Agent": UA, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=360) as r:
        raw = r.read()
        return r.status, json.loads(raw.decode("utf-8"))


def completion(messages, tools=None):
    reqbody = {"model": "deepseek-chat", "messages": messages, "stream": False}
    if tools is not None:
        reqbody["tools"] = tools
    status, data = request("/v1/chat/completions", reqbody, "POST")
    text = data["choices"][0]["message"].get("content") or ""
    return status, text


def main():
    # Ensure this live test exercises the actual Qwen incremental path.
    _, integ = request("/agent-integrations")
    qwen = next((a for a in integ["agents"] if a["id"] == "qwen-code"), None)
    if not qwen or not qwen.get("enabled"):
        request("/agent-integrations/qwen-code", {"enabled": True,
            "features": {"incremental_context": True}}, "POST")
    request("/sessions/reset", {}, "POST")

    prompt_prefix = "This is a long project context with many unique notes. The exact memory marker is " + MARKER + ".\n"
    user_context = prompt_prefix
    line = 0
    while len(user_context) < 64_900:
        user_context += f"Project note {line:05d}: alpha beta gamma delta epsilon zeta eta theta.\n"
        line += 1
    user_context = (user_context[:64_900] + "\nKeep the exact marker: " + MARKER).ljust(64_969, " ")[:64_969]
    assert len(user_context) == 64_969

    msgs = [
        {"role": "system", "content": "Remember project context from the user message. Answer briefly."},
        {"role": "user", "content": user_context + "\nReply with exactly READY and retain the marker."},
    ]
    started = time.time()
    code, a1 = completion(msgs)
    print(f"1) initial 64,969-char context: HTTP {code}, {len(a1)} answer chars, {time.time()-started:.1f}s")
    if not a1.strip(): raise RuntimeError("DeepSeek returned an empty first answer")

    msgs += [{"role": "assistant", "content": a1},
             {"role": "user", "content": "Reply with the exact marker stored in my long project context, and nothing else."}]
    started = time.time()
    code, a2 = completion(msgs)
    print(f"2) follow-up turn: HTTP {code}, answer={a2.strip()[:100]!r}, {time.time()-started:.1f}s")
    if MARKER not in a2: raise RuntimeError("follow-up did not recover the marker; context was lost")

    msgs += [{"role": "assistant", "content": a2},
             {"role": "user", "content": "Answer with exactly: DELTA_OK"}]
    started = time.time()
    code, a3 = completion(msgs)
    print(f"3) third turn: HTTP {code}, answer={a3.strip()[:100]!r}, {time.time()-started:.1f}s")
    if "DELTA_OK" not in a3: raise RuntimeError("third turn failed")

    _, summary = request("/sessions")
    stats = summary.get("requests", {})
    print("session stats:", json.dumps({"requests": stats, "chars_full": summary.get("chars_full"),
          "chars_sent": summary.get("chars_sent"), "chars_saved": summary.get("chars_saved"),
          "sessions": summary.get("sessions")}, ensure_ascii=False))
    if stats.get("full", 0) < 1 or stats.get("delta", 0) < 2:
        raise RuntimeError("The Qwen request path did not record one full + two incremental turns")
    if summary.get("chars_saved", 0) < 100_000:
        raise RuntimeError("The follow-up turns did not reduce repeated context sufficiently")
    print("PASS: 64,969-char first request accepted; two follow-ups used incremental context and retained the marker.")


if __name__ == "__main__":
    try: main()
    except Exception as e:
        print("FAIL:", e, file=sys.stderr)
        sys.exit(1)

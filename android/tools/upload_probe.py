#!/usr/bin/env python3
"""Diagnostic : que devient le fichier injecté dans le composeur DeepSeek ?

Envoie une image 1x1 via l'adaptateur puis observe, seconde par seconde, ce que
le DOM du composeur expose (input.files, vignettes, indicateurs d'occupation,
état du bouton d'envoi). Objectif : comprendre pourquoi l'attente de
téléversement de l'app expire toujours à 45 s.
"""
import json
import time
import urllib.request

BASE = "http://127.0.0.1:8090"
PNG = ("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/"
       "q842iQAAAABJRU5ErkJggg==")


def parse(v):
    for _ in range(3):
        if isinstance(v, str):
            try:
                v = json.loads(v)
            except Exception:
                return v
    return v


def ev(js, timeout=30):
    req = urllib.request.Request(BASE + "/api/debug/eval", data=js.encode(), method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = json.loads(r.read().decode())
    v = raw.get("result")
    if isinstance(v, str):
        try:
            v = json.loads(v)
        except Exception:
            pass
        if isinstance(v, str):   # l'adaptateur renvoie une chaîne JSON encodée
            try:
                v = json.loads(v)
            except Exception:
                pass
    return v


def main():
    print("état initial :", json.dumps(ev(
        "JSON.stringify((function(){var a=window.NemApiProvider;return {up:a.uploadState(),send:a.sendReady()};})())"
    ), ensure_ascii=False))

    inject = ev("""JSON.stringify((function(){
      var a=window.NemApiProvider;
      a.attachBegin();
      var c=a.attachChunk(JSON.stringify({mime:"image/png",name:"t.png",first:true,b64:"%s"}));
      var k=a.attachCommit();
      return {chunk:c,commit:k};
    })())""" % PNG)
    print("injection   :", json.dumps(inject, ensure_ascii=False))

    t0 = time.time()
    for _ in range(12):
        time.sleep(1.0)
        st = ev("""JSON.stringify((function(){
          var a=window.NemApiProvider, up=a.uploadState(), sd=a.sendReady();
          var root=document.querySelector("textarea") ? document.querySelector("textarea").closest("form")||document.body : document.body;
          var imgs=[].slice.call(document.querySelectorAll("img")).filter(function(i){return /^(blob:|data:)/.test(i.src);}).map(function(i){return i.src.slice(0,24)+" "+i.width+"x"+i.height;});
          var busy=!!document.querySelector("[class*='loading'],[class*='spinner'],[class*='uploading'],[aria-busy='true']");
          return {t:%.1f, up:up, send:sd, data_imgs:imgs, busy_any_page:busy};
        })())""" % (time.time() - t0))
        print(f"{time.time()-t0:5.1f}s", json.dumps(st, ensure_ascii=False))
        up, sd = parse((st or {}).get("up")), parse((st or {}).get("send"))
        st["up"], st["send"] = up, sd
        if up and up.get("ready") or (sd and sd.get("found") and not sd.get("disabled")):
            print("→ signal d'arrêt détecté")
            break

    # structure du composeur pour caler une détection fiable
    html = ev("""JSON.stringify((function(){
      var el=document.querySelector("textarea"); var root=el?el.closest("form")||el.parentElement.parentElement:document.body;
      return {tag:root.tagName, cls:String(root.className).slice(0,120), html:root.innerHTML.slice(0,1500)};
    })())""")
    print("composeur   :", json.dumps(html, ensure_ascii=False)[:2000])

    ev("JSON.stringify(window.NemApiProvider.clearAttachments())")


if __name__ == "__main__":
    main()

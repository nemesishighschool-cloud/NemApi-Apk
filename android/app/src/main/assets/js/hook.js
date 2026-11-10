/**
 * NemApi Android — accroche réseau (document-start, monde principal de la page).
 *
 * Rôle : tee du flux SSE de réponse, SANS jamais modifier la requête ni la
 * réponse. Une seule requête est émise vers le provider ; on lit une copie.
 *
 * Le hook n'utilise AUCUN timer : les chunks sont accumulés dès que le
 * réseau les livre. Kotlin vient lire l'état par `evaluateJavascript`
 * (__nap.state()), ce qui reste fiable quand la page est en arrière-plan
 * (les timers JS y sont ralentis, pas les callbacks réseau).
 */
(function () {
  "use strict";
  if (window.__nap) return;

  var H = {
    inst: "i" + Math.random().toString(36).slice(2, 7),
    seq: 0,
    cur: null,
    last: null,
    history: [],
    errors: []
  };
  window.__nap = H;

  // ---- viewport téléphone ----
  // L'UA reste celui d'un ordinateur (c'est lui qui décide du DOM renvoyé par
  // le provider), mais la LARGEUR de mise en page redevient celle d'un
  // téléphone : la page est lisible telle quelle, sans zoom ni débordement.
  function forcePhoneViewport() {
    try {
      var head = document.head || document.documentElement;
      if (!head) return;
      var m = document.querySelector('meta[name="viewport"]');
      if (!m) {
        m = document.createElement("meta");
        m.setAttribute("name", "viewport");
        head.appendChild(m);
      }
      var want = "width=device-width, initial-scale=1, viewport-fit=cover";
      if (m.getAttribute("content") !== want) m.setAttribute("content", want);
    } catch (e) {}
  }
  forcePhoneViewport();
  document.addEventListener("DOMContentLoaded", forcePhoneViewport, true);
  window.addEventListener("load", forcePhoneViewport, true);
  try {
    setTimeout(forcePhoneViewport, 800);
    setTimeout(forcePhoneViewport, 2500);
  } catch (e) {}

  /**
   * Parseur du flux DeepSeek (format vérifié sur chat.deepseek.com).
   *
   * Le site n'envoie pas des « deltas » plats mais des patchs JSON :
   *   {"v":{"response":{…,"fragments":[{"type":"RESPONSE","content":"TEST"}]}}}  ← instantané
   *   {"p":"response/fragments/-1/content","o":"APPEND","v":" FL"}              ← ajout
   *   {"v":"UX"}                                                                 ← ajout (même chemin)
   *   {"p":"response","o":"BATCH","v":[…]}                                       ← bruit (tokens…)
   *   {"p":"response/status","o":"SET","v":"FINISHED"}                           ← fin
   * Un instantané contient TOUT le texte déjà produit : il remplace, il ne
   * s'ajoute pas. Les fragments de type THINK ne font pas partie de la réponse.
   */
  function applySnapshot(rec, resp) {
    var frags = resp.fragments || [];
    var text = "";
    var lastType = "RESPONSE";
    for (var i = 0; i < frags.length; i++) {
      var f = frags[i] || {};
      var t = String(f.type || "RESPONSE");
      if (t === "THINK") {
        lastType = "THINK";
        continue;
      }
      lastType = "RESPONSE";
      if (typeof f.content === "string") text += f.content;
    }
    if (text.length >= rec.text.length) rec.text = text;
    rec.curType = lastType;
    if (String(resp.status || "") === "FINISHED") rec.finished = true;
  }

  function applyOpenAiShape(rec, obj) {
    var choices = obj.choices;
    if (!choices || !choices.length) return false;
    var ch = choices[0] || {};
    var d = ch.delta || ch.message || {};
    if (typeof d.content === "string" && d.content) {
      rec.text += d.content;
      return true;
    }
    return false;
  }

  function applyPatch(rec, node, depth) {
    if (node == null || depth > 6) return;
    if (typeof node === "string") {
      rec.text += node;
      return;
    }
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) applyPatch(rec, node[i], depth + 1);
      return;
    }
    if (typeof node !== "object") return;

    var p = typeof node.p === "string" ? node.p : null;
    var o = typeof node.o === "string" ? node.o : null;
    var v = node.v;

    if (o === "BATCH") {
      applyPatch(rec, v, depth + 1);
      return;
    }
    if (p) {
      if (/\/content$/.test(p) && typeof v === "string") {
        if (rec.curType === "THINK" && !/THINK/i.test(p)) {
          rec.thinking = (rec.thinking || "") + v;
        } else {
          rec.text += v;
        }
        rec.lastAt = Date.now();
        return;
      }
      if (typeof v === "string" && v === "FINISHED" && /status$/.test(p)) {
        rec.finished = true;
        return;
      }
      if (p === "response" && v && typeof v === "object") {
        if (v.fragments) applySnapshot(rec, v);
        else applyPatch(rec, v.response || v, depth + 1);
        return;
      }
      return; // toute autre route est du bruit (usage, ids, timestamps…)
    }
    if (typeof v === "string") {
      // {"v":"UX"} : continuation du dernier chemin de contenu
      if (rec.curType === "THINK") rec.thinking = (rec.thinking || "") + v;
      else rec.text += v;
      rec.lastAt = Date.now();
      return;
    }
    if (v && typeof v === "object") {
      if (v.response && typeof v.response === "object") {
        if (v.response.fragments) applySnapshot(rec, v.response);
        else applyPatch(rec, v.response, depth + 1);
        return;
      }
      if (v.fragments) {
        applySnapshot(rec, v);
        return;
      }
      applyPatch(rec, v, depth + 1);
      return;
    }
    if (applyOpenAiShape(rec, node)) return;
  }

  function appendPayload(rec, payload) {
    payload = String(payload || "").trim();
    if (!payload) return;
    if (payload === "[DONE]") {
      rec.done = true;
      return;
    }
    var j = null;
    try {
      j = JSON.parse(payload);
    } catch (e) {
      if (payload.charAt(0) !== "{") {
        rec.text += payload;
        rec.events++;
      }
      return;
    }
    if (j && j.error && typeof j.error === "object") {
      rec.error = "upstream_error";
      rec.errorMessage = String(j.error.message || j.error.code || "provider error");
      rec.done = true;
      return;
    }
    applyPatch(rec, j, 0);
    rec.events++;
    rec.lastAt = Date.now();
  }

  function handleLine(rec, line) {
    var t = String(line || "").replace(/\r$/, "");
    if (!t) return;
    if (t.charAt(0) === ":") return;
    var m = /^data:\s*(.*)$/.exec(t);
    if (m) {
      appendPayload(rec, m[1]);
      return;
    }
    if (t.charAt(0) === "{") appendPayload(rec, t);
  }

  function feed(rec, chunk) {
    rec.raw += chunk;
    rec.buf += chunk;
    var idx;
    while ((idx = rec.buf.indexOf("\n")) >= 0) {
      var line = rec.buf.slice(0, idx);
      rec.buf = rec.buf.slice(idx + 1);
      handleLine(rec, line);
    }
  }

  function finish(rec, error) {
    if (rec.buf) {
      handleLine(rec, rec.buf);
      rec.buf = "";
    }
    rec.done = true;
    rec.endedAt = Date.now();
    if (error) rec.error = error;
    H.last = rec;
    H.history.push({ id: rec.id, url: rec.url, text: rec.text.length, done: rec.done, error: rec.error });
    if (H.history.length > 20) H.history.shift();
  }

  H.reqs = H.reqs || [];
  function noteReq(method, url) {
    try {
      if (H.reqs.length > 40) H.reqs.shift();
      H.reqs.push(String(method || "GET").toUpperCase() + " " + pathOf(url));
    } catch (e) {}
  }

  function newRecord(url) {
    H.seq++;
    var rec = {
      id: H.seq,
      url: String(url || ""),
      startedAt: Date.now(),
      lastAt: Date.now(),
      text: "",
      raw: "",
      buf: "",
      events: 0,
      thinking: "",
      curType: "RESPONSE",
      finished: false,
      done: false,
      error: null,
      errorMessage: null
    };
    H.cur = rec;
    return rec;
  }

  function drainResponse(rec, res) {
    try {
      var ct = "";
      try {
        ct = (res.headers && res.headers.get ? res.headers.get("content-type") : "") || "";
      } catch (e) {}
      if (!res.body || typeof res.body.getReader !== "function") {
        var p = res.text ? res.text() : null;
        if (p && p.then) {
          p.then(function (t) {
            feed(rec, String(t || ""));
            finish(rec, null);
          })["catch"](function (e) {
            finish(rec, "stream_interrupted");
          });
          return;
        }
        finish(rec, null);
        return;
      }
      var reader = res.body.getReader();
      var dec = new TextDecoder("utf-8");
      var pump = function () {
        reader.read().then(function (r) {
          if (r.done) {
            finish(rec, null);
            return;
          }
          try {
            feed(rec, dec.decode(r.value, { stream: true }));
          } catch (e) {
            H.errors.push("decode: " + e.message);
          }
          pump();
        })["catch"](function (e) {
          finish(rec, "stream_interrupted");
        });
      };
      pump();
    } catch (e) {
      finish(rec, "stream_interrupted");
    }
  }

  // ---- suivi des téléversements de pièces jointes -------------------------
  // L'app attendait auparavant que le DOM veuille bien montrer une vignette :
  // quand les sélecteurs ne correspondaient pas, elle attendait le plafond
  // complet (45 s) à chaque image — d'où un temps de premier token toujours
  // identique, quelle que soit l'image. On observe maintenant les requêtes
  // elles-mêmes : démarrage, fin, code HTTP, durée.
  var UPLOAD_PATH_RE = /upload|file|attach|asset/i;
  var UPLOAD_NOT_RE = /pow_challenge|telemetry|event|history|sessions?\/list/i;

  function isUploadCall(method, url) {
    var p = pathOf(url);
    if (!p || UPLOAD_NOT_RE.test(p)) return false;
    return UPLOAD_PATH_RE.test(p) && String(method).toUpperCase() !== "GET";
  }

  var UP = { seen: false, active: 0, total: 0, ok: null, status: 0, ms: 0, startedAt: 0, endedAt: 0 };

  function uploadBegin(url) {
    UP.seen = true;
    UP.active++;
    UP.total++;
    UP.startedAt = Date.now();
    UP.url = pathOf(url);
  }

  function uploadEnd(status, failed) {
    UP.active = Math.max(0, UP.active - 1);
    UP.endedAt = Date.now();
    UP.ms = UP.startedAt ? UP.endedAt - UP.startedAt : 0;
    UP.status = status || 0;
    UP.ok = !failed && (!status || status < 400);
  }

  window.__napUpload = function () {
    return JSON.stringify({
      seen: UP.seen,
      active: UP.active,
      total: UP.total,
      ok: UP.ok,
      status: UP.status,
      ms: UP.ms,
      done: UP.seen && UP.active === 0
    });
  };

  window.__napUploadReset = function () {
    UP = { seen: false, active: 0, total: 0, ok: null, status: 0, ms: 0, startedAt: 0, endedAt: 0 };
    return "1";
  };

  // ---- détection des requêtes à observer ----
  // On teste le CHEMIN seul : le nom d'hôte « chat.deepseek.com » contient
  // « chat » et faisait capturer la télémétrie au lieu du flux de réponse.
  function pathOf(url) {
    try {
      return new URL(String(url || ""), location.origin).pathname;
    } catch (e) {
      return String(url || "");
    }
  }

  // Route vérifiée sur chat.deepseek.com : POST /api/v0/chat/completion (XHR, SSE)
  var CHAT_PATH_RE = /\/chat\/completion(s)?$/i;
  var NOT_CHAT_RE = /pow_challenge|telemetry|event|history|session|list|feedback|report/i;

  function isChatCall(method, url) {
    if (String(method).toUpperCase() !== "POST") return false;
    var p = pathOf(url);
    if (!p) return false;
    if (NOT_CHAT_RE.test(p)) return false;
    return CHAT_PATH_RE.test(p);
  }

  // ---- fetch ----
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      var url = "";
      var method = "GET";
      try {
        url = typeof input === "string" ? input : (input && input.url) || "";
        method = ((init && init.method) || (input && input.method) || "GET") + "";
        if (url && url.charAt(0) === "/") url = location.origin + url;
      } catch (e) {}
      var p = origFetch.apply(this, arguments);
      try {
        noteReq(method, url);
        if (isUploadCall(method, url)) {
          uploadBegin(url);
          p.then(function (res) {
            try { uploadEnd(res.status, !res.ok); } catch (e) { uploadEnd(0, false); }
          })["catch"](function () { uploadEnd(0, true); });
        }
        if (isChatCall(method, url)) {
          var rec = newRecord(url);
          p.then(function (res) {
            try {
              if (!res.ok) {
                rec.error = "upstream_error";
                rec.errorMessage = "HTTP " + res.status;
                finish(rec, "upstream_error");
                return;
              }
              drainResponse(rec, res.clone());
            } catch (e) {
              finish(rec, "stream_interrupted");
            }
          })["catch"](function (e) {
            finish(rec, "network_error");
          });
        }
      } catch (e) {}
      return p;
    };
  }

  // ---- XMLHttpRequest ----
  var origOpen = XMLHttpRequest.prototype.open;
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    try {
      this.__napMethod = method;
      this.__napUrl = url;
      this.__napInst = H.inst;
    } catch (e) {}
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function () {
    var xhr = this;
    try {
      H.lastWrapper = H.inst;
      noteReq(xhr.__napMethod, xhr.__napUrl);
      if (isUploadCall(xhr.__napMethod, xhr.__napUrl)) {
        uploadBegin(xhr.__napUrl);
        xhr.addEventListener("loadend", function () {
          try { uploadEnd(xhr.status, xhr.status >= 400 || xhr.status === 0); }
          catch (e) { uploadEnd(0, false); }
        });
      }
      if (isChatCall(xhr.__napMethod, xhr.__napUrl)) {
        var rec = newRecord(xhr.__napUrl);
        var lastLen = 0;
        xhr.addEventListener("progress", function () {
          try {
            if (xhr.responseType && xhr.responseType !== "text") return;
            var t = xhr.responseText || "";
            if (typeof t === "string" && t.length > lastLen) {
              feed(rec, t.slice(lastLen));
              lastLen = t.length;
            }
          } catch (e) {}
        });
        xhr.addEventListener("loadend", function () {
          try {
            // corps non textuel (arraybuffer/blob) : on récupère ce qu'on peut
            if (rec.raw.length === 0 && xhr.response && typeof xhr.response !== "object") {
              feed(rec, String(xhr.response));
            }
          } catch (e) {}
          try {
            if (xhr.status && xhr.status >= 400) finish(rec, "upstream_error");
            else finish(rec, null);
          } catch (e) {
            finish(rec, null);
          }
        });
      }
    } catch (e) {
      H.errors.push("send:" + (e && e.message ? e.message : e));
    }
    return origSend.apply(this, arguments);
  };

  // ---- API appelée par Kotlin (résultats en JSON sérialisé) ----
  H.reset = function () {
    H.cur = null;
    H.last = null;
    return JSON.stringify({ ok: true, seq: H.seq });
  };

  H.state = function () {
    var c = H.cur;
    if (!c) return JSON.stringify({ started: false, seq: H.seq });
    return JSON.stringify({
      started: true,
      id: c.id,
      text: c.text,
      events: c.events,
      done: !!c.done,
      finished: !!c.finished,
      thinking: (c.thinking || "").length,
      error: c.error,
      errorMessage: c.errorMessage,
      bytes: c.raw.length,
      age: Date.now() - c.startedAt,
      idle: Date.now() - (c.lastAt || c.startedAt),
      url: c.url
    });
  };

  // Diagnostic (debug) : fin du flux brut, pour vérifier le format réel du provider.
  H.raw = function (n) {
    var c = H.cur || H.last;
    if (!c) return "";
    return c.raw.slice(-(n || 3000));
  };

  // auto-test (utilisé par les tests Node, sans WebView)
  H.__test = function (raw) {
    var r = newRecord("test");
    feed(r, String(raw || ""));
    return JSON.stringify({ text: r.text, thinking: r.thinking, done: !!(r.done || r.finished) });
  };

  H.reqs_seen = function () {
    return JSON.stringify(H.reqs || []);
  };

  H.info = function () {
    return JSON.stringify({
      inst: H.inst,
      lastWrapper: H.lastWrapper || null,
      reqs: (H.reqs || []).length,
      seq: H.seq,
      has: !!H.cur,
      history: H.history,
      errors: H.errors.slice(-5),
      ua: navigator.userAgent
    });
  };
})();

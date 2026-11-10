/**
 * NemApi Android — adaptateur DOM DeepSeek (monde principal de la page).
 *
 * Reprend les sélecteurs et les heuristiques éprouvés de l'extension
 * NemApi v5.6.4 (extension/providers/deepseek.js), en version compacte et
 * synchrone : l'attente est pilotée par Kotlin, pas par des timers JS
 * (les timers sont ralentis quand la page est en arrière-plan).
 *
 * Toutes les méthodes renvoient une chaîne JSON.
 */
(function () {
  "use strict";

  var INPUT = [
    "textarea#chat-input",
    'textarea[placeholder*="DeepSeek" i]',
    'textarea[placeholder*="Message DeepSeek" i]',
    'textarea[placeholder*="Message" i]',
    'textarea[role="textbox"]',
    ".ds-textarea textarea",
    "textarea[data-testid*='chat' i]",
    "div[contenteditable='true'][role='textbox']",
    "div[contenteditable='true']",
    "textarea"
  ];

  var SEND_CANDIDATES = [
    '[role="button"].ds-icon-button',
    "button.ds-icon-button",
    '[role="button"].ds-button--primary',
    "button.ds-button--primary",
    '[role="button"].ds-button--circle',
    "button.ds-button--circle",
    '[role="button"][class*="_52c986b"]',
    "button[class*='_52c986b']",
    '[role="button"][class*="_7436101"]',
    "div[role='button'][aria-disabled]",
    'button[type="submit"]',
    'button[aria-label*="Send" i]',
    '[role="button"][aria-label*="Send" i]',
    "button[class*='send']",
    '[role="button"]',
    "button"
  ];

  var MESSAGE = [
    "[data-virtual-list-item-key] .ds-message",
    ".ds-message",
    "[class*='ds-message']"
  ];

  var SEND_SVG_PATH_RE = /M8\.3125|M13\.12\s*19\.98|M12\s*5\.25|paper|send|arrow/i;

  var ATT = { items: [], cur: null };
  var PROMPT_BUFFER = "";

  function J(o) {
    try {
      return JSON.stringify(o);
    } catch (e) {
      return '{"ok":false,"reason":"json"}';
    }
  }

  function visible(el) {
    try {
      if (!el) return false;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 && r.height <= 0) return false;
      var st = window.getComputedStyle(el);
      if (!st) return true;
      if (st.display === "none" || st.visibility === "hidden" || st.opacity === "0") return false;
      return true;
    } catch (e) {
      return !!el;
    }
  }

  function inputCandidates() {
    var out = [];
    var seen = new Set();
    for (var i = 0; i < INPUT.length; i++) {
      var nodes;
      try {
        nodes = document.querySelectorAll(INPUT[i]);
      } catch (e) {
        continue;
      }
      for (var k = 0; k < nodes.length; k++) {
        var el = nodes[k];
        if (seen.has(el)) continue;
        if (!visible(el)) continue;
        if (el.disabled || el.readOnly) continue;
        if (el.isContentEditable || el.tagName === "TEXTAREA" || el.tagName === "INPUT") {
          seen.add(el);
          out.push(el);
        }
      }
    }
    return out;
  }

  function findInput() {
    var c = inputCandidates();
    return c.length ? c[0] : null;
  }

  function disabled(el) {
    if (!el) return true;
    if (el.getAttribute && el.getAttribute("aria-disabled") === "true") return true;
    if (el.disabled) return true;
    var cls = (el.className && String(el.className)) || "";
    return /\bdisabled\b/i.test(cls);
  }

  function continueLabel(el) {
    if (!el) return "";
    var accept = /^(?:continue(?:\s+(?:generating|response|generation|the response))?|continue generating response|continuer(?:\s+(?:la génération|la reponse|la réponse|génération|réponse))?|poursuivre(?:\s+(?:la génération|la réponse))?|reprendre(?:\s+(?:la génération|la réponse))?|继续|继续生成|继续回答)$/i;
    var candidates = [
      el.getAttribute && el.getAttribute("aria-label"),
      el.getAttribute && el.getAttribute("title"),
      el.getAttribute && el.getAttribute("data-testid"),
      el.innerText,
      el.textContent
    ];
    for (var i = 0; i < candidates.length; i++) {
      var label = String(candidates[i] || "").replace(/[.…]+$/g, "").replace(/\s+/g, " ").trim();
      if (accept.test(label)) return label;
      if (i === 2 && /(?:^|[-_ ])continue(?:[-_ ]|$)/i.test(label)) return "Continue";
    }
    var cls = String(el.className || "");
    if (/(?:^|[-_ ])continue(?:[-_ ]|$)/i.test(cls)) return "Continue";
    return "";
  }

  function continueNodes() {
    try {
      return document.querySelectorAll(
        "button,[role='button'],[aria-label*='continue' i],[aria-label*='continuer' i]," +
          "[title*='continue' i],[title*='continuer' i],[data-testid*='continue' i]," +
          "[data-testid*='continuer' i],[class*='continue' i],[class*='continuer' i]"
      );
    } catch (e) {
      return document.querySelectorAll("button,[role='button'],[class*='continue']");
    }
  }

  function isSendButton(el) {
    if (!el || !visible(el) || disabled(el)) return false;
    var label = (
      (el.getAttribute && el.getAttribute("aria-label")) + " " +
      (el.title || "") + " " +
      (el.innerText || el.textContent || "")
    ).toLowerCase();
    if (/stop|cancel|attach|upload|file|camera|image|voice|microphone|mic|plus|添加|附件|上传|图片|语音|停止|中止/.test(label)) return false;
    if (el.classList && (el.classList.contains("ds-toggle-button") || el.classList.contains("bds-plus-btn"))) return false;

    var paths = el.querySelectorAll("svg path");
    for (var i = 0; i < paths.length; i++) {
      if (SEND_SVG_PATH_RE.test(paths[i].getAttribute("d") || "")) return true;
    }
    if (/send|envoyer|发送|submit/i.test(label)) return true;
    var cls = (el.className && String(el.className)) || "";
    if (
      (cls.indexOf("ds-icon-button") >= 0 ||
        cls.indexOf("ds-button--primary") >= 0 ||
        cls.indexOf("ds-button--circle") >= 0 ||
        cls.indexOf("_52c986b") >= 0 ||
        cls.indexOf("_7436101") >= 0) &&
      !/attach|upload|file|plus/.test(cls)
    ) {
      return true;
    }
    return false;
  }

  function composerRoot(near) {
    try {
      var el = near || findInput();
      var n = el;
      for (var i = 0; i < 6 && n; i++) {
        n = n.parentElement;
        if (n && n.querySelector && n.querySelectorAll("[role='button'],button").length >= 2) return n;
      }
      return el ? el.parentElement : null;
    } catch (e) {
      return null;
    }
  }

  function findSend(near) {
    var root = composerRoot(near) || document.body;
    var cands = [];
    for (var i = 0; i < SEND_CANDIDATES.length; i++) {
      try {
        root.querySelectorAll(SEND_CANDIDATES[i]).forEach(function (n) {
          cands.push(n);
        });
      } catch (e) {}
    }
    var seen = new Set();
    for (var k = cands.length - 1; k >= 0; k--) {
      var n = cands[k];
      if (seen.has(n)) continue;
      seen.add(n);
      if (isSendButton(n)) return n;
    }
    return null;
  }

  function fileInput() {
    var root = composerRoot(findInput()) || document;
    var list = root.querySelectorAll("input[type='file']");
    for (var i = 0; i < list.length; i++) {
      var t = (list[i].getAttribute("accept") || "").toLowerCase();
      if (!t || t.indexOf("image") >= 0 || t.indexOf("*") >= 0) return list[i];
    }
    return list.length ? list[0] : null;
  }

  function editorText(el) {
    if (!el) return "";
    if (el.tagName === "TEXTAREA" || el.tagName === "INPUT") return String(el.value || "");
    return String(el.innerText || el.textContent || "");
  }

  function norm(s) {
    return String(s || "").replace(/\r/g, "").replace(/\u00a0/g, " ").replace(/[ \t]+/g, " ").trim();
  }

  function holds(want, el) {
    var got = norm(editorText(el));
    var w = norm(want);
    if (got === w) return true;
    if (got.replace(/\n+/g, "\n") === w.replace(/\n+/g, "\n")) return true;
    if (w.length > 2000) {
      var a = w.split(/\s+/);
      var b = got.split(/\s+/);
      if (b.length + 8 < a.length) return false;
      var j = 0;
      for (var i = 0; i < a.length && j < b.length; i++) {
        if (a[i] === b[j]) j++;
      }
      return j === a.length;
    }
    return false;
  }

  function setNativeValue(el, value) {
    var proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, "value");
    if (desc && desc.set) desc.set.call(el, value);
    else el.value = value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function writeInto(el, text) {
    if (!el) return false;
    el.focus();
    if (el.tagName === "TEXTAREA" || el.tagName === "INPUT") {
      setNativeValue(el, text);
    } else if (el.isContentEditable) {
      try {
        var sel = window.getSelection();
        var range = document.createRange();
        range.selectNodeContents(el);
        sel.removeAllRanges();
        sel.addRange(range);
      } catch (e) {}
      var ok = false;
      try {
        ok = document.execCommand("insertText", false, text);
      } catch (e) {
        ok = false;
      }
      if (!ok) {
        el.textContent = text;
      }
      try {
        el.dispatchEvent(new InputEvent("input", { bubbles: true, data: text, inputType: "insertText" }));
      } catch (e) {
        el.dispatchEvent(new Event("input", { bubbles: true }));
      }
    } else {
      return false;
    }
    return true;
  }

  function messageEls() {
    for (var i = 0; i < MESSAGE.length; i++) {
      var nodes = document.querySelectorAll(MESSAGE[i]);
      if (nodes.length) return Array.prototype.slice.call(nodes);
    }
    return [];
  }

  function cleanText(t) {
    return String(t || "")
      .split("\n")
      .filter(function (line) {
        var s = line.trim();
        if (!s) return true;
        return !/^(Copy|Copied|Download|JSON|Think|Thinking|Regenerate|Retry|Share|Stop|Edit|Like|Dislike|Report|复制|下载|重新生成)$/i.test(s);
      })
      .join("\n")
      .replace(/\n{3,}/g, "\n\n")
      .trim();
  }

  function answerText() {
    var els = messageEls();
    if (!els.length) return { ok: false, reason: "answer_missing", text: "" };
    var last = els[els.length - 1];
    var main =
      last.querySelector(".ds-assistant-message-main-content") ||
      last.querySelector(".ds-markdown") ||
      last.querySelector("[class*='markdown']") ||
      last;
    return { ok: true, text: cleanText(main.innerText || main.textContent || ""), blocks: els.length };
  }

  function generating() {
    var stops = document.querySelectorAll(
      'button[aria-label^="Stop" i],[role="button"][aria-label^="Stop" i],' +
        'button[title^="Stop" i],[aria-label^="停止" i],[aria-label^="Stop generating" i]'
    );
    for (var i = 0; i < stops.length; i++) {
      if (visible(stops[i])) return true;
    }
    var els = messageEls();
    var scope = els.length ? els[els.length - 1] : composerRoot(findInput());
    if (scope && scope.querySelector) {
      var spin = scope.querySelector(".ds-loading, [aria-busy='true'], [class*='stop-generating']");
      if (spin && visible(spin)) return true;
    }
    return false;
  }

  var DS = {
    id: "deepseek",
    version: 1,

    ready: function () {
      var el = findInput();
      var login = /sign in|log in|登录|mot de passe|password/i.test(document.title || "")
        ? false
        : !!document.querySelector('input[type="password"]');
      return J({
        ok: !!el,
        has_input: !!el,
        login_required: login,
        url: location.href,
        title: document.title
      });
    },

    // Le prompt agent peut faire plusieurs centaines de ko. Le pont Android
    // envoie des fragments modestes, puis on écrit une seule fois dans React.
    promptBegin: function () {
      PROMPT_BUFFER = "";
      return J({ ok: true });
    },

    promptChunk: function (chunk) {
      PROMPT_BUFFER += String(chunk || "");
      return J({ ok: true, len: PROMPT_BUFFER.length });
    },

    promptCommit: function () {
      var value = PROMPT_BUFFER;
      PROMPT_BUFFER = "";
      return this.setPrompt(value);
    },

    setPrompt: function (text) {
      var want = String(text || "");
      var cands = inputCandidates();
      if (!cands.length) return J({ ok: false, reason: "input_missing" });
      var tried = 0;
      for (var i = 0; i < cands.length && tried < 3; i++) {
        var el = cands[i];
        tried++;
        try {
          writeInto(el, want);
        } catch (e) {
          continue;
        }
        if (holds(want, el)) {
          return J({ ok: true, len: editorText(el).length, want: want.length, tag: el.tagName, ce: !!el.isContentEditable });
        }
      }
      var first = cands[0];
      return J({
        ok: false,
        reason: "input_not_applied",
        got: editorText(first).length,
        want: want.length,
        tag: first.tagName,
        ce: !!first.isContentEditable
      });
    },

    sendReady: function () {
      var input = findInput();
      var btn = findSend(input);
      return J({ ok: true, found: !!btn, disabled: btn ? disabled(btn) : true, dead: btn ? false : true });
    },

    submit: function () {
      var input = findInput();
      var btn = findSend(input);
      if (btn && !disabled(btn)) {
        try {
          btn.click();
          return J({ ok: true, via: "click" });
        } catch (e) {}
      }
      if (input) {
        try {
          input.focus();
          var opts = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true, cancelable: true };
          input.dispatchEvent(new KeyboardEvent("keydown", opts));
          input.dispatchEvent(new KeyboardEvent("keypress", opts));
          input.dispatchEvent(new KeyboardEvent("keyup", opts));
          return J({ ok: true, via: "enter", had_button: !!btn });
        } catch (e) {}
      }
      return J({ ok: false, reason: "send_missing" });
    },

    /** Détecte le bouton de reprise laissé par DeepSeek après la limite de sortie. */
    continueAvailable: function () {
      var nodes = continueNodes();
      for (var i = nodes.length - 1; i >= 0; i--) {
        var el = nodes[i];
        if (!visible(el)) continue;
        var label = continueLabel(el);
        if (label)
          return J({ available: true, enabled: !disabled(el), label: label, tag: el.tagName || "" });
      }
      return J({ available: false, enabled: false });
    },

    /** Clique uniquement sur une action de reprise explicite (pas sur Envoyer). */
    continueGeneration: function () {
      var nodes = continueNodes();
      for (var i = nodes.length - 1; i >= 0; i--) {
        var el = nodes[i];
        if (!visible(el) || disabled(el)) continue;
        var label = continueLabel(el);
        if (label) {
          try { el.click(); return J({ ok: true, label: label, tag: el.tagName || "" }); } catch (e) {}
        }
      }
      return J({ ok: false, reason: "continue_missing" });
    },

    stop: function () {
      var stops = document.querySelectorAll(
        'button[aria-label^="Stop" i],[role="button"][aria-label^="Stop" i],[aria-label^="停止" i],button[class*="stop"]'
      );
      for (var i = 0; i < stops.length; i++) {
        if (visible(stops[i])) {
          try {
            stops[i].click();
            return J({ ok: true });
          } catch (e) {}
        }
      }
      return J({ ok: false, reason: "stop_missing" });
    },

    generating: function () {
      return J({ ok: true, generating: generating() });
    },

    answer: function () {
      return J(answerText());
    },

    // ---- pièces jointes (images) -------------------------------------
    // Le transfert se fait en morceaux : une image de 1 Mo devient ~1,4 Mo de
    // base64, trop lourd pour un seul evaluateJavascript.
    attachBegin: function () {
      ATT = { items: [], cur: null };
      return J({ ok: true });
    },

    /** Un morceau de base64 ; `first` ouvre une nouvelle image. */
    attachChunk: function (payload) {
      try {
        var o = JSON.parse(payload);
        if (o.first || !ATT.cur) {
          if (ATT.cur) ATT.items.push(ATT.cur);
          ATT.cur = { mime: o.mime || "image/png", name: o.name || "image.png", b64: "" };
        }
        ATT.cur.b64 += (o.b64 || "");
        return J({ ok: true, n: ATT.items.length + 1, chars: ATT.cur.b64.length });
      } catch (e) {
        return J({ ok: false, reason: "chunk_json" });
      }
    },

    /** Injecte les fichiers dans l'<input type=file> du composeur. */
    attachCommit: function () {
      var input = fileInput();
      if (!input) return J({ ok: false, reason: "file_input_missing" });
      var all = (ATT.items || []).slice();
      if (ATT.cur) all.push(ATT.cur);
      if (!all.length) return J({ ok: false, reason: "nothing_to_attach" });
      try {
        var dt = new DataTransfer();
        for (var k = 0; k < all.length; k++) {
          var bin = atob(all[k].b64);
          var arr = new Uint8Array(bin.length);
          for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
          dt.items.add(new File([arr], all[k].name, { type: all[k].mime }));
        }
        input.files = dt.files;
        input.dispatchEvent(new Event("change", { bubbles: true }));
        return J({ ok: true, wanted: all.length, in_input: input.files ? input.files.length : 0 });
      } catch (e) {
        return J({ ok: false, reason: String(e && e.message || e).slice(0, 120) });
      }
    },

    /**
     * État du téléversement, vu depuis le DOM du composeur.
     * `shots` = nombre de vignettes aperçues (0..n), `busy` = indicateur de
     * chargement. On ne conclut jamais sur la seule présence de boutons.
     */
    uploadState: function () {
      var el = findInput();
      var root = composerRoot(el) || document.body;
      var files = 0;
      try {
        var fi = fileInput();
        files = fi && fi.files ? fi.files.length : 0;
      } catch (e) {}
      var shots = 0;
      try {
        shots = root.querySelectorAll(
          "img[src^='blob:'], img[src^='data:image'], [class*='thumbnail'], [class*='preview-img'], [class*='_file-item']"
        ).length;
      } catch (e) {}
      var busy = false;
      try {
        busy = !!root.querySelector("[class*='loading'], [class*='spinner'], [class*='uploading'], [aria-busy='true']");
      } catch (e) {}
      return J({ ok: true, files: files, shots: shots, busy: busy, ready: !busy && (files > 0 || shots > 0) });
    },

    clearAttachments: function () {
      try {
        var fi = fileInput();
        if (fi) {
          fi.value = "";
          fi.dispatchEvent(new Event("change", { bubbles: true }));
        }
        var el = findInput();
        var root = composerRoot(el) || document.body;
        root.querySelectorAll("[class*='close'], [class*='remove'], [class*='delete']").forEach(function (b) {
          var t = (b.getAttribute("aria-label") || "") + " " + (b.getAttribute("title") || "");
          if (/remove|delete|close|supprimer/i.test(t)) { try { b.click(); } catch (e) {} }
        });
      } catch (e) {}
      ATT = [];
      return J({ ok: true });
    },

    clearPrompt: function () {
      var el = findInput();
      if (!el) return J({ ok: false });
      try {
        if (el.tagName === "TEXTAREA") setNativeValue(el, "");
        else writeInto(el, "");
      } catch (e) {}
      return J({ ok: true });
    },

    /** Diagnostic : structure de la page, SANS le texte des conversations. */
    describe: function () {
      var el = findInput();
      var btn = findSend(el);
      var els = messageEls();
      var desc = function (n) {
        if (!n) return null;
        return {
          tag: n.tagName,
          cls: String(n.className || "").slice(0, 120),
          id: n.id || "",
          aria: n.getAttribute ? n.getAttribute("aria-label") || "" : "",
          ce: !!n.isContentEditable,
          role: n.getAttribute ? n.getAttribute("role") || "" : ""
        };
      };
      return J({
        ok: true,
        url: location.href,
        title: document.title,
        inputs: inputCandidates().length,
        input: desc(el),
        send: desc(btn),
        messages: els.length,
        last_message: desc(els.length ? els[els.length - 1] : null),
        generating: generating(),
        ua: navigator.userAgent
      });
    }
  };

  window.__napDS = DS;
  window.NemApiProvider = DS;
  return "ok";
})();

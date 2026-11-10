/* Focused DOM regression test: DeepSeek's Continue control must be detected and clicked only when enabled. */
const fs = require("fs");
const path = require("path");
const src = fs.readFileSync(path.join(__dirname, "..", "app", "src", "main", "assets", "js", "adapter-deepseek.js"), "utf8");
let clicks = 0;
const attrs = { "aria-disabled": "true", "aria-label": "Continue" };
const button = {
  innerText: "Continue",
  textContent: "Continue",
  disabled: false,
  className: "continue-button",
  getAttribute(name) { return attrs[name] || null; },
  getBoundingClientRect() { return { width: 88, height: 32 }; },
  click() { clicks++; },
};
const win = { getComputedStyle() { return { display: "block", visibility: "visible", opacity: "1" }; } };
const doc = { querySelectorAll(selector) { return selector === "button,[role='button']" ? [button] : []; } };
new Function("window", "document", "location", src)(win, doc, { href: "https://chat.deepseek.com/" });
const adapter = win.__napDS;
const waiting = JSON.parse(adapter.continueAvailable());
if (!waiting.available || waiting.enabled) throw new Error("disabled Continue button was not detected as waiting");
if (JSON.parse(adapter.continueGeneration()).ok || clicks) throw new Error("disabled Continue button was clicked");
attrs["aria-disabled"] = "false";
const ready = JSON.parse(adapter.continueAvailable());
if (!ready.available || !ready.enabled) throw new Error("enabled Continue button was not detected");
if (!JSON.parse(adapter.continueGeneration()).ok || clicks !== 1) throw new Error("Continue button was not clicked exactly once");
console.log("PASS: Continue stays pending while disabled, then is detected and clicked exactly once.");

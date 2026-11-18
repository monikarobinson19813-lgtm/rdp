import fs from "node:fs";
import assert from "node:assert/strict";

const html = fs.readFileSync("web/index.html", "utf8");
const app = fs.readFileSync("web/app.mjs", "utf8");
const headers = fs.readFileSync("web/_headers", "utf8");

assert.ok(!/<form\b/i.test(html), "F36: forms are forbidden");
assert.ok(!/type\s*=\s*["']password["']/i.test(html), "F36: password inputs are forbidden");
for (const id of ["session-pin", "unlock-pin", "unlock-pattern"]) {
  const input = html.match(new RegExp("<input[^>]+id=[\\\"']" + id + "[\\\"'][^>]*>", "i"))?.[0] || "";
  assert.match(input, /autocomplete=["']off["']/i, id + " must disable autocomplete");
  assert.match(input, /inputmode=["']numeric["']/i, id + " must use numeric input mode");
}

for (const banned of ["sessionStorage", "indexedDB", "document.cookie", "caches.", "console."]) {
  assert.ok(!app.includes(banned), "F36 banned browser storage/logging primitive: " + banned);
}
assert.ok(app.includes("localStorage.getItem(pinStorageKey(id))"));
assert.ok(app.includes("localStorage.setItem(pinStorageKey(id), JSON.stringify({fingerprint, pinnedAt: Date.now()}))"));
assert.ok(app.includes("localStorage.removeItem(pinStorageKey(id))"));
assert.ok(app.includes('error.reason === "controller reconnected"'));
assert.ok(app.includes('error.reason === "controller-control reconnected"'));
assert.ok(app.includes("error.code === 1006 || error.code === 1011"));
assert.ok(app.includes("Math.min(30000, reconnectDelayMs * 2)"));
assert.ok(app.includes("sessionPinBytes = te.encode(rawPin)"));
assert.ok(app.includes('ui.sessionPin.value = ""'));
assert.ok(app.includes("overwriteSecret(sessionPinBytes)"));
assert.ok(app.includes('const RELAY_HOST = "remotephone-relay.monikarobinson19813.workers.dev"'));
assert.ok(!app.includes("location.search") && !app.includes("location.hash"), "Remote ID must never enter page URL");

const exactCsp = "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob:; connect-src wss://remotephone-relay.monikarobinson19813.workers.dev; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
assert.ok(headers.includes(exactCsp), "F33 exact CSP missing");
assert.ok(headers.includes("Strict-Transport-Security: max-age=31536000; includeSubDomains"));
assert.ok(headers.includes("Referrer-Policy: no-referrer"));
assert.ok(headers.includes("X-Content-Type-Options: nosniff"));
assert.ok(!html.includes("http://") && !html.includes("https://"), "HTML must not load third-party resources");
assert.ok(!app.includes("analytics") && !app.includes("cdn"), "No analytics/CDN code");
console.log("WEB1 F33/F36/F37 browser policy PASS");

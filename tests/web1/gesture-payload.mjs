import fs from "node:fs";
import assert from "node:assert/strict";
import {gesturePayload} from "../../web/controller/protocol-payloads.mjs";

const payload = gesturePayload(
  {x: 0.5, y: 0.5},
  {x: 0.5, y: 0.5},
  123.456,
);

assert.equal(payload.length, 24);
const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
assert.equal(view.getFloat32(0, false), 0.5);
assert.equal(view.getFloat32(4, false), 0.5);
assert.equal(view.getFloat32(8, false), 0.5);
assert.equal(view.getFloat32(12, false), 0.5);
assert.equal(view.getBigInt64(16, false), 123n);

const out = process.argv[2];
if (!out) throw new Error("output path required");
fs.writeFileSync(out, payload);
console.log("WEB1 F39 JS gesture payload PASS");

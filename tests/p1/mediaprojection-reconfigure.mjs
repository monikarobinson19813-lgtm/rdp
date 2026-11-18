import fs from "node:fs";
import assert from "node:assert/strict";

const source = fs.readFileSync(
  "v0.5-baseline/app/src/main/java/com/remotephone/direct/HostService.java",
  "utf8",
);

const start = source.indexOf("private synchronized void configureDisplayCapture()");
assert.ok(start >= 0, "configureDisplayCapture missing");
const end = source.indexOf("\n    private ", start + 20);
const method = source.slice(start, end);

assert.equal(
  (method.match(/projection\.createVirtualDisplay\(/g) || []).length,
  1,
  "configureDisplayCapture must create the VirtualDisplay only on the initial path",
);
assert.match(method, /if \(virtualDisplay == null\)/);
assert.match(method, /virtualDisplay\.resize\(captureW, captureH, streamDensity\)/);
assert.match(method, /virtualDisplay\.setSurface\(newReader\.getSurface\(\)\)/);
assert.ok(
  method.indexOf("virtualDisplay.resize") < method.indexOf("virtualDisplay.setSurface"),
  "Android guidance requires resize before swapping the Surface",
);
assert.ok(
  !/virtualDisplay\.release\(\)/.test(method),
  "reconfiguration must not release and recreate the VirtualDisplay",
);

console.log("P1 MediaProjection reconfigure source regression PASS");

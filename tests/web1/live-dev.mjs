import https from "node:https";
import crypto from "node:crypto";
import assert from "node:assert/strict";

const base = process.argv[2];
if (!base) throw new Error("usage: node tests/web1/live-dev.mjs https://<dev-worker-host>");
const u = new URL(base);
if (u.protocol !== "https:") throw new Error("DEV Worker URL must be https");

const hostToken = crypto.randomBytes(24).toString("hex"); // runtime-only synthetic token; never logged
const baseId = 700000000 + crypto.randomInt(0, 999000);

function rid(n) { return String(baseId + n).padStart(9, "0"); }
function wsKey() { return crypto.randomBytes(16).toString("base64"); }

function open(path, extraHeaders = {}) {
  return new Promise((resolve, reject) => {
    const req = https.request({
      hostname: u.hostname,
      port: 443,
      path,
      method: "GET",
      headers: {
        Connection: "Upgrade",
        Upgrade: "websocket",
        "Sec-WebSocket-Version": "13",
        "Sec-WebSocket-Key": wsKey(),
        ...extraHeaders,
      },
    });

    req.on("upgrade", (res, socket, head) => {
      resolve({
        status: res.statusCode,
        headers: res.headers,
        socket,
        head: new Uint8Array(head),
        body: "",
      });
    });

    req.on("response", (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => resolve({
        status: res.statusCode,
        headers: res.headers,
        socket: null,
        head: new Uint8Array(),
        body: Buffer.concat(chunks).toString("utf8"),
      }));
    });

    req.on("error", reject);
    req.end();
  });
}

function close(result) {
  if (result?.socket) {
    try { result.socket.destroy(); } catch {}
  }
}

function frameBinary(payload) {
  const data = Buffer.from(payload);
  if (data.length >= 126) throw new Error("test payload too large");
  const mask = crypto.randomBytes(4);
  const out = Buffer.alloc(2 + 4 + data.length);
  out[0] = 0x82;
  out[1] = 0x80 | data.length;
  mask.copy(out, 2);
  for (let i = 0; i < data.length; i++) out[6 + i] = data[i] ^ mask[i % 4];
  return out;
}

function readOneFrame(socket, initial = new Uint8Array()) {
  return new Promise((resolve, reject) => {
    let buf = Buffer.from(initial);
    let done = false;

    function finishErr(err) {
      if (done) return;
      done = true;
      cleanup();
      reject(err);
    }

    function cleanup() {
      socket.off("data", onData);
      socket.off("error", finishErr);
      socket.off("close", onClose);
    }

    function onClose() { finishErr(new Error("socket closed before frame")); }

    function tryParse() {
      if (buf.length < 2) return false;
      const b0 = buf[0], b1 = buf[1];
      const opcode = b0 & 0x0f;
      const masked = !!(b1 & 0x80);
      let len = b1 & 0x7f;
      let off = 2;
      if (len === 126) {
        if (buf.length < 4) return false;
        len = buf.readUInt16BE(2); off = 4;
      } else if (len === 127) {
        finishErr(new Error("unexpected 64-bit websocket frame length"));
        return true;
      }
      const maskLen = masked ? 4 : 0;
      if (buf.length < off + maskLen + len) return false;
      let payload = Buffer.from(buf.subarray(off + maskLen, off + maskLen + len));
      if (masked) {
        const mask = buf.subarray(off, off + 4);
        for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
      }
      done = true;
      cleanup();
      resolve({ opcode, payload });
      return true;
    }

    function onData(chunk) {
      buf = Buffer.concat([buf, chunk]);
      tryParse();
    }

    if (!tryParse()) {
      socket.on("data", onData);
      socket.on("error", finishErr);
      socket.on("close", onClose);
    }
  });
}

async function host(id, control = false, protocol = null) {
  const headers = {
    "X-RemotePhone-Role": control ? "host-control" : "host",
    "X-RemotePhone-Host-Token": hostToken,
  };
  if (protocol) headers["Sec-WebSocket-Protocol"] = protocol;
  return open(`/relay/${id}`, headers);
}

async function controller(id, {control = false, androidHeader = false, protocol = null} = {}) {
  const headers = {};
  if (androidHeader) headers["X-RemotePhone-Role"] = control ? "controller-control" : "controller";
  if (protocol) headers["Sec-WebSocket-Protocol"] = protocol;
  return open(`/relay/${id}`, headers);
}

const tests = [];
function test(name, fn) { tests.push({name, fn}); }

test("1 Android header controller -> 101, no protocol header", async () => {
  const id = rid(1), h = await host(id), c = await controller(id, {androidHeader: true});
  try {
    assert.equal(h.status, 101);
    assert.equal(c.status, 101);
    assert.equal(c.headers["sec-websocket-protocol"], undefined);
  } finally { close(c); close(h); }
});

test("2 controller.v1 -> 101 + exact echo", async () => {
  const id = rid(2), h = await host(id), c = await controller(id, {protocol: "remotephone.controller.v1"});
  try {
    assert.equal(h.status, 101);
    assert.equal(c.status, 101);
    assert.equal(c.headers["sec-websocket-protocol"], "remotephone.controller.v1");
  } finally { close(c); close(h); }
});

test("3 controller-control.v1 -> 101 + exact echo", async () => {
  const id = rid(3), h = await host(id, true), c = await controller(id, {control: true, protocol: "remotephone.controller-control.v1"});
  try {
    assert.equal(h.status, 101);
    assert.equal(c.status, 101);
    assert.equal(c.headers["sec-websocket-protocol"], "remotephone.controller-control.v1");
  } finally { close(c); close(h); }
});

test("4 header + subprotocol -> 400", async () => {
  const id = rid(4), c = await controller(id, {androidHeader: true, protocol: "remotephone.controller.v1"});
  try { assert.equal(c.status, 400); } finally { close(c); }
});

test("5 host.v1 -> 400", async () => {
  const id = rid(5), c = await controller(id, {protocol: "remotephone.host.v1"});
  try { assert.equal(c.status, 400); } finally { close(c); }
});

test("6 both allowlisted offers -> 400", async () => {
  const id = rid(6), c = await controller(id, {protocol: "remotephone.controller.v1, remotephone.controller-control.v1"});
  try { assert.equal(c.status, 400); } finally { close(c); }
});

test("7 unknown and >256-char offers -> 400", async () => {
  const id = rid(7);
  const a = await controller(id, {protocol: "remotephone.unknown.v1"});
  const b = await controller(id, {protocol: "x".repeat(257)});
  try {
    assert.equal(a.status, 400);
    assert.equal(b.status, 400);
  } finally { close(a); close(b); }
});

test("8 Host offline -> 404", async () => {
  const id = rid(8), c = await controller(id, {protocol: "remotephone.controller.v1"});
  try {
    assert.equal(c.status, 404);
    assert.match(c.body, /Host offline/);
  } finally { close(c); }
});

test("9 binary payload browser<->host byte-identical", async () => {
  const id = rid(9), h = await host(id), c = await controller(id, {protocol: "remotephone.controller.v1"});
  try {
    assert.equal(h.status, 101);
    assert.equal(c.status, 101);

    const a = Buffer.from([0,1,2,127,128,255]);
    const hostRead = readOneFrame(h.socket, h.head);
    c.socket.write(frameBinary(a));
    const gotHost = await hostRead;
    assert.equal(gotHost.opcode, 2);
    assert.deepEqual([...gotHost.payload], [...a]);

    const b = Buffer.from([255,9,8,7,0]);
    const controllerRead = readOneFrame(c.socket, c.head);
    h.socket.write(frameBinary(b));
    const gotController = await controllerRead;
    assert.equal(gotController.opcode, 2);
    assert.deepEqual([...gotController.payload], [...b]);
  } finally { close(c); close(h); }
});

test("10 host-role request ignores Sec-WebSocket-Protocol", async () => {
  const id = rid(10), h = await host(id, false, "remotephone.controller.v1");
  try {
    assert.equal(h.status, 101);
    assert.equal(h.headers["sec-websocket-protocol"], undefined);
  } finally { close(h); }
});

let passed = 0;
for (const t of tests) {
  await t.fn();
  passed++;
  console.log(`PASS ${t.name}`);
}
assert.equal(passed, 10);
console.log("WEB1 LIVE DEV F32 PASS: 10/10");

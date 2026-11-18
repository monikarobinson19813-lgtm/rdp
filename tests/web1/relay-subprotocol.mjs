import assert from "node:assert/strict";

class TestResponse {
  constructor(body = null, init = {}) {
    this.body = body;
    this.status = init.status ?? 200;
    this.headers = new Headers(init.headers || {});
    this.webSocket = init.webSocket || null;
  }
  static json(value, init = {}) {
    return new TestResponse(JSON.stringify(value), {
      ...init,
      status: init.status ?? 200,
      headers: { "content-type": "application/json", ...(init.headers || {}) },
    });
  }
}
globalThis.Response = TestResponse;

class FakeSocket {
  constructor() {
    this.readyState = 1;
    this.sent = [];
    this.closed = [];
    this.tags = [];
    this.attachment = null;
  }
  serializeAttachment(value) { this.attachment = structuredClone(value); }
  deserializeAttachment() { return this.attachment; }
  send(value) {
    if (value instanceof ArrayBuffer) this.sent.push(value.slice(0));
    else if (ArrayBuffer.isView(value)) this.sent.push(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength));
    else this.sent.push(value);
  }
  close(code, reason) { this.closed.push({ code, reason }); this.readyState = 3; }
}

globalThis.WebSocketPair = function WebSocketPair() {
  return { 0: new FakeSocket(), 1: new FakeSocket() };
};

class FakeStorage {
  constructor() { this.map = new Map(); }
  async get(key) { return this.map.get(key); }
  async put(key, value) { this.map.set(key, value); }
}

class FakeState {
  constructor() { this.storage = new FakeStorage(); this.sockets = []; }
  acceptWebSocket(ws, tags) { ws.tags = [...tags]; this.sockets.push(ws); }
  getWebSockets(tag) { return this.sockets.filter(ws => ws.tags.includes(tag)); }
}

const { RelayRoom } = await import("../../relay/cloudflare/src/index.js");

const URL = "https://relay.internal/relay/123456789";
const HOST_TOKEN = "synthetic-host-token-0000000000000000";

function request(headers = {}) {
  return new Request(URL, { headers });
}

async function connectHost(room, extra = {}) {
  return room.fetch(request({
    "X-RemotePhone-Role": "host",
    "X-RemotePhone-Host-Token": HOST_TOKEN,
    ...extra,
  }));
}

async function withOnlineHost() {
  const state = new FakeState();
  const room = new RelayRoom(state, {});
  const host = await connectHost(room);
  assert.equal(host.status, 101);
  return { state, room, host };
}

const tests = [];
function test(name, fn) { tests.push({ name, fn }); }

test("1 Android header controller -> 101, no protocol header", async () => {
  const { room } = await withOnlineHost();
  const r = await room.fetch(request({ "X-RemotePhone-Role": "controller" }));
  assert.equal(r.status, 101);
  assert.equal(r.headers.get("Sec-WebSocket-Protocol"), null);
});

test("2 controller.v1 -> 101 + exact echo", async () => {
  const { room } = await withOnlineHost();
  const r = await room.fetch(request({ "Sec-WebSocket-Protocol": "remotephone.controller.v1" }));
  assert.equal(r.status, 101);
  assert.equal(r.headers.get("Sec-WebSocket-Protocol"), "remotephone.controller.v1");
});

test("3 controller-control.v1 -> 101 + exact echo", async () => {
  const state = new FakeState();
  const room = new RelayRoom(state, {});
  const host = await room.fetch(request({
    "X-RemotePhone-Role": "host-control",
    "X-RemotePhone-Host-Token": HOST_TOKEN,
  }));
  assert.equal(host.status, 101);
  const r = await room.fetch(request({ "Sec-WebSocket-Protocol": "remotephone.controller-control.v1" }));
  assert.equal(r.status, 101);
  assert.equal(r.headers.get("Sec-WebSocket-Protocol"), "remotephone.controller-control.v1");
});

test("4 header + subprotocol -> 400", async () => {
  const { room } = await withOnlineHost();
  const r = await room.fetch(request({
    "X-RemotePhone-Role": "controller",
    "Sec-WebSocket-Protocol": "remotephone.controller.v1",
  }));
  assert.equal(r.status, 400);
});

test("5 remotephone.host.v1 -> 400", async () => {
  const state = new FakeState();
  const room = new RelayRoom(state, {});
  const r = await room.fetch(request({ "Sec-WebSocket-Protocol": "remotephone.host.v1" }));
  assert.equal(r.status, 400);
});

test("6 both allowlisted tokens in one offer -> 400", async () => {
  const { room } = await withOnlineHost();
  const r = await room.fetch(request({
    "Sec-WebSocket-Protocol": "remotephone.controller.v1, remotephone.controller-control.v1",
  }));
  assert.equal(r.status, 400);
});

test("7 unknown or oversized protocol -> 400", async () => {
  const { room } = await withOnlineHost();
  const unknown = await room.fetch(request({ "Sec-WebSocket-Protocol": "remotephone.unknown.v1" }));
  assert.equal(unknown.status, 400);
  const oversized = await room.fetch(request({ "Sec-WebSocket-Protocol": "x".repeat(257) }));
  assert.equal(oversized.status, 400);
});

test("8 Host offline -> 404", async () => {
  const state = new FakeState();
  const room = new RelayRoom(state, {});
  const r = await room.fetch(request({ "Sec-WebSocket-Protocol": "remotephone.controller.v1" }));
  assert.equal(r.status, 404);
});

test("9 binary browser<->host payload is byte-identical", async () => {
  const { state, room } = await withOnlineHost();
  const browser = await room.fetch(request({ "Sec-WebSocket-Protocol": "remotephone.controller.v1" }));
  assert.equal(browser.status, 101);
  const hostServer = state.getWebSockets("host")[0];
  const controllerServer = state.getWebSockets("controller")[0];

  const a = new Uint8Array([0, 1, 2, 127, 128, 255]).buffer;
  room.webSocketMessage(controllerServer, a);
  assert.deepEqual([...new Uint8Array(hostServer.sent.at(-1))], [...new Uint8Array(a)]);

  const b = new Uint8Array([255, 9, 8, 7, 0]).buffer;
  room.webSocketMessage(hostServer, b);
  assert.deepEqual([...new Uint8Array(controllerServer.sent.at(-1))], [...new Uint8Array(b)]);
});

test("10 host-role request ignores Sec-WebSocket-Protocol", async () => {
  const state = new FakeState();
  const room = new RelayRoom(state, {});
  const r = await connectHost(room, { "Sec-WebSocket-Protocol": "remotephone.controller.v1" });
  assert.equal(r.status, 101);
  assert.equal(r.headers.get("Sec-WebSocket-Protocol"), null);
});

let passed = 0;
for (const t of tests) {
  await t.fn();
  passed++;
  console.log(`PASS ${t.name}`);
}
assert.equal(passed, 10);
console.log("WEB1 F32 PASS: 10/10");

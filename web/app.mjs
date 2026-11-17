import {BrowserCryptoChannel, ByteStreamReader} from "./controller/crypto-channel.mjs";
import {gesturePayload} from "./controller/protocol-payloads.mjs";

const RELAY_HOST = "remotephone-relay.monikarobinson19813.workers.dev";
const MAIN_PROTOCOL = "remotephone.controller.v1";
const CONTROL_PROTOCOL = "remotephone.controller-control.v1";

const TYPE_FRAME = 1;
const TYPE_GESTURE = 2;
const TYPE_NAV = 3;
const TYPE_TEXT = 4;
const TYPE_PING = 5;
const TYPE_INFO = 6;
const TYPE_CONTROL = 8;
const TYPE_STATUS = 9;
const TYPE_UNLOCK = 10;
const TYPE_UNLOCK_RESULT = 11;
const TYPE_RECOVERY_FRAME = 12;
const TYPE_STREAM_QUALITY = 14;

const CONTROL_WAKE = 1;
const STREAM_QUALITY_AUTO = 0;
const UNLOCK_PIN = 1;
const UNLOCK_PATTERN = 2;

const te = new TextEncoder();
const td = new TextDecoder();

const ui = {
  connectPanel: document.querySelector("#connect-panel"),
  identityPanel: document.querySelector("#identity-panel"),
  viewerPanel: document.querySelector("#viewer-panel"),
  remoteId: document.querySelector("#remote-id"),
  sessionPin: document.querySelector("#session-pin"),
  connect: document.querySelector("#connect"),
  forget: document.querySelector("#forget"),
  connectStatus: document.querySelector("#connect-status"),
  identityCode: document.querySelector("#identity-code"),
  identityConfirm: document.querySelector("#identity-confirm"),
  identityCancel: document.querySelector("#identity-cancel"),
  remoteStatus: document.querySelector("#remote-status"),
  disconnect: document.querySelector("#disconnect"),
  screenWrap: document.querySelector("#screen-wrap"),
  screen: document.querySelector("#screen"),
  wake: document.querySelector("#wake"),
  textInput: document.querySelector("#text-input"),
  sendText: document.querySelector("#send-text"),
  unlockPanel: document.querySelector("#unlock-panel"),
  unlockPin: document.querySelector("#unlock-pin"),
  unlockPattern: document.querySelector("#unlock-pattern"),
  sendUnlockPin: document.querySelector("#send-unlock-pin"),
  sendUnlockPattern: document.querySelector("#send-unlock-pattern"),
  unlockStatus: document.querySelector("#unlock-status"),
};

let remoteId = "";
let sessionPinBytes = null;
let mainChannel = null;
let controlChannel = null;
let mainTransport = null;
let controlTransport = null;
let reconnectTimer = 0;
let reconnectDelayMs = 1000;
let manualDisconnect = true;
let takeover = false;
let generation = 0;
let pingTimer = 0;
let frameUrl = "";
let pointerStart = null;

function digits(value) {
  return (value || "").replace(/[^0-9]/g, "");
}

function pinStorageKey(id) {
  return "remotephone.host." + id;
}

function loadFingerprintPin(id) {
  try {
    const raw = localStorage.getItem(pinStorageKey(id));
    if (!raw) return null;
    const value = JSON.parse(raw);
    if (!value || typeof value.fingerprint !== "string" || typeof value.pinnedAt !== "number") return null;
    if (!/^[0-9a-f]{64}$/.test(value.fingerprint)) return null;
    return {fingerprint: value.fingerprint, pinnedAt: value.pinnedAt};
  } catch (_) {
    return null;
  }
}

function saveFingerprintPin(id, fingerprint) {
  localStorage.setItem(pinStorageKey(id), JSON.stringify({fingerprint, pinnedAt: Date.now()}));
}

function removeFingerprintPin(id) {
  localStorage.removeItem(pinStorageKey(id));
}

function verificationCode(fingerprint) {
  return fingerprint.slice(0, 20).match(/.{4}/g).join(" ");
}

function overwriteSecret(bytes) {
  if (bytes instanceof Uint8Array) bytes.fill(0);
}

function setStatus(message) {
  ui.connectStatus.textContent = message;
  ui.remoteStatus.textContent = message;
}

class RelayClosedError extends Error {
  constructor(code, reason) {
    super("relay closed");
    this.code = code;
    this.reason = reason || "";
  }
}

function openRelay(id, protocol) {
  const reader = new ByteStreamReader();
  const ws = new WebSocket("wss://" + RELAY_HOST + "/relay/" + id, protocol);
  ws.binaryType = "arraybuffer";

  const opened = new Promise((resolve, reject) => {
    ws.addEventListener("open", resolve, {once: true});
    ws.addEventListener("error", () => reject(new Error("relay connection failed")), {once: true});
  });

  let closeResolve;
  const closed = new Promise((resolve) => { closeResolve = resolve; });

  ws.addEventListener("message", (event) => {
    if (event.data instanceof ArrayBuffer) reader.feed(new Uint8Array(event.data));
  });

  ws.addEventListener("close", (event) => {
    const error = new RelayClosedError(event.code, event.reason);
    reader.close(error);
    closeResolve(error);
  }, {once: true});

  return {
    reader,
    ws,
    opened,
    closed,
    write: async (bytes) => {
      if (ws.readyState !== WebSocket.OPEN) throw new Error("relay not open");
      ws.send(bytes);
    },
    close: () => {
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) ws.close(1000, "client disconnect");
    },
  };
}

function isTakeoverClose(error) {
  return error && error.code === 1012 &&
    (error.reason === "controller reconnected" || error.reason === "controller-control reconnected");
}

function shouldAutoReconnect(error) {
  if (!error || isTakeoverClose(error)) return false;
  return error.code === 1006 || error.code === 1011;
}

function confirmFingerprint(fingerprint) {
  ui.identityCode.textContent = verificationCode(fingerprint);
  ui.identityPanel.classList.remove("hidden");
  ui.connectStatus.textContent = "Compare the identity code with the Host before continuing.";

  return new Promise((resolve) => {
    const finish = (value) => {
      ui.identityConfirm.removeEventListener("click", yes);
      ui.identityCancel.removeEventListener("click", no);
      ui.identityPanel.classList.add("hidden");
      resolve(value);
    };
    const yes = () => finish(true);
    const no = () => finish(false);
    ui.identityConfirm.addEventListener("click", yes);
    ui.identityCancel.addEventListener("click", no);
  });
}

async function connectChannel(id, protocol, expectedFingerprint, allowOob) {
  const transport = openRelay(id, protocol);
  await transport.opened;
  const channel = await BrowserCryptoChannel.connect({
    cryptoApi: crypto,
    reader: transport.reader,
    write: transport.write,
    pinBytes: sessionPinBytes,
    expectedFingerprint,
    confirmFingerprint: allowOob ? confirmFingerprint : undefined,
  });
  return {transport, channel};
}

async function connectSession(isReconnect = false) {
  if (!sessionPinBytes || sessionPinBytes.length !== 6 || !remoteId) return;
  const myGeneration = ++generation;
  clearReconnect();
  closeSockets();
  setStatus(isReconnect ? "Reconnecting…" : "Connecting…");

  try {
    const saved = loadFingerprintPin(remoteId);
    const expected = saved ? saved.fingerprint : "";

    const main = await connectChannel(remoteId, MAIN_PROTOCOL, expected, !expected);
    if (myGeneration !== generation) {
      main.transport.close();
      return;
    }

    if (!expected) saveFingerprintPin(remoteId, main.channel.fingerprint);

    mainTransport = main.transport;
    mainChannel = main.channel;

    const control = await connectChannel(remoteId, CONTROL_PROTOCOL, main.channel.fingerprint, false);
    if (myGeneration !== generation) {
      control.transport.close();
      return;
    }

    controlTransport = control.transport;
    controlChannel = control.channel;

    manualDisconnect = false;
    takeover = false;
    reconnectDelayMs = 1000;
    ui.connectPanel.classList.add("hidden");
    ui.viewerPanel.classList.remove("hidden");
    setStatus(isReconnect ? "Reconnected to Host" : "Connected to Host");
    await mainChannel.send(TYPE_STREAM_QUALITY, new Uint8Array([STREAM_QUALITY_AUTO]));

    watchTransport(main.transport, myGeneration);
    watchTransport(control.transport, myGeneration);
    readLoop(main.channel, myGeneration);
    readLoop(control.channel, myGeneration);
    startPing(myGeneration);
  } catch (error) {
    closeSockets();
    if (myGeneration !== generation || manualDisconnect || takeover) return;
    ui.connectPanel.classList.remove("hidden");
    ui.viewerPanel.classList.add("hidden");
    ui.connectStatus.textContent = safeError(error);
    scheduleReconnect(error);
  }
}

function watchTransport(transport, myGeneration) {
  transport.closed.then((error) => {
    if (myGeneration !== generation || manualDisconnect) return;
    if (isTakeoverClose(error)) {
      takeover = true;
      clearReconnect();
      closeSockets();
      ui.viewerPanel.classList.add("hidden");
      ui.connectPanel.classList.remove("hidden");
      ui.connectStatus.textContent = "Another Controller took over. Click Connect to reconnect manually.";
      return;
    }
    scheduleReconnect(error);
  });
}

function scheduleReconnect(error) {
  if (manualDisconnect || takeover || reconnectTimer || !shouldAutoReconnect(error)) return;
  const delay = reconnectDelayMs;
  reconnectDelayMs = Math.min(30000, reconnectDelayMs * 2);
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = 0;
    connectSession(true);
  }, delay);
}

function clearReconnect() {
  if (reconnectTimer) window.clearTimeout(reconnectTimer);
  reconnectTimer = 0;
}

function closeSockets() {
  stopPing();
  const main = mainTransport;
  const control = controlTransport;
  mainTransport = null;
  controlTransport = null;
  mainChannel = null;
  controlChannel = null;
  if (main) main.close();
  if (control) control.close();
}

function startPing(myGeneration) {
  stopPing();
  pingTimer = window.setInterval(async () => {
    if (myGeneration !== generation || !mainChannel) return;
    try {
      await mainChannel.send(TYPE_PING, new Uint8Array());
    } catch (_) {}
  }, 3000);
}

function stopPing() {
  if (pingTimer) window.clearInterval(pingTimer);
  pingTimer = 0;
}

async function readLoop(channel, myGeneration) {
  try {
    while (myGeneration === generation) {
      const message = await channel.read();
      if (channel === mainChannel) handleMainMessage(message);
      else if (channel === controlChannel) handleControlMessage(message);
    }
  } catch (_) {
    // Transport close handling owns reconnect policy.
  }
}

function handleMainMessage(message) {
  if (message.type === TYPE_FRAME || message.type === TYPE_RECOVERY_FRAME) {
    renderFrame(message.payload);
  } else if (message.type === TYPE_INFO) {
    // Dimensions are carried in the stream; gesture coordinates are normalized.
  } else if (message.type === TYPE_STATUS) {
    const status = td.decode(message.payload);
    ui.remoteStatus.textContent = status;
    ui.unlockPanel.classList.toggle("hidden", status !== "Host locked");
  } else if (message.type === TYPE_UNLOCK_RESULT) {
    showUnlockResult(message.payload);
  }
}

function handleControlMessage(message) {
  if (message.type === TYPE_STATUS) {
    const status = td.decode(message.payload);
    ui.remoteStatus.textContent = status;
    ui.unlockPanel.classList.toggle("hidden", status !== "Host locked");
  } else if (message.type === TYPE_UNLOCK_RESULT) {
    showUnlockResult(message.payload);
  }
}

function renderFrame(payload) {
  if (!(payload instanceof Uint8Array) || payload.length < 20) return;
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const jpegLength = view.getInt32(16, false);
  if (jpegLength < 1 || 20 + jpegLength > payload.length) return;
  const blob = new Blob([payload.slice(20, 20 + jpegLength)], {type: "image/jpeg"});
  const next = URL.createObjectURL(blob);
  ui.screen.onload = () => {
    if (frameUrl) URL.revokeObjectURL(frameUrl);
    frameUrl = next;
  };
  ui.screen.src = next;
}

function normalizedPoint(event) {
  const rect = ui.screen.getBoundingClientRect();
  if (!rect.width || !rect.height) return null;
  return {
    x: Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)),
    y: Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height)),
  };
}

async function sendOnce(channel, type, payload) {
  if (!channel) throw new Error("Host connection unavailable");
  await channel.send(type, payload);
}

async function sendUnlock(method, input) {
  const raw = input.value;
  const normalized = method === UNLOCK_PATTERN ? raw.replace(/[^1-9]/g, "") : raw.trim();
  input.value = "";

  if (method === UNLOCK_PIN && !/^[0-9]{4,16}$/.test(normalized)) {
    ui.unlockStatus.textContent = "Enter the Host device PIN (4–16 digits).";
    return;
  }
  if (method === UNLOCK_PATTERN) {
    if (!/^[1-9]{4,9}$/.test(normalized) || new Set(normalized).size !== normalized.length) {
      ui.unlockStatus.textContent = "Enter 4–9 unique pattern points.";
      return;
    }
  }

  const secret = te.encode(normalized);
  const payload = new Uint8Array(secret.length + 9);
  payload[0] = method;
  crypto.getRandomValues(payload.subarray(1, 9));
  payload.set(secret, 9);
  overwriteSecret(secret);

  const channel = controlChannel || mainChannel;
  try {
    await sendOnce(channel, TYPE_UNLOCK, payload);
    ui.unlockStatus.textContent = "Unlock sent once — waiting for Host.";
  } catch (_) {
    ui.unlockStatus.textContent = "Unlock was not sent. Reconnect before trying again.";
  } finally {
    overwriteSecret(payload);
  }
}

function showUnlockResult(payload) {
  const code = payload && payload.length ? payload[0] : 0;
  const labels = {
    1: "Unlock attempt accepted — checking Host.",
    2: "Remote secure unlock unsupported by this Android/OEM.",
    3: "Host is no longer locked.",
    5: "Credential surface not ready.",
    6: "Manual unlock required.",
    7: "Unlock cooldown active — wait before trying again.",
  };
  ui.unlockStatus.textContent = labels[code] || "Unlock request rejected.";
}

function safeError(error) {
  if (error && error.message === "Host identity changed") return "Host identity changed. Use Forget Host, then verify the Host identity again.";
  if (error && error.message === "fingerprint not confirmed") return "Host identity was not confirmed.";
  if (error && error.message === "Host authentication failed") return "Session authentication failed.";
  return "Connection unavailable.";
}

function fullDisconnect(message = "Disconnected") {
  manualDisconnect = true;
  takeover = false;
  generation++;
  clearReconnect();
  closeSockets();
  overwriteSecret(sessionPinBytes);
  sessionPinBytes = null;
  remoteId = "";
  ui.viewerPanel.classList.add("hidden");
  ui.identityPanel.classList.add("hidden");
  ui.connectPanel.classList.remove("hidden");
  ui.sessionPin.value = "";
  ui.unlockPin.value = "";
  ui.unlockPattern.value = "";
  ui.connectStatus.textContent = message;
}

ui.connect.addEventListener("click", () => {
  const id = digits(ui.remoteId.value);
  const rawPin = ui.sessionPin.value.trim();
  if (id.length !== 9) {
    ui.connectStatus.textContent = "Enter a 9-digit Remote ID.";
    return;
  }
  if (!/^[0-9]{6}$/.test(rawPin)) {
    ui.connectStatus.textContent = "Enter the 6-digit session PIN.";
    return;
  }

  overwriteSecret(sessionPinBytes);
  sessionPinBytes = te.encode(rawPin);
  ui.sessionPin.value = "";
  remoteId = id;
  manualDisconnect = false;
  takeover = false;
  reconnectDelayMs = 1000;
  connectSession(false);
});

ui.disconnect.addEventListener("click", () => fullDisconnect("Disconnected from Host"));

ui.forget.addEventListener("click", () => {
  const id = digits(ui.remoteId.value);
  if (id.length !== 9) {
    ui.connectStatus.textContent = "Enter the Host Remote ID to forget its identity pin.";
    return;
  }
  removeFingerprintPin(id);
  fullDisconnect("Host identity forgotten. Next connection requires a fresh Host identity check.");
  ui.remoteId.value = id;
});

ui.wake.addEventListener("click", async () => {
  try {
    await sendOnce(controlChannel || mainChannel, TYPE_CONTROL, new Uint8Array([CONTROL_WAKE]));
  } catch (_) {
    setStatus("Wake could not be sent.");
  }
});

document.querySelectorAll("[data-nav]").forEach((button) => {
  button.addEventListener("click", async () => {
    try {
      await sendOnce(mainChannel, TYPE_NAV, new Uint8Array([Number(button.dataset.nav)]));
    } catch (_) {}
  });
});

ui.sendText.addEventListener("click", async () => {
  const value = ui.textInput.value;
  if (!value) return;
  ui.textInput.value = "";
  try { await sendOnce(mainChannel, TYPE_TEXT, te.encode(value)); } catch (_) {}
});

ui.sendUnlockPin.addEventListener("click", () => sendUnlock(UNLOCK_PIN, ui.unlockPin));
ui.sendUnlockPattern.addEventListener("click", () => sendUnlock(UNLOCK_PATTERN, ui.unlockPattern));

ui.screenWrap.addEventListener("pointerdown", (event) => {
  const point = normalizedPoint(event);
  if (!point) return;
  pointerStart = {point, at: performance.now(), id: event.pointerId};
  ui.screenWrap.setPointerCapture(event.pointerId);
});

ui.screenWrap.addEventListener("pointerup", async (event) => {
  if (!pointerStart || pointerStart.id !== event.pointerId) return;
  const end = normalizedPoint(event);
  const start = pointerStart;
  pointerStart = null;
  if (!end) return;
  try {
    await sendOnce(mainChannel, TYPE_GESTURE, gesturePayload(start.point, end, performance.now() - start.at));
  } catch (_) {
    setStatus("Gesture could not be sent.");
  }
});

window.addEventListener("pagehide", () => {
  manualDisconnect = true;
  generation++;
  clearReconnect();
  closeSockets();
  overwriteSecret(sessionPinBytes);
  sessionPinBytes = null;
  overwriteSecret(te.encode(ui.unlockPin.value));
  overwriteSecret(te.encode(ui.unlockPattern.value));
  ui.sessionPin.value = "";
  ui.unlockPin.value = "";
  ui.unlockPattern.value = "";
});

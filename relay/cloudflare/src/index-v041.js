import base, { RelayRoom as BaseRelayRoom } from "./index.js";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/version") {
      return new Response("RemotePhone relay v0.4.1-control", { status: 200 });
    }
    return base.fetch(request, env);
  },
};

export class RelayRoom extends BaseRelayRoom {
  constructor(state, env) {
    super(state, env);
    this.hostControl = null;
    this.controllerControl = null;
    this.hostControlForwardChain = Promise.resolve();
    this.controllerControlForwardChain = Promise.resolve();
  }

  async fetch(request) {
    const role = (request.headers.get("X-RemotePhone-Role") || "").toLowerCase();
    if (role !== "host-control" && role !== "controller-control") {
      return super.fetch(request);
    }

    await this.updateDiag({
      lastControlRequestRole: role,
      lastControlRequestAt: Date.now(),
    });

    if (role === "host-control") {
      const token = request.headers.get("X-RemotePhone-Host-Token") || "";
      if (token.length < 32) {
        await this.updateDiag({ lastHostControlResult: "invalid-token", lastHostControlResultAt: Date.now() });
        return new Response("Invalid Host token", { status: 401 });
      }

      const tokenHash = await sha256Hex(token);
      const storedHash = await this.state.storage.get("hostTokenHash");
      if (!storedHash) {
        await this.state.storage.put("hostTokenHash", tokenHash);
      } else if (!timingSafeEqual(storedHash, tokenHash)) {
        await this.updateDiag({ lastHostControlResult: "token-mismatch", lastHostControlResultAt: Date.now() });
        return new Response("Remote ID belongs to another Host", { status: 403 });
      }

      if (isOpen(this.hostControl)) {
        try { this.hostControl.close(1012, "Host control reconnected"); } catch (_) {}
      }
      this.hostControl = null;
      await this.updateDiag({ lastHostControlResult: "accepted", lastHostControlAcceptedAt: Date.now() });
      return this.acceptControlSocket("host-control");
    }

    if (!isOpen(this.hostControl)) {
      await this.updateDiag({ lastControllerControlResult: "host-offline", lastControllerControlResultAt: Date.now() });
      return new Response("Host control offline", { status: 404 });
    }

    if (isOpen(this.controllerControl)) {
      try { this.controllerControl.close(1012, "Controller control reconnected"); } catch (_) {}
    }
    this.controllerControl = null;
    await this.updateDiag({ lastControllerControlResult: "accepted", lastControllerControlAcceptedAt: Date.now() });
    return this.acceptControlSocket("controller-control");
  }

  acceptControlSocket(role) {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    if (role === "host-control") {
      this.hostControl = server;
      this.hostControlForwardChain = Promise.resolve();
    } else {
      this.controllerControl = server;
      this.controllerControlForwardChain = Promise.resolve();
    }

    server.addEventListener("message", event => {
      const sourceAtReceive = server;
      const peerAtReceive = role === "host-control" ? this.controllerControl : this.hostControl;
      const prior = role === "host-control"
        ? this.hostControlForwardChain
        : this.controllerControlForwardChain;
      const next = prior.catch(() => {}).then(() =>
        this.forwardControlMessage(role, event.data, sourceAtReceive, peerAtReceive));
      if (role === "host-control") this.hostControlForwardChain = next;
      else this.controllerControlForwardChain = next;
      this.state.waitUntil(next.catch(() => {}));
    });

    const cleanup = () => {
      const now = Date.now();
      if (role === "host-control" && this.hostControl === server) {
        this.hostControl = null;
        this.state.waitUntil(this.updateDiag({ lastHostControlClosedAt: now }));
        if (isOpen(this.controllerControl)) {
          try { this.controllerControl.close(1011, "Host control disconnected"); } catch (_) {}
        }
        this.controllerControl = null;
      } else if (role === "controller-control" && this.controllerControl === server) {
        this.controllerControl = null;
        this.state.waitUntil(this.updateDiag({ lastControllerControlClosedAt: now }));
      }
    };

    server.addEventListener("close", cleanup);
    server.addEventListener("error", cleanup);

    return new Response(null, { status: 101, webSocket: client });
  }

  async forwardControlMessage(role, data, sourceAtReceive, peerAtReceive) {
    const sourceNow = role === "host-control" ? this.hostControl : this.controllerControl;
    const peerNow = role === "host-control" ? this.controllerControl : this.hostControl;
    if (sourceNow !== sourceAtReceive || peerNow !== peerAtReceive || !isOpen(peerAtReceive)) return;

    try {
      const payload = await normalizeWebSocketData(data);
      peerAtReceive.send(payload);
      await this.updateDiag({
        lastControlForwardFrom: role,
        lastControlForwardAt: Date.now(),
        lastControlForwardResult: "sent",
      });
    } catch (e) {
      await this.updateDiag({
        lastControlForwardFrom: role,
        lastControlForwardAt: Date.now(),
        lastControlForwardResult: "send-error",
        lastControlForwardError: safeError(e),
      });
    }
  }
}

function isOpen(ws) {
  return !!ws && ws.readyState === 1;
}

async function normalizeWebSocketData(data) {
  if (typeof Blob !== "undefined" && data instanceof Blob) return await data.arrayBuffer();
  return data;
}

function safeError(e) {
  if (!e) return "unknown";
  return String(e.message || e);
}

async function sha256Hex(text) {
  const bytes = new TextEncoder().encode(text);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(digest, b => b.toString(16).padStart(2, "0")).join("");
}

function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

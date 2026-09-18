const DEBUG_KEY = "da8c114f29b55812b9dabbb473815874";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response("RemotePhone relay OK", { status: 200 });
    }

    const debugMatch = url.pathname.match(/^\/debug\/(\d{9})$/);
    if (debugMatch) {
      if (url.searchParams.get("key") !== DEBUG_KEY) {
        return new Response("Forbidden", { status: 403 });
      }
      const roomId = env.RELAY.idFromName(debugMatch[1]);
      const debugRequest = new Request("https://relay.internal/debug", {
        headers: { "X-RemotePhone-Debug": DEBUG_KEY },
      });
      return env.RELAY.get(roomId).fetch(debugRequest);
    }

    const match = url.pathname.match(/^\/relay\/(\d{9})$/);
    if (!match) return new Response("Not found", { status: 404 });
    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
      return new Response("WebSocket required", { status: 426 });
    }

    const roomId = env.RELAY.idFromName(match[1]);
    return env.RELAY.get(roomId).fetch(request);
  },
};

export class RelayRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.host = null;
    this.controller = null;
    this.hostControl = null;
    this.controllerControl = null;
    this.hostMessages = 0;
    this.controllerMessages = 0;
    this.hostBytes = 0;
    this.controllerBytes = 0;
    this.hostConnectedAt = 0;
    this.controllerConnectedAt = 0;
    this.lastHostMessageAt = 0;
    this.lastControllerMessageAt = 0;
    this.lastTrafficDiagPersistAt = 0;
  }

  async fetch(request) {
    if (request.headers.get("X-RemotePhone-Debug") === DEBUG_KEY) {
      const persistent = await this.state.storage.get("diag") || {};
      return Response.json({
        hostOpen: isOpen(this.host),
        controllerOpen: isOpen(this.controller),
        hostControlOpen: isOpen(this.hostControl),
        controllerControlOpen: isOpen(this.controllerControl),
        hostMessages: this.hostMessages,
        controllerMessages: this.controllerMessages,
        hostBytes: this.hostBytes,
        controllerBytes: this.controllerBytes,
        hostConnectedAt: this.hostConnectedAt,
        controllerConnectedAt: this.controllerConnectedAt,
        lastHostMessageAt: this.lastHostMessageAt,
        lastControllerMessageAt: this.lastControllerMessageAt,
        persistent,
      });
    }

    const role = (request.headers.get("X-RemotePhone-Role") || "").toLowerCase();
    const validRoles = new Set(["host", "controller", "host-control", "controller-control"]);
    if (!validRoles.has(role)) {
      return new Response("Missing role", { status: 400 });
    }

    await this.updateDiag({
      lastRequestRole: role,
      lastRequestAt: Date.now(),
    });

    const isHostRole = role === "host" || role === "host-control";
    const isControlRole = role === "host-control" || role === "controller-control";

    if (isHostRole) {
      await this.bumpDiag(isControlRole ? "hostControlAttempts" : "hostAttempts");
      const token = request.headers.get("X-RemotePhone-Host-Token") || "";
      if (token.length < 32) {
        await this.updateDiag({
          lastHostResult: "invalid-token",
          lastHostResultAt: Date.now(),
          lastHostRole: role,
        });
        return new Response("Invalid Host token", { status: 401 });
      }

      const tokenHash = await sha256Hex(token);
      const storedHash = await this.state.storage.get("hostTokenHash");
      if (!storedHash) {
        await this.state.storage.put("hostTokenHash", tokenHash);
      } else if (!timingSafeEqual(storedHash, tokenHash)) {
        await this.updateDiag({
          lastHostResult: "token-mismatch",
          lastHostResultAt: Date.now(),
          lastHostRole: role,
        });
        return new Response("Remote ID belongs to another Host", { status: 403 });
      }

      const existing = isControlRole ? this.hostControl : this.host;
      if (isOpen(existing)) {
        try { existing.close(1012, "Host reconnected"); } catch (_) {}
      }
      if (isControlRole) this.hostControl = null;
      else this.host = null;
      await this.updateDiag({
        lastHostResult: "accepted",
        lastHostAcceptedAt: Date.now(),
        lastHostRole: role,
      });
      return this.acceptSocket(role);
    }

    await this.bumpDiag(isControlRole ? "controllerControlAttempts" : "controllerAttempts");
    const hostPeer = isControlRole ? this.hostControl : this.host;
    if (!isOpen(hostPeer)) {
      await this.updateDiag({
        lastControllerResult: "host-offline",
        lastControllerResultAt: Date.now(),
        lastControllerRole: role,
      });
      return new Response("Host offline", { status: 404 });
    }

    const existingController = isControlRole ? this.controllerControl : this.controller;
    if (isOpen(existingController)) {
      try { existingController.close(1012, "Controller reconnected"); } catch (_) {}
    }
    if (isControlRole) this.controllerControl = null;
    else this.controller = null;
    await this.updateDiag({
      lastControllerResult: "accepted",
      lastControllerAcceptedAt: Date.now(),
      lastControllerRole: role,
    });
    return this.acceptSocket(role);
  }

  acceptSocket(role) {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    if (role === "host") {
      this.host = server;
      this.hostConnectedAt = Date.now();
    } else if (role === "controller") {
      this.controller = server;
      this.controllerConnectedAt = Date.now();
    } else if (role === "host-control") {
      this.hostControl = server;
    } else {
      this.controllerControl = server;
    }

    server.addEventListener("message", event => {
      const now = Date.now();
      const size = messageSize(event.data);
      const type = messageType(event.data);
      if (role === "host" || role === "host-control") {
        this.hostMessages++;
        this.hostBytes += size;
        this.lastHostMessageAt = now;
      } else {
        this.controllerMessages++;
        this.controllerBytes += size;
        this.lastControllerMessageAt = now;
      }

      // Hot path: forward immediately. Main screen traffic and lightweight
      // control traffic use independent socket pairs.
      const peerAtReceive = role === "host"
        ? this.controller
        : role === "controller"
        ? this.host
        : role === "host-control"
        ? this.controllerControl
        : this.hostControl;
      this.forwardMessageFast(role, event.data, size, type, server, peerAtReceive, now);
      this.persistTrafficDiagThrottled(role, size, type, now);
    });

    const cleanup = () => {
      const now = Date.now();
      if (role === "host" && this.host === server) {
        this.host = null;
        this.state.waitUntil(this.updateDiag({ lastHostClosedAt: now }));
        if (isOpen(this.controller)) {
          try { this.controller.close(1011, "Host disconnected"); } catch (_) {}
        }
        this.controller = null;
      } else if (role === "controller" && this.controller === server) {
        this.controller = null;
        this.state.waitUntil(this.updateDiag({ lastControllerClosedAt: now }));
        if (isOpen(this.host)) {
          const oldHost = this.host;
          this.host = null;
          try { oldHost.close(1012, "Controller disconnected; reset session"); } catch (_) {}
          this.state.waitUntil(this.updateDiag({ lastHostResetForControllerAt: now }));
        }
      } else if (role === "host-control" && this.hostControl === server) {
        this.hostControl = null;
        this.state.waitUntil(this.updateDiag({ lastHostControlClosedAt: now }));
        if (isOpen(this.controllerControl)) {
          try { this.controllerControl.close(1011, "Host control disconnected"); } catch (_) {}
        }
        this.controllerControl = null;
      } else if (role === "controller-control" && this.controllerControl === server) {
        this.controllerControl = null;
        this.state.waitUntil(this.updateDiag({ lastControllerControlClosedAt: now }));
        if (isOpen(this.hostControl)) {
          const oldHostControl = this.hostControl;
          this.hostControl = null;
          try { oldHostControl.close(1012, "Controller control disconnected; reset session"); } catch (_) {}
          this.state.waitUntil(this.updateDiag({ lastHostControlResetForControllerAt: now }));
        }
      }
    };

    server.addEventListener("close", cleanup);
    server.addEventListener("error", cleanup);

    return new Response(null, { status: 101, webSocket: client });
  }

  forwardMessageFast(role, data, size, type, sourceAtReceive, peerAtReceive, forwardAt) {
    const sourceNow = role === "host"
      ? this.host
      : role === "controller"
      ? this.controller
      : role === "host-control"
      ? this.hostControl
      : this.controllerControl;
    const peerNow = role === "host"
      ? this.controller
      : role === "controller"
      ? this.host
      : role === "host-control"
      ? this.controllerControl
      : this.hostControl;
    if (sourceNow !== sourceAtReceive || peerNow !== peerAtReceive) {
      this.state.waitUntil(this.updateDiag({
        lastForwardFrom: role,
        lastForwardAt: forwardAt,
        lastForwardResult: "stale-session-dropped",
        lastForwardBytes: size,
        lastForwardType: type,
      }));
      return;
    }
    if (!isOpen(peerAtReceive)) {
      this.state.waitUntil(this.updateDiag({
        lastForwardFrom: role,
        lastForwardAt: forwardAt,
        lastForwardResult: "peer-not-open",
        lastForwardBytes: size,
        lastForwardType: type,
      }));
      return;
    }

    try {
      // Workers normally delivers binary WebSocket messages as ArrayBuffer, which
      // can be forwarded synchronously. Blob is handled off the hot path.
      if (typeof Blob !== "undefined" && data instanceof Blob) {
        this.state.waitUntil((async () => {
          try {
            const payload = await data.arrayBuffer();
            const sourceStill = role === "host"
              ? this.host
              : role === "controller"
              ? this.controller
              : role === "host-control"
              ? this.hostControl
              : this.controllerControl;
            const peerStill = role === "host"
              ? this.controller
              : role === "controller"
              ? this.host
              : role === "host-control"
              ? this.controllerControl
              : this.hostControl;
            if (sourceStill !== sourceAtReceive || peerStill !== peerAtReceive || !isOpen(peerAtReceive)) return;
            peerAtReceive.send(payload);
          } catch (e) {
            await this.updateDiag({
              lastForwardFrom: role,
              lastForwardAt: Date.now(),
              lastForwardResult: "send-error",
              lastForwardBytes: size,
              lastForwardType: type,
              lastForwardError: safeError(e),
            });
          }
        })());
        return;
      }

      peerAtReceive.send(data);
    } catch (e) {
      this.state.waitUntil(this.updateDiag({
        lastForwardFrom: role,
        lastForwardAt: forwardAt,
        lastForwardResult: "send-error",
        lastForwardBytes: size,
        lastForwardType: type,
        lastForwardError: safeError(e),
      }));
    }
  }

  persistTrafficDiagThrottled(role, size, type, now) {
    if (now - this.lastTrafficDiagPersistAt < 5000) return;
    this.lastTrafficDiagPersistAt = now;
    this.state.waitUntil(this.updateDiag({
      lastForwardFrom: role,
      lastForwardAt: now,
      lastForwardResult: "sent",
      lastForwardBytes: size,
      lastForwardType: type,
      hostMessages: this.hostMessages,
      controllerMessages: this.controllerMessages,
      hostBytes: this.hostBytes,
      controllerBytes: this.controllerBytes,
      lastHostMessageAt: this.lastHostMessageAt,
      lastControllerMessageAt: this.lastControllerMessageAt,
      lastForwardError: "",
    }));
  }

  async updateDiag(patch) {
    const current = await this.state.storage.get("diag") || {};
    await this.state.storage.put("diag", { ...current, ...patch });
  }

  async bumpDiag(field) {
    const current = await this.state.storage.get("diag") || {};
    current[field] = (current[field] || 0) + 1;
    await this.state.storage.put("diag", current);
  }
}

function isOpen(ws) {
  return !!ws && ws.readyState === 1;
}

function messageSize(data) {
  if (typeof data === "string") return new TextEncoder().encode(data).length;
  if (data instanceof ArrayBuffer) return data.byteLength;
  if (ArrayBuffer.isView(data)) return data.byteLength;
  if (typeof Blob !== "undefined" && data instanceof Blob) return data.size;
  return 0;
}

function messageType(data) {
  if (typeof data === "string") return "string";
  if (data instanceof ArrayBuffer) return "ArrayBuffer";
  if (ArrayBuffer.isView(data)) return data.constructor?.name || "TypedArray";
  if (typeof Blob !== "undefined" && data instanceof Blob) return "Blob";
  return Object.prototype.toString.call(data);
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

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
    this.hostMessages = 0;
    this.controllerMessages = 0;
    this.hostBytes = 0;
    this.controllerBytes = 0;
    this.hostConnectedAt = 0;
    this.controllerConnectedAt = 0;
    this.lastHostMessageAt = 0;
    this.lastControllerMessageAt = 0;
  }

  async fetch(request) {
    if (request.headers.get("X-RemotePhone-Debug") === DEBUG_KEY) {
      return Response.json({
        hostOpen: isOpen(this.host),
        controllerOpen: isOpen(this.controller),
        hostMessages: this.hostMessages,
        controllerMessages: this.controllerMessages,
        hostBytes: this.hostBytes,
        controllerBytes: this.controllerBytes,
        hostConnectedAt: this.hostConnectedAt,
        controllerConnectedAt: this.controllerConnectedAt,
        lastHostMessageAt: this.lastHostMessageAt,
        lastControllerMessageAt: this.lastControllerMessageAt,
      });
    }

    const role = (request.headers.get("X-RemotePhone-Role") || "").toLowerCase();
    if (role !== "host" && role !== "controller") {
      return new Response("Missing role", { status: 400 });
    }

    if (role === "host") {
      const token = request.headers.get("X-RemotePhone-Host-Token") || "";
      if (token.length < 32) return new Response("Invalid Host token", { status: 401 });

      const tokenHash = await sha256Hex(token);
      const storedHash = await this.state.storage.get("hostTokenHash");
      if (!storedHash) {
        await this.state.storage.put("hostTokenHash", tokenHash);
      } else if (!timingSafeEqual(storedHash, tokenHash)) {
        return new Response("Remote ID belongs to another Host", { status: 403 });
      }

      if (isOpen(this.host)) {
        try { this.host.close(1012, "Host reconnected"); } catch (_) {}
      }
      this.host = null;
      return this.acceptSocket("host");
    }

    if (!isOpen(this.host)) {
      return new Response("Host offline", { status: 404 });
    }
    if (isOpen(this.controller)) {
      try { this.controller.close(1012, "Controller reconnected"); } catch (_) {}
      this.controller = null;
    }
    return this.acceptSocket("controller");
  }

  acceptSocket(role) {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    if (role === "host") {
      this.host = server;
      this.hostConnectedAt = Date.now();
    } else {
      this.controller = server;
      this.controllerConnectedAt = Date.now();
    }

    server.addEventListener("message", event => {
      const size = messageSize(event.data);
      if (role === "host") {
        this.hostMessages++;
        this.hostBytes += size;
        this.lastHostMessageAt = Date.now();
      } else {
        this.controllerMessages++;
        this.controllerBytes += size;
        this.lastControllerMessageAt = Date.now();
      }

      const other = role === "host" ? this.controller : this.host;
      if (!isOpen(other)) return;
      try { other.send(event.data); } catch (_) {}
    });

    const cleanup = () => {
      if (role === "host" && this.host === server) {
        this.host = null;
        if (isOpen(this.controller)) {
          try { this.controller.close(1011, "Host disconnected"); } catch (_) {}
        }
        this.controller = null;
      } else if (role === "controller" && this.controller === server) {
        this.controller = null;
      }
    };

    server.addEventListener("close", cleanup);
    server.addEventListener("error", cleanup);

    return new Response(null, { status: 101, webSocket: client });
  }
}

function isOpen(ws) {
  return !!ws && ws.readyState === 1;
}

function messageSize(data) {
  if (typeof data === "string") return new TextEncoder().encode(data).length;
  if (data instanceof ArrayBuffer) return data.byteLength;
  if (ArrayBuffer.isView(data)) return data.byteLength;
  return 0;
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

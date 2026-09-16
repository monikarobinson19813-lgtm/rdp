export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response("RemotePhone relay OK", { status: 200 });
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
  }

  async fetch(request) {
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
      return new Response("Host busy", { status: 409 });
    }
    return this.acceptSocket("controller");
  }

  acceptSocket(role) {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    if (role === "host") this.host = server;
    else this.controller = server;

    server.addEventListener("message", event => {
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

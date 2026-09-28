export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response("RemotePhone relay OK", { status: 200 });
    }

    const debugMatch = url.pathname.match(/^\/debug\/(\d{9})$/);
    if (debugMatch) {
      // Diagnostics are never exposed by production deployments. Development
      // diagnostics require a Cloudflare secret binding; no auth material is
      // stored in source control.
      if (env.ENVIRONMENT !== "development") {
        return new Response("Not found", { status: 404 });
      }
      const expected = env.RPD_RELAY_DEBUG_SECRET || "";
      const supplied = request.headers.get("X-RemotePhone-Debug") || "";
      if (!expected || !timingSafeEqual(expected, supplied)) {
        return new Response("Forbidden", { status: 403 });
      }
      const roomId = env.RELAY.idFromName(debugMatch[1]);
      const debugRequest = new Request("https://relay.internal/debug", {
        headers: { "X-RemotePhone-Internal-Debug": "1" },
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

    // Android OkHttp protocol PING frames are handled by the Cloudflare runtime
    // without waking a hibernating Durable Object. Reserve an app-level
    // auto-response as well for any future text keepalive.
    this.state.setWebSocketAutoResponse(
      new WebSocketRequestResponsePair("rpd-keepalive", "rpd-keepalive-ack"),
    );
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.hostname === "relay.internal" &&
        url.pathname === "/debug" &&
        request.headers.get("X-RemotePhone-Internal-Debug") === "1") {
      return Response.json({
        hostOpen: this.isRoleOpen("host"),
        controllerOpen: this.isRoleOpen("controller"),
        hostControlOpen: this.isRoleOpen("host-control"),
        controllerControlOpen: this.isRoleOpen("controller-control"),
      });
    }

    const remoteMatch = url.pathname.match(/^\/relay\/(\d{9})$/);
    if (!remoteMatch) return new Response("Not found", { status: 404 });
    const remoteId = remoteMatch[1];

    const role = (request.headers.get("X-RemotePhone-Role") || "").toLowerCase();
    const validRoles = new Set(["host", "controller", "host-control", "controller-control"]);
    if (!validRoles.has(role)) {
      return new Response("Missing role", { status: 400 });
    }

    const isHostRole = role === "host" || role === "host-control";
    const isControlRole = role === "host-control" || role === "controller-control";

    if (isHostRole) {
      const token = request.headers.get("X-RemotePhone-Host-Token") || "";
      if (token.length < 32) {
        return new Response("Invalid Host token", { status: 401 });
      }

      const tokenHash = await sha256Hex(token);
      const storedHash = await this.state.storage.get("hostTokenHash");
      if (!storedHash) {
        await this.state.storage.put("hostTokenHash", tokenHash);
      } else if (!timingSafeEqual(storedHash, tokenHash)) {
        return new Response("Remote ID belongs to another Host", { status: 403 });
      }

      this.closeRoleForReplacement(role);
      return this.acceptSocket(role, remoteId);
    }

    const hostRole = isControlRole ? "host-control" : "host";
    if (!this.isRoleOpen(hostRole, remoteId)) {
      return new Response("Host offline", { status: 404 });
    }

    this.closeRoleForReplacement(role);
    return this.acceptSocket(role, remoteId);
  }

  acceptSocket(role, remoteId) {
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    this.state.acceptWebSocket(server, [role, `remote:${remoteId}`]);
    server.serializeAttachment({ role, remoteId, closing: "" });

    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, message) {
    const attachment = safeAttachment(ws);
    const role = attachment.role;
    const remoteId = attachment.remoteId;
    const peerRole = peerRoleFor(role);
    if (!peerRole || !remoteId) return;

    const peer = this.roleSocket(peerRole, remoteId);
    if (!peer) return;

    try {
      // Hibernation delivers text as string and binary as ArrayBuffer. Forward
      // the exact payload object without parsing, transforming, or logging it.
      peer.send(message);
    } catch (_) {
      // Endpoints own reconnect behavior. Do not log payloads or credentials.
    }
  }

  webSocketClose(ws, code, reason, wasClean) {
    const attachment = safeAttachment(ws);
    if (attachment.closing === "replaced") return;
    this.closePeerForDisconnect(attachment.role, attachment.remoteId);
    try { ws.close(code, reason); } catch (_) {}
  }

  webSocketError(ws, error) {
    const attachment = safeAttachment(ws);
    if (attachment.closing === "replaced") return;
    this.closePeerForDisconnect(attachment.role, attachment.remoteId);
    try { ws.close(1011, "Relay socket error"); } catch (_) {}
  }

  isRoleOpen(role, remoteId = null) {
    return !!this.roleSocket(role, remoteId);
  }

  roleSocket(role, remoteId = null) {
    for (const ws of this.state.getWebSockets(role)) {
      if (!isOpen(ws)) continue;
      const attachment = safeAttachment(ws);
      if (attachment.role !== role) continue;
      if (remoteId && attachment.remoteId !== remoteId) continue;
      return ws;
    }
    return null;
  }

  closeRoleForReplacement(role) {
    for (const ws of this.state.getWebSockets(role)) {
      if (!isOpen(ws)) continue;
      const attachment = safeAttachment(ws);
      ws.serializeAttachment({ ...attachment, closing: "replaced" });
      try { ws.close(1012, `${role} reconnected`); } catch (_) {}
    }
  }

  closePeerForDisconnect(role, remoteId) {
    const peerRole = peerRoleFor(role);
    if (!peerRole || !remoteId) return;
    const peer = this.roleSocket(peerRole, remoteId);
    if (!peer) return;

    const peerAttachment = safeAttachment(peer);
    peer.serializeAttachment({ ...peerAttachment, closing: "peer-disconnected" });
    try {
      const controllerSide = role === "controller" || role === "controller-control";
      peer.close(
        controllerSide ? 1012 : 1011,
        controllerSide ? "Controller disconnected; reset session" : "Host disconnected",
      );
    } catch (_) {}
  }
}

function peerRoleFor(role) {
  if (role === "host") return "controller";
  if (role === "controller") return "host";
  if (role === "host-control") return "controller-control";
  if (role === "controller-control") return "host-control";
  return "";
}

function safeAttachment(ws) {
  try {
    const value = ws.deserializeAttachment();
    if (value && typeof value === "object") return value;
  } catch (_) {}
  return { role: "", remoteId: "", closing: "" };
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

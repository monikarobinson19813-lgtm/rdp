# RemotePhone Internet relay

This Worker/Durable Object provides Remote-ID routing only. Screen, control and audio payloads remain encrypted end-to-end by the Android clients.

## Security

Production deployments set `ENVIRONMENT = "production"`. The `/debug/<remote-id>` route is therefore disabled in production and returns 404.

Any non-production diagnostic access uses the `RPD_RELAY_DEBUG_SECRET` Cloudflare secret binding. Never put its value in source control, logs, chat, screenshots, Hub pages, or `.dev.vars` committed to Git.

Rotate the binding before deployment:

```bash
npx wrangler secret put RPD_RELAY_DEBUG_SECRET
```

Local `.dev.vars` files are ignored by this directory's `.gitignore`.

## WebSocket hibernation

The relay uses the Durable Objects WebSocket Hibernation API. Each accepted socket is tagged by role and Remote ID, and role/Remote-ID metadata is stored with `serializeAttachment()` so routing survives hibernation and constructor re-entry.

The four channels remain separate: `host`, `controller`, `host-control`, and `controller-control`.

Payloads are forwarded opaquely without parsing, transforming, or logging message content. Android OkHttp protocol PING frames are handled by the Cloudflare runtime without waking a hibernating Durable Object. RemotePhone's encrypted application-level health messages are forwarded normally and may briefly wake the Durable Object; the object becomes hibernation-eligible again when the handler returns.

## Deploy

From this directory:

```bash
npx wrangler deploy
```

After deployment, verify `/health` returns `RemotePhone relay OK`, production `/debug/<remote-id>` returns 404, Host/Controller round-trip behavior is unchanged, and an idle connection can hibernate and later wake without reconnecting.

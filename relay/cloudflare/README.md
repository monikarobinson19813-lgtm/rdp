# RemotePhone v0.3 Internet relay

This Worker/Durable Object provides Remote-ID routing only. Screen, control and audio payloads remain encrypted end-to-end by the Android clients.

## Deploy

From this directory:

```bash
npx wrangler deploy
```

After deployment, copy the HTTPS Worker URL into:

`v0.3-overrides/RelayConfig.java`

Example:

```java
public static final String BASE_URL = "https://remotephone-relay.<account>.workers.dev";
```

## Protocol

- Host connects to `/relay/<9-digit-remote-id>` using WebSocket headers:
  - `X-RemotePhone-Role: host`
  - `X-RemotePhone-Host-Token: <persistent-random-token>`
- Controller connects to the same path with:
  - `X-RemotePhone-Role: controller`
- The Durable Object stores a hash of the first Host registration token for each Remote ID and refuses another token for the same ID.
- One Controller session per Host is supported in v0.3.
- The relay forwards opaque binary WebSocket frames. It does not receive the Android session PIN or decrypted remote-session payload.

## Security model

The Android v0.3 protocol uses ephemeral P-256 ECDH for session keys plus a persistent Host signing identity. The Controller pins the Host identity fingerprint after a successful first connection. The 6-digit session PIN is sent only inside the already-encrypted channel and is not used as the encryption root key.

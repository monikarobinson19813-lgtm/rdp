const te = new TextEncoder();

export function gesturePayload(a, b, duration) {
  const out = new Uint8Array(24);
  const view = new DataView(out.buffer);
  view.setFloat32(0, a.x, false);
  view.setFloat32(4, a.y, false);
  view.setFloat32(8, b.x, false);
  view.setFloat32(12, b.y, false);
  const roundedDuration = Math.round(Math.max(1, Math.min(5000, duration)));
  view.setBigInt64(16, BigInt(roundedDuration), false);
  return out;
}

export function navPayload(action) {
  if (![1, 2, 3].includes(action)) throw new Error("invalid nav action");
  return new Uint8Array([action]);
}

export function textPayload(value) {
  return te.encode(value);
}

export function unlockPayload(method, nonce8, credentialBytes) {
  if (![1, 2].includes(method)) throw new Error("invalid unlock method");
  if (!(nonce8 instanceof Uint8Array) || nonce8.length !== 8) throw new Error("unlock nonce must be 8 bytes");
  if (!(credentialBytes instanceof Uint8Array) || credentialBytes.length < 1) throw new Error("unlock credential required");
  const out = new Uint8Array(9 + credentialBytes.length);
  out[0] = method;
  out.set(nonce8, 1);
  out.set(credentialBytes, 9);
  return out;
}

export function controlPayload(action) {
  return new Uint8Array([action]);
}

export function streamQualityPayload(mode) {
  if (!Number.isInteger(mode) || mode < 0 || mode > 3) throw new Error("invalid stream quality");
  return new Uint8Array([mode]);
}

export function parseFramePayload(payload) {
  if (!(payload instanceof Uint8Array) || payload.length < 20) throw new Error("invalid frame payload");
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const width = view.getInt32(0, false);
  const height = view.getInt32(4, false);
  const timestamp = view.getBigInt64(8, false);
  const jpegLength = view.getInt32(16, false);
  if (width < 1 || height < 1 || jpegLength < 1 || 20 + jpegLength !== payload.length) {
    throw new Error("invalid frame payload");
  }
  return {
    width,
    height,
    timestamp,
    jpeg: payload.slice(20),
  };
}

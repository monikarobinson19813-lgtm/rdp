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

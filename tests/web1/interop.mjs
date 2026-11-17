import net from 'node:net';
import {webcrypto} from 'node:crypto';
import assert from 'node:assert/strict';
import {BrowserCryptoChannel, ByteStreamReader} from '../../web/controller/crypto-channel.mjs';
import {
  gesturePayload,
  navPayload,
  textPayload,
  unlockPayload,
  controlPayload,
  streamQualityPayload,
  parseFramePayload,
} from '../../web/controller/protocol-payloads.mjs';

const port = Number(process.argv[2]);
if (!Number.isInteger(port) || port < 1) throw new Error('usage: node interop.mjs <port>');
const TEST_PIN = new TextEncoder().encode('314159'); // synthetic CI-only value
const PING = 5;

function transport() {
  const reader = new ByteStreamReader();
  const socket = net.createConnection({host: '127.0.0.1', port});
  socket.on('data', (b) => reader.feed(new Uint8Array(b)));
  socket.on('end', () => reader.close(new Error('EOF')));
  socket.on('error', (e) => reader.close(e));
  return {
    reader,
    socket,
    write: (b) => new Promise((resolve, reject) => socket.write(Buffer.from(b), (e) => e ? reject(e) : resolve())),
  };
}

async function negativeF31() {
  const t = transport();
  let confirmationReached = false;
  let bytesAfterConfirmation = 0;
  const write = async (b) => {
    if (confirmationReached) bytesAfterConfirmation += b.length;
    return t.write(b);
  };
  await assert.rejects(
    BrowserCryptoChannel.connect({
      cryptoApi: webcrypto, reader: t.reader, write, pinBytes: TEST_PIN,
      confirmFingerprint: async () => { confirmationReached = true; return false; },
    }), /fingerprint not confirmed/
  );
  t.socket.destroy();
  assert.equal(confirmationReached, true, 'F31 confirmation point not reached');
  assert.equal(bytesAfterConfirmation, 0, 'TYPE_AUTH bytes written before OOB confirmation');
}

function splitOneByte(raw) { return [...raw].map((b) => new Uint8Array([b])); }
function splitRandom(raw, seed) {
  let x = seed >>> 0, off = 0; const out = [];
  while (off < raw.length) {
    x = (1664525 * x + 1013904223) >>> 0;
    const n = Math.min(raw.length - off, 1 + (x % 23));
    out.push(raw.subarray(off, off + n)); off += n;
  }
  return out;
}

async function positive(i) {
  const t = transport();
  const ch = await BrowserCryptoChannel.connect({
    cryptoApi: webcrypto, reader: t.reader, write: t.write, pinBytes: TEST_PIN,
    confirmFingerprint: async () => true,
  });
  const payload = new Uint8Array([i & 255, (i >>> 8) & 255]);
  await ch.send(PING, payload);
  const reply = await ch.read();
  assert.equal(reply.type, PING);
  assert.deepEqual([...reply.payload], [...payload]);
  if (i === 0) {
    const one = await ch.decodeRecordedFragments(splitOneByte(reply.raw));
    assert.equal(one.type, PING); assert.deepEqual([...one.payload], [...payload]);
    for (let seed = 1; seed <= 25; seed++) {
      const rr = await ch.decodeRecordedFragments(splitRandom(reply.raw, seed));
      assert.equal(rr.type, PING); assert.deepEqual([...rr.payload], [...payload]);
    }
  }
  t.socket.end();
  return ch.ecdsaScalarBytes.r === 31 || ch.ecdsaScalarBytes.s === 31;
}

async function payloadInterop() {
  const t = transport();
  const ch = await BrowserCryptoChannel.connect({
    cryptoApi: webcrypto, reader: t.reader, write: t.write, pinBytes: TEST_PIN,
    confirmFingerprint: async () => true,
  });

  await ch.send(2, gesturePayload({x: 0.5, y: 0.25}, {x: 0.75, y: 0.9}, 123.456));
  await ch.send(3, navPayload(2));
  await ch.send(4, textPayload('WEB1 payload ✓'));
  await ch.send(10, unlockPayload(1, new Uint8Array([1,2,3,4,5,6,7,8]), new TextEncoder().encode('2468')));
  await ch.send(8, controlPayload(1));
  await ch.send(14, streamQualityPayload(3));

  const frameMessage = await ch.read();
  assert.equal(frameMessage.type, 1, 'expected TYPE_FRAME');
  const frame = parseFramePayload(frameMessage.payload);
  assert.equal(frame.width, 720);
  assert.equal(frame.height, 1600);
  assert.equal(frame.timestamp, 123456789n);
  assert.deepEqual([...frame.jpeg], [255,216,1,35,255,217]);
  t.socket.end();
  console.log('WEB1 F40 payload interop PASS: gesture/nav/text/unlock/wake/quality/frame');
}

await negativeF31();
await payloadInterop();
let shortScalarSignatures = 0;
let handshakes = 0;
const minimum = 1000;
const maximum = 5000;
for (let i = 0; i < maximum && (handshakes < minimum || shortScalarSignatures < 1); i++) {
  if (await positive(i)) shortScalarSignatures++;
  handshakes++;
}
assert.ok(handshakes >= 1000, 'fewer than 1000 interop handshakes');
assert.ok(shortScalarSignatures >= 1, 'did not exercise a 31-byte ECDSA r/s component');
console.log(`WEB1 interop PASS: ${handshakes}/${handshakes} handshakes; F31 zero-auth PASS; F34 fragmentation PASS; short-scalar signatures=${shortScalarSignatures}`);

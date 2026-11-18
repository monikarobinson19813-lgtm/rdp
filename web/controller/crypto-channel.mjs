const MAGIC = new Uint8Array([0x52, 0x50, 0x44, 0x32]); // RPD2
const TYPE_AUTH = 100;
const TYPE_AUTH_OK = 101;
const MAX_HANDSHAKE_BLOB = 8192;
const MAX_PACKET = 16 * 1024 * 1024;
const MIN_PACKET = 29;
const te = new TextEncoder();

export class ByteStreamReader {
  #chunks = [];
  #length = 0;
  #waiters = [];
  #closedError = null;

  feed(chunk) {
    if (this.#closedError) throw this.#closedError;
    const b = chunk instanceof Uint8Array ? chunk : new Uint8Array(chunk);
    if (!b.length) return;
    this.#chunks.push(b.slice());
    this.#length += b.length;
    this.#wake();
  }

  close(error = new Error('stream closed')) {
    if (this.#closedError) return;
    this.#closedError = error;
    this.#wake();
  }

  async readExact(n) {
    if (!Number.isInteger(n) || n < 0) throw new Error('invalid read size');
    while (this.#length < n) {
      if (this.#closedError) throw this.#closedError;
      await new Promise((resolve) => this.#waiters.push(resolve));
    }
    const out = new Uint8Array(n);
    let off = 0;
    while (off < n) {
      const head = this.#chunks[0];
      const take = Math.min(head.length, n - off);
      out.set(head.subarray(0, take), off);
      off += take;
      this.#length -= take;
      if (take === head.length) this.#chunks.shift();
      else this.#chunks[0] = head.subarray(take);
    }
    return out;
  }

  #wake() {
    const ws = this.#waiters.splice(0);
    for (const w of ws) w();
  }
}

function concat(...parts) {
  const n = parts.reduce((s, p) => s + p.length, 0);
  const out = new Uint8Array(n);
  let off = 0;
  for (const p of parts) { out.set(p, off); off += p.length; }
  return out;
}

function u32be(n) {
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, n, false);
  return out;
}

function readU32be(b) {
  return new DataView(b.buffer, b.byteOffset, 4).getUint32(0, false);
}

async function readBlob(reader) {
  const n = readU32be(await reader.readExact(4));
  if (n < 1 || n > MAX_HANDSHAKE_BLOB) throw new Error('invalid handshake blob size');
  return reader.readExact(n);
}

function hex(bytes) {
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function derLength(bytes, pos) {
  if (pos >= bytes.length) throw new Error('truncated DER length');
  const first = bytes[pos++];
  if ((first & 0x80) === 0) return {len: first, pos};
  const count = first & 0x7f;
  if (count < 1 || count > 2 || pos + count > bytes.length) throw new Error('invalid DER length');
  if (bytes[pos] === 0) throw new Error('non-minimal DER length');
  let len = 0;
  for (let i = 0; i < count; i++) len = (len << 8) | bytes[pos++];
  if (len < 128) throw new Error('non-minimal DER long length');
  return {len, pos};
}

function derInteger(bytes, pos) {
  if (bytes[pos++] !== 0x02) throw new Error('expected DER INTEGER');
  const dl = derLength(bytes, pos); pos = dl.pos;
  if (dl.len < 1 || pos + dl.len > bytes.length) throw new Error('invalid DER INTEGER length');
  let v = bytes.subarray(pos, pos + dl.len); pos += dl.len;
  if (v[0] & 0x80) throw new Error('negative DER INTEGER');
  if (v.length > 1 && v[0] === 0 && (v[1] & 0x80) === 0) throw new Error('non-minimal DER INTEGER');
  if (v[0] === 0) v = v.subarray(1);
  if (v.length > 32) throw new Error('ECDSA scalar too large');
  return {value: v, pos};
}

export function ecdsaDerToRaw(der) {
  const b = der instanceof Uint8Array ? der : new Uint8Array(der);
  let pos = 0;
  if (b[pos++] !== 0x30) throw new Error('expected DER SEQUENCE');
  const seq = derLength(b, pos); pos = seq.pos;
  if (pos + seq.len !== b.length) throw new Error('DER trailing bytes or bad sequence length');
  const r = derInteger(b, pos); pos = r.pos;
  const s = derInteger(b, pos); pos = s.pos;
  if (pos !== b.length) throw new Error('DER trailing bytes');
  const raw = new Uint8Array(64);
  raw.set(r.value, 32 - r.value.length);
  raw.set(s.value, 64 - s.value.length);
  return {raw, rBytes: r.value.length, sBytes: s.value.length};
}

async function sha256(cryptoApi, bytes) {
  return new Uint8Array(await cryptoApi.subtle.digest('SHA-256', bytes));
}

async function hmac(cryptoApi, keyBytes, data) {
  const key = await cryptoApi.subtle.importKey('raw', keyBytes, {name: 'HMAC', hash: 'SHA-256'}, false, ['sign']);
  return new Uint8Array(await cryptoApi.subtle.sign('HMAC', key, data));
}

async function deriveSession(cryptoApi, shared, transcript) {
  const salt = await sha256(cryptoApi, concat(te.encode('RemotePhone-v0.3'), transcript));
  const prk = await hmac(cryptoApi, salt, shared);
  return hmac(cryptoApi, prk, concat(te.encode('session-key'), transcript));
}

async function importAes(cryptoApi, sessionKey) {
  return cryptoApi.subtle.importKey('raw', sessionKey, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

async function encryptFrame(cryptoApi, aesKey, type, payload = new Uint8Array()) {
  const iv = cryptoApi.getRandomValues(new Uint8Array(12));
  const plain = concat(new Uint8Array([type]), payload);
  const encrypted = new Uint8Array(await cryptoApi.subtle.encrypt({name: 'AES-GCM', iv, tagLength: 128}, aesKey, plain));
  const len = iv.length + encrypted.length;
  return concat(u32be(len), iv, encrypted);
}

async function decryptFrame(cryptoApi, aesKey, reader) {
  const lenBytes = await reader.readExact(4);
  const len = readU32be(lenBytes);
  if (len < MIN_PACKET || len > MAX_PACKET) throw new Error('invalid packet size');
  const body = await reader.readExact(len);
  const raw = concat(lenBytes, body);
  const iv = body.subarray(0, 12);
  const encrypted = body.subarray(12);
  const plain = new Uint8Array(await cryptoApi.subtle.decrypt({name: 'AES-GCM', iv, tagLength: 128}, aesKey, encrypted));
  if (!plain.length) throw new Error('empty packet');
  return {type: plain[0], payload: plain.subarray(1), raw};
}

export class BrowserCryptoChannel {
  constructor(cryptoApi, reader, write, aesKey, fingerprint, ecdsaScalarBytes) {
    this.cryptoApi = cryptoApi;
    this.reader = reader;
    this.write = write;
    this.aesKey = aesKey;
    this.fingerprint = fingerprint;
    this.ecdsaScalarBytes = ecdsaScalarBytes;
  }

  static async connect({cryptoApi, reader, write, pinBytes, expectedFingerprint = '', confirmFingerprint}) {
    if (!(pinBytes instanceof Uint8Array) || pinBytes.length !== 6 ||
        [...pinBytes].some((b) => b < 0x30 || b > 0x39)) {
      throw new Error('invalid session PIN');
    }
    const clientKeys = await cryptoApi.subtle.generateKey({name: 'ECDH', namedCurve: 'P-256'}, true, ['deriveBits']);
    const clientSpki = new Uint8Array(await cryptoApi.subtle.exportKey('spki', clientKeys.publicKey));
    const clientNonce = cryptoApi.getRandomValues(new Uint8Array(16));
    await write(concat(MAGIC, clientNonce, u32be(clientSpki.length), clientSpki));

    const identityBytes = await readBlob(reader);
    const hostSpki = await readBlob(reader);
    const serverNonce = await reader.readExact(16);
    const signatureDer = await readBlob(reader);
    const identityKey = await cryptoApi.subtle.importKey('spki', identityBytes, {name: 'ECDSA', namedCurve: 'P-256'}, false, ['verify']);
    const hostEphemeralKey = await cryptoApi.subtle.importKey('spki', hostSpki, {name: 'ECDH', namedCurve: 'P-256'}, false, []);
    const transcript = concat(clientNonce, serverNonce, clientSpki, hostSpki);
    const sig = ecdsaDerToRaw(signatureDer);
    const sigOk = await cryptoApi.subtle.verify({name: 'ECDSA', hash: 'SHA-256'}, identityKey, sig.raw, transcript);
    if (!sigOk) throw new Error('Host identity verification failed');

    const fp = hex(await sha256(cryptoApi, identityBytes));
    const normExpected = expectedFingerprint.toLowerCase().replace(/[^0-9a-f]/g, '');
    if (normExpected && normExpected !== fp) throw new Error('Host identity changed');
    if (!normExpected) {
      if (typeof confirmFingerprint !== 'function') throw new Error('fingerprint confirmation required');
      const confirmed = await confirmFingerprint(fp);
      if (!confirmed) throw new Error('fingerprint not confirmed');
    }

    const shared = new Uint8Array(await cryptoApi.subtle.deriveBits({name: 'ECDH', public: hostEphemeralKey}, clientKeys.privateKey, 256));
    const session = await deriveSession(cryptoApi, shared, transcript);
    const aesKey = await importAes(cryptoApi, session);
    await write(await encryptFrame(cryptoApi, aesKey, TYPE_AUTH, pinBytes));
    const auth = await decryptFrame(cryptoApi, aesKey, reader);
    if (auth.type !== TYPE_AUTH_OK) throw new Error('Host authentication failed');
    return new BrowserCryptoChannel(cryptoApi, reader, write, aesKey, fp, {r: sig.rBytes, s: sig.sBytes});
  }

  async send(type, payload = new Uint8Array()) {
    await this.write(await encryptFrame(this.cryptoApi, this.aesKey, type, payload));
  }

  read() {
    return decryptFrame(this.cryptoApi, this.aesKey, this.reader);
  }

  async decodeRecordedFragments(fragments) {
    const r = new ByteStreamReader();
    const pending = decryptFrame(this.cryptoApi, this.aesKey, r);
    for (const f of fragments) r.feed(f);
    return pending;
  }
}

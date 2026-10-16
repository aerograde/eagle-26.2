/*
 * Decode test for the serverless carrier's payload (tmp/autoconnect/SERVERLESS-LAN.md).
 *
 * Runs the engine's own encoder and decoder in Node with no browser and no
 * WebRTC: the module is an IIFE over `window`, so `window`, `window.crypto` and
 * `console` are stubbed and the seam `window.__eaglerAC._debug` does the rest.
 *
 *   node tmp/autoconnect/test-decode.mjs
 *
 * Model.  The sender probes the receiver's in-window sockets and numbers the ones
 * that answer, sorted; the receiver numbers *its own* sockets, sorted.  A pulse
 * aimed at the sender's symbol `k` lands on the receiver's socket `k`, so the
 * receiver's alphabet has to be its own list.  The sender derives its bits per
 * pulse from its own list length, which is why a probe that missed a socket can
 * also change the alphabet size - the last check below measures that.
 */
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const enginePath = join(here, "..", "..", "target_teavm_wasm_gc", "src", "web", "autoconnect.js");
const source = readFileSync(enginePath, "utf8");

const sandbox = {
  console: { log() {}, warn() {}, error() {} },
  TextEncoder, URLSearchParams, setTimeout, clearTimeout, Promise, Math, Date,
  JSON, Object, Array, String, Number, isFinite, parseInt,
};
sandbox.window = { crypto: { getRandomValues: (buf) => crypto.getRandomValues(buf) } };
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
vm.runInContext(source, sandbox);
const AC = sandbox.window.__eaglerAC;
if (!AC || !AC._debug) {
  console.error("FAIL: the engine did not install window.__eaglerAC._debug");
  process.exit(1);
}

const D = AC._debug;
const FINGERPRINT = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
  + "A1:B2:C3:D4:E5:F6:07:18:29:3A:4B:5C:6D:7E:8F:90";

const receiverSockets = [50011, 50023, 50037, 50048, 50062, 50071, 50088, 50094,
  50107, 50119, 50126, 50138, 50144, 50159, 50166, 50178];
const peerSockets = [50203, 50215, 50221, 50236, 50248, 50259, 50267, 50274,
  50282, 50295, 50306, 50312, 50324, 50337, 50345, 50358];

D.setIdentity(0x1A2B, FINGERPRINT, 61616);
const payload = D.buildPayload();

// The frame a sender with `senderList` actually produces: its bits per pulse, its
// pulse count, and the socket each pulse is aimed at.  `lost` are pulse indices
// whose arrival never reached the receiver.
function frame(senderList, lost = [], lead = []) {
  const per = D.symPerPulse(senderList.length);
  const count = Math.ceil(payload.bits.length / per);
  const out = lead.slice();
  for (let i = 0; i < count; i++) {
    if (lost.includes(i)) continue;
    const sym = D.symbolAt(payload.bits, i * per, per);
    out.push({ port: senderList[sym], source: 52000 + i * 3, at: 1000 + i });
  }
  return { stream: out, per, count };
}

// The receiver's side of the read: its own alphabet hypotheses (its list, and the
// list minus each single socket) against every plausible pieces-per-pulse.
function receive(stream, ownList) {
  const hyps = [ownList];
  for (let d = 0; d < ownList.length; d++) hyps.push(ownList.slice(0, d).concat(ownList.slice(d + 1)));
  const per = D.symPerPulse(ownList.length);
  const perSet = per > 2 ? [per, per - 1] : [per];
  for (const bitsPerPulse of perSet) {
    for (const alphabet of hyps) {
      const hit = D.decodeStream(stream, alphabet, bitsPerPulse);
      if (hit) {
        return { start: hit.start, fp: hit.parsed.fingerprint, port: hit.parsed.port, id: hit.parsed.id };
      }
    }
  }
  return null;
}

let failures = 0;
function check(name, ok, detail) {
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  (" + detail + ")" : ""}`);
  if (!ok) failures++;
}

console.log(`engine: ${enginePath}`);
console.log(`payload: ${payload.bytes.length} bytes, ${payload.bits.length} bits, `
  + `${D.symPerPulse(receiverSockets.length)} bit(s) per pulse, `
  + `${Math.ceil(payload.bits.length / D.symPerPulse(receiverSockets.length))} pulse(s), `
  + `${receiverSockets.length}-socket alphabet\n`);

// 1. A complete probe, a clean frame.
const f1 = frame(receiverSockets);
const clean = receive(f1.stream, receiverSockets);
check("clean frame decodes", !!clean && clean.fp === FINGERPRINT && clean.port === 61616,
  clean ? `start ${clean.start}, id ${clean.id.toString(16)}, port ${clean.port}` : "no decode");

// 2. Content-addressed read: two unrelated arrivals (a locate check, say) reach the
//    receiver before the frame.  The old reader attributed arrivals to its own pulse
//    times, so those two would have shifted every symbol by two.
const noisy = frame(receiverSockets, [], [
  { port: receiverSockets[3], source: 59999, at: 900 },
  { port: receiverSockets[7], source: 59998, at: 950 },
]);
const offset = receive(noisy.stream, receiverSockets);
check("a frame that starts inside a noisy stream decodes", !!offset && offset.fp === FINGERPRINT,
  offset ? `frame found at position ${offset.start}` : "no decode");

// 3. The sender's probe missed one of the receiver's sockets: every symbol above the
//    gap is numbered one lower, and the alphabet is one bit narrower (15 sockets ->
//    3 bits per pulse).  The receiver knows neither, and has to get there from its
//    own list, its own size and the CRC.
const missed = receiverSockets.slice();
missed.splice(4, 1);
const f3 = frame(missed);
const gap = receive(f3.stream, receiverSockets);
check(`a probe that missed one socket still decodes (${f3.count} pulses at ${f3.per} bit(s))`,
  !!gap && gap.fp === FINGERPRINT, gap ? `start ${gap.start}` : "no decode");

// 4. The defect this pass fixed: index the arrivals against the *peer's* socket list
//    (what tryDecodeSocket did) instead of the receiver's own.  The two lists are
//    disjoint, so every symbol reads as out of alphabet and no payload is found -
//    which is why no log in tmp/autoconnect/ ever contained a decoded payload.
const f4 = frame(receiverSockets);
const wrong = receive(f4.stream, peerSockets);
check("the peer's ports as the alphabet do not decode (the old defect)", wrong === null,
  wrong ? "decoded anyway" : "no decode, as the old logs showed");

// 5. A lost pulse is a lost bit: the frame has no redundancy, so the CRC rejects it.
//    Stated as a limit, not a success - the carrier answers it with fresh attempts.
const f5 = frame(receiverSockets, [40]);
const dropped = receive(f5.stream, receiverSockets);
check("one lost pulse inside the frame is rejected (documented limit)", dropped === null,
  dropped ? "decoded anyway" : "rejected, as expected");

// 6. What that limit means, per attempt and across the carrier's three attempts.
//    Independent arrivals means the frame decodes only if every one arrives.
function successRate(lossPerPulse, attempts, trials = 400) {
  let hits = 0;
  for (let t = 0; t < trials; t++) {
    for (let a = 0; a < attempts; a++) {
      const lost = [];
      for (let i = 0; i < f1.count; i++) if (Math.random() < lossPerPulse) lost.push(i);
      if (receive(frame(receiverSockets, lost).stream, receiverSockets)) { hits++; break; }
    }
  }
  return hits / trials;
}
for (const loss of [0.001, 0.005, 0.01, 0.05]) {
  console.log(`  per-pulse loss ${(loss * 100).toFixed(1)}%: one attempt `
    + `${(successRate(loss, 1) * 100).toFixed(1)}%, three attempts `
    + `${(successRate(loss, 3) * 100).toFixed(1)}%`);
}

console.log(failures ? `\n${failures} check(s) failed` : "\nall checks passed");
process.exit(failures ? 1 : 0);

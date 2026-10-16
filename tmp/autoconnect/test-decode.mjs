/*
 * Decode test for the serverless carrier's payload (tmp/autoconnect/SERVERLESS-LAN-FINAL.md).
 *
 * Runs the engine's own encoder and decoder in Node with no browser and no
 * WebRTC: the module is an IIFE over `window`, so `window`, `window.crypto` and
 * `console` are stubbed and the seam `window.__eaglerAC._debug` does the rest.
 *
 *   node tmp/autoconnect/test-decode.mjs
 *
 * Model.  The sender probes the receiver's in-window sockets, settles on a list,
 * announces it with the column sweep and codes the payload in base K over that
 * list; the receiver reads the list off the sweep's own repeats and decodes
 * against it.  So the alphabet is the *announced* list - not the receiver's own
 * sockets and not a power-of-two prefix of either - and the CRC is the only judge.
 * The checks below pin that contract, the hole-filling read, and the sweep reader
 * that turns a partial read into a candidate that simply fails instead of a wrong
 * alphabet that is accepted.
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
sandbox.location = { search: "" };
sandbox.window = { location: sandbox.location, crypto: { getRandomValues: (buf) => crypto.getRandomValues(buf) } };
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

const bitsPerPulse = (K) => Math.round(Math.log2(K) * 100) / 100;

// The frame a sender with `announced` actually produces: its base-K digits, the
// pulse count, and the socket each pulse is aimed at.  `lost` are pulse indices
// whose arrival never reached the receiver.
function frame(announced, lost = [], lead = [], at = 1000) {
  const digits = D.payloadToDigits(payload.bits, announced.length);
  const out = lead.slice();
  for (let i = 0; i < digits.length; i++) {
    if (lost.includes(i)) continue;
    out.push({ port: announced[digits[i]], source: 52000 + i * 3, at: at + i * 600 });
  }
  return { stream: out, count: digits.length, K: announced.length };
}

// The receiver's side of the read: every candidate list the sweep offered, each
// decoded in its own base, with the CRC as the only judge.
function receive(stream, ...candidateLists) {
  for (const alphabet of candidateLists) {
    D.resetDecodeBudget();               // one read's budget, as tryDecodeSocket sets it
    const hit = D.decodeStream(stream, alphabet, 400000);
    if (hit) {
      return { start: hit.start, K: alphabet.length, fp: hit.parsed.fingerprint,
        port: hit.parsed.port, id: hit.parsed.id };
    }
  }
  return null;
}

// The sweep as the sender fires it: ANNOUNCE_ROUNDS ascending rounds of one list,
// one arrival per pulse.  `rounds` selects how many rounds a read actually saw.
function sweep(announced, rounds = 3, cols = null) {
  const out = [];
  const list = cols || announced;
  for (let r = 0; r < rounds; r++) {
    for (const port of list) out.push({ port, source: 53000 + r * 100 + port % 97, at: 0 });
  }
  return out;
}

let failures = 0;
function check(name, ok, detail) {
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? "  (" + detail + ")" : ""}`);
  if (!ok) failures++;
}

console.log(`engine: ${enginePath}`);
console.log(`payload: ${payload.bytes.length} bytes, ${payload.bits.length} bits; `
  + `base-K pulse counts for K = 8/12/16: ${D.baseKNeed(8)}/${D.baseKNeed(12)}/${D.baseKNeed(16)}\n`);

// 1. Base-K round trip: the encoder and the decoder are exact inverses for every
//    alphabet size the engine can settle on, and the digit count is what the
//    sender fires.
for (const K of [2, 3, 4, 5, 6, 7, 8, 9, 12, 15, 16, 20, 32]) {
  const digits = D.payloadToDigits(payload.bits, K);
  const back = D.digitsToBits(digits.slice(0, D.baseKNeed(K)), K);
  const same = back.length === payload.bits.length
    && back.every((b, i) => b === (payload.bits[i] ? 1 : 0));
  check(`base ${K}: ${digits.length} digit(s) round-trip`, same
    && digits.length === D.baseKNeed(K) && digits.every((d) => d < K),
    `${D.baseKNeed(K)} digit(s), ${bitsPerPulse(K)} bit(s) per pulse`);
}

// 2. A complete read of a full frame, coded in base 8 over the announced list.
const K8 = receiverSockets.slice(0, 8);
const f2 = frame(K8);
const clean = receive(f2.stream, K8);
check(`a clean ${f2.count}-pulse frame decodes in base ${K8.length}`,
  !!clean && clean.fp === FINGERPRINT && clean.port === 61616,
  clean ? `start ${clean.start}, id ${clean.id.toString(16)}, K ${clean.K}` : "no decode");

// 3. Content-addressed read: two unrelated arrivals (a locate check, say) reach the
//    receiver before the frame.
const noisy = frame(K8, [], [
  { port: K8[3], source: 59999, at: 900 },
  { port: K8[7], source: 59998, at: 950 },
]);
const offset = receive(noisy.stream, K8);
check("a frame that starts inside a noisy stream decodes", !!offset && offset.fp === FINGERPRINT,
  offset ? `frame found at position ${offset.start}` : "no decode");

// 4. The alphabet is the list the sender announced, not the receiver's own sockets.
//    The sender settled on 15 of the receiver's 16 sockets, so every symbol above
//    the gap is numbered one lower and the base is 15: the receiver's own 16-socket
//    list is a different index space and must be rejected, while the announced
//    15-socket list decodes.
const announced15 = receiverSockets.filter((_, i) => i !== 4);
const f4 = frame(announced15);
const ownList = receive(f4.stream, receiverSockets);
const announced = receive(f4.stream, announced15);
check(`the receiver's own ${receiverSockets.length}-socket list does not decode a base `
  + `${announced15.length} frame (${f4.count} pulses)`, ownList === null,
  ownList ? "decoded anyway - the alphabet is not the announced list" : "rejected, as it must be");
check(`the announced ${announced15.length}-socket list decodes it`,
  !!announced && announced.K === announced15.length, announced ? `K ${announced.K}` : "no decode");

// 5. The defect this pass fixed: index the arrivals against the *peer's* socket list
//    (both lists disjoint) - every symbol reads as out of alphabet and no payload is
//    found, which is why no log in tmp/autoconnect/ ever contained a payload.
const f5 = frame(K8);
check("the peer's ports as the alphabet do not decode (the old defect)",
  receive(f5.stream, peerSockets) === null, "no decode, as the old logs showed");

// 6. A lost pulse: no longer a lost frame.  The contiguous pass rejects it, and the
//    slotted read (cadence from the arrivals, the CRC picking the digit) fills it.
const f6 = frame(K8, [5]);
check("one lost pulse in the middle is rejected contiguously",
  receive(f6.stream, K8) === null, "rejected, as expected");
D.resetDecodeBudget();
const slotted = D.decodeSlotted(f6.stream, K8, 0);
check("the slotted read fills the hole", !!slotted && slotted.fp === undefined
  && slotted.parsed.fingerprint === FINGERPRINT,
  slotted ? `${slotted.holes.length} hole(s), candidate ${slotted.cand} of `
    + `${Math.pow(K8.length, slotted.holes.length)}` : "no decode");
console.log(`  hole allowance by K: `
  + [2, 4, 8, 12, 16, 32].map((K) => `${K}->${D.holesAllowed(K)}`).join(" "));

// 7. The sweep reader.  A full three-round sweep of an 8-socket list has to come
//    out as one candidate list, in the sender's order.
const full = D.readColumnSweep(sweep(K8, 3));
check(`a full sweep yields the ${K8.length}-socket list, sorted`,
  full.length > 0 && full[0].cols.join(",") === K8.join(","),
  full.length ? `${full.length} candidate(s), first = ${full[0].cols.length} socket(s) (${full[0].note})` : "none");

// 8. The defect that ended the third acceptance run: a *partial* sweep read.  One
//    round delivered only the first half of the list, and the ascending run of
//    first appearances is then four sockets - still ascending, still a plausible
//    length, and it was accepted as the alphabet, so the frame that followed (eight
//    columns wide) decoded nothing.  With the repeats counted, the four-socket run
//    is one candidate among others, the eight-socket list is the first, and the
//    frame decodes.
const partialSweep = sweep(K8, 1, K8.slice(0, 4));
const f8 = frame(K8);
const partial = D.readColumnSweep(partialSweep.concat(f8.stream));
const best = partial.length ? partial[0] : null;
const withCandidates = receive(f8.stream, ...partial.map((c) => c.cols));
const withRunOnly = receive(f8.stream, ...partial.filter((c) => c.note === "first appearances")
  .map((c) => c.cols));
check(`a partial sweep read (${4} of ${K8.length} in round 1) still offers the full list first`,
  !!best && best.cols.length === K8.length && best.cols.join(",") === K8.join(","),
  best ? `first = ${best.cols.length} socket(s) (${best.note})` : "no candidate");
check("the frame decodes against the candidates", !!withCandidates && withCandidates.K === K8.length,
  withCandidates ? `K ${withCandidates.K}` : "no decode");
check("the four-socket run alone would not have decoded it (the run-3 failure)",
  withRunOnly === null, withRunOnly ? "decoded anyway" : "rejected, which is a retry not a wrong read");

// 9. A stray that arrives twice is not a column: the repeat-confirmed list drops it,
//    and the list it would have corrupted still decodes.
const stray = K8.slice();
const strayed = sweep(K8, 3).concat(f8.stream).concat([
  { port: receiverSockets[12], source: 61111, at: 999000 },
  { port: receiverSockets[12], source: 61112, at: 999500 },
]);
const withStray = D.readColumnSweep(strayed);
check("a socket that arrived twice but is not a column stays out of the confirmed list",
  withStray.length > 0 && withStray[0].cols.indexOf(receiverSockets[12]) < 0
  && withStray[0].cols.join(",") === stray.join(","),
  withStray.length ? `first = ${withStray[0].cols.length} socket(s) (${withStray[0].note})` : "none");

// 10. The hole search's worst case: a base-12 copy (90 pulses) that lost the four
//     pulses its allowance admits (20736 candidates).  The candidate is re-derived
//     from the base value plus one power of K per hole, which is what keeps the
//     search inside the read budget.
const K12 = receiverSockets.slice(0, 12);
const f10 = frame(K12, [7, 21, 44, 70]);
D.resetDecodeBudget();
const t10 = Date.now();
const four = D.decodeSlotted(f10.stream, K12, 0);
const ms10 = Date.now() - t10;
check("a base-12 copy with four holes decodes inside the read budget",
  !!four && four.parsed.fingerprint === FINGERPRINT,
  four ? `candidate ${four.cand} of ${Math.pow(K12.length, 4)}, ${ms10} ms` : "no decode");

// 11. The two copies of one frame, merged - the read the failing runs needed.  Both
//     failing runs of the shipped artifact ended with one side decoding and the other
//     holding a complete frame's worth of arrivals and no payload: a copy that lost
//     six pulses of a 112-pulse frame is past the hole search's allowance, so neither
//     copy decodes alone, while the two merged are an erasure code.
const K = K8;
const count8 = D.baseKNeed(K.length);
const lostA = [3, 40, 41, 42, 90, 100];
const lostB = [17, 25, 26, 27, 66, 88];
const fA = frame(K, lostA, [], 1000);
const fB = frame(K, lostB, [], 1000 + count8 * 600 + 3000);
D.resetDecodeBudget();
const aloneA = D.decodeSlotted(fA.stream, K, 0) || D.decodeSlotted(fA.stream, K, 1);
D.resetDecodeBudget();
const aloneB = D.decodeSlotted(fB.stream, K, 0) || D.decodeSlotted(fB.stream, K, 1);
D.resetDecodeBudget();
const both = D.decodeSlottedPair(fA.stream, fB.stream, K);
check(`neither copy of a ${count8}-pulse frame decodes alone (6 pulses lost each)`,
  !aloneA && !aloneB, `${lostA.length} and ${lostB.length} hole(s), allowance is `
    + `${D.holesAllowed(K.length)}`);
check("the two copies merged decode", !!both && both.parsed.fingerprint === FINGERPRINT,
  both ? `${both.holes} slot(s) only one copy carried, ${both.clashes} disagreed,
     candidate ${both.cand} of ${both.total}`.replace(/\s+/g, " ") : "no decode");

// 11b. The same merge repairs a read whose clock put a pulse in the neighbouring
//      slot: the copies then disagree in two adjacent slots, and the CRC picks.
const swapped = frame(K, [], [], 1000 + count8 * 600 + 3000).stream.slice();
swapped[9] = { ...swapped[9], at: swapped[9].at + 600 };
swapped[10] = { ...swapped[10], at: swapped[10].at - 600 };
const cleanCopy = frame(K, [], [], 1000).stream;
D.resetDecodeBudget();
const shifted = D.decodeSlottedPair(cleanCopy, swapped, K);
check("two copies that disagree in two slots still decode",
  !!shifted && shifted.parsed.fingerprint === FINGERPRINT,
  shifted ? `${shifted.clashes} disagreement(s), candidate ${shifted.cand} of ${shifted.total}` : "no decode");

// 12. What the pulse count buys: a copy is decodable while it loses a few pulses, so
//     the odds per attempt follow the frame's length.  Base K uses every socket the
//     probe settled on, which is what shortens the frame when the readback is short.
function successRate(alphabet, lossPerPulse, tries = 300, merge = false) {
  const count = D.baseKNeed(alphabet.length);
  let hits = 0;
  for (let t = 0; t < tries; t++) {
    const lost = [];
    for (let i = 0; i < count; i++) if (Math.random() < lossPerPulse) lost.push(i);
    if (receive(frame(alphabet, lost).stream, alphabet)) { hits++; continue; }
    const f = frame(alphabet, lost);
    D.resetDecodeBudget();
    const filled = D.decodeSlotted(f.stream, alphabet, 0);
    D.resetDecodeBudget();
    if (filled || D.decodeSlotted(f.stream, alphabet, 1)) { hits++; continue; }
    if (!merge) continue;
    // The second copy of the same frame, with its own pattern of lost pulses.
    const other = [];
    for (let i = 0; i < count; i++) if (Math.random() < lossPerPulse) other.push(i);
    const g = frame(alphabet, other, [], 1e6);
    D.resetDecodeBudget();
    if (D.decodeSlottedPair(f.stream, g.stream, alphabet)) hits++;
  }
  return hits / tries;
}
for (const alphabet of [K8, receiverSockets.slice(0, 12)]) {
  const row = [0.005, 0.01, 0.02, 0.05].map((loss) =>
    `${(loss * 100).toFixed(1)}% ${(successRate(alphabet, loss, 120) * 100).toFixed(0)}%`);
  const merged = [0.005, 0.01, 0.02, 0.05].map((loss) =>
    `${(loss * 100).toFixed(1)}% ${(successRate(alphabet, loss, 120, true) * 100).toFixed(0)}%`);
  console.log(`  K=${alphabet.length}: ${D.baseKNeed(alphabet.length)} pulses per copy, `
    + `clean+slotted+merged decode by loss ${merged.join("  ")}`);
  console.log(`  K=${alphabet.length}: clean+slotted only              `
    + `${row.join("  ")}`);
}

console.log(failures ? `\n${failures} check(s) failed` : "\nall checks passed");
process.exit(failures ? 1 : 0);

// Offline replica of autoconnect.js's transmit() timing, to explain the miss
// counts measured in grid-run10.log and to test fixes without a browser run.
//
// Mirrors: buildPayload() bit order, perRound = SYMBOL_CLASSES * K, the
// cls/col mapping, takeSource() (usedColumns persists across copies; per-round
// cap of CELLS_PER_SOURCE_PER_ROUND), and the two-copy structure.
//
// Usage: node tmp/autoconnect/sim-schedule.mjs [classCounts] [payloadMode]
//   classCounts  e.g. 4/6/4/8/8/5/7/4/6/7/8/6/7/7/2/7   (default: run 10)
//   payloadMode  random | fixed                          (default: random)

const SYMBOL_CLASSES = 16;
const CELLS_PER_SOURCE_PER_ROUND = 4;
const COPY_COUNT = 2;
const PAYLOAD_BYTES = 40;
const PAYLOAD_BITS = PAYLOAD_BYTES * 8;
const K = 15; // peer targets measured in run 10

const positional = process.argv.slice(2).filter((a) => !a.startsWith("--"));
const classCounts = (positional[0] || "4/6/4/8/8/5/7/4/6/7/8/6/7/7/2/7")
  .split("/").map((n) => parseInt(n, 10));
const mode = positional[1] || "random";
const sharedId = process.argv.some((a) => a.includes("sharedid"));

function bitsFromBytes(bytes) {
  const bits = [];
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i] & 0xff;
    for (let j = 7; j >= 0; j--) bits.push((b >> j) & 1);
  }
  return bits;
}
function crc16(bytes) {
  let crc = 0xffff;
  for (let i = 0; i < bytes.length; i++) {
    crc ^= (bytes[i] & 0xff) << 8;
    for (let j = 0; j < 8; j++) {
      crc = (crc & 0x8000) ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
    }
  }
  return crc & 0xffff;
}
function buildPayload() {
  const body = [0xa5, 0x3c, 0x12, 0x34];
  const fp = [];
  for (let i = 0; i < 32; i++) {
    fp.push(mode === "fixed" ? 0xab : (Math.random() * 256) & 0xff);
  }
  for (let i = 0; i < 32; i++) body.push(fp[i] || 0);
  body.push(0x40, 0x2c);
  const crc = crc16(body);
  body.push((crc >> 8) & 0xff, crc & 0xff);
  return { bytes: body, bits: bitsFromBytes(body) };
}

function makeSources(classCounts) {
  const sources = [];
  let id = 0;
  classCounts.forEach((n, cls) => {
    for (let i = 0; i < n; i++) {
      sources.push({ id: id++, cls, usedColumns: Object.create(null) });
    }
  });
  const pool = [];
  for (let c = 0; c < SYMBOL_CLASSES; c++) pool.push([]);
  sources.forEach((s) => pool[s.cls].push(s));
  return { sources, pool };
}

function takeSource(pool, cls, col, roundUsed) {
  const bucket = pool[cls];
  if (!bucket) return null;
  for (let i = 0; i < bucket.length; i++) {
    const s = bucket[i];
    const key = sharedId ? undefined : s.id;
    const used = roundUsed[key] || 0;
    if (used >= CELLS_PER_SOURCE_PER_ROUND) continue;
    if (s.usedColumns[col]) continue;
    s.usedColumns[col] = true;
    roundUsed[key] = used + 1;
    return s;
  }
  return null;
}

// listRound(): 3 shots per target, classes chosen by shot*5%16 then ((t+shot)*3)%16
function listRound(pool, K) {
  const used = Object.create(null);
  const fired = [];
  for (let t = 0; t < K; t++) {
    for (let shot = 0; shot < 3; shot++) {
      let s = takeSource(pool, (shot * 5) % SYMBOL_CLASSES, t, used)
        || takeSource(pool, ((t + shot) * 3) % SYMBOL_CLASSES, t, used);
      if (s) fired.push([s.id, t, shot]);
    }
  }
  return fired;
}

function describeMiss(pool, cls, col, roundUsed, bitsSeen) {
  const bucket = pool[cls];
  let empty = 0, roundCapped = 0, colUsed = 0, available = 0;
  for (const s of bucket) {
    const used = roundUsed[sharedId ? undefined : s.id] || 0;
    if (used >= CELLS_PER_SOURCE_PER_ROUND) roundCapped++;
    else if (s.usedColumns[col]) colUsed++;
    else available++;
  }
  if (!bucket.length) empty++;
  return { bitsSeen, empty, roundCapped, colUsed, available, bucket: bucket.length };
}

function runCopy(pool, payload, K, label) {
  const perRound = SYMBOL_CLASSES * K;
  const rounds = Math.ceil(payload.bits.length / perRound);
  const report = [];
  for (let r = 0; r < rounds; r++) {
    const start = r * perRound;
    const roundUsed = Object.create(null);
    let fired = 0, missed = 0, attempted = 0;
    const missDetails = [];
    for (let b = 0; b < perRound; b++) {
      const index = start + b;
      if (index >= payload.bits.length) break;
      if (!payload.bits[index]) continue;
      attempted++;
      const cls = index % SYMBOL_CLASSES;
      const col = Math.floor(index / SYMBOL_CLASSES) % K;
      const src = takeSource(pool, cls, col, roundUsed);
      if (!src) {
        missed++;
        if (missDetails.length < 4) missDetails.push(describeMiss(pool, cls, col, roundUsed, index));
        continue;
      }
      fired++;
    }
    report.push({ round: r, attempted, fired, missed, missDetails });
  }
  console.log(`${label}: rounds=${rounds} ` + report.map((x) =>
    `[r${x.round} attempted=${x.attempted} fired=${x.fired} missed=${x.missed}]`).join(" "));
  for (const x of report) {
    for (const d of x.missDetails) {
      console.log(`   miss at bit ${d.bitsSeen}: bucket=${d.bucket} empty=${d.empty} ` +
        `roundCapped=${d.roundCapped} colUsed=${d.colUsed} available=${d.available}`);
    }
  }
  return report;
}

const payload = buildPayload();
const { sources, pool } = makeSources(classCounts);
console.log(`payload: ${payload.bits.length} bits, ${payload.bits.filter((b) => b).length} set, K=${K}, ` +
  `sources=${sources.length}, per-class=${classCounts.join("/")}`);

if (sharedId) {
  const buggy = makeSources(classCounts);
  runCopy(buggy.pool, payload, K, "BUG: shared id (roundUsed[undefined])");
  process.exit(0);
}
runCopy(pool, payload, K, "copy 1 (fresh)");
runCopy(pool, payload, K, "copy 2 (usedColumns persisted)");

// Now the proposed fix: reset usedColumns per copy, and again per round.
const fresh = makeSources(classCounts);
runCopy(fresh.pool, payload, K, "fix A: reset per copy");
const fresh2 = makeSources(classCounts);
console.log("fix B: reset per round");
const perRound = SYMBOL_CLASSES * K;
const rounds = Math.ceil(payload.bits.length / perRound);
for (let r = 0; r < rounds; r++) {
  fresh2.sources.forEach((s) => { s.usedColumns = Object.create(null); });
  const start = r * perRound;
  const roundUsed = Object.create(null);
  let fired = 0, missed = 0, attempted = 0;
  for (let b = 0; b < perRound; b++) {
    const index = start + b;
    if (index >= payload.bits.length) break;
    if (!payload.bits[index]) continue;
    attempted++;
    const cls = index % SYMBOL_CLASSES;
    const col = Math.floor(index / SYMBOL_CLASSES) % K;
    if (takeSource(fresh2.pool, cls, col, roundUsed)) fired++; else missed++;
  }
  console.log(`   [r${r} attempted=${attempted} fired=${fired} missed=${missed}]`);
}

// Copy arithmetic check for the carrier, offline: does every set cell get a
// firing in each of the three copies, given that a (source, column) pair can
// only ever be used once (a repeat creates no candidate)?
//
// Mirrors autoconnect.js: takeSource (per-source usedColumns, persisted across
// copies; CELLS_PER_SOURCE_PER_ROUND per round), the class/bucket layout, the
// per-copy bucket rotation added by this pass, the list round (3 shots per
// target), and rounds = ceil(320 / (16 * K)).
//
// Usage: node tmp/autoconnect/sim-copies.mjs [K] [classCounts] [copies] [rotate]

const SYMBOL_CLASSES = 16;
const CELLS_PER_SOURCE_PER_ROUND = 4;
const PAYLOAD_BITS = 320;
const LIST_SHOTS = 3;

const K = Number(process.argv[2] || 10);
const classCounts = (process.argv[3] ||
  "8/8/8/8/8/8/8/8/8/8/8/8/8/8/8/8").split("/").map((n) => parseInt(n, 10));
const copies = Number(process.argv[4] || 3);
const rotate = (process.argv[5] || "rotate") !== "norotate";

function makeSources(counts) {
  const pool = [];
  for (let c = 0; c < SYMBOL_CLASSES; c++) pool.push([]);
  let id = 0;
  counts.forEach((n, cls) => {
    for (let i = 0; i < n; i++) pool[cls].push({ id: id++, cls, usedColumns: Object.create(null) });
  });
  return pool;
}

function takeSource(pool, cls, col, roundUsed) {
  const bucket = pool[cls];
  if (!bucket) return null;
  for (let i = 0; i < bucket.length; i++) {
    const s = bucket[i];
    if ((roundUsed[s.id] || 0) >= CELLS_PER_SOURCE_PER_ROUND) continue;
    if (s.usedColumns[col]) continue;
    s.usedColumns[col] = true;
    roundUsed[s.id] = (roundUsed[s.id] || 0) + 1;
    return s;
  }
  return null;
}

function rotateBuckets(pool) {
  for (let c = 0; c < pool.length; c++) {
    const b = pool[c];
    if (b && b.length > 1) b.push(b.shift());
  }
}

function listRound(pool, K) {
  const used = Object.create(null);
  let fired = 0;
  for (let t = 0; t < K; t++) {
    for (let shot = 0; shot < LIST_SHOTS; shot++) {
      const s = takeSource(pool, (shot * 5) % SYMBOL_CLASSES, t, used)
        || takeSource(pool, ((t + shot) * 3) % SYMBOL_CLASSES, t, used);
      if (s) fired++;
    }
  }
  return fired;
}

function randomPayload() {
  const bits = [];
  for (let i = 0; i < PAYLOAD_BITS; i++) bits.push(Math.random() < 0.5 ? 1 : 0);
  return bits;
}

const payload = randomPayload();
const setBits = payload.filter((b) => b).length;
const pool = makeSources(classCounts);
const rounds = Math.ceil(PAYLOAD_BITS / (SYMBOL_CLASSES * K));
const attempts = new Array(PAYLOAD_BITS).fill(0);

console.log(`K=${K} rounds=${rounds} copies=${copies} rotate=${rotate} ` +
  `sources=${classCounts.reduce((a, b) => a + b, 0)} set=${setBits}`);

for (let copy = 1; copy <= copies; copy++) {
  if (rotate) rotateBuckets(pool);
  listRound(pool, K);
  let fired = 0, missed = 0;
  for (let r = 0; r < rounds; r++) {
    const start = r * SYMBOL_CLASSES * K;
    const roundUsed = Object.create(null);
    for (let b = 0; b < SYMBOL_CLASSES * K; b++) {
      const index = start + b;
      if (index >= PAYLOAD_BITS) break;
      if (!payload[index]) continue;
      const cls = index % SYMBOL_CLASSES;
      const col = Math.floor(index / SYMBOL_CLASSES) % K;
      const src = takeSource(pool, cls, col, roundUsed);
      if (!src) { missed++; continue; }
      fired++;
      attempts[index]++;
    }
  }
  const dropped = payload.reduce((acc, bit, i) => acc + (bit && attempts[i] === copy - 1 ? 1 : 0), 0);
  console.log(`copy ${copy}: fired=${fired} missed=${missed} ` +
    `(set cells with no attempt so far: ${dropped})`);
}

const zero = payload.reduce((acc, bit, i) => acc + (bit && attempts[i] === 0 ? 1 : 0), 0);
const once = payload.reduce((acc, bit, i) => acc + (bit && attempts[i] === 1 ? 1 : 0), 0);
const twice = payload.reduce((acc, bit, i) => acc + (bit && attempts[i] === 2 ? 1 : 0), 0);
const thrice = payload.reduce((acc, bit, i) => acc + (bit && attempts[i] >= 3 ? 1 : 0), 0);
console.log(`set cells by attempts across ${copies} copies: 0=${zero} 1=${once} 2=${twice} 3+=${thrice}`);

// Union model: per-copy capture p, independent; a cell is read if any of its
// attempts arrived.
for (const p of [0.85, 0.95, 0.99]) {
  let miss = 0;
  payload.forEach((bit, i) => {
    if (!bit) return;
    // binomial-free: use the attempt count, assume each attempt independently captures
    let lost = 1;
    for (let a = 0; a < attempts[i]; a++) lost *= (1 - p);
    miss += lost;

  });
  console.log(`  union at capture ${Math.round(p * 100)}%: expected set cells still` +
    ` missing ~${miss.toFixed(2)}`);
}

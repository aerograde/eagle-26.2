#!/usr/bin/env node
"use strict";
const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");
const crypto = require("node:crypto");
const { promisify } = require("node:util");
const {
  atomicWrite,
  persistVerifiedBrotli,
  readText,
  readVerifiedBrotli
} = require("./content-verified-brotli.js");
const brotli = promisify(zlib.brotliCompress);
const gzip = promisify(zlib.gzip);
const root = path.resolve(process.argv[2] || path.join(__dirname, "../target_teavm_wasm_gc/build/web"));
const quality = Number(process.env.EAGLER_BROTLI_QUALITY || "11");
const skipGzip = process.env.EAGLER_SKIP_GZIP || "0";
const reuseHigherQuality = process.env.EAGLER_REUSE_HIGHER_BROTLI_QUALITY ?? "0";
const jobs = Number(process.env.EAGLER_COMPRESS_JOBS || "3");
if (!Number.isInteger(quality) || quality < 0 || quality > 11
    || !["0", "1"].includes(skipGzip) || !["0", "1"].includes(reuseHigherQuality)
    || !Number.isInteger(jobs) || jobs < 1 || jobs > 3) {
  throw new Error("Expected Brotli quality 0..11, skip gzip 0|1, reuse higher Brotli quality 0|1, and compression jobs 1..3");
}
const files = ["classes.wasm", "mesh-worker.wasm", "server-worker.wasm", "assets.epk",
  "sounds.epk", "music.epk", "classes.wasm-runtime.js"].filter(name => fs.existsSync(path.join(root, name)));
const hash = bytes => crypto.createHash("sha256").update(bytes).digest("hex");

// Packaging copies change mtimes even for identical assets. Verify content:
// both hashes are recorded so replacing either payload invalidates the cache.
function reusable(file, bytes, digest, decode) {
  if (!fs.existsSync(file)) return false;
  const compressed = fs.readFileSync(file);
  const identity = `${digest} ${hash(compressed)}`;
  if (readText(file + ".sha256") === identity) return true;
  try { if (!decode(compressed).equals(bytes)) return false; }
  catch { return false; }
  atomicWrite(file + ".sha256", identity + "\n");
  return true;
}

async function compressFile(name) {
  const source = path.join(root, name);
  const bytes = fs.readFileSync(source);
  const digest = hash(bytes);
  const output = source + ".br";
  const meta = `quality=${quality}`;
  const existingMeta = readText(output + ".meta");
  const existingQuality = /^quality=([0-9]|10|11)$/.exec(existingMeta);
  // Fast dev assembly may retain an already verified release-quality payload.
  // Keep its actual quality metadata/checksum; explicit quality remains exact
  // unless the caller opts in. Lower or unknown quality always recompresses.
  const acceptableQuality = existingMeta === meta || reuseHigherQuality === "1"
    && existingQuality !== null && Number(existingQuality[1]) > quality;
  const verified = acceptableQuality ? readVerifiedBrotli(source, bytes, quality, {
    allowHigherQuality: reuseHigherQuality === "1"
  }) : null;
  if (verified) {
    // Older packaging also checks mtime. Refresh only after content validation.
    const now = new Date(); fs.utimesSync(output, now, now);
    console.log(`[precompress] reused q${verified.quality} ${name} (content verified; requested q${quality})`);
  } else {
    const start = performance.now();
    const compressed = await brotli(bytes, { params: {
      [zlib.constants.BROTLI_PARAM_QUALITY]: quality,
      [zlib.constants.BROTLI_PARAM_LGWIN]: 24,
      [zlib.constants.BROTLI_PARAM_SIZE_HINT]: bytes.length
    }});
    persistVerifiedBrotli(source, bytes, compressed, quality);
    console.log(`[precompress] q${quality} ${name}: ${bytes.length} -> ${compressed.length} bytes, ${((performance.now()-start)/1000).toFixed(2)}s`);
  }
  const gz = source + ".gz";
  if (skipGzip === "1") {
    if (fs.existsSync(gz) && !reusable(gz, bytes, digest, zlib.gunzipSync)) {
      fs.rmSync(gz); fs.rmSync(gz + ".sha256", { force: true });
    }
  } else if (!reusable(gz, bytes, digest, zlib.gunzipSync)) {
    const compressed = await gzip(bytes, { level: 9 });
    atomicWrite(gz, compressed);
    atomicWrite(gz + ".sha256", `${digest} ${hash(compressed)}\n`);
  }
}

async function main() {
  const start = performance.now();
  let next = 0;
  // Only compression is concurrent. Wasm links stay serialized, and the
  // caller's existing 13,312 MiB process-tree cap covers this process too.
  await Promise.all(Array.from({length: Math.min(jobs, files.length)}, async () => {
    while (next < files.length) await compressFile(files[next++]);
  }));
  console.log(`[precompress] ${files.length} files, ${jobs} jobs, ${((performance.now()-start)/1000).toFixed(2)}s total`);
}
main().catch(error => { console.error(error); process.exitCode = 1; });

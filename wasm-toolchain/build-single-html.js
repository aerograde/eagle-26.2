#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const zlib = require("node:zlib");
const { spawnSync } = require("node:child_process");
const {
  persistVerifiedBrotli,
  readVerifiedBrotli
} = require("./content-verified-brotli.js");

const repo = path.resolve(__dirname, "..");
const webDir = path.join(repo, "target_teavm_wasm_gc", "build", "web");
// deploy_wasm_web.sh intentionally removes music.epk from the Wasm-GC output.
// Music remains produced by the regular TeaVM asset build and is shared with
// both the inline compatibility client and the optional resource-pack ZIP.
const musicSource = path.join(repo, "target_teavm", "build", "web", "music.epk");
const args = new Set(process.argv.slice(2));
const skipBuild = args.has("--skip-build") || args.has("--pack-only");
const reuseClient = args.has("--reuse-client");
const reuseMesh = args.has("--reuse-mesh");
const reuseServer = args.has("--reuse-server");
const diagnosticBuild = args.has("--diagnostic");
const fastPackage = args.has("--fast-package");
// Match the lean Eagler 1.8 distribution: gameplay/UI/animal effects remain in
// sounds.epk, while background music and records ship as an optional imported
// resource pack. --with-music remains available for a deliberately monolithic
// compatibility build.
const withoutMusic = !args.has("--with-music");
const withMusic = !withoutMusic;
const MAX_BUILD_LIMIT_MIB = 13 * 1024;
const buildLimitMiB = Number(process.env.EAGLER_BUILD_CAP_MIB || MAX_BUILD_LIMIT_MIB);
const WASM_GC_RUNTIME_SHA256 = "1e80092312d7bfe6efa74f8bb372d5521bc0549dcfbf384f25a145261a80d124";
const WASM_GC_RUNTIME_BYTES = 13984;

const rawArgs = process.argv.slice(2);
const valueFlags = new Set(["--output", "--music-pack-output"]);
const booleanFlags = new Set([
  "--skip-build", "--pack-only", "--reuse-client", "--reuse-mesh",
  "--reuse-server", "--diagnostic", "--fast-package", "--with-music", "--help", "-h"
]);
for (let i = 0; i < rawArgs.length; ++i) {
  const arg = rawArgs[i];
  if (valueFlags.has(arg)) {
    if (++i >= rawArgs.length) fail(`${arg} requires a path`);
  } else if (!booleanFlags.has(arg)) {
    fail(`unknown argument: ${arg}`);
  }
}

if (args.has("--help") || args.has("-h")) {
  console.log(`Usage: node wasm-toolchain/build-single-html.js [options]

Build precise hosted Wasm images and package a standalone Eaglercraft HTML.

  --reuse-client        Reuse a source-fresh precise classes.wasm
  --reuse-mesh          Reuse a source-fresh precise mesh-worker.wasm
  --reuse-server        Reuse a source-fresh precise server-worker.wasm
  --skip-build          Package the already assembled hosted build
  --output PATH         Set the standalone HTML output path
  --with-music          Embed optional music instead of making a lean client
  --music-pack-output P Set the optional music-pack output path
  --diagnostic          Produce a local-only named performance build
  --fast-package        Use faster lossless compression for larger test HTMLs

The linker process tree is hard-capped at 13 GiB. Fast/global-analysis Wasm is
not accepted as a release or reusable artifact.`);
  process.exit(0);
}

function valueAfter(flag, fallback) {
  const argv = process.argv.slice(2);
  const i = argv.indexOf(flag);
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : fallback;
}

const output = path.resolve(repo, valueAfter(
  "--output",
  diagnosticBuild ? "eaglercraft-26.2-0.5-diagnostic.html" : "eaglercraft-26.2-single.html"
));
const musicPackOutput = path.resolve(repo, valueAfter(
  "--music-pack-output",
  "eaglercraft-26.2-optional-music.zip"
));

function fail(message) {
  console.error(`[single-html] ERROR: ${message}`);
  process.exit(1);
}

if (!Number.isInteger(buildLimitMiB) || buildLimitMiB < 1024 || buildLimitMiB > MAX_BUILD_LIMIT_MIB) {
  fail(`EAGLER_BUILD_CAP_MIB must be an integer from 1024 through ${MAX_BUILD_LIMIT_MIB}`);
}

function run(command, commandArgs, extraEnv = {}) {
  console.log(`[single-html] ${command} ${commandArgs.join(" ")}`);
  const capRunner = path.join(repo, "wasm-toolchain", "run-memory-capped.js");
  const result = spawnSync(process.execPath, [
    capRunner,
    "--limit-mib",
    buildLimitMiB,
    command,
    ...commandArgs
  ], { cwd: repo, stdio: "inherit", env: { ...process.env, ...extraEnv } });
  if (result.error) fail(result.error.message);
  if (result.status !== 0) fail(`${command} exited with code ${result.status}`);
}

function read(name) {
  const file = path.join(webDir, name);
  if (!fs.existsSync(file)) fail(`missing ${file}`);
  return fs.readFileSync(file);
}

function newestSourceMtime(entry) {
  if (!fs.existsSync(entry)) return 0;
  const stat = fs.statSync(entry);
  if (stat.isFile()) return stat.mtimeMs;
  let newest = 0;
  for (const name of fs.readdirSync(entry)) {
    if (name === "build" || name === ".gradle" || name === "node_modules") continue;
    newest = Math.max(newest, newestSourceMtime(path.join(entry, name)));
  }
  return newest;
}

const commonLinkInputs = [
  "build.gradle.kts", "game/src/main/java", "game/build.gradle.kts",
  "platform/src", "platform/build.gradle.kts", "platform-teavm/src",
  "platform-teavm/build.gradle.kts", "teavm-compat/src",
  "teavm-compat/build.gradle.kts", "wasm-toolchain/teavm-eagler-patch",
  "wasm-toolchain/StandaloneTeaVMLinker.java", "wasm-toolchain/standalone-classpaths.js",
  "wasm-toolchain/standalone-link-cache.js"
];

function linkInputs(target) {
  const linkerInputs = target === "target_teavm_wasm_gc_server" ? [
    "wasm-toolchain/build-server-fastutil-slice.js",
    "wasm-toolchain/link-server-standalone.js"
  ] : [
    "wasm-toolchain/link-web-target-standalone.js"
  ];
  return commonLinkInputs.concat(linkerInputs,
      [`${target}/src`, `${target}/build.gradle.kts`])
    .map(name => path.join(repo, name));
}

function verifyReusableArtifact(file, flag, target, label) {
  if (!fs.existsSync(file)) fail(`${flag} requested, but no existing ${label} is available`);
  const modeFile = `${file}.link-mode`;
  if (!fs.existsSync(modeFile) || fs.readFileSync(modeFile, "utf8").trim() !== "precise") {
    fail(`${flag} refused: ${label} is not recorded as a precise link`);
  }
  const artifactMtime = fs.statSync(file).mtimeMs;
  const newestInput = Math.max(...linkInputs(target).map(newestSourceMtime));
  if (newestInput > artifactMtime + 1000) {
    fail(`${flag} refused: a ${target} source/toolchain input is newer than ${label}`);
  }
  console.log(`[single-html] Reusing source-fresh precise ${label} (${fs.statSync(file).size.toLocaleString()} bytes)`);
}

function prepareBaseAssets() {
  run("./gradlew", [
    ":target_lwjgl_desktop:buildAssetsEPK",
    "--console=plain",
    "--no-daemon",
    "--max-workers=1",
    "-Dorg.gradle.parallel=false",
    "-Dorg.gradle.jvmargs=-Xmx4096m -Xss4m -XX:MaxMetaspaceSize=768m -XX:+UseG1GC"
  ]);
  const packed = path.join(repo, "target_lwjgl_desktop", "build", "epk", "assets.epk");
  const hosted = path.join(repo, "target_teavm", "build", "web", "assets.epk");
  if (!fs.existsSync(packed)) fail("buildAssetsEPK completed without producing assets.epk");
  fs.mkdirSync(path.dirname(hosted), { recursive: true });
  fs.copyFileSync(packed, hosted);
  console.log(`[single-html] Installed source-matched assets.epk (${fs.statSync(hosted).size.toLocaleString()} bytes)`);
}

function compressedWasm(name, bytes) {
  const rawPath = path.join(webDir, name);
  const requestedQuality = diagnosticBuild || fastPackage ? 4 : 11;
  const cached = readVerifiedBrotli(rawPath, bytes, requestedQuality, {
    allowHigherQuality: diagnosticBuild || fastPackage
  });
  if (cached) {
    console.log(`[single-html] Reusing content-verified q${cached.quality} ${name}.br`);
    return cached.compressed;
  }
  const start = performance.now();
  console.log(`[single-html] Brotli-compressing ${bytes.length.toLocaleString()} byte ${name} at quality ${requestedQuality}`);
  const compressed = zlib.brotliCompressSync(bytes, {
    params: {
      [zlib.constants.BROTLI_PARAM_QUALITY]: requestedQuality,
      [zlib.constants.BROTLI_PARAM_LGWIN]: 24,
      [zlib.constants.BROTLI_PARAM_SIZE_HINT]: bytes.length
    }
  });
  persistVerifiedBrotli(rawPath, bytes, compressed, requestedQuality);
  console.log(`[single-html] Cached q${requestedQuality} ${name}.br (${((performance.now() - start) / 1000).toFixed(2)}s)`);
  return compressed;
}

function payloadTag(id, bytes) {
  const encoded = bytes.toString("base64");
  const lines = [];
  const width = 256 * 1024;
  for (let i = 0; i < encoded.length; i += width) {
    lines.push(encoded.slice(i, i + width));
  }
  return `<script type="application/octet-stream" id="${id}" data-size="${bytes.length}">\n${lines.join("\n")}\n</script>`;
}

function browserDecoderSource(source) {
  let out = source
    .replace(/import\.meta\.url/g, '"about:blank"')
    .replace(/export class /g, "class ")
    .replace(/export const /g, "const ")
    .replace(/export function /g, "function ")
    .replace(/export\{h as initSync,N as default\};?/, "window.__eagBrotli={initSync:h,decompress:decompress};");
  if (!out.includes("window.__eagBrotli=")) {
    fail("unsupported brotli-dec-wasm browser wrapper");
  }
  return out.replace(/<\/script/gi, "<\\/script");
}

if (!skipBuild) {
  const clientArtifact = path.join(
    repo,
    "target_teavm_wasm_gc",
    "build",
    "generated",
    "teavm",
    "wasm-gc",
    "classes.wasm"
  );
  if (reuseClient) {
    if (diagnosticBuild) fail("--reuse-client cannot promote a diagnostic build");
    verifyReusableArtifact(clientArtifact, "--reuse-client", "target_teavm_wasm_gc", "classes.wasm");
  } else {
    const standaloneClientArgs = ["wasm-toolchain/link-web-target-standalone.js", "client"];
    if (diagnosticBuild) standaloneClientArgs.push("--diagnostic");
    run(process.execPath, standaloneClientArgs);
  }
  if (reuseMesh) {
    const meshArtifact = path.join(
      repo,
      "target_teavm_wasm_gc_mesh",
      "build",
      "generated",
      "teavm",
      "wasm-gc",
      "mesh-worker.wasm"
    );
    verifyReusableArtifact(meshArtifact, "--reuse-mesh", "target_teavm_wasm_gc_mesh", "mesh-worker.wasm");
  } else {
    run(process.execPath, ["wasm-toolchain/link-web-target-standalone.js", "mesh"]);
  }
  const serverArtifact = path.join(
    repo,
    "target_teavm_wasm_gc_server",
    "build",
    "generated",
    "teavm",
    "wasm-gc",
    "server-worker.wasm"
  );
  if (reuseServer) {
    verifyReusableArtifact(serverArtifact, "--reuse-server", "target_teavm_wasm_gc_server", "server-worker.wasm");
  } else {
    // Keep exact dependency analysis and ADVANCED optimization, but link it in
    // a standalone JVM so Gradle does not consume the 13 GiB process-tree cap.
    run(process.execPath, ["wasm-toolchain/link-server-standalone.js"]);
  }
  // The client linker installs the generic runtime glue from the authenticated
  // TeaVM 0.13.1 tool classpath. Fail before deployment/compression if a clean
  // or reused build did not provide that exact resource.
  const runtimeArtifact = path.join(
    repo,
    "target_teavm_wasm_gc",
    "build",
    "generated", "teavm", "wasm-gc",
    "classes.wasm-runtime.js"
  );
  if (!fs.existsSync(runtimeArtifact)) {
    fail(`client linker did not install the required TeaVM 0.13.1 runtime JS: ${runtimeArtifact}`);
  }
  const runtimeBytes = fs.readFileSync(runtimeArtifact);
  const runtimeHash = crypto.createHash("sha256").update(runtimeBytes).digest("hex");
  if (runtimeBytes.length !== WASM_GC_RUNTIME_BYTES || runtimeHash !== WASM_GC_RUNTIME_SHA256) {
    fail(`invalid TeaVM 0.13.1 runtime JS at ${runtimeArtifact}: bytes=${runtimeBytes.length} sha256=${runtimeHash}`);
  }
  prepareBaseAssets();
  run("bash", ["wasm-toolchain/deploy_wasm_web.sh"], {
    EAGLER_USE_DEDICATED_SERVER_WASM: "1"
  });
  run("bash", ["wasm-toolchain/precompress-web.sh"], fastPackage ? {
    EAGLER_BROTLI_QUALITY: "4",
    EAGLER_SKIP_GZIP: "1",
    EAGLER_REUSE_HIGHER_BROTLI_QUALITY: "1"
  } : {});
}

const diagnosticMarker = path.join(webDir, "DIAGNOSTIC_BUILD_DO_NOT_DEPLOY");
if (diagnosticBuild) {
  fs.writeFileSync(diagnosticMarker,
    "Temporary 0.5 performance diagnostic build with named Wasm and verbose perfdebug hooks.\n" +
    "Rebuild without --diagnostic before Cloudflare staging.\n");
} else if (!skipBuild && fs.existsSync(diagnosticMarker)) {
  fs.unlinkSync(diagnosticMarker);
}

const decoderRoot = path.join(repo, "node_modules", "brotli-dec-wasm", "pkg");
if (!fs.existsSync(decoderRoot)) {
  fail("dependency missing; run `npm install` once, then rerun this command");
}

let html = read("index.html").toString("utf8");
const iwaBundleURL = String(process.env.EAGLER_IWA_BUNDLE_URL || "").trim();
if (iwaBundleURL) {
  let parsedIwaBundleURL;
  try {
    parsedIwaBundleURL = new URL(iwaBundleURL);
  } catch (error) {
    fail("EAGLER_IWA_BUNDLE_URL must be an absolute HTTPS URL");
  }
  if (parsedIwaBundleURL.protocol !== "https:") {
    fail("EAGLER_IWA_BUNDLE_URL must use HTTPS");
  }
  const marker = 'window.eaglercraftXIwaBundleURL = "";';
  if (!html.includes(marker)) fail("IWA bundle URL marker is missing from index.html");
  html = html.replace(marker,
    `window.eaglercraftXIwaBundleURL = ${JSON.stringify(parsedIwaBundleURL.href)};`);
}
const runtimeJs = read("classes.wasm-runtime.js").toString("utf8");
const workerJs = read("worker-bootstrap.js").toString("utf8");
const wasm = read("classes.wasm");
const meshWasm = read("mesh-worker.wasm");
// The slim Wasm-GC client intentionally excludes IntegratedServer. A matching
// dedicated server image is therefore required for hosted and standalone use.
const serverWasm = read("server-worker.wasm");
const assets = read("assets.epk");
const sounds = read("sounds.epk");
if (!fs.existsSync(musicSource)) {
  fail(`missing music EPK at ${musicSource}; build the regular TeaVM web assets first`);
}
const music = withMusic ? fs.readFileSync(musicSource) : null;
const favicon = fs.readFileSync(path.join(repo, "game", "src", "main", "resources", "pack.png"));
const decoderJs = browserDecoderSource(
  fs.readFileSync(path.join(decoderRoot, "brotli_dec_wasm.js"), "utf8")
);
const decoderWasm = fs.readFileSync(path.join(decoderRoot, "brotli_dec_wasm_bg.wasm"));

// Hosted preloads save startup latency, but a standalone file must not ask the
// browser to fetch sibling payloads before the inline fetch shim is installed.
// Besides producing noisy file:// origin errors, those speculative requests can
// contend with decoding the embedded payloads on low-memory devices.
html = html.replace(
  /\s*<link rel="preload" href="(?:(?:classes|mesh-worker|server-worker)\.wasm|assets\.epk|sounds\.epk|music\.epk)(?:\?v=[^"]*)?"[^>]*\/?>/g,
  ""
);

if (wasm.length > 130_000_000 && !diagnosticBuild) {
  fail(`classes.wasm is ${wasm.length} bytes; release obfuscation did not strip the debug name section`);
}
if (diagnosticBuild) {
  console.log("[single-html] DIAGNOSTIC build: Wasm function names and local perfdebug tooling are retained; do not stage or deploy");
}

const wasmBrotli = compressedWasm("classes.wasm", wasm);
const meshWasmBrotli = compressedWasm("mesh-worker.wasm", meshWasm);
const serverWasmBrotli = compressedWasm("server-worker.wasm", serverWasm);

html = html.replace(
  /<script type="text\/javascript" src="classes\.wasm-runtime\.js(?:\?v=[^"]+)?"><\/script>/,
  `<script type="text/javascript">\n${runtimeJs}\n</script>`
);
html = html.replace(
  /<script type="text\/javascript" src="worker-bootstrap\.js(?:\?v=[^"]+)?"><\/script>/,
  `<script type="text/javascript">\n${workerJs}\n</script>`
);

if (withoutMusic) {
  html = html.replace(
    '{ url: "sounds.epk", path: "" },\n\t\t\t\t{ url: "music.epk", path: "" }',
    '{ url: "sounds.epk", path: "" }'
  );
}

html = html.replace(
  'href="favicon.png"',
  `href="data:image/png;base64,${favicon.toString("base64")}"`
);

const payloads = [
  payloadTag("eag-inline-decoder", decoderWasm),
  payloadTag("eag-inline-wasm-br", wasmBrotli),
  payloadTag("eag-inline-mesh-wasm-br", meshWasmBrotli),
  payloadTag("eag-inline-assets", assets),
  payloadTag("eag-inline-sounds", sounds)
];
payloads.splice(3, 0, payloadTag("eag-inline-server-wasm-br", serverWasmBrotli));
if (music) payloads.push(payloadTag("eag-inline-music", music));

const prelude = `
${payloads.join("\n")}
<script type="text/javascript">
${decoderJs}
</script>
<script type="text/javascript">
(function () {
  "use strict";
  var originalFetch = window.fetch.bind(window);
  var cachedWasm = null;
  var cachedMeshWasm = null;
  var cachedServerWasm = null;
  var inlinePayloadCache = Object.create(null);
  var inlinePayloadCacheBytes = 0;
  var inlinePayloadPrefix = "eagler-inline-payload://";
  var payloadIds = {
    "assets.epk": "eag-inline-assets",
    "sounds.epk": "eag-inline-sounds"${music ? ',\n    "music.epk": "eag-inline-music"' : ""}
  };

  function decodePayload(id) {
    var node = document.getElementById(id);
    if (!node) throw new Error("single-file payload already released or missing: " + id);
    var encoded = node.textContent;
    var out = new Uint8Array(Number(node.getAttribute("data-size")) || 0);
    var offset = 0;
    var start = 0;
    while (start < encoded.length) {
      while (start < encoded.length && encoded.charCodeAt(start) <= 32) ++start;
      if (start >= encoded.length) break;
      var end = encoded.indexOf("\\n", start);
      if (end < 0) end = encoded.length;
      while (end > start && encoded.charCodeAt(end - 1) <= 32) --end;
      var binary = atob(encoded.slice(start, end));
      for (var i = 0; i < binary.length; ++i) out[offset + i] = binary.charCodeAt(i);
      offset += binary.length;
      binary = "";
      start = end + 1;
    }
    encoded = "";
    node.textContent = "";
    node.remove();
    if (offset !== out.length) throw new Error("inline payload size mismatch: " + id);
    return out;
  }

  function decodePayloadAsync(id) {
    if (inlinePayloadCache[id]) return inlinePayloadCache[id];
    var node = document.getElementById(id);
    if (!node) throw new Error("single-file payload already released or missing: " + id);
    var size = Number(node.getAttribute("data-size")) || 0;
    var encoded = node.textContent;
    var out = new Uint8Array(size);
    var offset = 0;
    var start = 0;
    inlinePayloadCacheBytes += size;
    window.__eaglerInlinePayloadCacheBytes = inlinePayloadCacheBytes;
    inlinePayloadCache[id] = new Promise(function (resolve, reject) {
      function step() {
        try {
          while (start < encoded.length && encoded.charCodeAt(start) <= 32) ++start;
          if (start >= encoded.length) {
            encoded = "";
            node.textContent = "";
            node.remove();
            if (offset !== out.length) throw new Error("inline payload size mismatch: " + id);
            resolve(out.buffer);
            return;
          }
          var end = encoded.indexOf("\\n", start);
          if (end < 0) end = encoded.length;
          while (end > start && encoded.charCodeAt(end - 1) <= 32) --end;
          var binary = atob(encoded.slice(start, end));
          for (var i = 0; i < binary.length; ++i) out[offset + i] = binary.charCodeAt(i);
          offset += binary.length;
          binary = "";
          start = end + 1;
          setTimeout(step, 0);
        } catch (ex) {
          reject(ex);
        }
      }
      setTimeout(step, 0);
    }).then(function (buffer) {
      delete inlinePayloadCache[id];
      inlinePayloadCacheBytes -= size;
      window.__eaglerInlinePayloadCacheBytes = inlinePayloadCacheBytes;
      out = null;
      return buffer;
    }, function (error) {
      delete inlinePayloadCache[id];
      inlinePayloadCacheBytes -= size;
      window.__eaglerInlinePayloadCacheBytes = inlinePayloadCacheBytes;
      out = null;
      throw error;
    });
    return inlinePayloadCache[id];
  }

  var decoderBytes = decodePayload("eag-inline-decoder");
  window.__eagBrotli.initSync({ module: decoderBytes });
  decoderBytes = null;

  window.__eagPrepareInlineAssets = function () {
    var entries = window.eaglercraftXOpts && window.eaglercraftXOpts.assetsURI;
    if (!Array.isArray(entries)) return;
    for (var i = 0; i < entries.length; ++i) {
      var name = String(entries[i].url || "").split("/").pop().split(/[?#]/)[0];
      var id = payloadIds[name];
      if (!id) continue;
      entries[i].url = inlinePayloadPrefix + name;
    }
  };

  window.__eaglerInlinePayloadCacheBytes = 0;

  window.fetch = function (input, init) {
    var url = typeof input === "string" ? input : (input && input.url) || String(input);
    if (url.indexOf(inlinePayloadPrefix) === 0) {
      var name = url.slice(inlinePayloadPrefix.length).split(/[?#]/)[0];
      var id = payloadIds[name];
      if (!id) return Promise.reject(new Error("unknown inline payload: " + name));
      return decodePayloadAsync(id).then(function (raw) {
        return new Response(raw, { status: 200, headers: { "Content-Type": "application/octet-stream" } });
      });
    }
    var clean = url.split("?")[0].split("#")[0].split("/").pop();
    if (clean === "mesh-worker.wasm") {
      if (!cachedMeshWasm) {
        cachedMeshWasm = Promise.resolve().then(function () {
          var compressed = decodePayload("eag-inline-mesh-wasm-br");
          var raw = window.__eagBrotli.decompress(compressed);
          compressed = null;
          return raw;
        });
      }
      return cachedMeshWasm.then(function (raw) {
        return new Response(raw, { status: 200, headers: { "Content-Type": "application/wasm" } });
      });
    }
    if (clean === "server-worker.wasm") {
      if (!cachedServerWasm) {
        cachedServerWasm = Promise.resolve().then(function () {
          var compressed = decodePayload("eag-inline-server-wasm-br");
          var raw = window.__eagBrotli.decompress(compressed);
          compressed = null;
          return raw;
        });
      }
      return cachedServerWasm.then(function (raw) {
        return new Response(raw, { status: 200, headers: { "Content-Type": "application/wasm" } });
      });
    }
    if (clean !== "classes.wasm") return originalFetch(input, init);
    if (!cachedWasm) {
      cachedWasm = Promise.resolve().then(function () {
        var compressed = decodePayload("eag-inline-wasm-br");
        var raw = window.__eagBrotli.decompress(compressed);
        compressed = null;
        return raw;
      });
    }
    return cachedWasm.then(function (raw) {
      return new Response(raw, { status: 200, headers: { "Content-Type": "application/wasm" } });
    });
  };

  window.__eagReleaseInlineWasm = function () { cachedWasm = null; cachedMeshWasm = null; cachedServerWasm = null; };
  // Keep the worker self-contained. A file:// document has an opaque origin, so a worker
  // created from one blob:null URL cannot importScripts() a second blob:null runtime URL.
  window.__eaglerInlineWorkerBlobURL = URL.createObjectURL(new Blob([
    ${JSON.stringify(runtimeJs)}, ${JSON.stringify("\n")}, ${JSON.stringify(workerJs)}
  ], { type: "text/javascript" }));
  var serverAssetNode = document.getElementById("eag-inline-assets");
  window.__eaglerWasmServerWorkerBootstrapURL = URL.createObjectURL(new Blob([
    ${JSON.stringify(`(function () {
  "use strict";
  var originalFetch = self.fetch.bind(self);
  var OriginalXHR = self.XMLHttpRequest;
  var encoded = \``)},
    serverAssetNode ? serverAssetNode.textContent : "",
    ${JSON.stringify(`\`;
  var assetBuffer = null;
  function decodeAsset() {
    if (assetBuffer) return assetBuffer;
    var size = ${assets.length};
    var out = new Uint8Array(size);
    var offset = 0;
    var start = 0;
    while (start < encoded.length) {
      while (start < encoded.length && encoded.charCodeAt(start) <= 32) ++start;
      if (start >= encoded.length) break;
      var end = encoded.indexOf("\\n", start);
      if (end < 0) end = encoded.length;
      while (end > start && encoded.charCodeAt(end - 1) <= 32) --end;
      var binary = atob(encoded.slice(start, end));
      for (var i = 0; i < binary.length; ++i) out[offset + i] = binary.charCodeAt(i);
      offset += binary.length;
      start = end + 1;
    }
    encoded = "";
    if (offset !== size) throw new Error("inline server asset size mismatch");
    assetBuffer = out.buffer;
    return assetBuffer;
  }
  self.fetch = function (input, init) {
    var url = typeof input === "string" ? input : (input && input.url) || String(input);
    if (url.indexOf("eagler-inline-payload://assets.epk") !== 0) {
      return originalFetch(input, init);
    }
    return Promise.resolve(new Response(decodeAsset(), {
      status: 200,
      headers: { "Content-Type": "application/octet-stream" }
    }));
  };
  self.XMLHttpRequest = function () {
    var delegate = null;
    var listeners = Object.create(null);
    var inline = false;
    var url = "";
    this.status = 0;
    this.response = null;
    this.responseType = "";
    this.addEventListener = function (type, listener) {
      listeners[type] = listener;
      if (delegate) delegate.addEventListener(type, listener);
    };
    this.open = function (method, nextURL, async) {
      url = String(nextURL);
      inline = url.indexOf("eagler-inline-payload://assets.epk") === 0;
      if (!inline) {
        delegate = new OriginalXHR();
        delegate.responseType = this.responseType;
        for (var type in listeners) delegate.addEventListener(type, listeners[type]);
        delegate.open(method, nextURL, async);
      }
    };
    this.send = function (body) {
      if (!inline) {
        delegate.responseType = this.responseType;
        delegate.send(body);
        return;
      }
      var selfXHR = this;
      setTimeout(function () {
        try {
          selfXHR.status = 200;
          selfXHR.response = decodeAsset();
          if (listeners.load) listeners.load.call(selfXHR, { target: selfXHR });
        } catch (ex) {
          if (listeners.error) listeners.error.call(selfXHR, { target: selfXHR, error: ex });
        }
      }, 0);
    };
  };
})();
`)},
    ${JSON.stringify(runtimeJs)}, ${JSON.stringify("\n")}, ${JSON.stringify(workerJs)}
  ], { type: "text/javascript" }));
})();
</script>`;

html = html.replace("<head>", `<head>\n${prelude}`);
html = html.replace(
  "\t\t(async () => {\n\t\t\ttry {",
  "\t\t(async () => {\n\t\t\ttry {\n\t\t\t\twindow.__eagPrepareInlineAssets();"
);
html = html.replace(
  /new URL\("classes\.wasm-runtime\.js(?:\?v=[^"]+)?", location\.href\)\.href/g,
  'null'
);
html = html.replace(
  /new URL\("worker-bootstrap\.js(?:\?v=[^"]+)?", location\.href\)\.href/g,
  '(window.__eaglerInlineWorkerBlobURL || new URL("worker-bootstrap.js", location.href).href)'
);
html = html.replace(
  "window.__eaglerWasmModule = module;",
  "window.__eaglerWasmModule = module;\n\t\t\t\t\twindow.__eagReleaseInlineWasm();"
);

fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, html);
const outputBytes = fs.statSync(output).size;
// Wasm + game assets + effects expand by 4/3 in base64. Music is deliberately
// outside this ceiling because it ships as the optional ZIP beside the client.
// The dedicated 26.2 server image keeps singleplayer lossless, but its current
// q11 payload puts the complete offline file just over the old 75 MB target.
// Keep a tight deterministic ceiling while allowing the verified full image.
const target = 76_000_000;

console.log("[single-html] size breakdown:");
console.log(`  Wasm raw:        ${wasm.length.toLocaleString()} B`);
console.log(`  Wasm Brotli:     ${wasmBrotli.length.toLocaleString()} B`);
console.log(`  Mesh Wasm raw:   ${meshWasm.length.toLocaleString()} B`);
console.log(`  Mesh Wasm Brotli:${meshWasmBrotli.length.toLocaleString()} B`);
console.log(`  Server Wasm raw: ${(serverWasm ? serverWasm.length : 0).toLocaleString()} B${serverWasm ? "" : " (full-image fallback)"}`);
console.log(`  Server Wasm Br:  ${serverWasmBrotli.length.toLocaleString()} B`);
console.log(`  assets.epk:      ${assets.length.toLocaleString()} B`);
console.log(`  sounds.epk:      ${sounds.length.toLocaleString()} B`);
console.log(`  music.epk:       ${(music ? music.length : 0).toLocaleString()} B${music ? "" : " (omitted by --without-music)"}`);
console.log(`  output HTML:     ${outputBytes.toLocaleString()} B (${(outputBytes / 1_000_000).toFixed(2)} MB)`);
console.log(`[single-html] wrote ${output}`);

if (withoutMusic) {
  const musicPack = spawnSync(process.execPath, [
    path.join(repo, "wasm-toolchain", "export-music-resource-pack.js"),
    musicSource,
    musicPackOutput
  ], { cwd: repo, stdio: "inherit" });
  if (musicPack.error) fail(musicPack.error.message);
  if (musicPack.status !== 0) fail(`optional music pack export exited with code ${musicPack.status}`);
}

if (withoutMusic && outputBytes > target && !diagnosticBuild && !fastPackage) {
  fail(`lean standalone exceeded the 76 MB target by ${(outputBytes - target).toLocaleString()} bytes`);
}

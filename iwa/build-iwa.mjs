import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import { brotliDecompressSync } from "node:zlib";
import {
  chmodSync, copyFileSync, cpSync, existsSync, mkdirSync, mkdtempSync,
  readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync
} from "node:fs";
import { basename, dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "..");
const { version } = JSON.parse(readFileSync(join(here, "package.json"), "utf8"));
if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error("IWA package version must have three numeric components");
const bundleName = `eaglercraft-26.2-${version}`;
const options = new Map();
let legacySource;
for (let i = 2; i < process.argv.length; ++i) {
  const arg = process.argv[i];
  if (arg === "--source" || arg === "--output" || arg === "--key") {
    if (++i >= process.argv.length || options.has(arg)) throw new Error(`Invalid ${arg} argument`);
    options.set(arg, process.argv[i]);
  } else if (!arg.startsWith("--") && !legacySource) {
    legacySource = arg;
  } else {
    throw new Error(`Unknown argument: ${arg}`);
  }
}
const source = resolve(options.get("--source") || legacySource || join(repo, "target_teavm_wasm_gc/build/web"));
const packageOutput = resolve(options.get("--output") || join(here, "build", `${bundleName}.swbn`));
if (existsSync(packageOutput)) throw new Error(`Output already exists: ${packageOutput}`);
mkdirSync(dirname(packageOutput), { recursive: true });
const output = mkdtempSync(join(dirname(packageOutput), `.${basename(packageOutput)}.stage-`));
process.on("exit", () => rmSync(output, { recursive: true, force: true }));
const dist = join(output, "dist");
const key = resolve(options.get("--key") || join(here, "build", "local-dev-key.pem"));
const unsigned = join(output, `${bundleName}.wbn`);
const signed = join(output, `${bundleName}.swbn`);
const runtimeGenerator = join(repo, "wasm-toolchain/generate-iwa-csp-runtime.mjs");
const rawWasm = {
  "classes.wasm": join(repo, "target_teavm_wasm_gc/build/generated/teavm/wasm-gc/classes.wasm"),
  "mesh-worker.wasm": join(repo,
    "target_teavm_wasm_gc_mesh/build/generated/teavm/wasm-gc/mesh-worker.wasm"),
  "server-worker.wasm": join(repo,
    "target_teavm_wasm_gc_server/build/generated/teavm/wasm-gc/server-worker.wasm")
};

const sha256 = value => createHash("sha256").update(value).digest("hex");
const required = [
  "index.html", "classes.wasm-runtime.js", "worker-bootstrap.js", "assets.epk",
  "sounds.epk", "classes.wasm.br", "mesh-worker.wasm.br", "server-worker.wasm.br",
  "classes.wasm.br.meta", "mesh-worker.wasm.br.meta", "server-worker.wasm.br.meta",
  "favicon.png"
];
for (const name of required) {
  if (!existsSync(join(source, name))) throw new Error(`Missing source artifact: ${join(source, name)}`);
}
for (const path of [runtimeGenerator, ...Object.values(rawWasm)]) {
  if (!existsSync(path)) throw new Error(`Missing source artifact: ${path}`);
}
if (existsSync(join(source, "DIAGNOSTIC_BUILD_DO_NOT_DEPLOY"))) {
  throw new Error("Refusing to package a diagnostic web build as a production IWA");
}
const sourceHTML = readFileSync(join(source, "index.html"), "utf8");
const sourceBuilds = new Set([...sourceHTML.matchAll(/\?v=([0-9a-f]{16})/g)].map(match => match[1]));
if (sourceBuilds.size !== 1) {
  throw new Error(`Expected one source build token, found ${[...sourceBuilds].join(", ") || "none"}`);
}
const sourceBuild = [...sourceBuilds][0];

const wasmReport = [];
for (const [name, rawPath] of Object.entries(rawWasm)) {
  const compressedPath = join(source, `${name}.br`);
  const compression = readFileSync(`${compressedPath}.meta`, "utf8").trim();
  const compressed = readFileSync(compressedPath);
  const decoded = brotliDecompressSync(compressed);
  const raw = readFileSync(rawPath);
  if (!decoded.equals(raw)) throw new Error(`${name}.br does not decode to the retained Wasm artifact`);
  wasmReport.push({
    file: name,
    rawSha256: sha256(raw),
    rawBytes: raw.length,
    compression,
    compressedSha256: sha256(compressed),
    compressedBytes: compressed.length
  });
}

rmSync(dist, { recursive: true, force: true });
mkdirSync(dist, { recursive: true });
for (const name of [
  "assets.epk", "sounds.epk", "favicon.png", "worker-bootstrap.js",
  "classes.wasm.br", "mesh-worker.wasm.br", "server-worker.wasm.br"
]) {
  copyFileSync(join(source, name), join(dist, name));
}
const localeSource = existsSync(join(source, "lang"))
  ? join(source, "lang") : join(repo, "target_teavm/build/web/lang");
if (!existsSync(localeSource)) throw new Error(`Missing locale artifacts: ${localeSource}`);
cpSync(localeSource, join(dist, "lang"), { recursive: true });
copyFileSync(join(here, "direct-socket-loopback.js"), join(dist, "iwa-direct-socket-loopback.js"));
copyFileSync(join(here, "brotli-loader-iwa.js"), join(dist, "brotli-loader-iwa.js"));
copyFileSync(join(repo, "cloudflare-client/brotli-decoder-worker.js"),
  join(dist, "brotli-decoder-worker.js"));
copyFileSync(join(repo, "node_modules/brotli-dec-wasm/pkg/brotli_dec_wasm.js"),
  join(dist, "brotli_dec_wasm.js"));
copyFileSync(join(repo, "node_modules/brotli-dec-wasm/pkg/brotli_dec_wasm_bg.wasm"),
  join(dist, "brotli_dec_wasm_bg.wasm"));

const generatedRuntime = join(dist, "classes.wasm-runtime.js");
execFileSync(process.execPath, [runtimeGenerator,
  "--runtime", join(source, "classes.wasm-runtime.js"),
  "--client", rawWasm["classes.wasm"],
  "--mesh", rawWasm["mesh-worker.wasm"],
  "--server", rawWasm["server-worker.wasm"],
  "--output", generatedRuntime
], { stdio: "inherit" });
const runtimeManifestPath = `${generatedRuntime}.manifest.json`;
const runtimeManifest = JSON.parse(readFileSync(runtimeManifestPath, "utf8"));
const expectedWasmHashes = new Map(wasmReport.map(entry => [entry.file, entry.rawSha256]));
for (const entry of runtimeManifest.wasm) {
  const name = entry.file.split("/").at(-1);
  if (expectedWasmHashes.get(name) !== entry.sha256) {
    throw new Error(`Generated runtime verified a different ${name}`);
  }
}

writeFileSync(join(dist, "iwa-worker.js"), `(() => {
  let bootstrapURL = new URL("worker-bootstrap.js", self.location.href).href;
  if (self.trustedTypes) {
    const policy = self.trustedTypes.createPolicy("default", {
      createScriptURL(value) {
        const url = new URL(value, self.location.href);
        if (url.origin !== self.location.origin) throw new TypeError("Cross-origin worker script rejected");
        return url.href;
      }
    });
    bootstrapURL = policy.createScriptURL(bootstrapURL);
  }
  importScripts(bootstrapURL);
})();
`);

writeFileSync(join(dist, "iwa-preset.js"), `(() => {
  if (window.trustedTypes) {
    window.trustedTypes.createPolicy("default", {
      createScriptURL(value) {
        const url = new URL(value, location.href);
        if (url.origin !== location.origin) throw new TypeError("Cross-origin script URL rejected");
        return url.href;
      },
      createHTML(value) {
        value = String(value);
        if (value === "press any key to enable sound…<div style='margin-top:0.6em;font-size:0.5em;font-weight:normal;color:#9a9a9a;'>(required)</div>" ||
            value.startsWith("<h2>This device is incompatible with Eaglercraft&ensp;:(</h2>") ||
            value.startsWith("<h2>WebGL context lost!</h2>")) return value;
        throw new TypeError("Unexpected HTML rejected");
      }
    });
  }
  window.eaglercraftXClientScriptURL = new URL("iwa-worker.js", location.href).href;
  window.eaglercraftXIwaBundleURL = "";
})();
`);

const withoutVersions = value => value.replace(/\?v=[0-9a-z_-]+/gi, "");
let html = withoutVersions(sourceHTML);
html = html.replace(/,?\s*\{\s*url:\s*["']music\.epk["']\s*,\s*path:\s*["']["']\s*\}/i, "");
for (const name of Object.keys(rawWasm)) {
  html = html.replaceAll(`fetch("${name}")`, `window.__eagFetchBrotliWasm("${name}.br")`);
  html = html.replaceAll(`href="${name}"`, `href="${name}.br"`);
}
html = html.replace(/(<link\s+rel="preload"\s+href="(?:classes|mesh-worker|server-worker)\.wasm\.br"[^>]*?)\s+type="application\/wasm"/g,
  "$1 type=\"application/octet-stream\"");
html = html.replace(/\s*<link\s+rel="preload"\s+href="(?:classes|mesh-worker|server-worker)\.wasm\.br"[^>]*\/?>/g, "");
let bootstrapAssignments = 0;
html = html.replace(/window\.__eaglerWasmWorkerBootstrapURL\s*=\s*new URL\("worker-bootstrap\.js",\s*location\.href\)\.href;/g,
  () => {
    ++bootstrapAssignments;
    return 'window.__eaglerWasmWorkerBootstrapURL = new URL("iwa-worker.js", location.href).href;';
  });
if (bootstrapAssignments !== 1) {
  throw new Error(`Expected one Wasm worker bootstrap assignment, found ${bootstrapAssignments}`);
}
if (/(?:fetch\(|href=")[^"']*(?:classes|mesh-worker|server-worker)\.wasm(?:["'])/.test(html)) {
  throw new Error("IWA index still references an uncompressed application Wasm artifact");
}

html = html.replace(/<script/i,
  '<script type="text/javascript" src="iwa-preset.js"></script>\n\t\t<script');
let inlineIndex = 0;
let bootModules = 0;
html = html.replace(/<script([^>]*)>([\s\S]*?)<\/script>/gi, (whole, attrs, body) => {
  if (/\bsrc\s*=/i.test(attrs)) return whole;
  const name = `iwa-inline-${String(++inlineIndex).padStart(2, "0")}.js`;
  let sourceText = body.trimStart();
  if (/\btype\s*=\s*["']module["']/i.test(attrs) &&
      sourceText.includes("__eaglerWasmGCInstantiate")) {
    sourceText = 'import "./brotli-loader-iwa.js";\n' + sourceText;
    ++bootModules;
  }
  writeFileSync(join(dist, name), sourceText + "\n");
  return `<script${attrs} src="${name}"></script>`;
});
if (bootModules !== 1) throw new Error(`Expected one Wasm boot module, found ${bootModules}`);
html = html.replace("</body>",
  '<script type="text/javascript" src="iwa-direct-socket-loopback.js"></script>\n</body>');
if (/<script(?![^>]*\bsrc=)[^>]*>/i.test(html)) throw new Error("Executable inline script remains");
writeFileSync(join(dist, "index.html"), html);

function walkFiles(path, result = []) {
  for (const name of readdirSync(path).sort()) {
    const child = join(path, name);
    if (statSync(child).isDirectory()) walkFiles(child, result);
    else result.push(child);
  }
  return result;
}

for (const path of walkFiles(dist)) {
  if (!/\.(?:js|mjs)$/.test(path)) continue;
  const script = readFileSync(path, "utf8");
  if (/\beval\s*\(|\bnew\s+Function\b|\bFunction\s*\(/.test(script)) {
    throw new Error(`IWA CSP-incompatible string execution in ${relative(dist, path)}`);
  }
  execFileSync(process.execPath, ["--check", path], { stdio: "inherit" });
}

const iconBase64 = readFileSync(join(dist, "favicon.png")).toString("base64");
writeFileSync(join(dist, "iwa-icon.svg"),
  `<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 128 128">` +
  `<image width="128" height="128" href="data:image/png;base64,${iconBase64}" style="image-rendering:pixelated"/>` +
  `</svg>\n`);

mkdirSync(join(dist, ".well-known"), { recursive: true });
writeFileSync(join(dist, ".well-known/manifest.webmanifest"), JSON.stringify({
  name: "Eaglercraft 26.2",
  short_name: "Eagler 26.2 TCP",
  description: "Eaglercraft 26.2 with direct Minecraft TCP support",
  version,
  start_url: "/",
  scope: "/",
  display: "fullscreen",
  icons: [{ src: "/iwa-icon.svg", type: "image/svg+xml", sizes: "256x256", purpose: "any" }],
  permissions_policy: {
    "direct-sockets": ["self"],
    "direct-sockets-private": ["self"],
    "local-network": ["self"],
    "loopback-network": ["self"],
    "cross-origin-isolated": ["self"]
  }
}, null, 2) + "\n");
writeFileSync(join(dist, "iwa-build-manifest.json"), JSON.stringify({
  kind: "wasmgc-iwa-production",
  sourceBuild,
  runtimeSha256: sha256(readFileSync(generatedRuntime)),
  bodyTuples: runtimeManifest.bodyTuples,
  wasm: wasmReport
}, null, 2) + "\n");

if (!existsSync(key)) {
  mkdirSync(dirname(key), { recursive: true });
  execFileSync("openssl", ["genpkey", "-algorithm", "Ed25519", "-out", key], { stdio: "inherit" });
  chmodSync(key, 0o600);
}

const dumpId = join(here, "node_modules/.bin/wbn-dump-id");
const wbn = join(here, "node_modules/.bin/wbn");
const sign = join(here, "node_modules/.bin/wbn-sign");
for (const tool of [dumpId, wbn, sign]) {
  if (!existsSync(tool)) throw new Error("Run npm install in iwa/ before packaging");
}
const id = execFileSync(dumpId, ["--key", key], { encoding: "utf8" }).trim();
const origin = `isolated-app://${id}/`;
execFileSync(wbn, ["--dir", dist, "--baseURL", origin, "--output", unsigned], { stdio: "inherit" });

const require = createRequire(import.meta.url);
const { Bundle } = require("wbn");
const bundle = new Bundle(readFileSync(unsigned));
if (!bundle.urls.includes(origin)) throw new Error("Unsigned bundle has no root response");
for (const url of bundle.urls) {
  const response = bundle.getResponse(url);
  const isIndexRedirect = url === `${origin}index.html` && response.status === 301 &&
    response.headers.location === "./";
  if (response.status !== 200 && !isIndexRedirect) {
    throw new Error(`Unexpected bundle response ${response.status} for ${url}`);
  }
}
for (const name of Object.keys(rawWasm)) {
  if (bundle.urls.includes(`${origin}${name}`)) throw new Error(`Raw ${name} was packaged`);
  const response = bundle.getResponse(`${origin}${name}.br`);
  if (sha256(response.body) !== wasmReport.find(entry => entry.file === name).compressedSha256) {
    throw new Error(`Packaged ${name}.br changed bytes`);
  }
}
const packagedRuntime = bundle.getResponse(`${origin}classes.wasm-runtime.js`).body;
if (sha256(packagedRuntime) !== sha256(readFileSync(generatedRuntime))) {
  throw new Error("Packaged CSP runtime changed bytes");
}

execFileSync(sign, ["sign", unsigned, key, "--output", signed], { stdio: "inherit" });
const signedHash = sha256(readFileSync(signed));
const bundleInfo = execFileSync(sign, ["info", signed], { encoding: "utf8" });
renameSync(signed, packageOutput);
writeFileSync(`${packageOutput}.info.txt`, bundleInfo);
writeFileSync(`${packageOutput}.sha256`, `${signedHash}  ${basename(packageOutput)}\n`);
console.log(`IWA origin: ${origin}`);
console.log(`Signed local IWA: ${packageOutput} (${statSync(packageOutput).size} bytes)`);
console.log("No website, Cloudflare, or R2 configuration was changed by the packager.");

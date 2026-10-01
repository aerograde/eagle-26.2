#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  existsSync, readFileSync, readdirSync, statSync, writeFileSync
} from "node:fs";
import { basename, dirname, relative, resolve } from "node:path";
import vm from "node:vm";

const repo = resolve(dirname(new URL(import.meta.url).pathname), "..");
const argv = process.argv.slice(2);
const options = new Map();
for (let i = 0; i < argv.length; i += 2) {
  if (!argv[i].startsWith("--") || i + 1 >= argv.length) {
    throw new Error("Expected --name value arguments");
  }
  options.set(argv[i], resolve(argv[i + 1]));
}

const runtimePath = options.get("--runtime") ||
  resolve(repo, "target_teavm_wasm_gc/build/web/classes.wasm-runtime.js");
const outputPath = options.get("--output");
const wasmPaths = [
  options.get("--client") || resolve(repo,
    "target_teavm_wasm_gc/build/generated/teavm/wasm-gc/classes.wasm"),
  options.get("--mesh") || resolve(repo,
    "target_teavm_wasm_gc_mesh/build/generated/teavm/wasm-gc/mesh-worker.wasm"),
  options.get("--server") || resolve(repo,
    "target_teavm_wasm_gc_server/build/generated/teavm/wasm-gc/server-worker.wasm")
];
if (!outputPath) throw new Error("--output is required");
for (const path of [runtimePath, ...wasmPaths]) {
  if (!existsSync(path)) throw new Error(`Missing input: ${path}`);
}

const sha256 = value => createHash("sha256").update(value).digest("hex");
const inputRuntime = readFileSync(runtimePath);
const reportPath = path => {
  const local = relative(repo, path);
  return local && !local.startsWith("..") ? local : basename(path);
};

function walk(path, result) {
  if (!existsSync(path)) return;
  const stat = statSync(path);
  if (stat.isFile()) {
    if (/teavm-core-[^/]+\.jar$/.test(path)) result.push(path);
    return;
  }
  for (const name of readdirSync(path).sort()) walk(resolve(path, name), result);
}

function findRuntimeJar() {
  const candidates = [];
  walk(resolve(process.env.HOME || "", ".gradle/caches/modules-2/files-2.1/org.teavm/teavm-core"), candidates);
  candidates.push(resolve(repo, "wasm-toolchain/teavm-eagler-patch/teavm-core-0.13.1-eagler.jar"));
  const wanted = sha256(inputRuntime);
  for (const jar of [...new Set(candidates)]) {
    if (!existsSync(jar)) continue;
    let minified;
    try {
      minified = execFileSync("unzip", ["-p", jar,
        "org/teavm/backend/wasm/wasm-gc-runtime.min.js"]);
    } catch (error) {
      continue;
    }
    if (sha256(minified) === wanted) return jar;
  }
  throw new Error(`No TeaVM core jar matches runtime SHA-256 ${wanted}`);
}

function makeContext(functionConstructor) {
  const context = {
    console, WebAssembly, Date, Error, Map, WeakMap, WeakRef,
    FinalizationRegistry, Symbol, Response, TextDecoder, Int8Array, JSON, Math,
    setTimeout, clearTimeout, fetch, process, Function: functionConstructor
  };
  context.globalThis = context;
  return context;
}

async function captureBodies() {
  const tuples = new Map();
  const perModule = [];
  for (const wasmPath of wasmPaths) {
    const calls = [];
    let context;
    function captureFunction(...args) {
      calls.push(args.map(String));
      return vm.compileFunction(String(args.at(-1)), args.slice(0, -1).map(String), {
        parsingContext: context
      });
    }
    context = makeContext(captureFunction);
    vm.createContext(context);
    vm.runInContext(inputRuntime.toString("utf8"), context, { filename: runtimePath });
    await context.TeaVM.wasmGC.load(readFileSync(wasmPath), { noAutoImports: true });

    const bodies = calls.filter(args => args[0] === "wrapCallFromJavaToJs" &&
      !args.at(-1).includes("return new Function"));
    const moduleTuples = new Set();
    for (const args of bodies) {
      const params = args.slice(1, -1);
      const body = args.at(-1);
      if (/\beval\s*\(|\bnew\s+Function\b|\bFunction\s*\(/.test(body)) {
        throw new Error(`Nested dynamic code in ${wasmPath}`);
      }
      for (const param of ["wrapCallFromJavaToJs", ...params]) {
        if (!/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(param)) {
          throw new Error(`Unsafe JS body parameter ${JSON.stringify(param)} in ${wasmPath}`);
        }
      }
      const key = JSON.stringify([...params, body]);
      tuples.set(key, { key, params, body });
      moduleTuples.add(key);
    }
    perModule.push({
      file: reportPath(wasmPath),
      calls: bodies.length,
      unique: moduleTuples.size,
      createImports: WebAssembly.Module.imports(new WebAssembly.Module(readFileSync(wasmPath)))
        .filter(entry => entry.module === "teavmJso" && entry.name.startsWith("createFunction"))
        .map(entry => entry.name).sort()
    });
  }
  return { tuples: [...tuples.values()].sort((a, b) => a.key.localeCompare(b.key)), perModule };
}

function replaceOnce(source, before, after, label) {
  const first = source.indexOf(before);
  if (first < 0 || source.indexOf(before, first + before.length) >= 0) {
    throw new Error(`Expected exactly one ${label} runtime block`);
  }
  return source.slice(0, first) + after + source.slice(first + before.length);
}

function bodyRegistrySource(tuples) {
  const entries = tuples.map(({ key, params, body }) =>
    `    [${JSON.stringify(key)}, function(wrapCallFromJavaToJs${params.length ? ", " : ""}${params.join(", ")}) {\n${body}\n    }]`
  ).join(",\n");
  return `
const iwaStaticJSBodies = new Map([
${entries}
]);

function iwaStaticJSBody(parts) {
    let key = JSON.stringify(parts);
    let result = iwaStaticJSBodies.get(key);
    if (typeof result !== "function") {
        throw new Error("Unknown static TeaVM JS body");
    }
    return result;
}
`;
}

function exportedWrapperSource(maxArity) {
  const functionCases = [];
  const methodCases = [];
  for (let arity = 0; arity <= maxArity; ++arity) {
    const params = Array.from({ length: arity }, (_, i) => `p${i}`);
    const normalParams = params.join(", ");
    const normalCall = params.join(", ");
    let functionCase = `        case ${arity}:\n`;
    if (arity > 0) {
      const fixed = params.slice(0, -1);
      const last = params.at(-1);
      functionCase += `            if (vararg) return function(${fixed.length ? fixed.join(", ") + ", " : ""}...${last}) {\n` +
        `                try { return fn(${[...fixed, last].join(", ")}); } catch (e) { rethrowJavaAsJs(e); }\n` +
        `            };\n`;
    } else {
      functionCase += `            if (vararg) throw new Error("Invalid zero-arity vararg export");\n`;
    }
    functionCase += `            return function(${normalParams}) {\n` +
      `                try { return fn(${normalCall}); } catch (e) { rethrowJavaAsJs(e); }\n` +
      `            };`;
    functionCases.push(functionCase);

    const javaArity = arity + 1;
    const methodParams = Array.from({ length: arity }, (_, i) => `p${i + 1}`);
    let methodCase = `        case ${javaArity}:\n`;
    if (methodParams.length > 0) {
      const fixed = methodParams.slice(0, -1);
      const last = methodParams.at(-1);
      methodCase += `            if (vararg) return function(${fixed.length ? fixed.join(", ") + ", " : ""}...${last}) {\n` +
        `                try { return fn(this, ${[...fixed, last].join(", ")}); } catch (e) { rethrowJavaAsJs(e); }\n` +
        `            };\n`;
    } else {
      methodCase += `            if (vararg) throw new Error("Invalid zero-arity vararg method");\n`;
    }
    methodCase += `            return function(${methodParams.join(", ")}) {\n` +
      `                try { return fn(this${methodParams.length ? ", " : ""}${methodParams.join(", ")}); } catch (e) { rethrowJavaAsJs(e); }\n` +
      `            };`;
    methodCases.push(methodCase);
  }
  return `
    function defineFunction(fn, vararg) {
        switch (fn.length) {
${functionCases.join("\n")}
            default: throw new Error("Unsupported exported function arity: " + fn.length);
        }
    }
    function defineMethod(fn, vararg) {
        switch (fn.length) {
${methodCases.join("\n")}
            default: throw new Error("Unsupported exported method arity: " + fn.length);
        }
    }
    function renameConstructor(name, constructor) {
        let result = function(marker, javaObject) {
            return constructor.call(this, marker, javaObject);
        };
        Object.defineProperty(result, "name", { value: name, configurable: true });
        return result;
    }
`;
}

function fixedBridgeSource(maxArity) {
  const lines = [];
  for (let arity = 0; arity <= maxArity; ++arity) {
    const params = Array.from({ length: arity }, (_, i) => `p${i + 1}`);
    const args = params.join(", ");
    const suffix = args ? `, ${args}` : "";
    lines.push(`    imports.teavmJso.createFunction${arity} = function(${args}${args ? ", " : ""}body) {
        return iwaStaticJSBody([${args}${args ? ", " : ""}body]).bind(this, wrapCallFromJavaToJs);
    };`);
    lines.push(`    imports.teavmJso.bindFunction${arity} = function(fn${suffix}) {
        return fn.bind(null${suffix});
    };`);
    lines.push(`    imports.teavmJso.callFunction${arity} = function(fn${suffix}) {
        try { return fn(${args}); } catch (e) { rethrowJsAsJava(e); }
    };`);
    lines.push(`    imports.teavmJso.callMethod${arity} = function(instance, method${suffix}) {
        try {
            return instance !== null ? instance[method](${args}) : getGlobalName(method)(${args});
        } catch (e) { rethrowJsAsJava(e); }
    };`);
    lines.push(`    imports.teavmJso.construct${arity} = function(constructor${suffix}) {
        try { return new constructor(${args}); } catch (e) { rethrowJsAsJava(e); }
    };`);
    lines.push(`    imports.teavmJso.arrayOf${arity} = function(${args}) { return [${args}]; };`);
  }
  return lines.join("\n");
}

function renderRuntime(base, tuples) {
  const globalBefore = `let globalsCache = new Map();
let stackDeobfuscator = null;
let exceptionFrameRegex = /.+:wasm-function\\[[0-9]+]:0x([0-9a-f]+).*/;
let getGlobalName = function(name) {
    let result = globalsCache.get(name);
    if (typeof result === "undefined") {
        result = new Function("return " + name + ";");
        globalsCache.set(name, result);
    }
    return result();
}
let setGlobalName = function(name, value) {
    new Function("value", name + " = value;")(value);
}`;
  const globalAfter = `let globalsCache = new Map();
let stackDeobfuscator = null;
let exceptionFrameRegex = /.+:wasm-function\\[[0-9]+]:0x([0-9a-f]+).*/;
function globalPath(name) {
    if (!/^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*$/.test(name)) {
        throw new Error("Unsafe global path: " + name);
    }
    let parts = name.split(".");
    if (parts[0] === "globalThis") parts.shift();
    return parts;
}
let getGlobalName = function(name) {
    let result = globalsCache.get(name);
    if (typeof result === "undefined") {
        let parts = globalPath(name);
        result = function() {
            let value = globalThis;
            for (let part of parts) value = value[part];
            return value;
        };
        globalsCache.set(name, result);
    }
    return result();
}
let setGlobalName = function(name, value) {
    let parts = globalPath(name);
    if (parts.length === 0) throw new Error("Cannot replace globalThis");
    let target = globalThis;
    for (let i = 0; i + 1 < parts.length; ++i) target = target[parts[i]];
    target[parts[parts.length - 1]] = value;
}`;
  let source = replaceOnce(base, globalBefore, globalAfter, "global resolver");

  const iifeMarker = "TeaVM.wasmGC = TeaVM.wasmGC || (() => {";
  source = replaceOnce(source, iifeMarker, iifeMarker + bodyRegistrySource(tuples), "TeaVM IIFE");

  const defineStart = source.indexOf("    function defineFunction(fn, vararg) {");
  const defineEnd = source.indexOf("    imports.teavmJso = {", defineStart);
  if (defineStart < 0 || defineEnd < 0) throw new Error("TeaVM export wrapper block not found");
  source = source.slice(0, defineStart) + exportedWrapperSource(32) + source.slice(defineEnd);

  const methodStart = source.indexOf("        defineMethod(cls, name, fn, vararg) {");
  const methodEnd = source.indexOf("        defineStaticMethod(cls, name, fn, vararg) {", methodStart);
  if (methodStart < 0 || methodEnd < 0) throw new Error("TeaVM defineMethod block not found");
  source = source.slice(0, methodStart) +
    `        defineMethod(cls, name, fn, vararg) {\n            cls.prototype[name] = defineMethod(fn, vararg);\n        },\n` +
    source.slice(methodEnd);

  const bridgeStart = source.indexOf("    let argumentList = [];");
  const bridgeEnd = source.indexOf("\n}\n\nfunction wrapImport", bridgeStart);
  if (bridgeStart < 0 || bridgeEnd < 0) throw new Error("TeaVM bridge factory block not found");
  source = source.slice(0, bridgeStart) + fixedBridgeSource(31) + source.slice(bridgeEnd);
  return source;
}

async function verifyRuntime(source) {
  if (/\beval\s*\(|\bnew\s+Function\b|\bFunction\s*\(/.test(source)) {
    throw new Error("Generated runtime still contains dynamic string execution");
  }
  for (const wasmPath of wasmPaths) {
    const blocked = () => { throw new Error("Dynamic code execution blocked"); };
    const context = makeContext(blocked);
    context.eval = blocked;
    vm.createContext(context);
    vm.runInContext(source, context, { filename: outputPath });
    await context.TeaVM.wasmGC.load(readFileSync(wasmPath), { noAutoImports: true });
  }
}

const runtimeJar = findRuntimeJar();
const baseRuntime = execFileSync("unzip", ["-p", runtimeJar,
  "org/teavm/backend/wasm/wasm-gc-runtime.js"], { encoding: "utf8" });
const captured = await captureBodies();
const generated = renderRuntime(baseRuntime, captured.tuples);
const repeated = renderRuntime(baseRuntime, captured.tuples);
if (generated !== repeated) throw new Error("Runtime generation is not deterministic");
await verifyRuntime(generated);
writeFileSync(outputPath, generated);

const report = {
  runtimeInput: reportPath(runtimePath),
  runtimeInputSha256: sha256(inputRuntime),
  runtimeJar: basename(runtimeJar),
  output: reportPath(outputPath),
  outputSha256: sha256(generated),
  bodyTuples: captured.tuples.length,
  modules: captured.perModule,
  wasm: wasmPaths.map(path => ({ file: reportPath(path), sha256: sha256(readFileSync(path)) }))
};
writeFileSync(`${outputPath}.manifest.json`, JSON.stringify(report, null, 2) + "\n");
console.log(JSON.stringify(report, null, 2));

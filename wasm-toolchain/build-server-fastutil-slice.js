#!/usr/bin/env node
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const childProcess = require("child_process");

const repo = path.resolve(__dirname, "..");
const output = path.join(repo, "wasm-toolchain", "generated", "fastutil-server-slice.jar");
const gameJar = path.join(repo, "game", "build", "libs", "game.jar");
const gameSlice = path.join(repo, "wasm-toolchain", "generated", "game-server-slice.jar");
const serverRoot = path.join(repo, "target_teavm_wasm_gc_server", "build", "libs",
    "target_teavm_wasm_gc_server.jar");
const dependencyJars = [
  gameSlice,
  path.join(repo, "platform", "build", "libs", "platform.jar"),
  path.join(repo, "platform-teavm", "build", "libs", "platform-teavm.jar"),
  path.join(repo, "teavm-compat", "build", "libs", "teavm-compat.jar"),
];

let analysisClasspathFile = null;
for (let i = 2; i < process.argv.length; ++i) {
  if (process.argv[i] === "--classpath-file" && i + 1 < process.argv.length) {
    analysisClasspathFile = path.resolve(process.argv[++i]);
  } else {
    fail(`unknown argument: ${process.argv[i]}`);
  }
}

function fail(message) {
  console.error(`[fastutil-slice] ${message}`);
  process.exit(1);
}

function findFastutilJar() {
  const cacheRoot = path.join(os.homedir(), ".gradle", "caches", "modules-2", "files-2.1",
      "it.unimi.dsi", "fastutil", "8.5.18");
  if (!fs.existsSync(cacheRoot)) {
    fail(`Gradle fastutil cache is missing: ${cacheRoot}`);
  }
  const hashes = fs.readdirSync(cacheRoot).sort();
  for (const hash of hashes) {
    const candidate = path.join(cacheRoot, hash, "fastutil-8.5.18.jar");
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  fail(`Could not find fastutil-8.5.18.jar below ${cacheRoot}`);
}

function run(command, args, options = {}) {
  const result = childProcess.spawnSync(command, args, {
    cwd: repo,
    encoding: "utf8",
    maxBuffer: 256 * 1024 * 1024,
    ...options,
  });
  if (result.status !== 0) {
    process.stderr.write(result.stdout || "");
    process.stderr.write(result.stderr || "");
    fail(`${command} exited with status ${result.status}`);
  }
  return result.stdout || "";
}

for (const root of [gameJar, serverRoot, ...dependencyJars.slice(1)]) {
  if (!fs.existsSync(root)) {
    fail(`Compile the source jars first; missing ${root}`);
  }
}

const fastutilJar = findFastutilJar();
const gameTemporary = fs.mkdtempSync(path.join(os.tmpdir(), "eagler-game-server-slice-"));
try {
  run("jar", ["xf", gameJar], { cwd: gameTemporary });
  const clientRoot = path.join(gameTemporary, "net", "minecraft", "client");
  for (const name of fs.readdirSync(clientRoot)) {
    if (name !== "server") {
      fs.rmSync(path.join(clientRoot, name), { recursive: true, force: true });
    }
  }
  const hostRoot = path.join(gameTemporary, "net", "lax1dude", "eaglercraft", "v1_8", "sp", "server");
  for (const name of fs.readdirSync(hostRoot)) {
    if (name === "MinecraftBackedHost.class" || name.startsWith("MinecraftBackedHost$")) {
      fs.rmSync(path.join(hostRoot, name), { force: true });
    }
  }
  fs.mkdirSync(path.dirname(gameSlice), { recursive: true });
  fs.rmSync(gameSlice, { force: true });
  // Stable ZIP entry dates keep identical slices reusable after client-only edits.
  run("jar", ["--create", "--file", gameSlice, "--date=1980-01-01T00:00:02Z", "-C", gameTemporary, "."], { cwd: repo });
} finally {
  fs.rmSync(gameTemporary, { recursive: true, force: true });
}
console.log(`[fastutil-slice] wrote ${gameSlice} (${fs.statSync(gameSlice).size} bytes)`);

let analysisClasspath = [fastutilJar, ...dependencyJars];
if (analysisClasspathFile) {
  if (!fs.existsSync(analysisClasspathFile)) {
    fail(`analysis classpath file is missing: ${analysisClasspathFile}`);
  }
  analysisClasspath = fs.readFileSync(analysisClasspathFile, "utf8")
      .split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
      .filter((entry) => path.basename(entry) !== "game.jar");
  analysisClasspath.unshift(gameSlice);
}
const classPath = analysisClasspath.join(path.delimiter);
const report = run("jdeps", [
  "--multi-release", "base",
  "--ignore-missing-deps",
  "-recursive",
  "-verbose:class",
  "-filter:none",
  "--class-path", classPath,
  // Analyze the complete server-side game image, not only classes which jdeps
  // can walk from the tiny TeaVM entry jar. Minecraft reaches a few utility
  // implementations through registries and polymorphic calls which are not
  // represented as an ordinary class dependency from ServerMainClass. TeaVM's
  // exact dependency analysis does see those implementations, so the slice has
  // to contain their fastutil types as well.
  serverRoot,
  gameSlice,
]);

const requiredClasses = new Set();
for (const line of report.split(/\r?\n/)) {
  const match = line.match(/->\s+(it\.unimi\.dsi\.fastutil(?:\.[A-Za-z0-9_$]+)+)\s+/);
  if (match) {
    requiredClasses.add(match[1].replace(/\./g, "/") + ".class");
  }
}
if (requiredClasses.size === 0) {
  fail("jdeps did not discover any fastutil dependencies");
}

const entries = run("jar", ["tf", fastutilJar]).split(/\r?\n/).filter(Boolean);
const selectedEntries = new Set(["META-INF/MANIFEST.MF"]);
for (const entry of entries) {
  if (!entry.endsWith(".class")) {
    continue;
  }
  if (requiredClasses.has(entry)) {
    selectedEntries.add(entry);
  }
}

// If bytecode names an outer or nested type, retain the complete nest. Nestmate
// access is a runtime contract and jdeps is allowed to omit unused nest members.
let changed;
do {
  changed = false;
  const selectedNests = new Set();
  for (const entry of selectedEntries) {
    if (entry.endsWith(".class")) {
      selectedNests.add(entry.replace(/\$[^/]*\.class$/, ".class"));
    }
  }
  for (const entry of entries) {
    if (!entry.endsWith(".class")) {
      continue;
    }
    const outer = entry.replace(/\$[^/]*\.class$/, ".class");
    if (selectedNests.has(outer) && !selectedEntries.has(entry)) {
      selectedEntries.add(entry);
      changed = true;
    }
  }
} while (changed);

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "eagler-fastutil-slice-"));
try {
  run("jar", ["xf", fastutilJar], { cwd: temporary });
  const fastutilRoot = path.join(temporary, "it", "unimi", "dsi", "fastutil");
  const stack = [fastutilRoot];
  while (stack.length > 0) {
    const directory = stack.pop();
    for (const name of fs.readdirSync(directory)) {
      const absolute = path.join(directory, name);
      const stat = fs.lstatSync(absolute);
      if (stat.isDirectory()) {
        stack.push(absolute);
      } else {
        const relative = path.relative(temporary, absolute).split(path.sep).join("/");
        if (!selectedEntries.has(relative)) {
          fs.rmSync(absolute);
        }
      }
    }
  }
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.rmSync(output, { force: true });
  run("jar", ["--create", "--file", output, "--date=1980-01-01T00:00:02Z", "-C", temporary, "it"], { cwd: repo });
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

const fullCount = entries.filter((entry) => entry.endsWith(".class")).length;
const selectedCount = Array.from(selectedEntries).filter((entry) => entry.endsWith(".class")).length;
console.log(`[fastutil-slice] ${selectedCount}/${fullCount} classes, ${fs.statSync(output).size} bytes`);
console.log(`[fastutil-slice] wrote ${output}`);

#!/usr/bin/env node
"use strict";

// Link the client or mesh-worker with the exact TeaVM settings used by the
// corresponding Gradle task, but without keeping Gradle's project model in the
// linker JVM. The default preserves precise dependency analysis and FULL
// optimization. Client-only --fast-analysis is an explicit prototype mode;
// TeaVM 0.13.1 forces its effective optimization to SIMPLE.

const childProcess = require("child_process");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { readStandaloneClasspaths } = require("./standalone-classpaths");
const { substituteGameJarWithOverlay } = require("./field-overlay-link");
const { withVerifiedCache, withOperationLock, toolchainInputs, environmentIdentity, cacheMode } = require("./standalone-link-cache");

const repo = path.resolve(__dirname, "..");
const generated = path.join(repo, "wasm-toolchain", "generated");
const linkerClasses = path.join(generated, "standalone-linker");
const WASM_GC_RUNTIME_RESOURCE = "org/teavm/backend/wasm/wasm-gc-runtime.min.js";
const WASM_GC_RUNTIME_SHA256 = "1e80092312d7bfe6efa74f8bb372d5521bc0549dcfbf384f25a145261a80d124";
const WASM_GC_RUNTIME_BYTES = 13984;

const argv = process.argv.slice(2);
const targetName = argv[0];
const diagnostic = argv.includes("--diagnostic");
const fastAnalysis = argv.includes("--fast-analysis");
const linkerHeapMiB = Number.parseInt(process.env.EAGLER_LINK_HEAP_MIB || "12288", 10);
cacheMode(); // Reject malformed cache policy before starting Gradle.
let fieldOverlayJar = null;
for (let index = 1; index < argv.length; index++) {
  if (argv[index] === "--field-overlay") {
    if (fieldOverlayJar || index + 1 >= argv.length) {
      console.error("--field-overlay requires exactly one JAR path");
      process.exit(2);
    }
    fieldOverlayJar = path.resolve(argv[++index]);
  } else if (argv[index] !== "--diagnostic" && argv[index] !== "--fast-analysis") {
    console.error(`unknown option: ${argv[index]}`);
    process.exit(2);
  }
}
if (!new Set(["client", "mesh"]).has(targetName)) {
  console.error("usage: link-web-target-standalone.js <client|mesh> [--diagnostic] [--fast-analysis] [--field-overlay <jar>]");
  process.exit(2);
}
if (fieldOverlayJar && (!fs.statSync(fieldOverlayJar, { throwIfNoEntry: false })?.isFile())) {
  console.error(`field overlay JAR does not exist: ${fieldOverlayJar}`);
  process.exit(2);
}
if (diagnostic && targetName !== "client") {
  console.error("--diagnostic is only valid for the client target");
  process.exit(2);
}
if (fastAnalysis && targetName !== "client") {
  console.error("--fast-analysis is only valid for the client target");
  process.exit(2);
}
if (fastAnalysis && diagnostic) {
  console.error("--fast-analysis cannot be combined with --diagnostic");
  process.exit(2);
}
if (!Number.isFinite(linkerHeapMiB) || linkerHeapMiB < 8192 || linkerHeapMiB > 12544) {
  console.error("EAGLER_LINK_HEAP_MIB must be between 8192 and 12544");
  process.exit(2);
}

const target = targetName === "client" ? {
  project: "target_teavm_wasm_gc",
  mainClass: "net.lax1dude.eaglercraft.v1_8.internal.wasm_gc_teavm.MainClass",
  fileName: "classes.wasm",
  obfuscated: !diagnostic,
} : {
  project: "target_teavm_wasm_gc_mesh",
  mainClass: "net.lax1dude.eaglercraft.v1_8.internal.wasm_gc_teavm_mesh.MeshMainClass",
  fileName: "mesh-worker.wasm",
  obfuscated: true,
};

const classpathFile = path.join(generated, `${targetName}-program.classpath`);
const analysisMode = fastAnalysis ? "fast-analysis" : "precise";
const effectiveOptimization = fastAnalysis ? "SIMPLE" : "FULL";
const outputDirectory = path.join(repo, target.project, "build", "generated",
    "teavm", fastAnalysis ? "wasm-gc-standalone-fast-analysis" : "wasm-gc-standalone");
const installedDirectory = path.join(repo, target.project, "build", "generated",
    "teavm", fastAnalysis ? "wasm-gc-fast-analysis" : "wasm-gc");
const linkLog = path.join(generated, `${targetName}-${analysisMode}-link.log`);

function run(command, args, options = {}) {
  console.log(`[standalone-${targetName}-link] ${command} ${args.join(" ")}`);
  const result = childProcess.spawnSync(command, args, {
    cwd: repo,
    encoding: "utf8",
    maxBuffer: 256 * 1024 * 1024,
    ...options,
  });
  if (options.stdio !== "inherit") {
    process.stdout.write(result.stdout || "");
    process.stderr.write(result.stderr || "");
  }
  if (result.status !== 0) process.exit(result.status == null ? 1 : result.status);
  return result.stdout || "";
}

function installClientRuntime(toolClasspath, destination) {
  const candidates = toolClasspath.filter(file => /teavm-core-0\.13\.1(?:-eagler)?\.jar$/.test(file));
  for (const jar of candidates) {
    const result = childProcess.spawnSync("unzip", ["-p", jar, WASM_GC_RUNTIME_RESOURCE], {
      cwd: repo, encoding: null, maxBuffer: 1024 * 1024,
    });
    if (result.status !== 0 || !result.stdout) continue;
    const digest = crypto.createHash("sha256").update(result.stdout).digest("hex");
    if (result.stdout.length !== WASM_GC_RUNTIME_BYTES || digest !== WASM_GC_RUNTIME_SHA256) continue;
    fs.mkdirSync(path.dirname(destination), { recursive: true });
    fs.writeFileSync(destination, result.stdout);
    console.log(`[standalone-client-link] runtime=${destination} bytes=${result.stdout.length} sha256=${digest} source=${jar}`);
    return;
  }
  throw new Error(`Authenticated TeaVM 0.13.1 tool classpath lacks the expected ${WASM_GC_RUNTIME_RESOURCE} (bytes=${WASM_GC_RUNTIME_BYTES}, sha256=${WASM_GC_RUNTIME_SHA256})`);
}

function findGradleModuleJar(group, module, version, fileName) {
  const moduleRoot = path.join(os.homedir(), ".gradle", "caches", "modules-2", "files-2.1",
      group, module, version);
  if (!fs.existsSync(moduleRoot)) throw new Error(`Gradle module cache is missing: ${moduleRoot}`);
  for (const hash of fs.readdirSync(moduleRoot).sort()) {
    const candidate = path.join(moduleRoot, hash, fileName);
    if (fs.existsSync(candidate)) return candidate;
  }
  throw new Error(`Could not find ${fileName} below ${moduleRoot}`);
}

withOperationLock(path.join(generated, "standalone-link-cache"), cacheOwner => {
  fs.mkdirSync(generated, { recursive: true });
  const prepared = readStandaloneClasspaths(run("./gradlew", [
    ":game:jar",
    ":platform:jar",
    ":platform-teavm:jar",
    ":teavm-compat:jar",
    `:${target.project}:jar`,
    `:${target.project}:printStandaloneClasspaths`,
    "--console=plain", "--no-daemon", "--max-workers=2",
  ]));
  const toolClasspath = prepared.tool;
  let programClasspath = prepared.program;
  if (fieldOverlayJar) {
    const overlaySubstitution = substituteGameJarWithOverlay({
      programClasspath,
      overlayJar: fieldOverlayJar,
    });
    programClasspath = overlaySubstitution.programClasspath;
    console.log(`[standalone-${targetName}-link] field-overlay-input=${overlaySubstitution.gameJar}`);
    console.log(`[standalone-${targetName}-link] field-overlay-jar=${fieldOverlayJar}`);
    console.log(`[standalone-${targetName}-link] field-overlay-link-input=${overlaySubstitution.destination}`);
  }
  if (targetName === "client") {
    // Shared vanilla connection setup references these desktop transport class
    // shapes before the web compatibility transformer folds the native path.
    // Keep strict validation enabled and provide only the small Java API jars;
    // no native classifier is linked or executed in the browser.
    programClasspath.push(
      findGradleModuleJar("io.netty", "netty-transport-classes-epoll", "4.2.15.Final",
          "netty-transport-classes-epoll-4.2.15.Final.jar"),
      findGradleModuleJar("io.netty", "netty-transport-classes-kqueue", "4.2.15.Final",
          "netty-transport-classes-kqueue-4.2.15.Final.jar"),
      findGradleModuleJar("io.netty", "netty-transport-native-unix-common", "4.2.15.Final",
          "netty-transport-native-unix-common-4.2.15.Final.jar"),
    );
  }
  fs.writeFileSync(classpathFile, `${programClasspath.join("\n")}\n`);

  const artifact = path.join(outputDirectory, target.fileName);
  function validateArtifact() {
    if (!fs.existsSync(artifact) || fs.statSync(artifact).size < 1024 * 1024) {
      throw new Error(`Standalone TeaVM did not produce a valid artifact: ${artifact}`);
    }
    if (fastAnalysis) {
      try {
        new WebAssembly.Module(fs.readFileSync(artifact));
      } catch (error) {
        throw new Error(`Fast-analysis artifact failed Wasm validation: ${error.message}`);
      }
    }
  }
  withVerifiedCache({
    owner: cacheOwner,
    manifest: path.join(generated, "standalone-link-cache", fastAnalysis
        ? `${targetName}-${diagnostic ? "diagnostic" : "release"}-fast-analysis.json`
        : `${targetName}-${diagnostic ? "diagnostic" : "release"}.json`),
    label: fastAnalysis ? `standalone-${targetName}-fast-analysis-link` : `standalone-${targetName}-link`,
    inputs: () => {
      const runtime = toolchainInputs([process.execPath, "java", "javac"]);
      return {
        metadata: { operation: fastAnalysis ? "fast-analysis-link" : "precise-link", targetName, target, diagnostic,
          dependencyAnalysis: analysisMode, fastDependencyAnalysis: fastAnalysis,
          requestedOptimization: "FULL", effectiveOptimization, strict: true,
          linkerHeapMiB, outputDirectory, classpathFile, linkerClasses, cwd: repo,
          toolCommands: runtime.resolvedCommands, environment: environmentIdentity() },
        groups: { toolClasspath, programClasspath, runtime: runtime.files,
          source: [__filename, classpathFile, path.join(__dirname, "field-overlay-link.js"), path.join(__dirname, "standalone-link-cache.js"),
            path.join(__dirname, "standalone-classpaths.js"), path.join(__dirname, "run-memory-capped.js"),
            path.join(__dirname, "StandaloneTeaVMLinker.java")] },
      };
    },
    outputs: [outputDirectory],
    validate: validateArtifact,
    run: () => {
      fs.rmSync(linkerClasses, { recursive: true, force: true });
      fs.mkdirSync(linkerClasses, { recursive: true });
      run("javac", [
        "-cp", toolClasspath.join(path.delimiter),
        "-d", linkerClasses,
        path.join(repo, "wasm-toolchain", "StandaloneTeaVMLinker.java"),
      ]);

      fs.rmSync(outputDirectory, { recursive: true, force: true });
      fs.mkdirSync(outputDirectory, { recursive: true });
      run(process.execPath, [
        path.join(repo, "wasm-toolchain", "run-memory-capped.js"),
        "--limit-mib", "13312",
        "--log-file", linkLog,
        "java",
          "-XX:+UnlockExperimentalVMOptions",
          "-XX:+UseCompactObjectHeaders",
          `-Xmx${linkerHeapMiB}m`,
          // The mesh target's generated registry initializer is deeply nested. TeaVM's
          // WasmGC init-function splitter walks that tree recursively, so give that
          // linker thread enough stack without changing the linked program or its
          // optimization level. The client has already proven sufficient at 2 MiB.
          targetName === "mesh" ? "-Xss32m" : "-Xss2m",
          "-XX:MaxMetaspaceSize=256m",
          "-XX:+UseParallelGC",
          "-XX:ParallelGCThreads=4",
        "-cp", [linkerClasses, ...toolClasspath].join(path.delimiter),
        "StandaloneTeaVMLinker",
        classpathFile,
        target.mainClass,
        outputDirectory,
        target.fileName,
        "FULL",
        String(target.obfuscated),
        ...(fastAnalysis ? ["true"] : []),
      ], { stdio: "inherit" });
    },
  });
  const hash = crypto.createHash("sha256").update(fs.readFileSync(artifact)).digest("hex");
  fs.mkdirSync(installedDirectory, { recursive: true });
  const installedArtifact = path.join(installedDirectory, target.fileName);
  fs.copyFileSync(artifact, installedArtifact);
  fs.writeFileSync(`${installedArtifact}.link-mode`,
      fastAnalysis ? "fast-analysis-effective-simple\n" : "precise\n");
  if (targetName === "client") {
    installClientRuntime(toolClasspath, path.join(installedDirectory, "classes.wasm-runtime.js"));
  }
  console.log(`[standalone-${targetName}-link] artifact=${artifact}`);
  console.log(`[standalone-${targetName}-link] bytes=${fs.statSync(artifact).size} sha256=${hash}`);
  console.log(`[standalone-${targetName}-link] installed=${installedArtifact}`);
});

#!/usr/bin/env node
"use strict";

const childProcess = require("child_process");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { readStandaloneClasspaths } = require("./standalone-classpaths");
const { withVerifiedCache, withOperationLock, toolchainInputs, environmentIdentity, cacheMode } = require("./standalone-link-cache");

const repo = path.resolve(__dirname, "..");
const diagnostic = process.env.EAGLER_SERVER_DIAGNOSTIC === "1";
const SERVER_LINK_HEAP_MIN_MIB = 8192;
const SERVER_LINK_HEAP_MAX_MIB = 12544;
const SERVER_LINK_HEAP_DEFAULT_MIB = 12544;
// Keep this literal as the source-current prepare-client anchor. Isolated
// workspaces rewrite it to their conservative 11264 MiB default; an explicit
// EAGLER_SERVER_LINK_HEAP_MIB override still wins in either checkout.
const SERVER_LINK_HEAP_DEFAULT_ARG = "-Xmx12544m";
const rawServerLinkHeapMiB = process.env.EAGLER_SERVER_LINK_HEAP_MIB
  ?? String(SERVER_LINK_HEAP_DEFAULT_MIB);
if (!/^\d+$/.test(rawServerLinkHeapMiB)) {
  console.error(`EAGLER_SERVER_LINK_HEAP_MIB must be an integer between ${SERVER_LINK_HEAP_MIN_MIB} and ${SERVER_LINK_HEAP_MAX_MIB}`);
  process.exit(2);
}
const serverLinkHeapMiB = Number(rawServerLinkHeapMiB);
if (!Number.isSafeInteger(serverLinkHeapMiB)
    || serverLinkHeapMiB < SERVER_LINK_HEAP_MIN_MIB
    || serverLinkHeapMiB > SERVER_LINK_HEAP_MAX_MIB) {
  console.error(`EAGLER_SERVER_LINK_HEAP_MIB must be an integer between ${SERVER_LINK_HEAP_MIN_MIB} and ${SERVER_LINK_HEAP_MAX_MIB}`);
  process.exit(2);
}
const serverLinkHeapArg = serverLinkHeapMiB === SERVER_LINK_HEAP_DEFAULT_MIB
  ? SERVER_LINK_HEAP_DEFAULT_ARG : `-Xmx${serverLinkHeapMiB}m`;
cacheMode(); // Bypass applies to both slice preparation and the precise link.
const generated = path.join(repo, "wasm-toolchain", "generated");
const linkerClasses = path.join(generated, "standalone-linker");
const classpathFile = path.join(generated, "server-program.classpath");
const analysisClasspathFile = path.join(generated, "server-full-program.classpath");
const outputDirectory = path.join(repo, "target_teavm_wasm_gc_server", "build", "generated",
    "teavm", diagnostic ? "wasm-gc-named-diagnostic" : "wasm-gc-standalone");
const installedDirectory = path.join(repo, "target_teavm_wasm_gc_server", "build", "generated",
    "teavm", "wasm-gc");
const fastutilSlice = path.join(generated, "fastutil-server-slice.jar");
const gameSlice = path.join(generated, "game-server-slice.jar");
const linkLog = path.join(generated, "server-precise-link.log");

function findGradleModuleJar(group, module, version, fileName) {
  const moduleRoot = path.join(os.homedir(), ".gradle", "caches", "modules-2", "files-2.1",
      group, module, version);
  if (!fs.existsSync(moduleRoot)) {
    throw new Error(`Gradle module cache is missing: ${moduleRoot}`);
  }
  for (const hash of fs.readdirSync(moduleRoot).sort()) {
    const candidate = path.join(moduleRoot, hash, fileName);
    if (fs.existsSync(candidate)) return candidate;
  }
  throw new Error(`Could not find ${fileName} below ${moduleRoot}`);
}

function run(command, args, options = {}) {
  console.log(`[standalone-server-link] ${command} ${args.join(" ")}`);
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
  if (result.status !== 0) {
    process.exit(result.status == null ? 1 : result.status);
  }
  return result.stdout || "";
}

function gradlePaths(task, properties = []) {
  const output = run("./gradlew", [task, "--console=plain", "--no-daemon", "--max-workers=1", ...properties]);
  const paths = output.split(/\r?\n/).map((line) => line.trim())
      .filter((line) => line.startsWith("/") && fs.existsSync(line));
  if (paths.length === 0) {
    throw new Error(`No classpath entries were printed by ${task}`);
  }
  return paths;
}

withOperationLock(path.join(generated, "standalone-link-cache"), cacheOwner => {
  fs.mkdirSync(generated, { recursive: true });
  const prepared = readStandaloneClasspaths(run("./gradlew", [
    ":game:jar",
    ":platform:jar",
    ":platform-teavm:jar",
    ":teavm-compat:jar",
    ":target_teavm_wasm_gc_server:jar",
    ":target_teavm_wasm_gc_server:printStandaloneClasspaths",
    "--console=plain", "--no-daemon", "--max-workers=2",
  ]));
  const sliceProperties = [
    `-PeaglerSlimFastutilJar=${fastutilSlice}`,
    `-PeaglerServerGameJar=${gameSlice}`,
  ];
  const toolClasspath = prepared.tool;
  const fullProgramClasspath = prepared.program;
  fs.writeFileSync(analysisClasspathFile, `${fullProgramClasspath.join("\n")}\n`);
  withVerifiedCache({
    owner: cacheOwner,
    manifest: path.join(generated, "standalone-link-cache", "server-slices.json"),
    label: "standalone-server-slices",
    inputs: () => {
      const runtime = toolchainInputs([process.execPath, "jar", "jdeps"]);
      return {
        metadata: { operation: "server-slice-preparation", cwd: repo,
          arguments: ["--classpath-file", analysisClasspathFile],
          toolCommands: runtime.resolvedCommands, environment: environmentIdentity() },
        groups: { fullProgramClasspath, runtime: runtime.files,
          sliceRoots: [path.join(repo, "game/build/libs/game.jar"),
            path.join(repo, "target_teavm_wasm_gc_server/build/libs/target_teavm_wasm_gc_server.jar"),
            path.join(repo, "platform/build/libs/platform.jar"),
            path.join(repo, "platform-teavm/build/libs/platform-teavm.jar"),
            path.join(repo, "teavm-compat/build/libs/teavm-compat.jar"),
            findGradleModuleJar("it.unimi.dsi", "fastutil", "8.5.18", "fastutil-8.5.18.jar")],
          source: [__filename, analysisClasspathFile, path.join(__dirname, "standalone-link-cache.js"),
            path.join(__dirname, "standalone-classpaths.js"),
            path.join(__dirname, "build-server-fastutil-slice.js")] },
      };
    },
    outputs: [fastutilSlice, gameSlice],
    validate: () => {
      for (const file of [fastutilSlice, gameSlice]) {
        if (!fs.existsSync(file) || fs.statSync(file).size === 0) throw new Error(`Invalid server slice: ${file}`);
      }
    },
    run: () => run(process.execPath, [
      path.join(repo, "wasm-toolchain", "build-server-fastutil-slice.js"),
      "--classpath-file", analysisClasspathFile,
    ]),
  });
  const programClasspath = gradlePaths(":target_teavm_wasm_gc_server:printWasmGCClasspath", sliceProperties);
  // These tiny Java facades are excluded from the ordinary web runtime because
  // their native implementations cannot run in a browser. The compatibility
  // transformer replaces their native entry points, however, and strict TeaVM
  // still needs the class shapes referenced by otherwise shared vanilla server
  // code. Including the Java-only jars here preserves the existing web behavior
  // while allowing strict missing-class validation to remain enabled.
  programClasspath.push(
    findGradleModuleJar("com.mojang", "jtracy", "1.0.37", "jtracy-1.0.37.jar"),
    findGradleModuleJar("io.netty", "netty-transport-classes-epoll", "4.2.15.Final",
        "netty-transport-classes-epoll-4.2.15.Final.jar"),
    findGradleModuleJar("io.netty", "netty-transport-classes-kqueue", "4.2.15.Final",
        "netty-transport-classes-kqueue-4.2.15.Final.jar"),
    findGradleModuleJar("io.netty", "netty-transport-native-unix-common", "4.2.15.Final",
        "netty-transport-native-unix-common-4.2.15.Final.jar"),
  );
  fs.writeFileSync(classpathFile, `${programClasspath.join("\n")}\n`);

  const artifact = path.join(outputDirectory, "server-worker.wasm");
  function validateArtifact() {
    if (!fs.existsSync(artifact) || fs.statSync(artifact).size < 1024 * 1024) {
      throw new Error(`Standalone TeaVM did not produce a valid artifact: ${artifact}`);
    }
  }
  withVerifiedCache({
    owner: cacheOwner,
    manifest: path.join(generated, "standalone-link-cache", `server-${diagnostic ? "diagnostic" : "release"}.json`),
    label: "standalone-server-link",
    inputs: () => {
      const runtime = toolchainInputs([process.execPath, "java", "javac"]);
      return {
        metadata: { operation: "precise-link", diagnostic, optimization: "ADVANCED", serverLinkHeapMiB,
          mainClass: "net.lax1dude.eaglercraft.v1_8.internal.wasm_gc_teavm_server.ServerMainClass",
          obfuscated: !diagnostic, outputDirectory, classpathFile, linkerClasses, cwd: repo,
          toolCommands: runtime.resolvedCommands, environment: environmentIdentity() },
        groups: { toolClasspath, programClasspath, runtime: runtime.files,
          source: [__filename, classpathFile, path.join(__dirname, "standalone-link-cache.js"),
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
          // JDK 25 compact headers materially shrink TeaVM's millions of dependency
          // graph objects without changing the graph, optimizer, or emitted Wasm.
          // Keep the total process tree hard-capped at 13 GiB below.
          "-XX:+UnlockExperimentalVMOptions",
          "-XX:+UseCompactObjectHeaders",
          // The adaptive pending-type sets remove the former multi-gigabyte sparse
          // int[] blow-up without allocating a dense bitmap for high type IDs.
          // The default leaves native/GC headroom below the immutable 13,312 MiB
          // process-tree cap; EAGLER_SERVER_LINK_HEAP_MIB is an explicit bounded
          // override for constrained hosts.
          serverLinkHeapArg,
          "-Xss2m",
          "-XX:MaxMetaspaceSize=256m",
          // Dependency analysis is single-threaded, but the first capped run spent
          // roughly half its wall time in Serial full collections. Bound Parallel GC
          // to four helpers: emitted Wasm semantics are unchanged, while collection
          // no longer serializes a 12 GiB exact dependency graph on one core.
          "-XX:+UseParallelGC",
          "-XX:ParallelGCThreads=4",
        "-cp", [linkerClasses, ...toolClasspath].join(path.delimiter),
        "StandaloneTeaVMLinker",
        classpathFile,
        "net.lax1dude.eaglercraft.v1_8.internal.wasm_gc_teavm_server.ServerMainClass",
        outputDirectory,
        "server-worker.wasm",
        "ADVANCED",
        diagnostic ? "false" : "true",
      ], { stdio: "inherit" });
    },
  });
  const hash = crypto.createHash("sha256").update(fs.readFileSync(artifact)).digest("hex");
  console.log(`[standalone-server-link] artifact=${artifact}`);
  console.log(`[standalone-server-link] bytes=${fs.statSync(artifact).size} sha256=${hash}`);
  if (diagnostic) {
    console.log("[standalone-server-link] diagnostic named artifact; release artifact was not replaced");
  } else {
    fs.mkdirSync(installedDirectory, { recursive: true });
    const installedArtifact = path.join(installedDirectory, "server-worker.wasm");
    fs.copyFileSync(artifact, installedArtifact);
    fs.writeFileSync(`${installedArtifact}.link-mode`, "precise\n");
    console.log(`[standalone-server-link] installed=${installedArtifact}`);
  }
});

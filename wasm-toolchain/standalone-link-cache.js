"use strict";

// Content receipts for completed standalone preparation/link operations. No
// timestamp-only fast path: every prospective hit rehashes inputs and outputs.
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const FORMAT = 1;
const ENV_NAMES = ["JAVA_HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ",
  "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS", "_JAVA_OPTIONS", "NODE_OPTIONS",
  "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH"];
const INJECTED_OPTIONS = ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS", "_JAVA_OPTIONS", "NODE_OPTIONS",
  "LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH"];

function digest(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  }
  return value;
}

function encode(value) {
  return JSON.stringify(canonical(value));
}

function hashFile(file) {
  const fd = fs.openSync(file, "r");
  try {
    const before = fs.fstatSync(fd, { bigint: true });
    if (!before.isFile()) throw new Error(`Not a regular file: ${file}`);
    const hash = crypto.createHash("sha256");
    const buffer = Buffer.allocUnsafe(1024 * 1024);
    let count;
    while ((count = fs.readSync(fd, buffer, 0, buffer.length, null)) !== 0) {
      hash.update(buffer.subarray(0, count));
    }
    const after = fs.fstatSync(fd, { bigint: true });
    if (before.size !== after.size || before.mtimeNs !== after.mtimeNs
        || before.ctimeNs !== after.ctimeNs) throw new Error(`File changed while hashing: ${file}`);
    return { bytes: before.size.toString(), sha256: hash.digest("hex") };
  } finally {
    fs.closeSync(fd);
  }
}

function snapshotPaths(entries) {
  // Only reuse duplicate reads within this single snapshot, never across runs.
  const memo = new Map();
  function visit(name, ancestors = new Set(), requiredRoot = false) {
    const absolute = path.resolve(name);
    const stat = fs.lstatSync(absolute);
    let resolved;
    try {
      resolved = fs.realpathSync(absolute);
    } catch (error) {
      // An installed JDK can contain a dangling optional lib/src.zip link.
      // Preserve that exact descendant state; do not turn missing required
      // roots, permission failures or symlink loops into valid cache inputs.
      if (!requiredRoot && stat.isSymbolicLink() && error.code === "ENOENT") {
        return { path: absolute, kind: "symlink", target: fs.readlinkSync(absolute), targetMissing: true };
      }
      throw error;
    }
    if (ancestors.has(resolved)) throw new Error(`Cyclic input directory: ${absolute}`);
    if (stat.isSymbolicLink()) {
      return { path: absolute, kind: "symlink", target: fs.readlinkSync(absolute),
        resolved, content: visit(resolved, ancestors) };
    }
    if (memo.has(absolute)) return memo.get(absolute);
    let result;
    if (stat.isDirectory()) {
      const next = new Set(ancestors).add(resolved);
      const names = fs.readdirSync(absolute).sort();
      result = { path: absolute, resolved, kind: "directory",
        entries: names.map(name => visit(path.join(absolute, name), next)) };
      if (encode(names) !== encode(fs.readdirSync(absolute).sort())) {
        throw new Error(`Directory changed while hashing: ${absolute}`);
      }
    } else if (stat.isFile()) {
      result = { path: absolute, resolved, kind: "file", ...hashFile(absolute) };
    } else {
      throw new Error(`Unsupported input type: ${absolute}`);
    }
    memo.set(absolute, result);
    return result;
  }
  // Array order and repeated classpath entries are semantically significant.
  return entries.map(entry => visit(entry, new Set(), true));
}

function resolveCommand(command) {
  const candidates = command.includes(path.sep) ? [path.resolve(command)]
    : (process.env.PATH || "").split(path.delimiter).map(dir => path.resolve(dir || ".", command));
  for (const candidate of candidates) {
    try {
      fs.accessSync(candidate, fs.constants.X_OK);
      if (fs.statSync(candidate).isFile()) return candidate;
    } catch (_) { /* Continue searching the actual executable PATH. */ }
  }
  throw new Error(`Could not resolve executable: ${command}`);
}

function toolchainInputs(commands) {
  const files = [];
  const resolvedCommands = commands.map(command => {
    const executable = resolveCommand(command);
    files.push(executable);
    const real = fs.realpathSync(executable);
    if (["java", "javac", "jar", "jdeps"].includes(path.basename(real))) {
      const home = path.dirname(path.dirname(real));
      // Modules, native libraries and configuration may alter compiler behavior.
      // Include executable launchers too, including javac's implementation JDK.
      for (const name of ["release", "bin", "lib", "conf"]) {
        const item = path.join(home, name);
        if (!fs.existsSync(item)) throw new Error(`Incomplete JDK runtime: ${item}`);
        files.push(item);
      }
    }
    return { command, executable, resolved: real };
  });
  return { files: [...new Set(files)], resolvedCommands };
}

function environmentIdentity(env = process.env) {
  return Object.fromEntries(ENV_NAMES.map(name => [name,
    Object.hasOwn(env, name) ? { sha256: digest(String(env[name])) } : null]));
}

function cacheMode(env = process.env) {
  const mode = env.EAGLER_STANDALONE_LINK_CACHE ?? "1";
  if (mode !== "0" && mode !== "1") throw new Error("EAGLER_STANDALONE_LINK_CACHE must be 0 or 1");
  return mode === "1";
}

function fingerprint(spec) {
  return { metadata: canonical(spec.metadata),
    groups: Object.fromEntries(Object.entries(spec.groups).map(([name, entries]) =>
      [name, snapshotPaths(entries)])) };
}

function atomicWrite(file, value) {
  const temporary = `${file}.${process.pid}.${crypto.randomBytes(6).toString("hex")}.tmp`;
  try {
    fs.writeFileSync(temporary, JSON.stringify(value, null, 2) + "\n", { flag: "wx" });
    fs.renameSync(temporary, file);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
}

const ownedLocks = new WeakSet();

function acquireOperationLock(directory) {
  fs.mkdirSync(directory, { recursive: true });
  const lock = path.join(path.resolve(directory), "operation.lock");
  let lockFd;
  try {
    lockFd = fs.openSync(lock, "wx");
  } catch (error) {
    if (error.code === "EEXIST") throw new Error(`Standalone cache operation already locked: ${lock}`);
    throw error;
  }
  let startTicks = null;
  if (process.platform === "linux") {
    try {
      const stat = fs.readFileSync(`/proc/${process.pid}/stat`, "utf8");
      startTicks = stat.slice(stat.lastIndexOf(")") + 2).trim().split(/\s+/)[19];
    } catch (_) { /* PID and token still identify this lock on other hosts. */ }
  }
  const owner = Object.freeze({ lock, pid: process.pid, startTicks, token: crypto.randomBytes(16).toString("hex") });
  try {
    fs.writeFileSync(lockFd, JSON.stringify(owner));
  } catch (error) {
    fs.closeSync(lockFd);
    fs.unlinkSync(lock);
    throw error;
  }
  fs.closeSync(lockFd);
  ownedLocks.add(owner);
  function release() {
    try {
      if (fs.readFileSync(lock, "utf8") === JSON.stringify(owner)) fs.unlinkSync(lock);
    } catch (_) { /* Never remove another operation's lock. */ }
    ownedLocks.delete(owner);
    process.removeListener("exit", release);
  }
  process.once("exit", release); // Existing wrappers propagate child errors with process.exit().
  return { owner, release };
}

function assertOwnedLock(owner, directory) {
  if (!ownedLocks.has(owner) || owner.lock !== path.join(path.resolve(directory), "operation.lock")
      || fs.readFileSync(owner.lock, "utf8") !== JSON.stringify(owner)) {
    throw new Error("Standalone cache operation requires its live, verified lock owner");
  }
}

function withOperationLock(directory, run) {
  const lease = acquireOperationLock(directory);
  try {
    return run(lease.owner);
  } finally {
    lease.release();
  }
}

function withVerifiedCache({ manifest, label, inputs, outputs, run, validate = () => {}, env = process.env, owner }) {
  const allowHit = cacheMode(env);
  const directory = path.dirname(manifest);
  let lease;
  if (owner) assertOwnedLock(owner, directory);
  else lease = acquireOperationLock(directory);
  let before;
  let reason = allowHit ? "no verified receipt" : "explicit bypass";
  try {
    // Injected agents/modules can read files outside the declared classpaths.
    // Hash their option values without retaining secrets, but never reuse or
    // certify a cache entry while such untracked runtime options are active.
    const injected = INJECTED_OPTIONS.filter(name => env[name]);
    if (injected.length) {
      reason = `untracked runtime options (${injected.join(", ")})`;
    } else {
      try {
        before = fingerprint(inputs());
      } catch (error) {
        reason = `input unavailable: ${error.message}`;
      }
    }
    const key = before ? digest(encode(before)) : null;
    if (allowHit && before) {
      try {
        const record = JSON.parse(fs.readFileSync(manifest, "utf8"));
        if (record.format === FORMAT && record.key === key
            && encode(record.inputs) === encode(before)
            && encode(record.outputs) === encode(snapshotPaths(outputs))) {
          validate();
          console.log(`[${label}] content cache HIT key=${key}`);
          return { hit: true, key };
        }
        reason = "inputs or complete outputs changed";
      } catch (error) {
        reason = `receipt/output unavailable: ${error.code || error.name}`;
      }
    }
    console.log(`[${label}] content cache MISS (${reason})`);
    // A failed/bypassed operation cannot leave an older success receipt behind.
    fs.rmSync(manifest, { force: true });
    run();
    validate();
    if (before) {
      try {
        const after = fingerprint(inputs());
        if (encode(after) !== encode(before)) throw new Error("inputs changed during operation");
        const completeOutputs = snapshotPaths(outputs);
        if (!completeOutputs.length) throw new Error("no output paths declared");
        atomicWrite(manifest, { format: FORMAT, key, inputs: before, outputs: completeOutputs });
        console.log(`[${label}] verified content receipt written key=${key}`);
        return { hit: false, recorded: true, key, reason };
      } catch (error) {
        console.log(`[${label}] success not cached (${error.message})`);
      }
    }
    return { hit: false, recorded: false, key, reason };
  } finally {
    if (lease) lease.release();
  }
}

module.exports = { withVerifiedCache, withOperationLock, toolchainInputs, environmentIdentity, cacheMode,
  snapshotPaths, fingerprint, hashFile };

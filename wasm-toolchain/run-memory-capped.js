#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const { spawn } = require("node:child_process");

const DEFAULT_LIMIT_MIB = 13824;
const POLL_INTERVAL_MS = 100;
const KILL_GRACE_MS = 2000;

const argv = process.argv.slice(2);
let limitMiB = DEFAULT_LIMIT_MIB;
let logFile = null;
let memoryMetric = "rss";
if (argv[0] === "--limit-mib") {
  limitMiB = Number(argv[1]);
  argv.splice(0, 2);
}
if (argv[0] === "--log-file") {
  logFile = argv[1];
  argv.splice(0, 2);
}
if (argv[0] === "--metric") {
  memoryMetric = argv[1];
  argv.splice(0, 2);
}

if (!Number.isFinite(limitMiB) || limitMiB <= 0 || argv.length === 0 ||
    (memoryMetric !== "rss" && memoryMetric !== "pss")) {
  console.error(
    "usage: run-memory-capped.js [--limit-mib N] [--log-file PATH] " +
    "[--metric rss|pss] <command> [args...]"
  );
  process.exit(2);
}

function readProcess(pid, includePss) {
  try {
    const stat = fs.readFileSync(`/proc/${pid}/stat`, "utf8");
    const closeParen = stat.lastIndexOf(")");
    const fields = stat.slice(closeParen + 2).split(" ");
    const ppid = Number(fields[1]);
    const rssPages = Number(fields[21]);
    let pssBytes = 0;
    if (includePss) {
      try {
        const rollup = fs.readFileSync(`/proc/${pid}/smaps_rollup`, "utf8");
        const match = /^Pss:\s+(\d+)\s+kB$/m.exec(rollup);
        if (match) pssBytes = Number(match[1]) * 1024;
      } catch (_err) {
        // Very short-lived Chromium helpers can disappear between /proc scans.
      }
    }
    return { pid, ppid, rssBytes: rssPages * 4096, pssBytes };
  } catch (_err) {
    return null;
  }
}

function readProcessTable(includePss) {
  if (process.platform === "linux") {
    const processes = [];
    for (const name of fs.readdirSync("/proc")) {
      if (!/^\d+$/.test(name)) continue;
      const process = readProcess(Number(name), includePss);
      if (process) processes.push(process);
    }
    return processes;
  }
  // macOS and other Unix hosts have no /proc; ps reports pid/ppid/rss (KiB).
  // PSS accounting is Linux-only, so the rss metric is used elsewhere.
  const { execFileSync } = require("node:child_process");
  const processes = [];
  let output;
  try {
    output = execFileSync("ps", ["-axo", "pid=,ppid=,rss="], { encoding: "utf8" });
  } catch (_err) {
    return processes;
  }
  for (const line of output.split("\n")) {
    const parts = line.trim().split(/\s+/);
    if (parts.length < 3) continue;
    const pid = Number(parts[0]);
    const ppid = Number(parts[1]);
    const rssKiB = Number(parts[2]);
    if (!Number.isFinite(pid) || !Number.isFinite(ppid) || !Number.isFinite(rssKiB)) continue;
    processes.push({ pid, ppid, rssBytes: rssKiB * 1024, pssBytes: 0 });
  }
  return processes;
}

function processTreeMemory(rootPid, includePss) {
  const processes = readProcessTable(includePss);

  const descendants = new Set([rootPid]);
  let changed;
  do {
    changed = false;
    for (const process of processes) {
      if (!descendants.has(process.pid) && descendants.has(process.ppid)) {
        descendants.add(process.pid);
        changed = true;
      }
    }
  } while (changed);

  let rssBytes = 0;
  let pssBytes = 0;
  for (const process of processes) {
    if (descendants.has(process.pid)) {
      rssBytes += process.rssBytes;
      pssBytes += process.pssBytes;
    }
  }
  return { rssBytes, pssBytes };
}

function killGroup(pid, signal) {
  try {
    process.kill(-pid, signal);
  } catch (err) {
    if (err.code !== "ESRCH") throw err;
  }
}

const command = argv[0];
const commandArgs = argv.slice(1);
const limitBytes = limitMiB * 1024 * 1024;
const startedAt = process.hrtime.bigint();
let peakBytes = 0;
let peakRssBytes = 0;
let peakPssBytes = 0;
let memoryExceeded = false;

console.log(`[memory-cap] limit=${limitMiB} MiB metric=${memoryMetric} command=${command}`);
const child = spawn(command, commandArgs, {
  cwd: process.cwd(),
  detached: true,
  stdio: logFile ? ["inherit", "pipe", "pipe"] : "inherit"
});

let logStream = null;
if (logFile) {
  logStream = fs.createWriteStream(logFile, { flags: "w" });
  child.stdout.on("data", (chunk) => {
    process.stdout.write(chunk);
    logStream.write(chunk);
  });
  child.stderr.on("data", (chunk) => {
    process.stderr.write(chunk);
    logStream.write(chunk);
  });
}

function forwardTermination(signal) {
  killGroup(child.pid, signal);
  setTimeout(() => killGroup(child.pid, "SIGKILL"), KILL_GRACE_MS).unref();
}

process.once("SIGINT", () => forwardTermination("SIGINT"));
process.once("SIGTERM", () => forwardTermination("SIGTERM"));
process.once("SIGHUP", () => forwardTermination("SIGHUP"));

const monitor = setInterval(() => {
  const memory = processTreeMemory(child.pid, memoryMetric === "pss");
  const measuredBytes = memoryMetric === "pss" ? memory.pssBytes : memory.rssBytes;
  peakBytes = Math.max(peakBytes, measuredBytes);
  peakRssBytes = Math.max(peakRssBytes, memory.rssBytes);
  peakPssBytes = Math.max(peakPssBytes, memory.pssBytes);
  if (!memoryExceeded && measuredBytes > limitBytes) {
    memoryExceeded = true;
    console.error(
      `[memory-cap] build tree reached ${(measuredBytes / 1048576).toFixed(0)} MiB ${memoryMetric}; ` +
      `terminating it because it exceeded the configured ${limitMiB} MiB ${memoryMetric} limit`
    );
    killGroup(child.pid, "SIGTERM");
    setTimeout(() => killGroup(child.pid, "SIGKILL"), KILL_GRACE_MS).unref();
  }
}, memoryMetric === "pss" ? 1000 : POLL_INTERVAL_MS);

child.on("error", (err) => {
  clearInterval(monitor);
  console.error(`[memory-cap] failed to start ${command}: ${err.message}`);
  process.exit(1);
});

child.on("exit", (code, signal) => {
  clearInterval(monitor);
  if (logStream) logStream.end();
  const elapsedSeconds = Number(process.hrtime.bigint() - startedAt) / 1e9;
  if (memoryMetric === "pss") {
    console.log(`[memory-cap] peak child-tree PSS=${(peakPssBytes / 1048576).toFixed(0)} MiB`);
    console.log(`[memory-cap] peak child-tree RSS=${(peakRssBytes / 1048576).toFixed(0)} MiB (shared mappings double-counted)`);
  } else {
    console.log(`[memory-cap] peak child-tree RSS=${(peakBytes / 1048576).toFixed(0)} MiB`);
  }
  console.log(`[memory-cap] wall time=${elapsedSeconds.toFixed(1)} s`);
  if (memoryExceeded) process.exit(137);
  if (signal) {
    console.error(`[memory-cap] ${command} exited from signal ${signal}`);
    process.exit(1);
  }
  process.exit(code === null ? 1 : code);
});

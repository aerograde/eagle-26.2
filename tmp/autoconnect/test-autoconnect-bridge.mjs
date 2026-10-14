/*
 * Unit harness for the parts of autoconnect.js that do not need a browser:
 * the module surface, the string bridge the Java side calls, and the code
 * fallback's credential derivation (two independent "peers" loading the module
 * must derive the same ICE credentials from the same 6-character code).
 *
 * It deliberately does not prove anything about WebRTC behaviour - that needs a
 * real browser and is measured in tmp/autoconnect/run-peers.py.  Run with:
 *   node tmp/autoconnect/test-autoconnect-bridge.mjs
 */
import { readFileSync } from "node:fs";
import { webcrypto } from "node:crypto";
import vm from "node:vm";

const source = readFileSync(new URL("../../target_teavm_wasm_gc/src/web/autoconnect.js", import.meta.url), "utf8");

let failures = 0;
// The engine reports its own version; the harness only requires that it installs
// and reports one, not a particular number (it moves with the carrier).
const ENGINE = new URL("../../target_teavm_wasm_gc/src/web/autoconnect.js", import.meta.url);

function check(name, condition, detail) {
  if (condition) {
    console.log("ok   " + name);
  } else {
    failures++;
    console.log("FAIL " + name + (detail === undefined ? "" : " -> " + detail));
  }
}

function loadModule() {
  const window = {
    crypto: webcrypto,
    setTimeout, clearTimeout, setInterval, clearInterval,
    location: { search: "" }
  };
  const sandbox = {
    window, console, setTimeout, clearTimeout, setInterval, clearInterval,
    Promise, Uint8Array, TextEncoder, Math, Date, JSON, Object, String, Number, Array,
    // The module must degrade rather than hang when WebRTC is missing.
    RTCPeerConnection: class {
      constructor() { throw new Error("no WebRTC in this harness"); }
      static generateCertificate() { return Promise.reject(new Error("no WebRTC in this harness")); }
    }
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(source, sandbox, { filename: "autoconnect.js" });
  return window.__eaglerAC;
}

const alice = loadModule();
const bob = loadModule();

check("module installs and reports a version", !!(alice && typeof alice.version === "number" && alice.version >= 4),
  alice && alice.version);
check("bridge entry points exist",
  typeof alice.snapshotJson === "function" && typeof alice.pollEventsJson === "function"
  && typeof alice.sendJson === "function" && typeof alice.startJson === "function"
  && typeof alice.newCode === "function" && typeof alice.enterCode === "function"
  && typeof alice.fallback === "function" && typeof alice.phase === "function");

const snapshot = JSON.parse(alice.snapshotJson());
check("snapshot parses", snapshot && typeof snapshot === "object");
check("snapshot starts idle in phase 0", snapshot.state === "idle" && snapshot.phase === "phase 0 - connection pool",
  snapshot.state + " / " + snapshot.phase);
check("snapshot carries the plan's 20 s fallback budget", snapshot.timeoutMs === 20000, snapshot.timeoutMs);
check("snapshot has a peer array", Array.isArray(snapshot.peers) && snapshot.peers.length === 0);
check("fallback is not raised before start", snapshot.fallback && snapshot.fallback.active === false);

check("sendJson is false without an open channel", alice.sendJson("hello") === false);
check("empty event queue drains to []", alice.pollEventsJson() === "[]");

check("a malformed code is rejected", alice.enterCode("nope") === false && alice.enterCode("ABCDE1") === false);
check("a good code is accepted", alice.enterCode("AC24XY") === true);

const aCreds = await alice.codeCreds("AC24XY");
const bCreds = await bob.codeCreds("ac-24 xy");   // same code, messy typing
const cCreds = await bob.codeCreds("ZZZZZZ");
check("both peers derive the same ufrag", aCreds && bCreds && aCreds.ufrag === bCreds.ufrag,
  aCreds && aCreds.ufrag + " vs " + (bCreds && bCreds.ufrag));
check("both peers derive the same pwd", aCreds && bCreds && aCreds.pwd === bCreds.pwd);
check("another code derives other credentials", aCreds.ufrag !== cCreds.ufrag);
check("ufrag fits the shape Chrome validates", /^[A-Za-z0-9+/]{4,256}$/.test(aCreds.ufrag), aCreds.ufrag);
check("pwd fits the 22-256 characters Chrome requires",
  aCreds.pwd.length >= 22 && aCreds.pwd.length <= 256, aCreds.pwd.length);
check("derived credentials are not the shared constants", aCreds.ufrag !== "AC24aa");

const code = alice.newCode();
check("newCode returns six characters of the code alphabet", /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/.test(code), code);
check("newCode applies the code", alice.code() === code && alice.codeMode() === true);
const started = JSON.parse(alice.snapshotJson());
check("snapshot reports code mode with the code", started.codeMode === true && started.code === code);
check("startJson accepts options", alice.startJson('{"name":"alice"}') === true);

await new Promise((resolve) => setTimeout(resolve, 50));
const events = JSON.parse(alice.pollEventsJson());
check("events drain as a JSON array", Array.isArray(events), JSON.stringify(events));
check("the queue is empty after draining", alice.pollEventsJson() === "[]");
check("a missing WebRTC implementation ends in a reported failure, not a throw",
  ["failed", "stopped", "listening", "scanning"].includes(JSON.parse(alice.snapshotJson()).state));

console.log(failures === 0 ? "\nall checks passed" : "\n" + failures + " check(s) failed");
process.exit(failures === 0 ? 0 : 1);

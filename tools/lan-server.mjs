#!/usr/bin/env node
/*
 * lan-server.mjs - serve an Eaglercraft web build and offer same-network peer
 * discovery on the same origin.
 *
 * Why this exists
 * ---------------
 * The "discover peers on the LAN with no code exchange" idea cannot be built
 * out of WebRTC alone: a page cannot read its own LAN address (host candidates
 * are mDNS-obfuscated), cannot choose or scan ICE ports (Chrome paces checks),
 * and another ICE agent silently drops checks whose credentials it does not
 * know (see tmp/probe/FINDINGS.md).  The one address every peer can already
 * reach before anyone types a code is the page origin, because both players
 * load the client from it.  This server turns that shared origin into a
 * rendezvous: it keeps a presence list per network scope and relays the small
 * WebRTC offer/answer/candidate messages.  The game traffic itself never passes
 * through it - that stays a direct WebRTC data channel between the two peers.
 *
 * Endpoints (all under /eagler-lan/v1/)
 *   GET  health     -> {service, version, room, scope}
 *   POST presence   -> join/refresh; returns the peers on this network
 *   GET  events     -> long-poll: peer list changes + queued signals
 *   POST signal     -> queue an offer/answer/candidate for one peer
 *   POST bye        -> leave immediately
 *
 * Peers are grouped by network scope: for private/link-local IPv4 addresses the
 * /24 is the scope (so 192.168.1.9 and 192.168.1.44 see each other), for other
 * addresses the exact source address is the scope (two players behind the same
 * NAT), and loopback is its own scope.
 *
 * Usage:
 *   node tools/lan-server.mjs --dir dist --port 8090
 *   node tools/lan-server.mjs --help
 *
 * Then both players open the same URL, for example
 *   http://192.168.0.100:8090/direct-connect.html
 */

import http from "node:http";
import os from "node:os";
import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import { randomBytes } from "node:crypto";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(HERE, "..");

const DEFAULTS = {
  dir: fs.existsSync(path.join(PROJECT_ROOT, "target_teavm_wasm_gc", "build", "web"))
    ? path.join(PROJECT_ROOT, "target_teavm_wasm_gc", "build", "web")
    : process.cwd(),
  host: "0.0.0.0",
  port: 8090,
  room: "lan",
  ttlMs: 30000,
  maxPeers: 64,
  maxPayload: 65536,
  maxMailbox: 512,
  eventWaitMs: 20000,
  quiet: false,
};

function usage() {
  console.log(`Eaglercraft LAN discovery server

  node tools/lan-server.mjs [options]

  --dir <path>     directory to serve (default: the wasm-gc web build, else cwd)
  --host <addr>    bind address (default ${DEFAULTS.host})
  --port <n>       port (default ${DEFAULTS.port})
  --room <name>    discovery room; peers only see peers in the same room
                   (default "${DEFAULTS.room}")
  --quiet          only log errors
  --help           this text
`);
}

function parseArgs(argv) {
  const opts = { ...DEFAULTS };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${a} needs a value`);
      return v;
    };
    switch (a) {
      case "--dir": opts.dir = path.resolve(next()); break;
      case "--host": opts.host = next(); break;
      case "--port": opts.port = Number(next()); break;
      case "--room": opts.room = String(next()).slice(0, 32) || DEFAULTS.room; break;
      case "--quiet": opts.quiet = true; break;
      case "--help":
      case "-h": usage(); process.exit(0); break;
      default: throw new Error(`unknown option ${a}`);
    }
  }
  if (!Number.isInteger(opts.port) || opts.port <= 0 || opts.port > 65535) {
    throw new Error(`invalid --port ${opts.port}`);
  }
  return opts;
}

// --------------------------------------------------------------------------
// presence + signaling state
// --------------------------------------------------------------------------

/** peerId -> { id, name, room, scope, addr, seen, mailbox: [], cursor } */
const peers = new Map();
/** scope|room -> Set<peerId> - used for the cheap "did the list change" check */
const groups = new Map();
/** peerId -> Set<res> - long-poll waiters */
const waiters = new Map();

function groupKey(room, scope) {
  return room + "\u0000" + scope;
}

function addToGroup(peer) {
  const key = groupKey(peer.room, peer.scope);
  let set = groups.get(key);
  if (!set) groups.set(key, (set = new Set()));
  set.add(peer.id);
}

function removeFromGroup(peer) {
  const key = groupKey(peer.room, peer.scope);
  const set = groups.get(key);
  if (set) {
    set.delete(peer.id);
    if (set.size === 0) groups.delete(key);
  }
}

function peersFor(peer) {
  const out = [];
  const set = groups.get(groupKey(peer.room, peer.scope));
  if (!set) return out;
  for (const id of set) {
    if (id === peer.id) continue;
    const other = peers.get(id);
    if (!other) continue;
    out.push(publicPeer(other));
  }
  out.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  return out;
}

function publicPeer(peer) {
  return { id: peer.id, name: peer.name, addr: peer.addr, ageMs: Date.now() - peer.seen };
}

function dropPeer(peer, reason) {
  if (!peers.has(peer.id)) return;
  peers.delete(peer.id);
  removeFromGroup(peer);
  wakeWaiters(peer.id, "peer-left");
  notifyGroup(peer.room, peer.scope, peer.id, reason);
}

/** Wake long-pollers so they re-read the peer list. */
function notifyGroup(room, scope, exceptPeerId, reason) {
  const set = groups.get(groupKey(room, scope));
  if (!set) return;
  for (const id of set) {
    if (id === exceptPeerId) continue;
    wakeWaiters(id, reason);
  }
}

function wakeWaiters(peerId, reason) {
  const set = waiters.get(peerId);
  if (!set) return;
  for (const res of [...set]) {
    try { res.end(JSON.stringify({ cursor: peers.get(peerId)?.cursor ?? 0, wake: reason, events: drainEvents(peerId) })); } catch { /* client gone */ }
  }
  waiters.delete(peerId);
}

/** Signals queued for this peer since its cursor, plus a peer-list snapshot. */
function drainEvents(peerId) {
  const peer = peers.get(peerId);
  if (!peer) return [];
  const events = [];
  events.push({ type: "peers", peers: peersFor(peer) });
  for (const item of peer.mailbox) {
    events.push({ type: "signal", from: item.from, kind: item.kind, payload: item.payload });
  }
  peer.mailbox.length = 0;
  return events;
}

function sweepExpired(now) {
  for (const peer of [...peers.values()]) {
    if (now - peer.seen > peer.ttlMs) {
      log(`peer ${peer.id} (${peer.name}) expired`);
      dropPeer(peer, "expired");
    }
  }
}

// --------------------------------------------------------------------------
// static file serving
// --------------------------------------------------------------------------

const CONTENT_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".htm": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".wasm": "application/wasm",
  ".epk": "application/octet-stream",
  ".bin": "application/octet-stream",
  ".zip": "application/zip",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".ico": "image/x-icon",
  ".css": "text/css; charset=utf-8",
  ".txt": "text/plain; charset=utf-8",
  ".map": "application/json; charset=utf-8",
  ".ogg": "audio/ogg",
  ".mp3": "audio/mpeg",
  ".webmanifest": "application/manifest+json",
};

function contentTypeFor(file) {
  const ext = path.extname(file).toLowerCase();
  if (ext === ".br" || ext === ".gz") {
    const inner = path.extname(file.slice(0, -ext.length)).toLowerCase();
    return CONTENT_TYPES[inner] || "application/octet-stream";
  }
  return CONTENT_TYPES[ext] || "application/octet-stream";
}

function safeJoin(root, urlPath) {
  const decoded = decodeURIComponent(urlPath);
  const clean = path.posix.normalize(decoded).replace(/^(\.\.[/\\])+/, "");
  const full = path.join(root, clean);
  const rel = path.relative(root, full);
  if (rel.startsWith("..") || path.isAbsolute(rel)) return null;
  return full;
}

async function statFile(file) {
  try {
    const st = await fsp.stat(file);
    return st.isDirectory() ? null : st;
  } catch {
    return null;
  }
}

// A precompressed sidecar is only usable while it still describes the payload
// sitting next to it. deploy_wasm_web.sh rewrites classes.wasm (and the shell)
// in place but leaves the previous build's .br beside it, so serving a sidecar
// without this check hands the browser the previous build: the page loads, the
// UI looks right, and the client silently runs stale code. Compare against the
// payload's own timestamp instead of hashing it - the sidecar is written after
// the payload it compresses, and the payload is what a deploy rewrites.
async function pickEncodedVariant(file, acceptEncoding, sourceStat) {
  if (!acceptEncoding) return null;
  const wants = String(acceptEncoding).toLowerCase();
  const describes = (variant) => variant.mtimeMs >= sourceStat.mtimeMs;
  if (wants.includes("br")) {
    const br = await statFile(file + ".br");
    if (br && describes(br)) return { file: file + ".br", encoding: "br", size: br.size };
  }
  if (wants.includes("gzip")) {
    const gz = await statFile(file + ".gz");
    if (gz && describes(gz)) return { file: file + ".gz", encoding: "gzip", size: gz.size };
  }
  return null;
}

async function serveStatic(req, res, opts, url) {
  let file = safeJoin(opts.dir, url.pathname);
  if (!file) {
    res.writeHead(403).end("Forbidden");
    return true;
  }
  let st = await statFile(file);
  if (!st && !path.extname(file)) {
    file = safeJoin(opts.dir, path.posix.join(url.pathname, "index.html"));
    st = file ? await statFile(file) : null;
  }
  if (!st) return false;

  const headers = {
    "Content-Type": contentTypeFor(file),
    "Cache-Control": "no-cache",
    "X-Content-Type-Options": "nosniff",
  };
  const variant = await pickEncodedVariant(file, req.headers["accept-encoding"], st);
  const target = variant ? variant.file : file;
  const size = variant ? variant.size : st.size;
  if (variant) {
    headers["Content-Encoding"] = variant.encoding;
    headers["Vary"] = "Accept-Encoding";
  }
  if (req.method === "HEAD") {
    headers["Content-Length"] = String(size);
    res.writeHead(200, headers).end();
    return true;
  }
  headers["Content-Length"] = String(size);
  res.writeHead(200, headers);
  await pipeFile(target, res);
  return true;
}

function pipeFile(file, res) {
  return new Promise((resolve) => {
    const stream = fs.createReadStream(file);
    stream.on("error", () => { try { res.end(); } catch { /* ignore */ } resolve(); });
    stream.on("end", resolve);
    stream.pipe(res);
  });
}

// --------------------------------------------------------------------------
// json helpers
// --------------------------------------------------------------------------

function sendJson(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "Content-Length": Buffer.byteLength(text),
  });
  res.end(text);
}

function readJsonBody(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error("payload too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      if (chunks.length === 0) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch (e) {
        reject(new Error("invalid json"));
      }
    });
    req.on("error", reject);
  });
}

function clientAddress(req) {
  let addr = req.socket.remoteAddress || "";
  if (addr.startsWith("::ffff:")) addr = addr.slice(7);
  if (addr === "::1") addr = "127.0.0.1";
  return addr;
}

/** Same-network grouping: /24 for private IPv4, exact address otherwise. */
function networkScope(addr) {
  if (addr.startsWith("127.") || addr === "localhost") return "loopback";
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(addr);
  if (m) {
    const a = Number(m[1]);
    const b = Number(m[2]);
    const isPrivate = a === 10
      || (a === 192 && b === 168)
      || (a === 172 && b >= 16 && b <= 31)
      || (a === 169 && b === 254);
    if (isPrivate) return `${a}.${b}.${m[3]}.0/24`;
    return addr;
  }
  if (addr.includes(":")) {
    // IPv6: everything the site can see on one link; /64 is the link prefix.
    return "ipv6:" + addr.split(":").slice(0, 4).join(":");
  }
  return addr || "unknown";
}

const PEER_ID_RE = /^[0-9a-f]{16,32}$/;
const ROOM_RE = /^[a-z0-9_-]{1,32}$/;

function validId(value) {
  return typeof value === "string" && PEER_ID_RE.test(value);
}

function validRoom(value) {
  return typeof value === "string" && ROOM_RE.test(value);
}

function sanitizeName(value) {
  if (typeof value !== "string") return "";
  const trimmed = value.replace(/[\u0000-\u001f\u007f]/g, "").trim();
  return trimmed.slice(0, 24);
}

// --------------------------------------------------------------------------
// api
// --------------------------------------------------------------------------

async function handleApi(req, res, opts, url) {
  const route = url.pathname.slice("/eagler-lan/v1/".length);
  const addr = clientAddress(req);
  const scope = networkScope(addr);
  sweepExpired(Date.now());

  if (route === "health" && req.method === "GET") {
    sendJson(res, 200, {
      service: "eagler-lan",
      version: 1,
      room: opts.room,
      scope,
      peers: peers.size,
      ttlMs: opts.ttlMs,
      hint: "POST /eagler-lan/v1/presence to join",
    });
    return true;
  }

  if (route === "presence" && req.method === "POST") {
    let body;
    try {
      body = await readJsonBody(req, 8192);
    } catch (e) {
      sendJson(res, 400, { error: e.message });
      return true;
    }
    const room = validRoom(body.room) ? body.room : opts.room;
    const name = sanitizeName(body.name);
    let peer = validId(body.peerId) ? peers.get(body.peerId) : null;
    if (peer && (peer.room !== room || peer.scope !== scope || peer.addr !== addr)) {
      // Same id arriving from a different network (or a reused id): drop the old entry.
      dropPeer(peer, "moved");
      peer = null;
    }
    if (!peer) {
      const groupSet = groups.get(groupKey(room, scope));
      if (groupSet && groupSet.size >= opts.maxPeers) {
        sendJson(res, 503, { error: "this network already has the maximum number of peers" });
        return true;
      }
      const id = randomBytes(8).toString("hex");
      // ttlMs belongs on the peer itself, not only in DEFAULTS: sweepExpired()
      // reads peer.ttlMs, and a peer created without it never compares as
      // expired (`now - seen > undefined` is false), so the sweeper was a no-op
      // and every player who ever loaded the page stayed on the roster.  Live
      // symptom: /health reported an 11-hour-old peer, and the client kept
      // "rebuilding the link" to ghosts that had closed hours earlier.
      peer = { id, name: name || `player-${id.slice(0, 4)}`, room, scope, addr,
        seen: 0, ttlMs: Math.max(opts.ttlMs, 10000), mailbox: [], cursor: 0 };
      peers.set(id, peer);
      addToGroup(peer);
      log(`peer ${peer.id} (${peer.name}) joined from ${addr} [${scope}]`);
      notifyGroup(room, scope, peer.id, "peer-joined");
    } else {
      if (name) peer.name = name;
      wakeWaiters(peer.id, "presence");
    }
    peer.seen = Date.now();
    if (!validRoom(body.room) && validRoom(opts.room)) peer.room = opts.room;
    sendJson(res, 200, {
      peerId: peer.id,
      room: peer.room,
      scope,
      ttlMs: Math.max(opts.ttlMs, 10000),
      peers: peersFor(peer),
    });
    return true;
  }

  if (route === "events" && req.method === "GET") {
    const peerId = url.searchParams.get("peerId");
    const peer = validId(peerId) ? peers.get(peerId) : null;
    if (!peer) {
      sendJson(res, 410, { error: "unknown peer; re-register with /presence" });
      return true;
    }
    peer.seen = Date.now();
    const waitMs = Math.min(Math.max(Number(url.searchParams.get("waitMs")) || opts.eventWaitMs, 0), opts.eventWaitMs);
    peer.cursor = (peer.cursor || 0) + 1;
    const immediate = drainEvents(peerId);
    const hasSignal = immediate.some((e) => e.type === "signal");
    if (hasSignal || waitMs <= 0) {
      sendJson(res, 200, { cursor: peer.cursor, events: immediate });
      return true;
    }
    let set = waiters.get(peerId);
    if (!set) waiters.set(peerId, (set = new Set()));
    const timer = setTimeout(() => {
      set.delete(res);
      if (set.size === 0) waiters.delete(peerId);
      if (!res.writableEnded) sendJson(res, 200, { cursor: peer.cursor, events: [] });
    }, waitMs);
    set.add(res);
    res.on("close", () => {
      clearTimeout(timer);
      set.delete(res);
      if (set.size === 0) waiters.delete(peerId);
    });
    return true;
  }

  if (route === "signal" && req.method === "POST") {
    let body;
    try {
      body = await readJsonBody(req, opts.maxPayload * 2);
    } catch (e) {
      sendJson(res, 400, { error: e.message });
      return true;
    }
    const from = validId(body.from) ? peers.get(body.from) : null;
    if (!from) {
      sendJson(res, 410, { error: "unknown sender; re-register with /presence" });
      return true;
    }
    from.seen = Date.now();
    const target = validId(body.to) ? peers.get(body.to) : null;
    if (!target) {
      // The peer we are trying to reach is gone: tell the sender to drop it.
      sendJson(res, 404, { error: "peer is not present" });
      return true;
    }
    if (target.room !== from.room || target.scope !== from.scope) {
      sendJson(res, 403, { error: "peer is not on this network" });
      return true;
    }
    const kind = String(body.kind || "");
    if (!["offer", "answer", "candidate", "text", "bye"].includes(kind)) {
      sendJson(res, 400, { error: "unknown signal kind" });
      return true;
    }
    const payload = body.payload === undefined ? null : body.payload;
    const encoded = JSON.stringify(payload);
    if (encoded.length > opts.maxPayload) {
      sendJson(res, 413, { error: "signal payload too large" });
      return true;
    }
    if (target.mailbox.length >= opts.maxMailbox) {
      // Keep the newest; a stale backlog only delays the next handshake.
      target.mailbox.shift();
    }
    target.mailbox.push({ from: from.id, kind, payload });
    wakeWaiters(target.id, "signal");
    sendJson(res, 202, { ok: true, queued: target.mailbox.length });
    return true;
  }

  if (route === "bye" && req.method === "POST") {
    let body;
    try {
      body = await readJsonBody(req, 4096);
    } catch (e) {
      sendJson(res, 400, { error: e.message });
      return true;
    }
    const peer = validId(body.peerId) ? peers.get(body.peerId) : null;
    if (peer) {
      log(`peer ${peer.id} (${peer.name}) left`);
      dropPeer(peer, "bye");
    }
    sendJson(res, 200, { ok: true });
    return true;
  }

  sendJson(res, 404, { error: "unknown discovery route", route });
  return true;
}

// --------------------------------------------------------------------------
// main
// --------------------------------------------------------------------------

/**
 * Origin URLs a player on this network can reach this server at. An explicit
 * --host is respected as-is; with the default wildcard bind we list every
 * non-internal IPv4 interface plus loopback.
 */
function localUrls(opts) {
  const urls = [];
  const explicit = opts.host !== "0.0.0.0" && opts.host !== "::" && opts.host !== "";
  if (explicit) {
    urls.push(`http://${opts.host}:${opts.port}`);
    return urls;
  }
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family !== "IPv4" || entry.internal) continue;
      urls.push(`http://${entry.address}:${opts.port}`);
    }
  }
  urls.push(`http://127.0.0.1:${opts.port}`);
  return urls;
}

function log(message) {
  if (!LOG_OPTS.quiet) {
    const stamp = new Date().toISOString().slice(11, 19);
    console.log(`[${stamp}] ${message}`);
  }
}

let LOG_OPTS = { quiet: false };

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  LOG_OPTS = opts;
  if (!fs.existsSync(opts.dir)) {
    console.error(`[lan-server] --dir does not exist: ${opts.dir}`);
    process.exit(1);
  }

  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
      if (url.pathname.startsWith("/eagler-lan/v1/")) {
        const handled = await handleApi(req, res, opts, url);
        if (!handled && !res.writableEnded) sendJson(res, 404, { error: "not found" });
        return;
      }
      if (req.method !== "GET" && req.method !== "HEAD") {
        res.writeHead(405).end("Method not allowed");
        return;
      }
      const served = await serveStatic(req, res, opts, url);
      if (!served) {
        res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" }).end("Not found");
      }
    } catch (e) {
      if (!res.writableEnded) {
        try { sendJson(res, 500, { error: "internal error" }); } catch { /* ignore */ }
      }
      log(`request failed: ${e && e.message}`);
    }
  });

  server.on("error", (e) => {
    console.error(`[lan-server] ${e.code === "EADDRINUSE" ? `port ${opts.port} is already in use` : e.message}`);
    process.exit(1);
  });

  const sweeper = setInterval(() => sweepExpired(Date.now()), 5000);
  sweeper.unref?.();

  server.listen(opts.port, opts.host, () => {
    // Print the addresses a player can actually type. "this machine's LAN
    // address" is not one of them, and picking the wrong one (a VPN, a docker
    // bridge) is the usual reason two players never see each other.
    const page = fs.existsSync(path.join(opts.dir, "direct-connect.html")) ? "/direct-connect.html" : "/";
    console.log(`[lan-server] serving ${opts.dir}  (room "${opts.room}")`);
    for (const url of localUrls(opts)) {
      console.log(`[lan-server] open on BOTH players: ${url}${page}`);
    }
    console.log("[lan-server] automatic discovery only works when every player opens the client from one of those addresses");
  });

  const shutdown = () => {
    console.log("[lan-server] shutting down");
    clearInterval(sweeper);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 1000).unref?.();
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

// Only start the server when executed directly; importing it (tests) must not bind.
const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));
if (invokedDirectly) {
  main().catch((e) => {
    console.error(`[lan-server] ${e && e.stack || e}`);
    process.exit(1);
  });
}

export { networkScope, sanitizeName, validId, validRoom, parseArgs };

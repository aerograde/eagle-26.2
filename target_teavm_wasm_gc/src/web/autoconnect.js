/*
 * autoconnect.js - serverless automatic LAN peer discovery and connection.
 *
 * No server, no codes, no external service.  Two players who open the same page
 * on one network find each other, exchange the DTLS fingerprints the browser
 * will not let them share in advance, and open a data channel - with no
 * signalling path of any kind.  Everything here rides on browser facts measured
 * directly in tmp/autoconnect/ (spike runs 1-10 and the FINDINGS log):
 *
 *  1. Two ICE agents with equal ice-ufrag/ice-pwd validate each other's
 *     connectivity checks, so a check sent to a live peer address:port succeeds
 *     with no signalling.  Both players rewrite their local description's
 *     credentials to the same constants and apply a forged remote answer (same
 *     credentials, placeholder fingerprint, no candidates) because
 *     addIceCandidate requires a remote description.
 *  2. The OS picks the ICE port, so a page owns a socket inside a given window
 *     only by retrying.  A connection owns two UDP sockets and exactly one of
 *     them serves every target on the peer's address; which one is readable from
 *     the connection's own candidate-pair.localCandidateId after one check
 *     (spike10: one socket served every target, 40/40, 40/40, 40/40).  That is
 *     the census this engine runs over its pulse sources, and it is why a
 *     pulse's symbol is exact instead of the coin flip that sank the previous
 *     build (half its pulses carried an unchosen class).
 *  3. Ports are exact in both directions: the receiver reads the sender's socket
 *     as the remote candidate's port (94/94 and 174/174 arrivals matched the
 *     sender's real socket), and the socket a pulse lands on is the socket it
 *     was aimed at - a page can only be aimed at on a port it owns, which is why
 *     every non-reader connection here is forced out of the discovery window.
 *     A pulse is therefore an exactly readable edge between one of the sender's
 *     sockets and one of the receiver's.
 *  4. Chrome caches getStats() for ~50-55 ms, so an arrival is only datable to
 *     about half a slot; that is why slot position alone can never carry a
 *     payload at 100 ms resolution (15 runs died there).  What a burst can carry
 *     is identity: 32 sources fired in one tick are reported whole in one poll
 *     (22-53 ms), and 200 ms grids read 4/4 clean.  Payload position is
 *     therefore carried by rounds hundreds of ms apart, never by a fine clock.
 *  5. A repeat check from the same (source, target) pair creates no new
 *     candidate at all, and requestsReceived does not count payload pulses, so
 *     every cell needs a fresh pair.  A source with queued checks fires late (up
 *     to 1540 ms behind 24 of them), so payload sources are never the
 *     connections the sweep uses, and no connection fires twice in one round.
 *  6. A DTLS fingerprint mismatch ends the transport without exposing the peer's
 *     certificate, so the 256-bit fingerprint has to travel as data.  Two
 *     carriers were measured and dropped: a check's PRIORITY attribute (Chrome
 *     recomputes the prflx priority - spike-prio shows 0x6E001EFF, type
 *     preference 110) and slot-timed pulses alone (the stat cache).
 *
 * The carrier: a cell is (class of the sender's sending socket, one of the
 * receiver's sockets, a round).  The sender's class is exact because the census
 * says which socket each source uses; the receiver's socket is exact because it
 * is where the pulse landed, and the sender aims at it.  One payload is sent in
 * three copies; each copy is a list round (three pulses per target socket, which
 * is how the receiver learns exactly which sockets this copy will use) followed
 * by rounds of cells 400 ms apart.  Both peers transmit and read at the same
 * time: outbound pulses never come back to the sender's own readers.
 *
 * Phases (the plan's phases, each mechanism replaced only where measured):
 *   phase 0  connection pool   - certificate, readers (port lottery), sources,
 *                                scanners; every non-reader socket forced out of
 *                                the discovery window so a probe can only ever
 *                                answer with a reader
 *   phase 1  discovery         - subnet sweep of the window, first peer found
 *   phase 1.5 ports            - the peer's sockets from the probe readback
 *                                (sockets that answered *now*, so live by
 *                                construction - the previous build's dead port
 *                                came from aiming at a cached list), the peer's
 *                                address from the source port of its probe
 *   phase 2  fingerprint       - the cell grid, three copies, both directions
 *   phase 3  final connection  - real fingerprint, shared credentials, DTLS, the
 *                                data channel
 *   code fallback              - 6-character code, credentials derived from it
 *
 * API for the game layer (window.__eaglerAC)
 *   start({ name, stun })   begin the phases; idempotent while already running
 *   stop()                  close every connection this module owns
 *   state()    idle|listening|scanning|linking|fallback|connected|failed|stopped
 *   phase()    the plan phase the engine is in, for the status line
 *   status()   human readable progress
 *   peers()    [{ id, address, port, state }]
 *   self()     { id, name, fingerprint, finalPort, ports }
 *   onEvent(cb){ type, ... }
 *   send(text) send a string over the peer data channel (false when not open)
 *   logs()     the engine's own log lines
 *   newCode()  generate a 6-char code, apply it and return it (show this one)
 *   enterCode(code)  apply a code the other player showed (true when accepted)
 *   codeCreds(code)  { ufrag, pwd } the code derives - diagnostics only
 *
 * String bridge used by the Java side
 *   (net.lax1dude.eaglercraft.v1_8.lan.AutoConnect, same shape as the
 *   lan-discovery bridge):
 *   snapshotJson()      -> JSON state snapshot
 *   pollEventsJson()    -> JSON array, drains the event queue
 *   sendJson(text)      -> boolean
 *   startJson(optsJson) -> boolean
 */
(function () {
  "use strict";

  // The revision the game-side bridge checks before it will speak to this
  // module: platform-teavm's AutoConnect.moduleInstalled() compares
  // window.__eaglerAC.version against its own VERSION and gates every other
  // call on the result.  The two drifted - this module said 8, the bridge still
  // said 4 - and because that one check gates everything, auto-connect went
  // silently dead: the module loaded, the LAN screen offered the entry, and
  // nothing was ever started.  Both halves now say 4, and the bridge builds its
  // comparison from its own constant so a future bump cannot leave a literal
  // behind again.  Keep the numbers equal when either side changes.
  var VERSION = 4;
  if (window.__eaglerAC && window.__eaglerAC.version === VERSION) {
    return; // already installed
  }

  // Shared ICE credentials.  4-256 ufrag chars ([A-Za-z0-9+/]) and 22-256 pwd
  // chars, which is what Chrome's SDP validation enforces.  The plan's
  // "AC24shared7x" is 12 characters and Chrome rejects it with "ICE pwd must be
  // between 22 and 256 characters long" (measured), so the constant keeps its
  // prefix and is padded to the minimum length.
  var UFRAG = "AC24aa";
  var PWD = "AC24shared7xpadded000000";

  // Code fallback.  Six characters from the same alphabet the manual join codes
  // use, so there are no look-alike pairs to misread.
  var CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  var CODE_LEN = 6;
  var FALLBACK_AFTER_MS = 20000;
  var PHASE_NAMES = {
    boot: "phase 0 - connection pool",
    readers: "phase 0 - connection pool",
    sources: "phase 0 - connection pool",
    scan: "phase 1 - discovery",
    ports: "phase 1.5 - sockets",
    locate: "phase 1.5 - sockets",
    census: "phase 1.5 - sockets",
    grid: "phase 2 - fingerprint exchange",
    final: "phase 3 - final connection",
    connect: "phase 3 - final connection",
    code: "code fallback",
    error: "error"
  };

  // Discovery window.  A reader a peer cannot find is useless, so every reader
  // socket lives inside it; then every *other* connection is forced out of it,
  // so a peer's probe can only ever answer with a reader socket.  That is what
  // makes the sender's target list and the receiver's socket list the same set.
  var W_START = 50000;
  var W_COUNT = 512;
  var W_END = W_START + W_COUNT - 1;
  var SUBNETS = ["192.168.0.", "192.168.1."];
  // A port nothing binds: one check there labels a connection without creating
  // an answer or an arrival.
  var CENSUS_PORT = W_START - 7;

  // --------------------------------------------------------------- carrier
  // Four bits, sixteen classes.  Thirty-two classes halved the rounds but left
  // classes with no source at all (~13% empty with 48 sources), and an empty
  // class is a cell that can never be lit in any copy - a systematic failure no
  // copy count votes away.  Sixteen classes with 96 sources holds ~6 per class.
  var SYMBOL_BITS = 4;
  var SYMBOL_CLASSES = 1 << SYMBOL_BITS;   // 16 classes, srcPort % 16
  // One source can light several cells in a round: its checks are paced ~16/s,
  // and a round is 8 s, so four of them land in the same burst.  That is what
  // lets 96 sources cover 320 bits without a per-source-per-round limit.
  var CELLS_PER_SOURCE_PER_ROUND = 4;
  var PULSE_SPACING_MS = 45;
  var LIST_SHOTS = 3;                      // pulses per target in a list round
  // Rounds are two seconds apart.  The nominal stat cache is ~53 ms, but on a
  // loaded machine a poll pass here has been measured at 250-1000 ms (the prior
  // build's 1-9 ms passes were measured on a quiet one), and a burst must not be
  // attributed to the wrong round when that happens.  Two seconds is twenty
  // times the worst pass seen, and a copy only needs three or four rounds.
  var LIST_GAP_MS = 6000;                  // list round -> first payload round
  var ROUND_STEP_MS = 6000;                // burst -> next burst
  var COPY_GAP_MS = 5000;                  // last round -> next copy's list round
                                           // (must exceed CLUSTER_GAP or the two merge)
  var CLUSTER_GAP_MS = 5000;               // arrivals closer than this share a burst
  var LIST_MIN_SHOTS = 2;                  // a listed socket must have heard this many
  var COPY_COUNT = 3;
  var QUIET_MS = 900;                      // no arrivals this long: the window is ours
  // payload: magic(2) id(2) fingerprint(32) final port(2) crc16(2) = 40 bytes
  var PAYLOAD_MAGIC = 0xA53C;
  var PAYLOAD_BYTES = 40;
  var PAYLOAD_BITS = PAYLOAD_BYTES * 8;

  // ------------------------------------------------------------- pool sizes
  // Every connection counts against Chrome's 500-connection cap with no refund
  // on close, so each pool is sized inside a ~430 budget.
  // Readers are the grid's columns, so their count is the width of the carrier.
  // Ten of them (was six): the payload is 320 bits over 16 classes, and the
  // number of cells that share one (class, column) pair sets how many fresh
  // (source, target) pairs a column needs across three copies.  At K = 10 the
  // grid is 16 x 10 x 2 rounds = 320 cells exactly, so a cell's group holds two
  // entries and three copies need six of the eight pairs a class owns - the
  // arithmetic the old K = 6 could not satisfy (four rounds, groups of up to
  // four cells, nine firings against six pairs: that is where copy 3 lost its
  // pulses in grid-run12.log).
  // Reader count is the grid's width: the sender's K columns are exactly the
  // peer's reader sockets, so rounds = ceil(20 / K) and the pool a full three
  // copies needs is 3 x rounds sources per class (see trimPool).  At K = 8
  // (ten readers, run 16) that is nine per class - the lottery never delivers
  // nine, and the copies came out 160/85/39.  Twenty readers put ~20 sockets in
  // the window, rounds collapses to 1, and the delivered quota of three carries
  // all three copies.
  var READER_TARGET = 20;       // reader connections = grid sockets
  // The lottery keeps ~7% of its attempts (run 17: 17 sockets from 8 x 32
  // created; run 16: 10 from 6 x 24), so one socket costs ~14 connections and
  // Chrome's 500-connection lifetime cap - with no refund on close - is what
  // actually sizes the carrier.  Twelve batches of 24 is ~288 worst case for
  // the readers, which is what leaves room for the source pool below.
  var LOTTERY_BATCH = 24;
  var LOTTERY_MAX_BATCHES = 12;
  var PORTS_WAIT_MS = 2500;     // a fresh connection needs this long to report ports
  var SCAN_CONNECTIONS = 24;
  // 150 candidates, trimmed to the 128 the payload needs, which the round-robin
  // trim makes 8 per class.  The earlier 110-candidate pool came out 4/6/4/8/8/5/
  // 7/4/6/7/8/6/7/7/2/7 - classes with two and four sources cannot carry three
  // copies of their own cells, which is exactly where grid-run12.log lost copy 3
  // (85 pulses against 162 in copy 1).
  var SOURCE_TARGET = 150;
  // What the payload actually needs once the probe is done: 128 sources, ~8 per
  // class.  Everything else - all the scanners and the surplus sources - is
  // closed before the exchange, and every readback is issued through a small
  // concurrency window, because run 9 showed the stalls line up with this
  // engine's own wide stats batches: a census over 175 connections starved a
  // reader's report for 29 s while the reader had five remote candidates of its
  // own.
  var PAYLOAD_SOURCES = 128;
  // What one class can carry, and the quota the trim actually holds to: the
  // weakest class's own count, capped at this.  Run 15's second transmitter had
  // classes of 4/5/5 against the first's 8 and starved (132/86/49 pulses against
  // 158/158/151), so a pool is only as good as its thinnest class - keep that
  // many from every class and both peers can carry the same number of copies.
  var PAYLOAD_SOURCES_PER_CLASS = 8;
  // Computed in trimPool from the sockets the peer actually offers: the number
  // of rounds the payload needs, and how many full copies the trimmed pool can
  // carry.  Defined here so transmit() reads the exchange, not a constant.
  var exchangeRounds = 0;
  var exchangeCopies = COPY_COUNT;
  var STATS_BATCH = 8;
  var LOCATE_CONNECTIONS = 4;
  var OUT_OF_WINDOW_TRIES = 4;

  // Timing budgets.
  var SCAN_ROUND_HOSTS = 2;
  var SCAN_ROUND_MS = 600;
  var SCAN_BURST = 64;
  var SCAN_BURST_GAP_MS = 40;
  var SCAN_GRACE_MS = 12000;
  var SCAN_BUDGET_MS = 60000;
  var PROBE_WAIT_MS = 15000;     // the readback keeps polling until this deadline
  var BURST_FIRES = 40;          // fires per tick, so the stats thread keeps up
  var BURST_GAP_MS = 12;
  var LOCATE_WAIT_MS = 15000;
  var CENSUS_WAIT_MS = 2000;
  // Three copies must all land before the union can be complete: a copy is
  // list(8 s) + 2 rounds(8 s each) + gap(6 s) = 30 s at K >= 10, so three copies
  // need ~90 s.  The wait after transmission only has to catch evidence that
  // arrived late, because tryDecode already runs throughout the transmission.
  var EXCHANGE_BUDGET_MS = 120000;
  // One transmitter at a time.  Run 13 measured why: with both peers sending
  // three copies at once, every reader on both pages reported its stats pass
  // taking ~3 s (ten readers, "answered again after 6 timeout(s), 13-36 remote
  // candidates each"), and an arrival that is read late is an arrival lost.  The
  // two sides elect a first transmitter by comparing the lowest socket each owns
  // with the lowest socket it probed on the peer - complementary answers, no
  // message needed - and the other side holds off long enough for the first
  // transmission to finish.  The quiet page is then read at ~0.5 s per pass, the
  // latency the carrier was sized against.
  var STAGGER_MS = 45000;
  var MAX_ATTEMPTS = 1;
  // A stats pass is the expensive thing this file does: with ~100 live
  // connections it has been measured at 0.25-3 s.  At 20 ms per cycle the six
  // readers were issuing ~300 passes a second, which is what starved the very
  // call the read depends on (grid-run10.log: a reader poll returned after
  // 43817 ms).  One cycle per quarter second is ~24 passes a second and still
  // dates an arrival 25x finer than CLUSTER_GAP_MS.
  var POLL_CYCLE_MS = 250;
  // A report is waited for, not dropped (a dropped report loses its arrivals),
  // but it must still land inside the round that carries it: 8 s rounds, with
  // clusters split at 5 s.  The timeout is per reader, so one slow connection
  // stretches only its own interval.
  var POLL_TIMEOUT_MS = 2000;
  var POLL_TIMEOUT_MS_MAX = 4000;
  var STUCK_BACKOFF_MS = 1200;
  var STUCK_BACKOFF_MS_MAX = 4000;
  var WATCH_TICK_MS = 150;      // decode/exchange decisions, no stats involved
  var CONNECT_TIMEOUT_MS = 25000;
  var STUN_DEFAULT = [{ urls: "stun:stun.l.google.com:19302" }];

  // ------------------------------------------------------------------ state
  var state = "idle";
  var statusLine = "idle";
  var logs = [];
  var subs = [];
  var eventQueue = [];
  var EVENT_QUEUE_MAX = 64;
  var currentPhase = PHASE_NAMES.boot;
  var lastError = null;
  var running = false;
  var startedAt = 0;
  var attempt = 0;

  var selfId = randomHex(2);
  var selfName = "player-" + selfId;
  var certificate = null;
  var fingerprint = "";
  var stunServers = STUN_DEFAULT;
  var role = "unknown";                 // caller (swept) | responder (was probed)
  var creds = { ufrag: UFRAG, pwd: PWD };
  var codeMode = false;
  var currentCode = null;
  var fallbackTimer = 0;
  var fallbackRaised = false;

  var readers = [];                     // [{ pc, ports, win }] win = in-window ports
  var sources = [];                     // [{ pc, class, usedColumns, usedRounds }]
  var pulsePool = [];                   // sources bucketed by class
  var scanners = [];
  var locatePcs = [];
  var finalPc = null;
  var finalPort = 0;
  var finalChannel = null;
  var finalRemoteApplied = false;
  var connectedPeer = null;

  var arrivals = [];                    // { pc, port, source, at }
  var stuckUntil = Object.create(null);
  var ownWindowPorts = Object.create(null);
  var peerAddress = null;
  var peerPort = 0;                     // first peer socket seen probing us
  var peerTargets = [];                 // peer sockets to aim at (probe readback)
  var exchange = { active: false, done: false, startedAt: 0, gen: 0 };
  var ready = false;
  // True while the census and the peer-socket probe are reading this page's
  // sockets.  Those passes are made of getStats() reads, and so is every reader
  // poll; a live reader poll is not merely wasted work before there is anything
  // to read (the peer has not pulsed yet), it is what held every census and
  // probe read past its timeout in sym-run3/4.txt.  The reader loops idle while
  // this is set.
  var preparing = false;
  var lastArrivalAt = 0;
  var lastDecodeAt = 0;
  var peers = Object.create(null);
  var roundCursor = 0;

  // ---------------------------------------------------------------- helpers
  function now() { return Date.now(); }

  function randomHex(bytes) {
    var buf = new Uint8Array(bytes);
    (window.crypto || window.msCrypto).getRandomValues(buf);
    var out = "";
    for (var i = 0; i < buf.length; i++) out += (buf[i] + 0x100).toString(16).slice(1);
    return out;
  }

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function inWindow(port) { return port >= W_START && port <= W_END; }

  var throttle = Object.create(null);

  // Diagnostics that would otherwise repeat every poll pass on a loaded machine.
  function logThrottled(phase, message, everyMs) {
    var key = phase + ":" + message.replace(/\d+/g, "#");
    var at = now();
    if (throttle[key] && at - throttle[key] < (everyMs || 5000)) return;
    throttle[key] = at;
    log(phase, message);
  }

  function log(phase, message) {
    var at = startedAt ? (now() - startedAt) : 0;
    if (PHASE_NAMES[phase]) currentPhase = PHASE_NAMES[phase];
    if (phase === "error") lastError = String(message);
    logs.push({ phase: phase, message: message, at: at });
    statusLine = phase + ": " + message;
    try {
      console.log("[autoconnect +" + at + "ms] " + statusLine);
      if (window.__log && typeof window.__log.push === "function") window.__log.push(statusLine);
    } catch (e) { /* never let logging break the engine */ }
    emit({ type: "status", phase: phase, status: statusLine });
  }

  function emit(event) {
    event.at = startedAt ? (now() - startedAt) : 0;
    if (event.type !== "status") {
      if (eventQueue.length >= EVENT_QUEUE_MAX) eventQueue.shift();
      try { eventQueue.push(JSON.stringify(event)); } catch (e) { /* skip */ }
    }
    for (var i = 0; i < subs.length; i++) {
      try { subs[i](event); } catch (e) { /* a subscriber's error is its own */ }
    }
  }

  // ------------------------------------------------------------ code fallback
  function sanitizeCode(value) {
    if (value === null || value === undefined) return null;
    var text = String(value).toUpperCase().replace(/[^A-Z0-9]/g, "");
    if (text.length !== CODE_LEN) return null;
    for (var i = 0; i < text.length; i++) {
      if (CODE_ALPHABET.indexOf(text.charAt(i)) < 0) return null;
    }
    return text;
  }

  function makeCode() {
    var buf = new Uint8Array(CODE_LEN);
    (window.crypto || window.msCrypto).getRandomValues(buf);
    var out = "";
    for (var i = 0; i < CODE_LEN; i++) out += CODE_ALPHABET[buf[i] % CODE_ALPHABET.length];
    return out;
  }

  function credsFromBytes(bytes) {
    var ufrag = "";
    for (var i = 0; i < 8; i++) ufrag += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
    var pwd = "";
    for (var j = 8; j < 24; j++) pwd += CODE_ALPHABET[bytes[j] % CODE_ALPHABET.length];
    while (pwd.length < 22) pwd += "Ac7";
    return { ufrag: ufrag, pwd: pwd };
  }

  function deriveCreds(code) {
    var text = "eagler-autoconnect:" + code;
    var subtle = window.crypto && window.crypto.subtle;
    if (subtle && subtle.digest && typeof TextEncoder !== "undefined") {
      return subtle.digest("SHA-256", new TextEncoder().encode(text)).then(function (hash) {
        return credsFromBytes(new Uint8Array(hash));
      });
    }
    var seed = 0x9E3779B9;
    for (var i = 0; i < text.length; i++) seed = (seed * 31 + text.charCodeAt(i)) >>> 0;
    var bytes_ = new Uint8Array(24);
    for (var k = 0; k < bytes_.length; k++) {
      seed = (seed * 1664525 + 1013904223) >>> 0;
      bytes_[k] = (seed >>> 16) & 0xFF;
    }
    return Promise.resolve(credsFromBytes(bytes_));
  }

  function startCode(code) {
    var clean = sanitizeCode(code);
    if (!clean) {
      log("code", "that is not a " + CODE_LEN + "-character code");
      return Promise.resolve(null);
    }
    if (running) stop();
    codeMode = true;
    currentCode = clean;
    return deriveCreds(clean).then(function (derived) {
      creds = derived;
      log("code", "code mode: both peers derive the same credentials from the shared code");
      return start({ name: selfName });
    }).then(function () {
      return currentCode;
    }, function (err) {
      log("error", "could not start code mode: " + (err && err.message ? err.message : err));
      return null;
    });
  }

  function clearFallbackTimer() {
    if (fallbackTimer) { window.clearTimeout(fallbackTimer); fallbackTimer = 0; }
  }

  function maybeFallback() {
    fallbackTimer = 0;
    if (!running || codeMode || state === "connected" || state === "fallback") return;
    if (state === "linking") {                  // the grid is mid-flight; let it finish
      fallbackTimer = window.setTimeout(maybeFallback, 10000);
      return;
    }
    fallbackRaised = true;
    state = "fallback";
    log("code", "no peer in " + Math.round(FALLBACK_AFTER_MS / 1000) + "s: a " + CODE_LEN
      + "-character code can connect the two players");
    emit({ type: "fallback" });
  }

  // -------------------------------------------------------------------- sdp
  function mungeCreds(sdp) {
    return sdp
      .replace(/a=ice-ufrag:[^\r\n]*/g, "a=ice-ufrag:" + creds.ufrag)
      .replace(/a=ice-pwd:[^\r\n]*/g, "a=ice-pwd:" + creds.pwd);
  }

  function dummyFingerprint() {
    var parts = [];
    for (var i = 0; i < 32; i++) parts.push("AB");
    return parts.join(":");
  }

  function forgeAnswer(localSdp, options) {
    var opts = options || {};
    var lines = String(localSdp).split(/\r\n|\n/);
    var kept = [];
    for (var i = 0; i < lines.length; i++) {
      var L = lines[i].replace(/\r$/, "");
      if (L === "") continue;
      if (/^a=candidate:/.test(L)) continue;
      if (/^a=ice-ufrag:/.test(L)) { kept.push("a=ice-ufrag:" + creds.ufrag); continue; }
      if (/^a=ice-pwd:/.test(L)) { kept.push("a=ice-pwd:" + creds.pwd); continue; }
      if (/^a=fingerprint:/.test(L)) {
        kept.push("a=fingerprint:sha-256 " + (opts.fingerprint || dummyFingerprint()));
        continue;
      }
      if (/^a=setup:/.test(L)) { kept.push("a=setup:" + (opts.setup || "passive")); continue; }
      kept.push(L);
    }
    return kept.join("\r\n") + "\r\n";
  }

  function candidateLine(address, port) {
    return "candidate:1 1 udp 2130706431 " + address + " " + port + " typ host";
  }

  // Every check this page ever sends goes through here.  A pair that has already
  // been used is silently a no-op at the ICE layer - no new prflx candidate is
  // created - so it must never be asked for twice: the same (source, target)
  // pair is never fired again, even in a later copy.
  function fire(pc, address, port) {
    var used = pc.__acUsed || (pc.__acUsed = Object.create(null));
    var key = address + ":" + port;
    if (used[key]) return Promise.resolve(false);
    used[key] = true;
    try {
      return pc.addIceCandidate({
        candidate: candidateLine(address, port), sdpMid: "0", sdpMLineIndex: 0
      }).then(function () { return true; }, function () { return false; });
    } catch (e) {
      return Promise.resolve(false);
    }
  }

  // ------------------------------------------------ one stats queue per page
  // Measured in spike-load.html (FINDINGS section 22): eight *sequential*
  // getStats() passes cost 0 ms with 10, 42, 74, 138 and 170 live connections,
  // and 0 ms (max 19 ms) with 120 checks in flight - while this engine logged a
  // 33777 ms pass and nine timeouts by running up to ten reader passes plus the
  // census's own batches at the same time.  Stats cost is a scheduling property
  // of the page, so every pass now goes through one queue with a hard cap on how
  // many are in flight, and reader passes jump ahead of census and pool reads:
  // a late arrival report is a lost arrival, a late census read is not.
  var STATS_MAX_IN_FLIGHT = 1;
  var statsHigh = [];      // the read: arrivals
  var statsNormal = [];    // census, pool building, probing
  var statsInFlight = 0;
  var statsBusyMs = 0;

  function pumpStats() {
    while (statsInFlight < STATS_MAX_IN_FLIGHT && (statsHigh.length || statsNormal.length)) {
      var job = statsHigh.length ? statsHigh.shift() : statsNormal.shift();
      statsInFlight++;
      Promise.resolve().then(job.fn).then(function (value) {
        statsInFlight--;
        job.resolve(value);
        pumpStats();
      }, function (err) {
        statsInFlight--;
        job.reject(err);
        pumpStats();
      });
    }
  }

  function withStats(fn, high) {
    return new Promise(function (resolve, reject) {
      (high ? statsHigh : statsNormal).push({ fn: fn, resolve: resolve, reject: reject });
      pumpStats();
    });
  }

  function pcStats(pc, high) {
    return withStats(function () {
      return withTimeout(pc.getStats(), STATS_JOB_TIMEOUT_MS);
    }, high).then(function (stats) { return stats || EMPTY_STATS; });
  }

  function udpPorts(pc) {
    return pcStats(pc).then(function (stats) {
      var ports = [];
      stats.forEach(function (s) {
        if (s.type === "local-candidate" && s.protocol === "udp" && s.port) ports.push(s.port);
      });
      return ports;
    }, function () { return []; });
  }

  // A connection reports its local candidates only after the ICE gather has run,
  // which is why the first version of this file read an empty port list for most
  // of a freshly made batch and threw the connections away.
  function waitForPorts(pc, maxMs) {
    var until = now() + (maxMs || PORTS_WAIT_MS);
    return (function attempt() {
      return udpPorts(pc).then(function (ports) {
        if (ports.length || now() > until) return ports;
        return sleep(60).then(attempt);
      });
    })();
  }

  function windowPorts(ports) {
    var win = [];
    for (var i = 0; i < ports.length; i++) if (inWindow(ports[i])) win.push(ports[i]);
    return win;
  }

  // Which socket of this connection talks to the peer.  Read back from the
  // connection's own pair - the census, and the reason a payload's symbols are
  // exact rather than a coin flip between the connection's two sockets.
  function usedLocalPorts(pc) {
    return pcStats(pc).then(function (stats) {
      var rem = {}, loc = {}, map = {};
      stats.forEach(function (s) {
        if (s.type === "remote-candidate") rem[s.id] = s.port;
        if (s.type === "local-candidate") loc[s.id] = s.port;
      });
      stats.forEach(function (s) {
        if (s.type !== "candidate-pair") return;
        var r = rem[s.remoteCandidateId], l = loc[s.localCandidateId];
        if (r !== undefined && l !== undefined) map[r] = l;
      });
      return map;
    }, function () { return {}; });
  }

  // Every remote candidate of a connection, with the local socket that carried
  // it: the whole read side of the carrier.
  function candidatesWithSockets(pc) {
    return pcStats(pc, true).then(function (stats) {
      var localOf = {}, portOf = {}, rows = [];
      stats.forEach(function (s) {
        if (s.type === "candidate-pair" && s.localCandidateId && s.remoteCandidateId) {
          localOf[s.remoteCandidateId] = s.localCandidateId;
        }
      });
      stats.forEach(function (s) {
        if (s.type === "local-candidate" && s.id) portOf[s.id] = s.port;
      });
      stats.forEach(function (s) {
        if (s.type === "remote-candidate") {
          rows.push({
            id: s.id, port: s.port, candidateType: s.candidateType,
            localPort: portOf[localOf[s.id]] || 0
          });
        }
      });
      return rows;
    }, function () { return []; });
  }

  function succeededPairs(pc) {
    return pcStats(pc).then(function (stats) {
      var cands = {}, rows = [];
      stats.forEach(function (s) {
        if (s.type === "remote-candidate" || s.type === "local-candidate") cands[s.id] = s;
      });
      stats.forEach(function (s) {
        if (s.type !== "candidate-pair" || s.state !== "succeeded") return;
        var rc = cands[s.remoteCandidateId];
        if (!rc || !rc.port) return;
        rows.push({ address: rc.address || "", port: rc.port, candidateType: rc.candidateType });
      });
      return rows;
    }, function () { return []; });
  }

  // A stats read that never settles must not hold the only slot in the queue:
  // the queue is what makes every read cheap (STATS_MAX_IN_FLIGHT below), but a
  // hung getStats() promise then parks it for the rest of the page's life - no
  // later read, census or arrival, ever runs again.  sym-run5.txt is that shape:
  // both pages found each other, then the census read was queued behind a read
  // that never came back and neither side ever logged a socket count.  A timed
  // out read reports nothing, which is what it measured anyway.
  var STATS_JOB_TIMEOUT_MS = 8000;
  var EMPTY_STATS = { forEach: function () {} };

  function withTimeout(promise, ms) {
    return new Promise(function (resolve) {
      var done = false;
      var timer = window.setTimeout(function () { if (!done) { done = true; resolve(null); } }, ms);
      promise.then(function (v) {
        if (!done) { done = true; window.clearTimeout(timer); resolve(v); }
      }, function () {
        if (!done) { done = true; window.clearTimeout(timer); resolve(null); }
      });
    });
  }

  // ------------------------------------------------------------ connections
  function withCert() {
    return certificate ? { certificates: [certificate] } : {};
  }

  // A pooled connection: shared credentials, forged answer, no data.
  function makeSharedPc() {
    var pc = new RTCPeerConnection(withCert());
    pc.createDataChannel("probe", { ordered: true });
    return pc.createOffer().then(function (offer) {
      return pc.setLocalDescription({ type: "offer", sdp: mungeCreds(offer.sdp) });
    }).then(function () {
      return pc.setRemoteDescription({ type: "answer", sdp: forgeAnswer(pc.localDescription.sdp) });
    }).then(function () { return pc; });
  }

  // Issue stats reads a few at a time.  A hundred and seventy-five at once is
  // what starved the page's own stats service in runs 6-9.
  function mapLimited(items, limit, fn) {
    var index = 0;
    var out = [];
    function worker() {
      if (index >= items.length) return Promise.resolve();
      var at = index++;
      return Promise.resolve(fn(items[at], at)).then(function (v) {
        out[at] = v;
        return worker();
      });
    }
    var jobs = [];
    for (var i = 0; i < Math.min(limit, items.length); i++) jobs.push(worker());
    return Promise.all(jobs).then(function () { return out; });
  }

  function makeSharedBatch(count) {
    var jobs = [];
    for (var i = 0; i < count; i++) jobs.push(makeSharedPc());
    return Promise.all(jobs);
  }

  function closePc(pc) {
    try { pc.close(); } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------- phase 0: the pool
  // Readers are the grid's receiving side, so they live in the discovery window:
  // the port lottery creates connections until enough of them have a socket
  // there.  Everything else is forced out of the window, so a peer's probe can
  // only ever answer with a reader socket - that is what makes the sender's
  // target list and the receiver's socket list the same set.
  function buildReaders() {
    var attempts = 0;
    function step() {
      if (!running) return Promise.resolve();
      if (readers.length >= READER_TARGET || attempts >= LOTTERY_MAX_BATCHES) return Promise.resolve();
      attempts++;
      return makeSharedBatch(LOTTERY_BATCH).then(function (pcs) {
        return Promise.all(pcs.map(function (pc) {
          return waitForPorts(pc).then(function (ports) {
            var win = windowPorts(ports);
            if (!win.length) { closePc(pc); return null; }
            for (var i = 0; i < win.length; i++) ownWindowPorts[win[i]] = true;
            return { pc: pc, ports: ports, win: win };
          });
        })).then(function (kept) {
          for (var i = 0; i < kept.length; i++) {
            if (!kept[i]) continue;
            if (readers.length >= READER_TARGET) { closePc(kept[i].pc); continue; }
            readers.push(kept[i]);
          }
          log("readers", "port lottery: " + readers.length + "/" + READER_TARGET
            + " reader connection(s) after " + attempts + " batch(es)");
          return step();
        });
      });
    }
    return step().then(function () {
      var total = 0;
      readers.forEach(function (r) { total += r.win.length; });
      log("readers", readers.length + " reader connection(s), " + total + " socket(s) in the window: "
        + readers.map(function (r) { return r.win.join("/"); }).join(" "));
    });
  }

  // A non-reader connection must own no in-window socket, or a peer's probe
  // would read one of its sockets as a payload target.
  function makeOutOfWindow(count) {
    var made = [];
    var rounds = 0;
    function round() {
      rounds++;
      if (!running || made.length >= count || rounds > 8) return Promise.resolve(made);
      var want = Math.min(LOTTERY_BATCH, count - made.length);
      return makeSharedBatch(want).then(function (pcs) {
        return Promise.all(pcs.map(function (pc) {
          return waitForPorts(pc).then(function (ports) {
            if (windowPorts(ports).length) { closePc(pc); return null; }
            return pc;
          });
        })).then(function (kept) {
          for (var i = 0; i < kept.length; i++) if (kept[i]) made.push(kept[i]);
          if (made.length < count) log("sources", "out-of-window pool: " + made.length + "/" + count
            + " after " + rounds + " round(s)");
          return round();
        });
      });
    }
    return round();
  }

  // Pulse sources: never readers and never the sweep, so their send queue stays
  // empty (a source with queued checks fires its pulse up to 1540 ms late).
  function buildSources() {
    return makeOutOfWindow(SOURCE_TARGET).then(function (pcs) {
      sources = pcs.map(function (pc, i) {
        pc.__acId = i;
        // The id has to live on the source object too: takeSource() keys its
        // per-round counter by s.__acId, and a missing key made every source
        // share one counter, so a round stopped after four pulses instead of
        // firing its ~120.  Measured in grid-run10.log ("4 pulses, 112
        // unfilled") and reproduced offline in tmp/autoconnect/sim-schedule.mjs
        // with --sharedid.
        return { pc: pc, __acId: i, class: -1, usedColumns: Object.create(null) };
      });
      log("sources", sources.length + " pulse sources ready (all out of the window)");
    });
  }

  function buildScanners() {
    return makeOutOfWindow(SCAN_CONNECTIONS).then(function (pcs) {
      scanners = pcs;
      log("sources", scanners.length + " scanner connections ready");
    });
  }

  // The final connection's socket is deliberately outside the window: the peer
  // reaches it at the port carried in the payload, so it must never answer a
  // probe and masquerade as a grid socket.  Its data channel is created before
  // the offer, so it is part of the negotiated session.
  function buildFinalPc() {
    var tries = 0;
    function attempt() {
      tries++;
      var pc = new RTCPeerConnection(withCert());
      var channel = null;
      try {
        channel = pc.createDataChannel("eagler-autoconnect", { ordered: true });
      } catch (e) {
        channel = null;
      }
      return pc.createOffer().then(function (offer) {
        return pc.setLocalDescription({ type: "offer", sdp: mungeCreds(offer.sdp) });
      }).then(function () {
        return pc.setRemoteDescription({ type: "answer", sdp: forgeAnswer(pc.localDescription.sdp) });
      }).then(function () {
        return waitForPorts(pc);
      }).then(function (ports) {
        // An in-window socket here would answer a peer's probe and masquerade as
        // a grid socket, so it is re-made until its ports are outside.
        if (windowPorts(ports).length && tries < OUT_OF_WINDOW_TRIES) {
          closePc(pc);
          return attempt();
        }
        if (windowPorts(ports).length) log("final", "final connection kept in-window after "
          + tries + " tries");
        finalPc = pc;
        finalChannel = channel;
        return pc;
      });
    }
    return attempt();
  }

  // ------------------------------------------------------ phase 1: sweep
  function hostOrder() {
    var hosts = [];
    for (var a = 100; a <= 199; a++) hosts.push(a);
    for (var b = 200; b <= 254; b++) hosts.push(b);
    for (var c = 1; c <= 99; c++) hosts.push(c);
    return hosts;
  }

  function scanTargets() {
    var hosts = hostOrder();
    var rounds = [];
    for (var s = 0; s < SUBNETS.length; s++) {
      for (var i = 0; i < hosts.length; i += SCAN_ROUND_HOSTS) {
        var round = [];
        for (var j = i; j < Math.min(i + SCAN_ROUND_HOSTS, hosts.length); j++) {
          for (var p = 0; p < W_COUNT; p++) round.push({ address: SUBNETS[s] + hosts[j], port: W_START + p });
        }
        rounds.push(round);
      }
    }
    log("scan", "sweeping " + W_COUNT + " reader ports x 254 hosts in " + rounds.length
      + " rounds of ~" + rounds[0].length + " checks");

    var found = null;
    var pumpDone = false;
    var pumpGraceUntil = 0;
    var pumpCursor = 0;
    var batchCursor = 0;
    var pollCursor = 0;
    var pollRounds = 0;

    function pumpRound() {
      if (!running || found) { pumpDone = true; return Promise.resolve(); }
      if (pumpCursor >= rounds.length) {
        pumpDone = true;
        pumpGraceUntil = now() + SCAN_GRACE_MS;
        log("scan", "all " + rounds.length + " rounds fired");
        return Promise.resolve();
      }
      var batch = rounds[pumpCursor++];
      var i = 0;
      // Bursts, not one synchronous flood: the ICE thread has to digest the
      // candidates, and a thousand adds at once is what makes the first stat poll
      // never come back.
      function burst() {
        var n = 0;
        while (i < batch.length && n < SCAN_BURST) {
          fire(scanners[batchCursor % scanners.length], batch[i].address, batch[i].port);
          batchCursor++;
          i++; n++;
        }
        if (i >= batch.length) return Promise.resolve();
        return sleep(SCAN_BURST_GAP_MS).then(burst);
      }
      return burst().then(function () { return sleep(SCAN_ROUND_MS); }).then(pumpRound);
    }

    function poll() {
      if (!running || found) return Promise.resolve(found);
      var slice = [];
      for (var i = 0; i < 4; i++) slice.push(scanners[(pollCursor + i) % scanners.length]);
      pollCursor = (pollCursor + 4) % scanners.length;
      pollRounds++;
      if (pollRounds % 20 === 0) {
        log("scan", "sweep heartbeat: " + pollRounds + " stat polls, "
          + (pumpDone ? "all rounds fired" : "pumping round " + pumpCursor));
      }
      var chain = Promise.resolve();
      slice.forEach(function (pc) {
        chain = chain.then(function () {
          if (found) return null;
          return succeededPairs(pc).then(function (rows) {
            for (var j = 0; j < rows.length; j++) {
              var r = rows[j];
              if (!r.address) continue;
              if (ownWindowPorts[r.port]) continue;   // our own reader answered
              found = { address: r.address, port: r.port };
              log("scan", "peer found at " + r.address + ":" + r.port);
              return found;
            }
            return null;
          });
        });
      });
      return chain;
    }

    var pumping = pumpRound();
    var deadline = now() + SCAN_BUDGET_MS;
    return (function wait() {
      if (!running) return Promise.resolve(null);
      if (found) return pumping.then(function () { return found; });
      return poll().then(function () {
        if (found) return found;
        if (peerPort) {
          log("scan", "sweep stopped: a peer is already probing our readers");
          return null;
        }
        if (!pumpDone) {
          if (now() > deadline) return null;
          return sleep(300).then(wait);
        }
        if (now() < pumpGraceUntil) return sleep(300).then(wait);
        return null;
      });
    })();
  }

  // Fire a set of checks from the *sources* and read back which of the peer's
  // sockets answered.  The sources are used deliberately: a connection that
  // spent a sweep on thousands of candidates takes hundreds of milliseconds to
  // report, and reading forty of those starved the page's stats (measured: a
  // reader with two candidates waiting 400 ms, once 37 s, behind scanner polls).
  // A source carries one or two candidates, and the readback polls until a
  // deadline instead of once, because a loaded machine answers slowly.
  function probeFromScanners(address, ports, label, deadlineMs) {
    if (!address || !scanners.length || !ports.length) return Promise.resolve([]);
    var i = 0;
    function burst() {
      var n = 0;
      while (i < ports.length && n < BURST_FIRES) {
        fire(scanners[i % scanners.length], address, ports[i]);
        i++; n++;
      }
      if (i >= ports.length) return Promise.resolve();
      return sleep(BURST_GAP_MS).then(burst);
    }
    var found = Object.create(null);
    log("ports", label + ": " + ports.length + " checks over " + scanners.length
      + " scanner(s) (~" + Math.ceil(ports.length / scanners.length) + " each)");
    return burst().then(function () {
      var deadline = now() + deadlineMs;
      return (function poll() {
        // Read back from the firing connections only, a few at a time.
        return mapLimited(scanners, STATS_BATCH, function (pc) {
          return succeededPairs(pc).then(function (rows) {
            for (var j = 0; j < rows.length; j++) {
              var r = rows[j];
              if (!r.address || r.address !== address) continue;
              if (ownWindowPorts[r.port]) continue;
              found[r.port] = true;
            }
          });
        }).then(function () {
          // Never stop at the first answer: a peer has as many sockets as it has
          // readers, and the count is the grid's width - a short list means a
          // slow, long transmission.
          if (now() > deadline) return;
          if (Object.keys(found).length >= gridSockets().length) return;
          return sleep(1000).then(poll);
        });
      })();
    }).then(function () {
      var out = Object.keys(found).map(Number).sort(function (a, b) { return a - b; });
      log("ports", label + " answered with " + out.length + " socket(s): " + (out.join(",") || "none"));
      return out;
    });
  }

  function peerPortsOf(ports) {
    var found = Object.create(null);
    var list = [];
    for (var i = 0; i < ports.length; i++) if (!found[ports[i]]) { found[ports[i]] = true; list.push(ports[i]); }
    return list;
  }

  // The sockets of the peer a payload can be aimed at.  Sockets that answered a
  // check *now*, so they are live by construction - the previous build's dead
  // port came from aiming at a list cached much earlier.  Only a reader socket
  // can ever appear here, because every connection of ours that is not a reader
  // owns no in-window socket, and the probe only fires at in-window ports.
  function probePeerPorts(address) {
    var ports = [];
    for (var p = W_START; p <= W_END; p++) ports.push(p);
    return probeFromScanners(address, ports, "peer socket probe", PROBE_WAIT_MS).then(function (found) {
      peerTargets = found;
      return peerTargets;
    });
  }


  // The peer that was found cannot read our address (candidate addresses are
  // redacted), but it does read the source port of our checks.  A single-port
  // sweep of the two common /24s finds the address behind that port; the port is
  // a live socket of the peer, so whichever address answers is its own.
  function findAddressByPort(port) {
    var candidates = [];
    for (var s = 0; s < SUBNETS.length; s++) {
      for (var h = 1; h <= 254; h++) candidates.push(SUBNETS[s] + h);
    }
    var addresses = Object.create(null);
    var i = 0;
    function burst() {
      var n = 0;
      while (i < candidates.length && n < BURST_FIRES) {
        fire(scanners[i % scanners.length], candidates[i], port);
        i++; n++;
      }
      if (i >= candidates.length) return Promise.resolve();
      return sleep(BURST_GAP_MS).then(burst);
    }
    log("locate", "looking for the address behind port " + port + " (" + candidates.length + " checks)");
    return burst().then(function () {
      var deadline = now() + LOCATE_WAIT_MS;
      return (function poll() {
        return mapLimited(scanners, STATS_BATCH, function (pc) {
          return succeededPairs(pc).then(function (rows) {
            for (var j = 0; j < rows.length; j++) if (rows[j].address) addresses[rows[j].address] = true;
          });
        }).then(function () {
          if (Object.keys(addresses).length || now() > deadline) return;
          return sleep(1000).then(poll);
        });
      })();
    }).then(function () {
      var list = Object.keys(addresses);
      log("locate", "port " + port + " answered at " + (list.join(",") || "no address"));
      return list.length ? list[0] : null;
    });
  }

  // ---------------------------------------------------------------- census
  // One check per connection at a port nothing answers on, read back from the
  // connection's own pair.  This is what makes a pulse's symbols exact: the
  // sender knows which of its two sockets each source will use (the defect that
  // sank the previous build), and - measured in this pass - a connection answers
  // an incoming check from that same socket, so the socket the *receiver* names
  // as a column is the socket the *sender* read back from its own probe.
  function censusConnections(pcs, label) {
    if (!peerAddress || !pcs.length) return Promise.resolve(0);
    var chain = Promise.resolve();
    pcs.forEach(function (pc) {
      chain = chain.then(function () { return fire(pc, peerAddress, CENSUS_PORT); });
    });
    return chain.then(function () { return sleep(CENSUS_WAIT_MS); }).then(function () {
      return mapLimited(pcs, STATS_BATCH, function (pc) {
        return usedLocalPorts(pc).then(function (map) {
          var chosen = map[CENSUS_PORT] || 0;
          if (!chosen) return;
          if (!pc.__acChosen) pc.__acChosen = chosen;   // the pair is pruned later; keep the label
        });
      });
    }).then(function () {
      var n = 0;
      pcs.forEach(function (pc) { if (pc.__acChosen) n++; });
      log("census", label + ": " + n + " of " + pcs.length + " labelled");
      return n;
    });
  }

  function censusColumns() {
    return censusConnections(readers.map(function (r) { return r.pc; }), "reader sockets");
  }

  function censusSources() {
    return censusConnections(sources.map(function (s) { return s.pc; }), "pulse sources").then(function () {
      pulsePool = [];
      for (var c = 0; c < SYMBOL_CLASSES; c++) pulsePool.push([]);
      var n = 0;
      sources.forEach(function (s) {
        var chosen = s.pc.__acChosen || 0;
        s.class = chosen ? (chosen % SYMBOL_CLASSES) : -1;
        if (s.class >= 0) { pulsePool[s.class].push(s); n++; }
      });
      log("census", "source classes: " + pulsePool.map(function (b) { return b.length; }).join("/")
        + " (" + n + " labelled)");
      return n;
    });
  }

  // The receiver's columns: the socket each reader answers from, which is the
  // socket a peer's probe reads back and therefore the socket a payload is aimed
  // at.  Sorted, so both sides order the columns the same way.
  function gridSockets() {
    var out = [];
    for (var i = 0; i < readers.length; i++) {
      if (readers[i].pc.__acChosen) out.push({ pc: readers[i].pc, port: readers[i].pc.__acChosen });
    }
    out.sort(function (a, b) { return a.port - b.port; });
    return out;
  }

  // -------------------------------------------------------- readers: arrivals
  function pollReader(reader) {
    if ((reader.stuckUntil || 0) > now()) return Promise.resolve();
    var t0 = now();
    return withTimeout(candidatesWithSockets(reader.pc), reader.timeoutMs || POLL_TIMEOUT_MS).then(function (rows) {
      var took = now() - t0;
      if (!rows) {
        // Backoff and timeout belong to the reader, not to a socket key: the old
        // `stuckUntil[reader.win[0]]` let one slow report delay every reader in
        // the same cycle and then skipped the key for everyone.
        reader.fails = (reader.fails || 0) + 1;
        reader.stuckUntil = now() + Math.min(STUCK_BACKOFF_MS * reader.fails, STUCK_BACKOFF_MS_MAX);
        reader.timeoutMs = Math.min((reader.timeoutMs || POLL_TIMEOUT_MS) + 750, POLL_TIMEOUT_MS_MAX);
        logThrottled("grid", "reader " + reader.win.join("/") + " stat poll timed out (" + took
          + " ms, " + reader.fails + " in a row; this reader's own timeout is now "
          + reader.timeoutMs + " ms)");
        return;
      }
      if (reader.fails) {
        log("grid", "reader " + reader.win.join("/") + " answered again after " + reader.fails
          + " timeout(s) (" + took + " ms, " + rows.length + " remote candidate(s))");
        reader.fails = 0;
        reader.timeoutMs = POLL_TIMEOUT_MS;
      }
      if (took > 150) {
        logThrottled("grid", "reader stat poll took " + took + " ms for "
          + rows.length + " remote candidate(s)");
      }
      var seen = reader.seen || (reader.seen = Object.create(null));
      for (var i = 0; i < rows.length; i++) {
        var r = rows[i];
        if (r.candidateType !== "prflx") continue;
        if (seen[r.id]) continue;
        seen[r.id] = true;
        var at = now();
        // The socket a pulse landed on: reported per pair when available, and
        // otherwise unambiguous because a reader aims at one port.
        var port = r.localPort || (reader.win.length === 1 ? reader.win[0] : 0);
        if (!port) continue;
        arrivals.push({ reader: reader, port: port, source: r.port, at: at });
        if (arrivals.length > 6000) arrivals.splice(0, arrivals.length - 4000);
        lastArrivalAt = at;
        if (!peerPort) {
          peerPort = r.port;
          log("scan", "a peer is probing our readers from port " + r.port);
        }
      }
    });
  }

  function watchLoop() {
    // One loop per reader, deliberately.  Until this pass every cycle issued one
    // stats pass per reader and waited for all of them (Promise.all), so a single
    // slow reader set the sampling interval for every reader - and an arrival not
    // sampled before ICE prunes the pair that carried it is lost, not delayed.
    // grid-run12.log has that shape exactly: one reader timed out 22 times while
    // all six were sampled at its pace, and no read line was ever printed.  Each
    // reader now polls on its own clock with its own backoff and timeout, while
    // the decode/maybeExchange decision runs on a light loop that touches no
    // stats at all.
    for (var i = 0; i < readers.length; i++) readerLoop(readers[i]);
    function decide() {
      if (!running || state === "connected") return Promise.resolve();
      if (exchange.active && !exchange.done) {
        var parsed = tryDecode();
        if (parsed) beginHandshake(parsed);
      } else {
        maybeExchange();
      }
      return sleep(WATCH_TICK_MS).then(decide);
    }
    return Promise.resolve().then(decide).catch(function (err) {
      log("error", "watch loop: " + String(err && err.message ? err.message : err));
    });
  }

  function readerLoop(reader) {
    function step() {
      if (!running || state === "connected") return Promise.resolve();
      // Idle through the census and the probe: there is nothing to collect yet,
      // and each poll is a getStats() pass the census and the probe are waiting
      // behind.  The peer cannot be pulsing either - it is running the same
      // pass - and a pulse that arrives a moment early is re-read on the next
      // tick, because the pulse connection lives SYM_CLOSE_AFTER_MS and the
      // decode only runs once the arrivals settle.
      if (preparing) return sleep(POLL_CYCLE_MS).then(step);
      return pollReader(reader).then(function () {
        return sleep(POLL_CYCLE_MS).then(step);
      });
    }
    return Promise.resolve().then(step).catch(function (err) {
      log("error", "reader loop " + reader.win.join("/") + ": "
        + String(err && err.message ? err.message : err));
      if (running && state !== "connected") return sleep(POLL_CYCLE_MS).then(step);
    });
  }

  // ------------------------------------------------------ phase 2: the grid
  function bitsFromBytes(bytes) {
    var bits = [];
    for (var i = 0; i < bytes.length; i++) {
      var b = bytes[i] & 0xFF;
      for (var j = 7; j >= 0; j--) bits.push((b >> j) & 1);
    }
    return bits;
  }

  function bytesFromBits(bits, count) {
    var bytes = [];
    for (var i = 0; i < count; i++) {
      var v = 0;
      for (var j = 0; j < 8; j++) v = (v << 1) | (bits[i * 8 + j] || 0);
      bytes.push(v);
    }
    return bytes;
  }

  function crc16(bytes) {
    var crc = 0xFFFF;
    for (var i = 0; i < bytes.length; i++) {
      crc ^= (bytes[i] & 0xFF) << 8;
      for (var j = 0; j < 8; j++) {
        crc = (crc & 0x8000) ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
      }
    }
    return crc & 0xFFFF;
  }

  function fingerprintBytes(fp) {
    var out = [];
    var parts = String(fp).split(":");
    for (var i = 0; i < parts.length; i++) out.push(parseInt(parts[i], 16) & 0xFF);
    return out;
  }

  function bytesToFingerprint(bytes) {
    var parts = [];
    for (var i = 0; i < bytes.length; i++) {
      parts.push((bytes[i] < 0x10 ? "0" : "") + bytes[i].toString(16).toUpperCase());
    }
    return parts.join(":");
  }

  function buildPayload() {
    var body = [(PAYLOAD_MAGIC >> 8) & 0xFF, PAYLOAD_MAGIC & 0xFF];
    body.push((selfId >> 8) & 0xFF, selfId & 0xFF);
    var fp = fingerprintBytes(fingerprint);
    for (var i = 0; i < 32; i++) body.push(fp[i] || 0);
    body.push((finalPort >> 8) & 0xFF, finalPort & 0xFF);
    var crc = crc16(body);
    body.push((crc >> 8) & 0xFF, crc & 0xFF);
    return { bytes: body, bits: bitsFromBytes(body) };
  }

  function parsePayload(bits) {
    if (!bits || bits.length < PAYLOAD_BITS) return null;
    var bytes = bytesFromBits(bits, PAYLOAD_BYTES);
    if (((bytes[0] << 8) | bytes[1]) !== PAYLOAD_MAGIC) return null;
    var body = bytes.slice(0, bytes.length - 2);
    var crc = (bytes[bytes.length - 2] << 8) | bytes[bytes.length - 1];
    if (crc16(body) !== crc) return null;
    var fp = [];
    for (var i = 0; i < 32; i++) fp.push(bytes[4 + i]);
    var port = (bytes[36] << 8) | bytes[37];
    if (!port) return null;
    return {
      id: (bytes[2] << 8) | bytes[3],
      fingerprint: bytesToFingerprint(fp),
      port: port
    };
  }

  // A source can fire at most one pulse per round (two queued checks from one
  // connection arrive a beat apart and can straddle a burst boundary) and never
  // at the same column twice in a payload (a repeated pair creates no candidate
  // at all).
  // A source may fire several times in one round (a round is 8 s and checks are
  // paced ~16/s, so four fit comfortably), but never at the same column twice in
  // a payload: a repeated pair creates no candidate at all.
  function takeSource(cls, col, roundUsed) {
    var bucket = pulsePool[cls];
    if (!bucket) return null;
    for (var i = 0; i < bucket.length; i++) {
      var s = bucket[i];
      var used = roundUsed[s.__acId] || 0;
      if (used >= CELLS_PER_SOURCE_PER_ROUND) continue;
      if (s.usedColumns[col]) continue;
      s.usedColumns[col] = true;
      roundUsed[s.__acId] = used + 1;
      return s;
    }
    return null;
  }

  // ------------------------------------------------------- the socket channel
  // Measured in spike-sockets.html (four runs, 60/60 pulses each): a pulse aimed
  // at one of the peer's sockets lands on that socket, and the receiver reads
  // *which* of its own sockets it landed on from candidate-pair.localCandidateId
  // (the local half of the pair, which pollReader already records as `port`).
  // So the symbol is the destination socket - not the source port the old grid
  // read, which is the sender's coin flip (FINDINGS section 11a: 55/39 and
  // 89/85) and corrupted half of every payload.
  //
  // The read is by construction, not by timing: no clock, no slot boundary, no
  // cluster.  One pulse, one symbol.  The price is that every pulse needs a
  // fresh connection (E4 in section 27: re-adding a known candidate to a known
  // connection is a no-op), so the payload's cost is connections, and the
  // columns per pulse are chosen to fit the 500-connection lifetime cap.
  var SYM = { t0: 0, cols: [], perPulse: 0, pulses: [], seen: {}, settled: 0 };
  // The socket channel is the carrier unless a run is explicitly asked for the
  // legacy grid (window.__eaglerAC.start({ socket: false })).
  var useSocketChannel = true;
  var SYM_SETTLE_MS = 250;      // a pulse is read this long after the last one
  var SYM_PULSE_GAP_MS = 80;    // ICE pacing: one pulse per tick
  // A pulse connection only has to live until the peer's reader has sampled its
  // arrival (measured median 37 ms, p90 61 ms, against a ~53 ms report lattice).
  // Leaving all 80 open slowed the peer's reader polls to 4 s and starved the
  // probe's own stats reads (sym-run1/2.txt): a reader is a live participant, so
  // every open pulse connection is a pair it must answer.  A 700 ms lifetime at
  // an 80 ms gap keeps ~9 in flight instead of ~55.
  var SYM_CLOSE_AFTER_MS = 700;

  // Bits per pulse = floor(log2(columns)), so the symbol always indexes a real
  // column and the alphabet is a power of two (a symbol outside it would aim at
  // an undefined port).  Six sockets -> 2 bits, ten -> 3, sixteen -> 4.
  function symPerPulse(K) {
    if (K < 2) return 0;
    var b = 0, v = 1;
    while (v * 2 <= K) { v *= 2; b++; }
    return b;
  }

  function transmitSocket() {
    var gen = ++exchange.gen;
    var payload = buildPayload();
    var cols = SYM.cols.slice();
    var perPulse = symPerPulse(cols.length);
    if (!perPulse) {
      log("grid", "no peer socket to aim at (" + cols.length + " column(s))");
      return Promise.resolve(false);
    }
    var n = Math.ceil(payload.bits.length / perPulse);
    SYM.t0 = now();
    SYM.cols = cols;
    SYM.perPulse = perPulse;
    SYM.pulses = [];
    SYM.seen = Object.create(null);
    log("sym", "transmitting " + payload.bits.length + " bits as " + n + " pulse(s) x "
      + perPulse + " bit(s) over " + cols.length + " peer socket(s)");
    var i = 0;
    function tick() {
      if (i >= n || !running || state === "connected" || gen !== exchange.gen) {
        log("sym", "transmission done: " + SYM.pulses.length + " pulse(s) in "
          + (now() - SYM.t0) + " ms");
        return Promise.resolve(SYM.pulses.length);
      }
      // One pulse per tick: arrivals are read back in time order, so two pulses
      // in flight at once could swap order and corrupt two symbols at once.
      var idx = i * perPulse;
      var sym = 0;
      for (var b = 0; b < perPulse; b++) sym = (sym << 1) | (payload.bits[idx + b] ? 1 : 0);
      var target = cols[sym];
      var rec = { i: i, sym: sym, target: target, at: 0, pc: null };
      i++;
      return makeSharedPc().then(function (pc) {
        rec.pc = pc;
        rec.at = now();
        SYM.pulses.push(rec);
        return fire(pc, peerAddress, target);
      }).then(function () {
        window.setTimeout(function () { closePc(rec.pc); }, SYM_CLOSE_AFTER_MS);
        // Close the previous pulse before opening the next one: measured in
        // sym-run1/2.txt, ~55 open pulse connections made every stats pass - the
        // reader polls *and* the probe's own socket readback - take 2-4 s.
        return sleep(SYM_PULSE_GAP_MS);
      }).then(tick);
    }
    return tick();
  }

  // The first arrival for each pulse, keyed by the column it landed on and
  // deduplicated by the source port that carried it (a pulse connection owns two
  // ports, and only one of them carries the check).
  function collectSocketArrivals() {
    var out = [];
    for (var i = 0; i < arrivals.length; i++) {
      var a = arrivals[i];
      if (a.at < SYM.t0) continue;
      var key = a.port + "/" + a.source;
      if (SYM.seen[key]) continue;
      SYM.seen[key] = true;
      out.push(a);
    }
    return out;
  }

  function tryDecodeSocket() {
    if (!SYM.cols.length || !SYM.pulses.length) return null;
    if (now() - lastArrivalAt < SYM_SETTLE_MS) return null;
    // One arrival per pulse, in time order: the earliest unclaimed arrival at or
    // after each pulse's fire time.  A pulse whose arrival has not been sampled
    // yet contributes nothing and the read is simply tried again on the next
    // tick - the settle window above is what keeps that from cutting a pulse off.
    var seen = collectSocketArrivals().sort(function (a, b) { return a.at - b.at; });
    var bits = [];
    var claimed = Object.create(null);
    var read = 0;
    SYM.settled = seen.length;
    for (var i = 0; i < SYM.pulses.length; i++) {
      var p = SYM.pulses[i];
      var col = -1;
      for (var j = 0; j < seen.length; j++) {
        var a = seen[j];
        if (claimed[j]) continue;
        if (a.at < p.at) continue;
        claimed[j] = true;
        col = SYM.cols.indexOf(a.port);
        break;
      }
      if (col < 0) {
        // No arrival attributed: fill zeros, which the CRC will reject.
        for (var z = 0; z < SYM.perPulse; z++) bits.push(0);
        continue;
      }
      read++;
      for (var b = SYM.perPulse - 1; b >= 0; b--) bits.push((col >> b) & 1);
    }
    if (read && read !== SYM.pulses.length) {
      logThrottled("sym", "read " + read + " of " + SYM.pulses.length + " pulse(s) so far");
    }
    var parsed = parsePayload(bits);
    if (parsed) log("sym", "payload decoded from " + SYM.pulses.length + " socket pulse(s)");
    return parsed;
  }

  function transmit() {
    var gen = ++exchange.gen;
    var payload = buildPayload();
    var K = peerTargets.length;
    if (!K) {
      log("grid", "no peer socket to aim at");
      return Promise.resolve(false);
    }
    var perRound = SYMBOL_CLASSES * K;
    var rounds = Math.ceil(payload.bits.length / perRound);
    var copies = exchangeCopies || COPY_COUNT;
    log("grid", "transmitting " + payload.bits.length + " bits as " + rounds + " round(s) x "
      + copies + " copies over " + K + " peer socket(s) x " + SYMBOL_CLASSES + " classes");

    var copy = 0;
    // Fresh pairs only: a pair that has already carried a check exists at the
    // ICE layer, and firing it again creates no candidate - fire() enforces
    // that.  Each copy therefore starts its scan one source further along every
    // class bucket, so a cell that needs a second or third attempt is carried by
    // a source the earlier copies did not spend on that column.
    function rotateBuckets() {
      for (var c = 0; c < pulsePool.length; c++) {
        var bucket = pulsePool[c];
        if (bucket && bucket.length > 1) bucket.push(bucket.shift());
      }
    }
    function listRound() {
      var jobs = [];
      var used = Object.create(null);
      for (var t = 0; t < K; t++) {
        for (var shot = 0; shot < LIST_SHOTS; shot++) {
          var s = takeSource(shot * 5 % SYMBOL_CLASSES, t, used)
            || takeSource(((t + shot) * 3) % SYMBOL_CLASSES, t, used);
          if (s) jobs.push(fire(s.pc, peerAddress, peerTargets[t]));
        }
      }
      return Promise.all(jobs);
    }

    function runRound(r, fired) {
      if (r >= rounds || !running || state === "connected" || gen !== exchange.gen) {
        log("grid", "copy " + copy + "/" + copies + ": " + fired + " pulses");
        return Promise.resolve(fired);
      }
      roundCursor++;
      var start = r * perRound;
      var jobs = [];
      var missed = 0;
      var roundUsed = Object.create(null);
      for (var b = 0; b < perRound; b++) {
        var index = start + b;
        if (index >= payload.bits.length) break;
        if (!payload.bits[index]) continue;
        var cls = index % SYMBOL_CLASSES;
        var col = Math.floor(index / SYMBOL_CLASSES) % K;
        var src = takeSource(cls, col, roundUsed);
        if (!src) { missed++; continue; }
        jobs.push({ pc: src.pc, port: peerTargets[col] });
      }
      // Several cells of one source in the same round are fired together and the
      // ICE pacing spreads them a beat apart; the round is 8 s, so they land in
      // the same burst.
      // Fired in small ticks: a hundred and sixty candidate additions in one
      // turn is what starves the stats call that dates the answer.
      var done = 0;
      function tick() {
        var slice = [];
        for (var k = 0; k < BURST_FIRES && done < jobs.length; k++, done++) slice.push(jobs[done]);
        var sub = slice.map(function (j) { return fire(j.pc, peerAddress, j.port); });
        if (done >= jobs.length) return Promise.all(sub);
        return Promise.all(sub).then(function () { return sleep(BURST_GAP_MS); }).then(tick);
      }
      return tick().then(function () {
        if (missed) log("grid", "copy " + copy + " round " + (r + 1) + ": " + (fired + jobs.length)
          + " pulses, " + missed + " unfilled");
        return sleep(ROUND_STEP_MS).then(function () { return runRound(r + 1, fired + jobs.length); });
      });
    }

    function nextCopy() {
      if (copy >= copies || !running || state === "connected" || gen !== exchange.gen) {
        log("grid", "transmission done");
        return Promise.resolve(true);
      }
      copy++;
      rotateBuckets();
      return listRound().then(function () {
        log("grid", "copy " + copy + ": list round fired at " + K + " socket(s)");
        return sleep(LIST_GAP_MS);
      }).then(function () {
        return runRound(0, 0);
      }).then(function () { return sleep(COPY_GAP_MS); }).then(nextCopy);
    }
    return nextCopy();
  }

  function clusterArrivals() {
    var list = arrivals.slice().sort(function (a, b) { return a.at - b.at; });
    var clusters = [];
    var cur = [];
    for (var i = 0; i < list.length; i++) {
      if (cur.length && list[i].at - cur[cur.length - 1].at > CLUSTER_GAP_MS) {
        clusters.push(cur);
        cur = [];
      }
      cur.push(list[i]);
    }
    if (cur.length) clusters.push(cur);
    return clusters;
  }

  // A list round is one burst with LIST_SHOTS pulses on every socket this copy
  // will use; sockets heard two or three times are the sender's target list, in
  // sorted order - the same order the sender indexes its cells in.
  function columnsFromListRound(cluster) {
    var count = Object.create(null);
    for (var i = 0; i < cluster.length; i++) count[cluster[i].port] = (count[cluster[i].port] || 0) + 1;
    var ports = [];
    for (var p in count) if (count[p] >= LIST_MIN_SHOTS) ports.push(Number(p));
    return ports.sort(function (a, b) { return a - b; });
  }

  function cellsOfCluster(cluster, columns) {
    var perRound = SYMBOL_CLASSES * columns.length;
    var bits = new Array(perRound);
    for (var i = 0; i < bits.length; i++) bits[i] = 0;
    for (var j = 0; j < cluster.length; j++) {
      var a = cluster[j];
      var col = columns.indexOf(a.port);
      if (col < 0) continue;
      var cls = a.source % SYMBOL_CLASSES;
      bits[cls + SYMBOL_CLASSES * col] = 1;
    }
    return bits;
  }

  var bestFilled = 0;
  // Cross-copy evidence, keyed by the column list and the position of the burst
  // inside its copy (its phase).  Every copy sends the same payload on the same
  // schedule, so a cell that a burst dropped in copy 1 is filled by the same
  // phase in copy 2.  Keying on the phase also keeps a list round - which is at
  // phase -1 in its own copy - from ever being read as payload: measured in
  // grid-run11.log, one copy carries ~154 pulses and the receiver reads ~120-132
  // of them, so a single copy is always ~20 bits short and no copy count helps
  // unless the copies are merged.
  var cellEvidence = Object.create(null);      // columnsKey -> [phase -> bits]

  function accumulate(columnsKey, phase, columns, cluster) {
    if (phase < 0 || phase > 8) return;
    var list = cellEvidence[columnsKey];
    if (!list) { list = cellEvidence[columnsKey] = []; }
    var bits = list[phase];
    if (!bits) {
      bits = list[phase] = new Array(SYMBOL_CLASSES * columns.length);
      for (var i = 0; i < bits.length; i++) bits[i] = 0;
    }
    var cell = cellsOfCluster(cluster, columns);
    for (var q = 0; q < cell.length; q++) if (cell[q]) bits[q] = 1;
  }

  function unionBits(columnsKey, rounds, columns) {
    var list = cellEvidence[columnsKey];
    var bits = [];
    var perRound = SYMBOL_CLASSES * columns.length;
    for (var i = 0; i < perRound * rounds; i++) bits.push(0);
    if (!list) return bits;
    for (var r = 0; r < rounds; r++) {
      var phase = list[r];
      if (!phase) continue;
      for (var q = 0; q < perRound; q++) if (phase[q]) bits[r * perRound + q] = 1;
    }
    return bits;
  }

  function tryDecode() {
    if (now() - lastDecodeAt < 200) return null;
    lastDecodeAt = now();
    if (arrivals.length < 6) return null;
    var clusters = clusterArrivals();
    if (clusters.length < 2) return null;
    var mine = gridSockets().map(function (s) { return s.port; });

    // Each copy starts with its own list round, so every burst is a candidate
    // list round and the rounds behind it are the copy that follows it.
    for (var li = 0; li < clusters.length - 1; li++) {
      var columns = columnsFromListRound(clusters[li]);
      if (columns.length < 2) continue;
      var perRound = SYMBOL_CLASSES * columns.length;
      var rounds = Math.ceil(PAYLOAD_BITS / perRound);
      if (li + rounds >= clusters.length) break;      // this copy is still arriving
      for (var start = li + 1; start + rounds <= clusters.length; start++) {
        var bits = [];
        var filled = 0;
        for (var r = 0; r < rounds; r++) {
          var cell = cellsOfCluster(clusters[start + r], columns);
          for (var q = 0; q < cell.length; q++) if (cell[q]) filled++;
          bits = bits.concat(cell);
          // Only the canonical window (the one right after this list round) is
          // accumulated: a later window would attribute the next copy's bursts
          // to the wrong phases.  Each copy's own list round contributes its own
          // canonical window, so the union still spans every copy.
          if (start === li + 1) {
            accumulate(columns.join(","), r, columns, clusters[start + r]);
          }
        }
        var union = unionBits(columns.join(","), rounds, columns);
        var unionFilled = 0;
        for (var u = 0; u < union.length; u++) if (union[u]) unionFilled++;
        if (unionFilled > filled) {
          bits = union;
          filled = unionFilled;
        }
        // Only report the read when it improves: repeated lines for the same
        // shape hide how far a decode got.
        if (filled > bestFilled) {
          bestFilled = filled;
          log("grid", "read improved to " + filled + " of " + bits.length
            + " cells (burst " + start + ", " + columns.length + " column(s), "
            + arrivals.length + " arrival(s) seen)");
        }
        var parsed = parsePayload(bits);
        if (parsed) {
          log("grid", "payload decoded: columns " + columns.join(",") + ", rounds from burst " + start);
          return parsed;
        }
      }
      // A list pulse can be lost, and then the sender's list has one socket the
      // list round did not show: the sender's list is always a subset of this
      // receiver's own in-window sockets, so every one-element removal is tried.
      var hypotheses = [mine];
      for (var drop = 0; drop < mine.length; drop++) {
        hypotheses.push(mine.slice(0, drop).concat(mine.slice(drop + 1)));
      }
      for (var h = 0; h < hypotheses.length; h++) {
        var hyp = hypotheses[h];
        if (hyp.length < 2) continue;
        var perRound2 = SYMBOL_CLASSES * hyp.length;
        var rounds2 = Math.ceil(PAYLOAD_BITS / perRound2);
        if (li + rounds2 >= clusters.length) break;
        for (var start2 = li + 1; start2 + rounds2 <= clusters.length; start2++) {
          var bits2 = [];
          for (var r2 = 0; r2 < rounds2; r2++) bits2 = bits2.concat(cellsOfCluster(clusters[start2 + r2], hyp));
          var parsed2 = parsePayload(bits2);
          if (parsed2) {
            log("grid", "payload decoded against our own socket list: " + hyp.join(","));
            return parsed2;
          }
        }
      }
    }
    return null;
  }

  // ------------------------------------------------------ phase 3: the link
  function finishFinal(peerIp, peerPortNumber, peerFingerprint, isClient) {
    if (!finalPc || finalRemoteApplied) return Promise.resolve(null);
    finalRemoteApplied = true;
    var answer = forgeAnswer(finalPc.localDescription.sdp, {
      fingerprint: peerFingerprint,
      setup: isClient ? "passive" : "active"
    });
    log("final", "DTLS " + (isClient ? "client" : "server") + " - peer port " + peerPortNumber);
    return finalPc.setRemoteDescription({ type: "answer", sdp: answer }).then(function () {
      if (peerIp && peerPortNumber) {
        var tries = 0;
        (function poke() {
          if (!running || tries > 12) return;
          tries++;
          fire(finalPc, peerIp, peerPortNumber).then(function () {
            if (finalChannel && finalChannel.readyState === "open") return;
            if (finalPc.connectionState === "connected") return;
            window.setTimeout(poke, 900);
          });
        })();
      }
      return new Promise(function (resolve) {
        var settled = false;
        function done(ok) {
          if (settled) return;
          settled = true;
          resolve(ok ? finalChannel : null);
        }
        if (finalChannel) {
          finalChannel.addEventListener("open", function () {
            log("connect", "data channel open");
            done(true);
          });
          finalChannel.addEventListener("message", function (ev) {
            emit({ type: "message", data: ev.data });
          });
        }
        finalPc.addEventListener("datachannel", function (e) {
          log("connect", "peer opened a data channel");
          var ch = e.channel;
          ch.addEventListener("open", function () { done(true); });
          ch.addEventListener("message", function (ev) { emit({ type: "message", data: ev.data }); });
        });
        finalPc.addEventListener("connectionstatechange", function () {
          log("final", "connection state " + finalPc.connectionState);
          if (finalPc.connectionState === "failed") done(false);
        });
        window.setTimeout(function () { done(false); }, CONNECT_TIMEOUT_MS);
      });
    }, function (err) {
      log("error", "remote description rejected: " + (err && err.message));
      return null;
    });
  }

  function beginHandshake(parsed) {
    if (state === "connected" || exchange.done) return;
    exchange.done = true;
    state = "linking";
    connectedPeer = {
      id: parsed.id.toString(16),
      address: peerAddress,
      port: parsed.port,
      state: "connecting"
    };
    peers[connectedPeer.id] = connectedPeer;
    emit({ type: "peer-found", peer: connectedPeer });
    var isClient = selfId < parsed.id;
    function afterFinal(channel) {
      if (!channel) {
        if (state !== "connected") {
          state = "failed";
          log("error", "the final connection did not come up");
          emit({ type: "failed", reason: "final" });
        }
        return;
      }
      connectedPeer.state = "connected";
      state = "connected";
      log("connect", "connected over a direct data channel");
      emit({ type: "connected", peer: connectedPeer });
    }
    finishFinal(peerAddress, parsed.port, parsed.fingerprint, isClient).then(afterFinal, afterFinal);
  }

  // --------------------------------------------------------- orchestration
  function maybeExchange() {
    if (exchange.active || !ready || !running || state === "connected" || state === "failed") return;
    if (!peerAddress || !peerTargets.length || readers.length < 2) return;
    var socketReady = useSocketChannel && SYM.cols.length >= 2;
    if (!socketReady && now() - lastArrivalAt < QUIET_MS) return;  // legacy carrier: the peer's probe is still arriving
    exchange.active = true;
    exchange.startedAt = now();
    attempt++;
    log("grid", "window quiet - exchange attempt " + attempt + " of " + MAX_ATTEMPTS
      + " (" + gridSockets().length + " socket(s) of ours, " + peerTargets.length + " of the peer's)");
    // The socket channel fires its pulses from connections made on demand, so
    // the 128-source pool the old grid needed is not just unnecessary - it is
    // what made every getStats() slow (section 22: a reader's poll took 4-8 s
    // with ~230 live connections and 0 ms with ~135).  Only build the pool when
    // there is no socket alphabet to use.
    var havePool = false;
    for (var bi = 0; bi < pulsePool.length; bi++) if (pulsePool[bi].length) havePool = true;
    var labelled = (havePool || SYM.cols.length >= 2) ? Promise.resolve(1) : censusSources();
    var grid = gridSockets();
    var ownMin = grid.length ? grid[0].port : 0;      // gridSockets() is sorted
    var peerMin = peerTargets.length ? peerTargets[0] : 0;  // probe result is sorted
    var amFirst = !peerMin || (ownMin && ownMin < peerMin);
    // The stagger and the quiet window exist for the legacy carrier, whose slots
    // need the page to itself.  The socket channel reads by construction, so it
    // needs neither: both peers may pulse at once, because a receiver only ever
    // sees the peer's pulses on its own readers (sym-run1.txt: waiting for quiet
    // meant neither side ever transmitted).
    var stagger = (useSocketChannel && SYM.cols.length >= 2)
      ? Promise.resolve(true)
      : (amFirst ? Promise.resolve(true) : sleep(STAGGER_MS));
    if (!amFirst && !(useSocketChannel && SYM.cols.length >= 2)) {
      log("grid", "holding back " + Math.round(STAGGER_MS / 1000)
        + "s so the peer's transmission has the page to itself (own " + ownMin
        + " vs peer " + peerMin + ")");
    }
    labelled.then(function () {

      return stagger;
    }).then(transmitSocket).then(function () {
      return waitForPayload(EXCHANGE_BUDGET_MS);
    }).then(function (parsed) {
      if (parsed) return beginHandshake(parsed);
      if (state === "connected" || !running || exchange.done) return null;
      exchange.active = false;
      if (attempt < MAX_ATTEMPTS) {
        log("grid", "no payload decoded on attempt " + attempt + "; retrying");
        return sleep(1500);
      }
      state = "failed";
      log("error", "no payload decoded after " + attempt + " attempts");
      emit({ type: "failed", reason: "exchange" });
      return null;
    }).catch(function (err) {
      log("error", "exchange: " + String(err && err.message ? err.message : err));
      exchange.active = false;
    });
  }

  function waitForPayload(budgetMs) {
    var deadline = now() + budgetMs;
    return (function poll() {
      if (!running || state === "connected" || exchange.done) return Promise.resolve(null);
      // The socket channel first: it is the one that reads by construction.  The
      // old grid decoder stays as a fallback so a peer still running the old
      // build can be read.
      var parsed = tryDecodeSocket() || tryDecode();
      if (parsed) return Promise.resolve(parsed);
      if (now() > deadline) return Promise.resolve(null);
      return sleep(150).then(poll);
    })();
  }

  // --------------------------------------------------------------- lifecycle
  // Close everything the exchange does not use.  The probe and the sweep are
  // over by here, so the 40 scanners are dead weight, and only the balanced
  // 128 sources a payload needs are kept; the rest are closed.  This is the
  // change that makes the read cheap again: the same page that reported a
  // reader's stats 4-8 s late with ~230 live connections reports in tens of
  // milliseconds with ~135.
  function trimPool() {
    var perClass = Infinity, withSources = 0;
    for (var ci = 0; ci < SYMBOL_CLASSES; ci++) {
      var b0 = pulsePool[ci];
      if (!b0 || !b0.length) continue;
      withSources++;
      perClass = Math.min(perClass, b0.length);
    }
    if (!isFinite(perClass)) perClass = 0;
    var quota = Math.min(PAYLOAD_SOURCES_PER_CLASS, perClass);
    var want = quota * withSources;
    var kept = [];
    for (var round = 0; round < quota && kept.length < want; round++) {
      var added = 0;
      for (var c = 0; c < SYMBOL_CLASSES && kept.length < want; c++) {
        var bucket = pulsePool[c];
        if (!bucket || bucket.length <= round) continue;
        var s = bucket[round];
        if (s.__acKept) continue;
        s.__acKept = true;
        kept.push(s);
        added++;
      }
      if (!added) break;
    }
    var closed = 0;
    sources.forEach(function (s) {
      if (!s.__acKept) { closePc(s.pc); closed++; }
    });
    scanners.forEach(closePc);
    var scannerCount = scanners.length;
    scanners = [];
    sources = kept;
    var pool = [];
    for (var k = 0; k < SYMBOL_CLASSES; k++) pool.push([]);
    kept.forEach(function (s) { if (s.class >= 0) pool[s.class].push(s); });
    pulsePool = pool;
    // Make the payload fit the pool that actually exists, rather than firing a
    // fixed three copies and hoping.  One copy of the payload lights every set
    // cell, and a (class, column) group holds exactly `rounds` cells - one per
    // round - each of which needs its own source, because a (source, column)
    // pair can be spent only once (fire() refuses a pair that already exists).
    // A class therefore needs rounds x COPY_COUNT sources to carry three full
    // copies, i.e. three sources per round it must fill in the same column.
    // When the lottery cannot supply that many, fire the number of full copies
    // the pool can actually fill: a short third copy carries no cell the first
    // two did not already carry, so it is worse than useless (grid-run16.log:
    // 160/85/39 pulses against a pool of three per class and three rounds).
    var k = Math.max(1, peerTargets.length);
    exchangeRounds = Math.max(1, Math.ceil(PAYLOAD_BITS / (SYMBOL_CLASSES * k)));
    exchangeCopies = Math.max(1, Math.min(COPY_COUNT,
      Math.floor(quota / exchangeRounds)));
    log("census", "pool trimmed for the exchange: " + kept.length + " source(s) kept, "
      + closed + " + " + scannerCount + " connection(s) closed; quota " + quota
      + " per class; classes " + pool.map(function (b) { return b.length; }).join("/")
      + "; K " + k + " -> " + exchangeRounds + " round(s), pool carries "
      + exchangeCopies + " full copy/copies (three copies need "
      + (COPY_COUNT * exchangeRounds) + " per class)");
  }

  // Everything both roles need before the grid: our own columns (the sockets our
  // readers answer from), the peer's sockets (probed now, so live), and our
  // pulse sources' classes.  Only this chain sets `ready`, and only the exchange
  // runs the census again for a retry.
  // The read and the census both go through getStats(), and getStats() is the
  // one expensive call this engine makes.  Run 14 showed the cost of leaving
  // dead weight alive across them: with the 32 scanners and the 6 locate
  // connections still open, the census of 150 candidates never finished at all
  // and the exchange never started.  Everything the probe needed goes away the
  // moment the probe is done, so the census and the read run against readers and
  // sources only.
  function releaseProbes() {
    var n = scanners.length + locatePcs.length;
    if (!n) return;
    scanners.forEach(closePc);
    locatePcs.forEach(closePc);
    scanners = [];
    locatePcs = [];
    log("census", "closed " + n + " probe connection(s) before the census: the read only needs readers and sources");
  }

  function prepare() {
    preparing = true;
    return censusColumns().then(function () {
      return probePeerPorts(peerAddress);
    }).then(function () {
      // The socket channel's alphabet: the peer's sockets, sorted (probePeerPorts
      // sorts), which is the same list the peer derives from gridSockets().
      SYM.cols = peerTargets.slice();
      if (peerTargets.length < 2) log("ports", "only " + peerTargets.length
        + " peer socket(s); the socket channel needs at least two");
      releaseProbes();
      if (useSocketChannel && SYM.cols.length >= 2) {
        // Socket channel: the pulse connections are made on demand, so the page
        // keeps only its readers.  That is what makes the read cheap - the pool
        // is what the old grid needed to pay for its pulses.
        log("census", "socket channel ready: " + gridSockets().length + " own socket(s), "
          + SYM.cols.length + " peer socket(s), " + symPerPulse(SYM.cols.length)
          + " bit(s) per pulse; no source pool (" + sources.length + " connection(s) released)");
        sources.forEach(closePc);
        sources = [];
        pulsePool = [];
        exchangeRounds = Math.ceil(PAYLOAD_BITS / symPerPulse(SYM.cols.length));
        exchangeCopies = 1;
        ready = true;
        return null;
      }
      return censusSources().then(function () {
        trimPool();
        ready = true;
        log("census", "ready: " + gridSockets().length + " own socket(s), "
          + peerTargets.length + " peer socket(s), " + sources.length + " source(s) live");
        return null;
      });
    }).then(function (result) {
      preparing = false;
      return result;
    }, function (err) {
      preparing = false;
      throw err;
    });
  }

  function finalPortOf() {
    if (!finalPc) return Promise.resolve(0);
    return waitForPorts(finalPc).then(function (ports) {
      finalPort = ports.length ? ports[0] : 0;
      log("final", "final connection on port " + (finalPort || "?"));
      return finalPort;
    });
  }

  function start(options) {
    if (options && options.socket === false) useSocketChannel = false;
    else if (options && options.socket === true) useSocketChannel = true;
    if (running) return Promise.resolve(state);
    var opts = options || {};
    if (opts.name) selfName = String(opts.name).slice(0, 24);
    if (opts.stun) stunServers = opts.stun;
    running = true;
    startedAt = now();
    state = "listening";
    if (!codeMode) {
      clearFallbackTimer();
      fallbackTimer = window.setTimeout(maybeFallback, FALLBACK_AFTER_MS);
    }
    log("boot", "peer id " + selfId + " (" + selfName + ")"
      + (codeMode ? " [code mode]" : " [shared credentials (AC24)]"));

    return RTCPeerConnection.generateCertificate({ name: "ECDSA", namedCurve: "P-256" })
      .catch(function () { return null; })
      .then(function (cert) {
        certificate = cert;
        if (cert && typeof cert.getFingerprints === "function") {
          var fps = cert.getFingerprints();
          if (fps && fps.length) fingerprint = fps[0].value;
        }
        if (fingerprint) {
          log("boot", "certificate " + fingerprint.slice(0, 17) + "...");
          return null;
        }
        return makeSharedPc().then(function (pc) {
          var m = /a=fingerprint:sha-256 ([0-9A-Fa-f:]+)/.exec(pc.localDescription.sdp || "");
          fingerprint = m ? m[1] : "";
          closePc(pc);
          log("boot", "certificate " + (fingerprint ? fingerprint.slice(0, 17) + "..." : "unavailable"));
        });
      })
      .then(function () {
        if (!fingerprint) throw new Error("no DTLS fingerprint available");
        return buildReaders();
      })
      .then(function () {
        // The socket channel's pulse connections are made on demand, and the
        // 128-source pool is exactly what made every getStats() slow in section
        // 22 (4-8 s per reader poll with ~230 live connections, 0 ms with ~135).
        // The pool is only built for the legacy carrier, which needs it.
        return Promise.all([buildFinalPc(), buildScanners(),
          useSocketChannel ? Promise.resolve(null) : buildSources()]);
      })
      .then(function () {
        return finalPortOf();
      })
      .then(function () {
        if (!readers.length) log("readers", "no reader socket in the window: this page can find, not be found");
        watchLoop();
        state = "scanning";
        return scanTargets();
      })
      .then(function (peer) {
        if (peer) {
          role = "caller";
          peerAddress = peer.address;
          peers.pending = { id: "pending", address: peer.address, port: peer.port, state: "linking" };
          emit({ type: "peer-found", peer: peers.pending });
          state = "linking";
          return prepare();
        }
        if (!peerPort) {
          log("scan", "no peer found (auto-connect unavailable here, manual codes still work)");
          if (state !== "connected") state = "linking";
          return null;
        }
        role = "responder";
        return findAddressByPort(peerPort).then(function (address) {
          if (!address) { log("locate", "the peer's address was not found"); return null; }
          peerAddress = address;
          state = "linking";
          return prepare();
        });
      })
      .catch(function (err) {
        state = "failed";
        log("error", String(err && err.message ? err.message : err));
        emit({ type: "failed", reason: String(err) });
      });
  }

  function stop() {
    running = false;
    state = "stopped";
    clearFallbackTimer();
    readers.forEach(function (r) { closePc(r.pc); });
    sources.forEach(function (s) { closePc(s.pc); });
    scanners.forEach(closePc);
    locatePcs.forEach(closePc);
    if (finalPc) closePc(finalPc);
    readers = []; sources = []; scanners = []; locatePcs = [];
    pulsePool = []; arrivals = [];
    finalPc = null; finalChannel = null;
    exchange.active = false;
    statusLine = "stopped";
  }

  var AC = {
    version: VERSION,
    start: start,
    stop: stop,
    state: function () { return state; },
    phase: function () { return currentPhase; },
    status: function () { return statusLine; },
    self: function () {
      return {
        id: selfId, name: selfName, fingerprint: fingerprint, finalPort: finalPort,
        ports: gridSockets().map(function (s) { return s.port; }), peerAddress: peerAddress, role: role
      };
    },
    peers: function () {
      var out = [];
      for (var k in peers) out.push(peers[k]);
      return out;
    },
    diagnosis: function () {
      return {
        phase: currentPhase, role: role, peerAddress: peerAddress, peerPort: peerPort,
        peerTargets: peerTargets, ownSockets: gridSockets().map(function (s) { return s.port; }),
        arrivals: arrivals.length, sources: sources.length, attempt: attempt,
        exchangeActive: exchange.active, exchangeDone: exchange.done,
        // the socket channel's own numbers: columns (the peer's sockets), bits
        // per pulse, pulses fired, and how many of them the receiver has read
        sym: {
          columns: SYM.cols.length, bitsPerPulse: SYM.perPulse,
          pulses: SYM.pulses.length, settled: SYM.settled,
          arrivals: collectSocketArrivals().length
        }
      };
    },
    onEvent: function (cb) { if (typeof cb === "function") subs.push(cb); },
    logs: function () { return logs; },
    send: function (data) {
      if (finalChannel && finalChannel.readyState === "open") {
        try { finalChannel.send(data); return true; } catch (e) { /* ignore */ }
      }
      return false;
    },
    code: function () { return currentCode; },
    codeMode: function () { return codeMode; },
    fallback: function () {
      return {
        active: fallbackRaised && state !== "connected",
        raised: fallbackRaised, code: currentCode, codeMode: codeMode,
        timeoutMs: FALLBACK_AFTER_MS
      };
    },
    newCode: function () {
      var code = makeCode();
      startCode(code);
      return code;
    },
    enterCode: function (code) {
      if (!sanitizeCode(code)) return false;
      startCode(code);
      return true;
    },
    codeCreds: function (code) {
      var clean = sanitizeCode(code);
      return clean ? deriveCreds(clean) : Promise.resolve(null);
    },
    snapshotJson: function () {
      var list = AC.peers().map(function (p) {
        return { id: p.id, address: p.address, port: p.port, state: p.state };
      });
      return JSON.stringify({
        version: VERSION, state: state, phase: currentPhase, status: statusLine,
        code: currentCode, codeMode: codeMode, timeoutMs: FALLBACK_AFTER_MS,
        fallback: AC.fallback(), running: running, self: AC.self(), peers: list,
        lastError: lastError
      });
    },
    pollEventsJson: function () {
      var out = "[" + eventQueue.join(",") + "]";
      eventQueue.length = 0;
      return out;
    },
    sendJson: function (text) { return AC.send(text); },
    startJson: function (optsJson) {
      var opts = null;
      try { opts = optsJson ? JSON.parse(optsJson) : null; } catch (e) { opts = null; }
      start(opts || {});
      return true;
    }
  };
  window.__eaglerAC = AC;
})();

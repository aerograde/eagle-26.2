/*
 * autoconnect.js - serverless automatic LAN peer discovery and connection.
 *
 * No server, no codes, no external service except a public STUN server the real
 * connection may fall back to.  Everything else is plain WebRTC on the local
 * network, built on five browser facts measured directly (tmp/autoconnect/):
 *
 *  1. Two ICE agents with equal ice-ufrag/ice-pwd validate each other's
 *     connectivity checks, so a check sent to a listening peer's address:port
 *     succeeds with no signalling.  Both players rewrite their local
 *     description's credentials to the same constants and apply a forged remote
 *     answer (same credentials, placeholder fingerprint, no candidates) because
 *     addIceCandidate requires a remote description.
 *  2. The OS picks the ICE port, so a page owns a socket in a given window only
 *     by retrying: ~0.39% of sockets land in a 64-port window.  The narrow
 *     window is what the subnet sweep covers (each port costs 254 checks); the
 *     wide one is what a peer probes on our address once it knows it.
 *  3. Candidate addresses are redacted by the browser: only ports are readable.
 *     A check's source port IS readable, so an announce burst tells the probed
 *     peer which of the prober's sockets are live, and a payload can be aimed at
 *     ports the receiver is known to poll instead of at guessed ports.
 *  4. Chrome paces connectivity checks at ~16/s per connection and caps a page
 *     at 500 live connections with no refund on close, so the sweep runs in
 *     small rounds (2 window ports x 254 hosts) and every pool is sized inside
 *     that 500 budget.
 *  5. A DTLS fingerprint mismatch ends the transport without ever exposing the
 *     peer's certificate in getStats, so the fingerprint must travel as data.
 *
 * The DTLS fingerprint travels as pulses: one pulse is one extra connection
 * whose check lands on a specific listener port, and the receiver counts new
 * peer-reflexive candidates per slot.  What a pulse *says* is the port of the
 * connection that sent it - the receiving side reads that port exactly - so one
 * pulse carries four bits and the slot grid only has to supply position.  Four
 * verified listener ports carry four symbols per slot, the preamble is what lets
 * the receiver bind its own sockets without prior agreement, and the payload is
 * repeated so a pulse lost to the network costs one copy, not the run.
 *
 * Code fallback (the plan's second tier).  Auto-connect is one tier of two:
 * if no peer is connected after FALLBACK_AFTER_MS the module parks in state
 * "fallback" and the game asks for a 6-character code.  One player shows a
 * code, the other types it, and both derive the same ICE credentials from it
 * (SHA-256), so the phases that follow are the same ones with synchronized,
 * authenticated credentials.
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
 *   (net.lax1dude.eaglercraft.v1_8.lan.AutoConnect, the same shape as the
 *   lan-discovery bridge):
 *   snapshotJson()      -> JSON state snapshot
 *   pollEventsJson()    -> JSON array, drains the event queue
 *   sendJson(text)      -> boolean
 *   startJson(optsJson) -> boolean
 */
(function () {
  "use strict";

  var VERSION = 4;
  if (window.__eaglerAC && window.__eaglerAC.version === VERSION) {
    return; // already installed
  }

  // Shared ICE credentials.  4-256 ufrag chars ([A-Za-z0-9+/]) and 22-256 pwd
  // chars, which is what Chrome's SDP validation enforces.  The plan's
  // "AC24shared7x" is 12 characters, and Chrome rejects it with "ICE pwd must
  // be between 22 and 256 characters long" (measured), so the constant keeps
  // its prefix and is padded to the minimum length.
  var UFRAG = "AC24aa";
  var PWD = "AC24shared7xpadded000000";

  // Code fallback.  Six characters from the same alphabet the manual join
  // codes use, so there are no look-alike pairs to misread.  The plan's
  // 20-second budget is what triggers the prompt.
  var CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  var CODE_LEN = 6;
  var FALLBACK_AFTER_MS = 20000;
  // Phase names, for the status line and the game UI.  The plan's phases are
  // kept as labels; the engine's own log tags map onto them.
  var PHASE_NAMES = {
    boot: "phase 0 - connection pool",
    listeners: "phase 0 - connection pool",
    beacons: "phase 0 - connection pool",
    scan: "phase 1 - discovery",
    locate: "phase 1 - discovery",
    ports: "phase 1.5 - ports",
    wake: "phase 1.5 - ports",
    announce: "phase 1.5 - ports",
    pulse: "phase 2 - fingerprint exchange",
    decode: "phase 2 - fingerprint exchange",
    final: "phase 3 - final connection",
    connect: "phase 3 - final connection",
    code: "code fallback",
    error: "error"
  };

  // Discovery window: the same 512 ports the pulse channel uses, so every
  // listener a page owns is discoverable, not just the ones that land in a
  // token 48-port slice.  Coverage is bought by sweeping one or two host
  // addresses at a time, which keeps each connection's pair count low.
  var W_START = 50000;
  var W_COUNT = 512;
  var W_END = W_START + W_COUNT - 1;

  // Pulse window: what a peer probes on our address to find every listener.
  var P_START = 50000;
  var P_COUNT = 512;
  var P_END = P_START + P_COUNT - 1;

  // Census port: outside the pulse window, so no socket of this exchange lives
  // there and a check at it cannot reach the peer's pulse decode.  It exists to
  // ask a connection which of its two UDP sockets talks to the peer - see
  // censusSources().
  var CENSUS_PORT = P_START - 7;

  // Subnets to sweep, most common first.
  var SUBNETS = ["192.168.0.", "192.168.1."];

  // Pulse channel.  A pulse's *value* is the port of the connection that sent
  // it: the receiving side reads a check's source port exactly (6/6 in the
  // spike), and the sender picks which of its connections fires, so one pulse
  // carries SYMBOL_BITS bits instead of the single bit its mere presence could.
  // The slot grid therefore only has to say *when* a pulse belongs, which is
  // what the measurements support: a well-read channel matched 23 of 24 bits
  // against truth, while a source port is exact by construction.
  // Slot length, measured rather than assumed: an arrival's timestamp carries
  // the check's travel time plus the delay of the poll that noticed it (~24 ms),
  // and at 100 ms slots that put roughly half the pulses on the wrong side of a
  // boundary (tmp/autoconnect/runC.log).
  // 200 ms, with the poll loop kept as free of other work as it can be: a pulse's
  // timestamp is the poll that noticed it, so the pass cost is the clock's
  // resolution.  Measured passes: 1-9 ms with eight to twelve sockets being read
  // finely, 113-333 ms when a decode or a census ran inside the same turn.
  var SLOT_MS = 200;
  var CHANNELS = 4;
  // Preamble: a sync burst on every channel, then one staircase slot per
  // channel two slots apart, then a marker burst.  Two slots of spacing is what
  // makes the staircase readable: a pulse read one slot late then still lands on
  // a slot no other channel claims (measured with consecutive steps: one pulse
  // read a slot away made two sockets claim the same channel, and a start that
  // does not bind all four is dropped).
  var PREAMBLE_SLOTS = 10;
  var MARKER_SLOT = PREAMBLE_SLOTS - 1;        // the far end of the preamble
  var PAYLOAD_MAGIC = 0xA5;
  // magic, peer id, final port, four reply ports, DTLS fingerprint
  var PAYLOAD_BODY_BYTES = 1 + 2 + 2 + 32;   // magic, peer id, final port, fingerprint
  // CRC-16, not CRC-8: a garbled read only has to match the magic and the
  // checksum to be accepted, and with one byte that is a 1-in-256 accident.
  // Measured once (run 7): a wrong bit pattern passed magic + CRC-8, produced a
  // peer id of 0x0070 and a "final port" of 3072, and a handshake was attempted
  // against a port that never existed.  Two bytes make that a 1-in-65k one, on
  // top of the magic byte.
  var CRC_BYTES = 2;
  var PAYLOAD_BITS = (PAYLOAD_BODY_BYTES + CRC_BYTES) * 8;
  var SYMBOL_BITS = 4;                         // value a pulse's port carries
  var SYMBOL_CLASSES = 1 << SYMBOL_BITS;       // port % 16 is the symbol
  var DATA_SLOTS = Math.ceil(PAYLOAD_BITS / (CHANNELS * SYMBOL_BITS));   // 19, was 152
  // Copies are not free: they are paid for out of the *source* supply, and that
  // supply is per port class, not per page.  A pulse can only be sent by a
  // connection whose port class (port % 16) *is* the symbol it carries, so each
  // class is a bucket, and every copy spends one source out of the bucket of
  // every symbol it carries.  Measured on the last revision
  // (tmp/autoconnect/final-run1.log): ~246 sources with a known port, about 15
  // per class, against a demand of 4 x (how often that class appears in the 19
  // symbols) plus the preamble's share (~10 sources per class, 40 per channel).
  // That is more than those buckets held: 51 of 464 pulses were dropped on
  // attempt 1 (11%), then 172 and 281 as the same targets were already spent,
  // and the cells read empty were exactly the symbol cells the checksum reads.
  // Two copies need 2 x (how often the class appears) against the ~12 sources a
  // class still has, so nothing goes unsent, while the vote across the copies
  // still repairs a pulse that was late or lost in flight.
  var PAYLOAD_REPEATS = 2;
  // The sync is still the densest burst in the transmission - four shots on each
  // channel at slot 0 against one pulse per channel in a data slot - so a start
  // can be told from a data pulse by counting pulses rather than by guessing.  It
  // is also the largest consumer of the source supply, so it is kept no bigger
  // than that comparison needs: at 8 shots x 4 copies it reserved 40 sources per
  // channel and emptied the symbol buckets.  The marker closes the preamble, and
  // the eight slots between them are empty - which is also what the grid is read
  // against.
  // Three shots, not four: the sync only has to out-count a data slot (one
  // pulse per channel) to be found as a start, and the source it frees pays for
  // the staircase's second shot - a channel whose one staircase pulse is missed
  // or read late takes its twenty cells with it.
  var SYNC_SHOTS = 3;                           // per channel, at slot 0
  var MARKER_SHOTS = 1;                         // per channel, at slot MARKER_SLOT
  // The staircase promised by the preamble comment above: one pulse per channel
  // in its own slot, two slots apart (2, 4, 6, 8 for channels 0..3).  The slot a
  // socket hears is what names its channel.  Encoding the channel in the port
  // class instead was measured to be a coin flip: the same four sockets bound to
  // different channels from one tick to the next (run 12), because wake, census
  // and enumeration traffic lands on those sockets carrying every class.
  var STAIR_FIRST = 2;
  var STAIR_SHOTS = 2;                          // per channel, in its own slot
  var STAIR_LAST = STAIR_FIRST + (CHANNELS - 1) * 2;
  var PREAMBLE_SOURCES = PAYLOAD_REPEATS * (SYNC_SHOTS + MARKER_SHOTS + STAIR_SHOTS);   // per channel
  var PAYLOAD_SLOTS = (PREAMBLE_SLOTS + DATA_SLOTS) * PAYLOAD_REPEATS;

  // Connection budget: Chrome allows 500 live RTCPeerConnections per page and
  // closing one does not return budget, so these must add up:
  //   224 lottery attempts + 72 scanners + 160 pulse pool + 4 beacons
  //   + 12 enumerate + 6 locate + 1 final = 479
  var BUILD_BATCH = 32;
  var LISTENER_TARGET = 4;         // sockets we poll for pulses
  var LISTENER_MAX_BATCHES = 7;    // 224 connections for the port lottery
  var BEACON_TARGET = 4;           // extra announce sources, also polled
  var SCAN_CONNECTIONS = 72;       // subnet sweep, reused as pulse sources
  var PULSE_POOL_TARGET = 160;     // dedicated pulse sources
  var ENUM_CONNECTIONS = 24;       // probe the peer's listeners (reused as sources)
  var LOCATE_CONNECTIONS = 6;      // find the finder's address from its port

  // Sweep shape: host-major, ports-minor, and small rounds.  Every port of a
  // few hosts is covered before moving on, so a peer that owns a socket in the
  // discovery window is met in the first round or two, and each connection only
  // holds a handful of pairs (that is what keeps addIceCandidate cheap; a /24
  // with 400 pairs per connection took 105-134 s).
  var SCAN_ROUND_HOSTS = 2;
  var SCAN_ROUND_MS = 600;
  var SCAN_BURST = 128;            // candidates handed to the ICE thread at once
  var SCAN_BURST_GAP_MS = 40;
  var SCAN_GRACE_MS = 15000;
  var LISTENER_BUDGET_MS = 14000;
  var SCAN_BUDGET_MS = 90000;
  var RECEIVE_BUDGET_MS = 180000;
  var EXCHANGE_TIMEOUT_MS = (PREAMBLE_SLOTS + PAYLOAD_SLOTS) * SLOT_MS + 6000;
  var CONNECT_TIMEOUT_MS = 25000;
  var ENUM_WAIT_MS = 6000;
  var AIM_CANDIDATES = 8;          // peer ports a payload considers, not 4
  var ANNOUNCE_TARGET_CAP = 40;    // ports probed per announce round
  var ANNOUNCE_STAGGER_MS = 150;   // gap between announce sources (readable shape)
  var ANNOUNCE_EVERY_MS = 6000;    // re-announce until connected
  var VOTE_AIM_DELAY_MS = 15000;   // wait this long for the peer's own answer first
  var ANNOUNCE_ECHO_MS = 1300;     // how long our own announce's echoes keep coming
  var ANNOUNCE_SLOTS = 4;          // announce cycle slices, one per peer id modulo
  var ANNOUNCE_SLOT_MS = 1400;
  var ANNOUNCE_ECHO_MS = 2600;     // how long our own announce's echoes keep arriving
  var POLL_IDLE_MS = 60;
  var WATCH_CYCLE_MS = 20;         // how fast the fine sockets are re-read
  var COARSE_SLICE = 3;            // own sockets polled per pass (~1.5 s full sweep)
  var FINE_WINDOW_MS = 25000;      // how long a socket that saw a pulse stays fine
  var WAKE_PULSES = 6;             // checks per port in the wake burst
  var WAKE_LEAD_MS = 3200;         // wake burst -> payload head start
  var PAYLOAD_GAP_MS = 6000;       // gap between two payload attempts
  var CARRIER_QUIET_MS = 3500;     // no peer pulse for this long means the window is free
  var ATTEMPT_JITTER_MS = 4000;    // random hold-off before a payload attempt
  var FOUR_PORT_WAIT_MS = 12000;   // how long to wait for four ports before using two
  var MAX_ATTEMPTS = 3;            // payload transmissions before giving up
  var DECODE_EVERY_MS = 400;       // how often the in-flight stream is re-read
  var POLL_TIMEOUT_MS = 400;       // a stat read may take this long, then it is skipped
  var STUCK_BACKOFF_MS = 2500;     // how long a socket that timed out is left alone
  var ECHO_MS = 80;                // a check's own answer arrives 25-60 ms later

  var STUN_DEFAULT = [{ urls: "stun:stun.l.google.com:19302" }];

  // ------------------------------------------------------------------ state
  var state = "idle";
  var statusLine = "idle";
  var logs = [];
  var subs = [];
  var eventQueue = [];               // drained by pollEventsJson() (Java bridge)
  var EVENT_QUEUE_MAX = 64;
  var currentPhase = PHASE_NAMES.boot;
  var lastError = null;
  var creds = { ufrag: UFRAG, pwd: PWD };   // the shared credentials in use
  var codeMode = false;              // credentials came from a 6-character code
  var currentCode = null;            // that code, for display
  var fallbackTimer = 0;
  var fallbackRaised = false;        // the 20 s prompt has been raised once
  var peers = Object.create(null);
  var running = false;
  var startedAt = 0;

  var selfId = randomHex(2);            // 16 bits, carried in the payload
  var selfName = "player-" + selfId;
  var certificate = null;
  var fingerprint = "";
  var stunServers = STUN_DEFAULT;

  var listeners = [];                   // [{ pc, port, allPorts }] polled
  var beacons = [];                     // [{ pc, port }] announce sources, polled
  var scanners = [];                    // RTCPeerConnection[]
  var enumPcs = [];                     // RTCPeerConnection[] (spent, reused)
  var locatePcs = [];                   // RTCPeerConnection[]
  var pulsePool = [];                   // RTCPeerConnection[]
  var ownPorts = Object.create(null);
  var finalPc = null;
  var finalPort = 0;
  var finalChannel = null;
  var finalRemoteApplied = false;

  var peerId = 0;                       // id decoded from the peer
  var peerAddress = null;               // where we can reach the peer
  var peerEnumPorts = [];               // ports the peer answered on
  var peerReplyPorts = [];              // ports the peer says it polls
  var receivedPayload = null;           // { id, port, replyPorts, fingerprint }
  var role = "unknown";                 // caller | responder
  var announced = 0;
  var sentPayloads = 0;
  var sentTargets = [];
  var enumerating = false;
  var handshakeStarted = false;
  var votes = Object.create(null);      // peer port -> { distinct, count, first }
  var ownAnnounceTargets = Object.create(null);
  var arrivals = [];                    // { socket, source, at }
  var seenRemoteIds = Object.create(null);
  // Every data slot fires every channel at once, so a start cannot be told from
  // a data slot by how many sockets pulsed together - the staircase is what
  // tells them apart, and a start that does not bind all four channels is
  // dropped before the checksum ever sees it.  That leaves the checksum as the
  // only judge, so it is worth examining plenty of starts.
  var ALIGN_CANDIDATES = 30;            // plausible starts examined per decode
  var SAMPLE_BINS = 8;                  // phase histogram resolution per slot
  var TRANSMIT_SLOTS = (PREAMBLE_SLOTS + DATA_SLOTS) * PAYLOAD_REPEATS;
  var arrivalsSinceLog = 0;
  var socketIndex = [];                      // [{ pc, port }] every socket we own
  var fineUntil = Object.create(null);       // socket port -> fine-poll deadline
  var peerSockets = Object.create(null);     // peer port -> last time it probed us
  var aiming = false;
  var transmitting = false;     // our own slot loop is running: nothing may block it
  var transmitStartedAt = 0;    // and a stuck flag must not mute the receiver
  var lastDecodeStartedAt = 0;
  var lastDecodeMs = 0;         // what one decode read costs the poll loop
  var lastDecodeAt = 0;
  var stuckUntil = Object.create(null);    // socket port -> skip polling until
  var watchPasses = 0;
  var lastArrivalAt = 0;
  var lastPeerAt = 0;
  var firedAt = Object.create(null);    // our socket port -> when we last pulsed from it
  var firstAimReadyAt = 0;
  var lastAlignLog = 0;
  // How far back an alignment search looks.  It has to cover a whole
  // transmission or the search can only see the last copy's preamble: anchoring
  // there puts earlier copies' pulses before the grid and every one of them is
  // dropped, which is exactly a half-filled symbol grid (measured: 12-13 of 19
  // cells with the grid spanning slots -2..28 instead of the whole 58).  Scaled
  // off the transmission, not guessed.
  var ALIGN_WINDOW_MS = (PREAMBLE_SLOTS + DATA_SLOTS) * PAYLOAD_REPEATS * SLOT_MS + 3000;
  var wakePending = Object.create(null);   // peer port -> we are waiting for its answer
  var wokeConfirmed = Object.create(null); // peer port -> when it answered our wake burst
  var socketArrivals = Object.create(null);  // our socket port -> [{source, at}]
  var socketQuiet = Object.create(null);     // our socket port -> echo window end
  var socketArrivals = Object.create(null);  // our socket port -> [{source, at}]
  var lastT0 = 0;
  var lastCells = null;                  // census of the last grid read back
  var sentAt = 0;
  var firstProbeAt = 0;

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
    // The bridge queue carries only what the game reacts to; a status line is
    // emitted on every log() and would drown the queue.
    if (event.type !== "status") {
      if (eventQueue.length >= EVENT_QUEUE_MAX) eventQueue.shift();
      try { eventQueue.push(JSON.stringify(event)); } catch (e) { /* skip */ }
    }
    for (var i = 0; i < subs.length; i++) {
      try { subs[i](event); } catch (e) { /* subscriber errors are its own */ }
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
    // SHARED_TAIL pads the derived 16 characters to the 22 the browser needs;
    // it is part of both peers' derivation, so they still agree.
    for (var j = 8; j < 24; j++) pwd += CODE_ALPHABET[bytes[j] % CODE_ALPHABET.length];
    while (pwd.length < 22) pwd += "Ac7";
    return { ufrag: ufrag, pwd: pwd };
  }

  // Both peers derive the same credentials from the same code, so the phases
  // that follow are the shared-credential ones with synchronized peers.
  function deriveCreds(code) {
    var text = "eagler-autoconnect:" + code;
    var subtle = window.crypto && window.crypto.subtle;
    if (subtle && subtle.digest && typeof TextEncoder !== "undefined") {
      return subtle.digest("SHA-256", new TextEncoder().encode(text)).then(function (hash) {
        return credsFromBytes(new Uint8Array(hash));
      });
    }
    // No WebCrypto (an exotic engine): a deterministic non-cryptographic hash
    // of the same string, so the two peers still agree on the credentials.
    var seed = 0x9E3779B9;
    for (var i = 0; i < text.length; i++) seed = (seed * 31 + text.charCodeAt(i)) >>> 0;
    var bytes_ = new Uint8Array(24);
    for (var k = 0; k < bytes_.length; k++) {
      seed = (seed * 1664525 + 1013904223) >>> 0;
      bytes_[k] = (seed >>> 16) & 0xFF;
    }
    return Promise.resolve(credsFromBytes(bytes_));
  }

  /** Apply a code and (re)start every phase with credentials derived from it. */
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
      log("code", "code mode: both peers derive the same credentials from the code");
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

  // The plan's first tier ends at FALLBACK_AFTER_MS without a peer, and the
  // prompt is the entry to the second tier.  Scanning continues in the
  // background either way, so a slow discovery can still connect on its own.
  function maybeFallback() {
    fallbackTimer = 0;
    if (!running || codeMode || state === "connected" || state === "failed") return;
    fallbackRaised = true;
    state = "fallback";
    log("code", "no peer in " + Math.round(FALLBACK_AFTER_MS / 1000) + "s: a " + CODE_LEN
      + "-character code can connect the two players");
    emit({ type: "fallback" });
  }

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
      if (/^a=ice-ufrag:/.test(L)) { kept.push("a=ice-ufrag:" + (opts.ufrag || creds.ufrag)); continue; }
      if (/^a=ice-pwd:/.test(L)) { kept.push("a=ice-pwd:" + (opts.pwd || creds.pwd)); continue; }
      if (/^a=fingerprint:/.test(L)) { kept.push("a=fingerprint:sha-256 " + (opts.fingerprint || dummyFingerprint())); continue; }
      if (/^a=setup:/.test(L)) { kept.push("a=setup:" + (opts.setup || "passive")); continue; }
      kept.push(L);
    }
    return kept.join("\r\n") + "\r\n";
  }

  function candidateLine(address, port) {
    return "candidate:1 1 udp 2130706431 " + address + " " + port + " typ host";
  }

  function pairKey(address, port) { return address + ":" + port; }

  // Every check we ever send goes through here, so a connection is never asked
  // for a pair it has already sent (that would be a silent no-op and the pulse
  // would simply not exist).
  function fire(pc, address, port) {
    var used = pc.__acUsed || (pc.__acUsed = Object.create(null));
    var key = pairKey(address, port);
    if (used[key]) return Promise.resolve(false);
    used[key] = true;
    // Every check we send is answered on the socket that sent it, and that
    // answer is indistinguishable from a pulse the peer sent us.  Stamp the
    // moment of the send so the receive side can throw its own echoes away -
    // without this the decoder reads its own transmission back as the peer's.
    if (pc.__acPort) firedAt[pc.__acPort] = now();
    if (pc.__acPorts) {
      for (var q = 0; q < pc.__acPorts.length; q++) firedAt[pc.__acPorts[q]] = now();
    }
    try {
      return pc.addIceCandidate({
        candidate: candidateLine(address, port), sdpMid: "0", sdpMLineIndex: 0
      }).then(function () { return true; }, function () { return false; });
    } catch (e) {
      return Promise.resolve(false);
    }
  }

  function udpPorts(pc) {
    return pc.getStats().then(function (stats) {
      var rows = [];
      stats.forEach(function (s) {
        if (s.type === "local-candidate" && s.protocol === "udp") {
          rows.push({ port: s.port, address: s.address || "", type: s.candidateType || "" });
        }
      });
      return rows;
    }, function () { return []; });
  }

  function succeededPairs(pc) {
    return pc.getStats().then(function (stats) {
      var cands = {}, rows = [];
      stats.forEach(function (s) {
        if (s.type === "remote-candidate" || s.type === "local-candidate") cands[s.id] = s;
      });
      stats.forEach(function (s) {
        if (s.type !== "candidate-pair" || s.state !== "succeeded") return;
        var rc = cands[s.remoteCandidateId];
        if (!rc) return;
        rows.push({ address: rc.address || "", port: rc.port, candidateType: rc.candidateType });
      });
      return rows;
    }, function () { return []; });
  }

  function probeStats(pc) {
    return pc.getStats().then(function (stats) {
      var pairs = 0, ok = 0, prflx = 0;
      stats.forEach(function (s) {
        if (s.type === "candidate-pair") pairs++;
        if (s.type === "candidate-pair" && s.state === "succeeded") ok++;
        if (s.type === "remote-candidate" && s.candidateType === "prflx") prflx++;
      });
      return { ice: pc.iceConnectionState, pairs: pairs, succeeded: ok, prflx: prflx };
    }, function () { return { ice: "stats-failed", pairs: -1, succeeded: -1, prflx: -1 }; });
  }

  // A peer that has found us: one of its sockets, not a local port of ours, has
  // probed two or more of our own sockets.  A single sweep connection reaches
  // exactly one of our sockets once, and our own checks come back to the socket
  // that sent them, so neither can pass this test.
  function peerSeen() {
    for (var p in votes) {
      var n = Number(p);
      if (ownPorts[p]) continue;
      if (n < P_START || n > P_END) continue;
      if (votes[p] && votes[p].distinct >= 2) return true;
    }
    return false;
  }

  // Every remote candidate that is not in the offer is something that actually
  // reached us.  The candidate pair tells us which local socket it arrived on,
  // which is what the payload's timing is read against.
  function remoteCandidates(pc) {
    return pc.getStats().then(function (stats) {
      var rows = [];
      var localOf = Object.create(null);
      stats.forEach(function (s) {
        if (s.type === "candidate-pair" && s.localCandidateId && s.remoteCandidateId) {
          localOf[s.remoteCandidateId] = s.localCandidateId;
        }
      });
      var portOf = Object.create(null);
      stats.forEach(function (s) {
        if (s.type === "local-candidate" && s.id) portOf[s.id] = s.port;
      });
      stats.forEach(function (s) {
        if (s.type === "remote-candidate") {
          rows.push({
            id: s.id,
            port: s.port,
            candidateType: s.candidateType,
            localPort: portOf[localOf[s.id]] || 0
          });
        }
      });
      return rows;
    }, function () { return []; });
  }

  // ------------------------------------------------------------ connections
  function withCert() {
    return certificate ? { certificates: [certificate] } : {};
  }

  // A discovery connection: shared credentials, forged answer, no data.
  function makeSharedPc() {
    var pc = new RTCPeerConnection(withCert());
    pc.createDataChannel("probe", { ordered: true });
    return pc.createOffer().then(function (offer) {
      return pc.setLocalDescription({ type: "offer", sdp: mungeCreds(offer.sdp) });
    }).then(function () {
      return pc.setRemoteDescription({ type: "answer", sdp: forgeAnswer(pc.localDescription.sdp) });
    }).then(function () { return pc; });
  }

  function makeSharedBatch(count) {
    var jobs = [];
    for (var i = 0; i < count; i++) jobs.push(makeSharedPc());
    return Promise.all(jobs);
  }

  function closePc(pc) {
    try { pc.close(); } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------------ phase 0: lottery
  function buildListeners(deadline) {
    var attempts = 0;
    function step() {
      if (!running) return Promise.resolve();
      if (listeners.length >= LISTENER_TARGET) return Promise.resolve();
      if (now() > deadline || attempts >= LISTENER_MAX_BATCHES) return Promise.resolve();
      attempts++;
      return makeSharedBatch(BUILD_BATCH).then(function (batch) {
        var chain = Promise.resolve();
        batch.forEach(function (pc) {
          chain = chain.then(function () {
            if (!running || listeners.length >= LISTENER_TARGET) { closePc(pc); return null; }
            return udpPorts(pc).then(function (rows) {
              rows.forEach(function (r) { ownPorts[r.port] = true; });
              var usable = rows.filter(function (r) { return r.port >= P_START && r.port <= P_END; });
              if (!usable.length) { closePc(pc); return null; }
              var port = usable[0].port;
              pc.__acPorts = rows.map(function (r) { return r.port; });
              listeners.push({ pc: pc, port: port });
              registerSocket(pc, port);
              log("listeners", "listener " + port + " (" + listeners.length + "/" + LISTENER_TARGET
                + ")" + (port >= W_START && port <= W_END ? " [in the discovery window]" : ""));
              return null;
            });
          });
        });
        return chain.then(step);
      });
    }
    return step().then(function () { return attempts; });
  }

  function haveDiscoverySocket() {
    for (var i = 0; i < listeners.length; i++) {
      if (listeners[i].port >= W_START && listeners[i].port <= W_END) return true;
    }
    return false;
  }

  // Beacons are announce sources as well as polled sockets: the peer learns
  // their ports from our announce and can aim a payload straight at them.
  function buildBeacons() {
    return makeSharedBatch(BEACON_TARGET).then(function (batch) {
      var chain = Promise.resolve();
      batch.forEach(function (pc) {
        chain = chain.then(function () {
          return udpPorts(pc).then(function (rows) {
            rows.forEach(function (r) { ownPorts[r.port] = true; });
            var usable = rows.filter(function (r) { return r.port >= P_START && r.port <= P_END; });
            if (!usable.length) { closePc(pc); return null; }
            beacons.push({ pc: pc, port: usable[0].port });
            registerSocket(pc, usable[0].port);
          });
        });
      });
      return chain.then(function () {
        log("beacons", beacons.length + " announce beacons on ports "
          + beacons.map(function (b) { return b.port; }).join(","));
      });
    });
  }

  function buildScanners() {
    return makeSharedBatch(SCAN_CONNECTIONS).then(function (batch) {
      var chain = Promise.resolve();
      batch.forEach(function (pc) {
        chain = chain.then(function () {
          return udpPorts(pc).then(function (rows) {
            rows.forEach(function (r) { ownPorts[r.port] = true; });
            scanners.push(pc);
            registerSocket(pc, rows.length ? rows[0].port : 0);
          });
        });
      });
      return chain.then(function () {
        log("scanners", scanners.length + " scanner connections ready");
      });
    });
  }

  function buildPulsePool() {
    if (pulsePool.length >= PULSE_POOL_TARGET) return Promise.resolve();
    var built = pulsePool.length;
    function step() {
      if (!running || built >= PULSE_POOL_TARGET) return Promise.resolve();
      return makeSharedBatch(Math.min(BUILD_BATCH, PULSE_POOL_TARGET - built)).then(function (batch) {
        var chain = Promise.resolve();
        batch.forEach(function (pc) {
          chain = chain.then(function () {
            return udpPorts(pc).then(function (rows) {
              rows.forEach(function (r) { ownPorts[r.port] = true; });
              registerSocket(pc, rows.length ? rows[0].port : 0);
              pulsePool.push(pc);
              built++;
            });
          });
        });
        return chain.then(step);
      });
    }
    return step().then(function () {
      log("pulses", "pulse pool ready: " + pulsePool.length + " fresh source ports");
    });
  }

  // ------------------------------------------------------- phase 1: sweep
  // Likely host addresses first: DHCP pools almost always hand out 100-199, so
  // a peer that has just joined the network is met before the sweep widens.
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
    log("scan", "sweeping " + W_COUNT + " discovery ports x 254 hosts in " + rounds.length
      + " rounds of ~" + rounds[0].length + " checks");

    var found = null;
    var pumpDone = false;
    var pumpGraceUntil = 0;
    var pollCursor = 0;
    var pollRounds = 0;
    var pumpedRounds = 0;
    var pumpDoneMs = 0;

    function pumpRound(index) {
      if (!running || found || peerSeen()) { pumpDone = true; pumpDoneMs = now() - startedAt; return Promise.resolve(); }
      if (index >= rounds.length) {
        pumpDone = true;
        pumpDoneMs = now() - startedAt;
        pumpGraceUntil = now() + SCAN_GRACE_MS;
        log("scan", "all " + rounds.length + " rounds fired after " + pumpDoneMs + " ms");
        return Promise.resolve();
      }
      pumpedRounds = index + 1;
      var batch = rounds[index];
      // Bursts, not one synchronous flood: the ICE thread has to digest the
      // candidates, and a thousand adds at once is what makes the first stat
      // poll never come back.
      var i = 0;
      function burst() {
        var n = 0;
        while (i < batch.length && n < SCAN_BURST) {
          fire(scanners[i % scanners.length], batch[i].address, batch[i].port);
          i++; n++;
        }
        if (i >= batch.length) return Promise.resolve();
        return sleep(SCAN_BURST_GAP_MS).then(burst);
      }
      return burst().then(function () { return sleep(SCAN_ROUND_MS); })
        .then(function () { return pumpRound(index + 1); });
    }

    function poll() {
      if (!running || found) return Promise.resolve(found);
      var slice = [];
      for (var i = 0; i < 4; i++) slice.push(scanners[(pollCursor + i) % scanners.length]);
      pollCursor = (pollCursor + 4) % scanners.length;
      pollRounds++;
      if (pollRounds % 20 === 0) {
        var hb = (pollRounds + " stat polls, " + (pumpDone ? "all rounds fired" : "pumping round " + pumpedRounds)
          + ", pump done in " + pumpDoneMs + " ms");
        log("scan", "sweep heartbeat: " + hb);
        probeStats(slice[0]).then(function (s) {
          log("scan", "scanner sample: ice=" + s.ice + " pairs=" + s.pairs
            + " succeeded=" + s.succeeded + " prflx=" + s.prflx);
        });
      }
      var chain = Promise.resolve();
      slice.forEach(function (pc) {
        chain = chain.then(function () {
          if (found) return null;
          return succeededPairs(pc).then(function (rows) {
            for (var j = 0; j < rows.length; j++) {
              var r = rows[j];
              if (!r.address) continue;
              if (ownPorts[r.port]) continue;      // our own listener answered
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

    var pumping = pumpRound(0);
    var deadline = now() + SCAN_BUDGET_MS;
    return (function wait() {
      if (!running) return Promise.resolve(null);
      if (found) return pumping.then(function () { return found; });
      return poll().then(function () {
        if (found) return found;
        if (peerSeen()) {                      // a peer is already probing us
          log("scan", "sweep stopped: a peer is probing our own ports");
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

  // Every port the peer owns inside the pulse window: what an announce fires at
  // so the peer's polled sockets see us and learn our live ports.
  function enumeratePeerPorts(address) {
    var known = Object.create(null);
    var portsToTry = [];
    for (var p = P_START; p <= P_END; p++) portsToTry.push(p);
    return makeSharedBatch(ENUM_CONNECTIONS).then(function (pcs) {
      enumPcs = enumPcs.concat(pcs);
      var chain = Promise.resolve();
      pcs.forEach(function (pc) {
        chain = chain.then(function () {
          return udpPorts(pc).then(function (rows) {
            rows.forEach(function (r) { ownPorts[r.port] = true; });
            registerSocket(pc, rows.length ? rows[0].port : 0);
          });
        });
      });
      return chain.then(function () {
        for (var i = 0; i < portsToTry.length; i++) {
          fire(pcs[i % pcs.length], address, portsToTry[i]);
        }
        return pcs;
      });
    }).then(function (pcs) {
      return sleep(ENUM_WAIT_MS).then(function () { return pcs; });
    }).then(function (pcs) {
      var chain = Promise.resolve();
      pcs.forEach(function (pc) {
        chain = chain.then(function () {
          return succeededPairs(pc).then(function (rows) {
            for (var i = 0; i < rows.length; i++) {
              var r = rows[i];
              if (r.address !== address) continue;
              if (ownPorts[r.port]) continue;
              known[r.port] = true;
            }
          });
        });
      });
      return chain;
    }).then(function () {
      var ports = Object.keys(known).map(Number).sort(function (a, b) { return a - b; });
      log("ports", "peer answered on " + (ports.join(",") || "no port"));
      return ports;
    });
  }

  // The peer that was found cannot read our address (candidate addresses are
  // redacted), but it does read the source port of our checks.  A single-port
  // sweep of the two common /24s finds the address behind that port.
  function findAddressByPort(port) {
    var candidates = [];
    for (var s = 0; s < SUBNETS.length; s++) {
      for (var h = 1; h <= 254; h++) candidates.push(SUBNETS[s] + h);
    }
    var addresses = Object.create(null);
    return makeSharedBatch(LOCATE_CONNECTIONS).then(function (pcs) {
      locatePcs = locatePcs.concat(pcs);
      var chain = Promise.resolve();
      pcs.forEach(function (pc) {
        chain = chain.then(function () {
          return udpPorts(pc).then(function (rows) {
            rows.forEach(function (r) { ownPorts[r.port] = true; });
            registerSocket(pc, rows.length ? rows[0].port : 0);
          });
        });
      });
      return chain.then(function () {
        for (var i = 0; i < candidates.length; i++) {
          fire(pcs[i % pcs.length], candidates[i], port);
        }
        return pcs;
      });
    }).then(function (pcs) {
      // Six connections pace 508 checks over five seconds, so the wait has to
      // cover the pacer, not just the round trip.
      return sleep(5500).then(function () { return pcs; });
    }).then(function (pcs) {
      var chain = Promise.resolve();
      pcs.forEach(function (pc) {
        chain = chain.then(function () {
          return succeededPairs(pc).then(function (rows) {
            for (var i = 0; i < rows.length; i++) if (rows[i].address) addresses[rows[i].address] = true;
          });
        });
      });
      return chain;
    }).then(function () {
      var list = Object.keys(addresses);
      log("locate", "port " + port + " answered at " + (list.join(",") || "no address"));
      return list.length ? list[0] : null;
    });
  }

  // ------------------------------------------------- phase 2: watch it all
  // A peer picks its own sockets and never says which of them it watches, and a
  // check cannot tell a watched socket from any other - so the earlier design's
  // aim stage had nothing to aim at.  Instead of guessing, this page now watches
  // every socket it owns in the window: a rotating coarse schedule sweeps them
  // all (a full pass is about a second), and a socket that has just seen a pulse
  // is pulled into a fine schedule for the rest of the exchange.  Any live port
  // of the peer is then a legitimate target, and the wake burst below is what
  // tells us which of our sockets to tighten on before the payload starts.
  function registerSocket(pc, port) {
    if (!port) return;
    for (var i = 0; i < socketIndex.length; i++) if (socketIndex[i].pc === pc) return;
    pc.__acPort = port;
    if (!pc.__acPorts) pc.__acPorts = [port];
    socketIndex.push({ pc: pc, port: port });
  }

  // A stat poll must never be able to stall the loop: one connection that is
  // busy answering a sweep can hold its statistics for seconds, and waiting on
  // it would stop every other socket from being read at all.
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

  function pollOne(s) {
    if ((stuckUntil[s.port] || 0) > now()) return Promise.resolve();
    return withTimeout(remoteCandidates(s.pc), POLL_TIMEOUT_MS).then(function (rows) {
      if (!rows) {
        stuckUntil[s.port] = now() + STUCK_BACKOFF_MS;
        return;
      }
      for (var i = 0; i < rows.length; i++) {
        var r = rows[i];
        if (r.candidateType !== "prflx") continue;
        if (seenRemoteIds[r.id]) continue;
        seenRemoteIds[r.id] = true;
        var at = now();
        var mine = r.localPort || s.port;
        // One of our own checks coming back: the answer to a check arrives on
        // the very socket that sent it, a few milliseconds later.  Counting
        // those as peer traffic is what made the carrier sense useless.
        if (at - (firedAt[mine] || 0) <= ECHO_MS) {
          fineUntil[mine] = at + FINE_WINDOW_MS;
          // The answer to a wake pulse names the port that answered: that is a
          // port of the peer's that is alive and watching.
          if (r.port && (wakePending[r.port] || 0) > at) wokeConfirmed[r.port] = at;
          continue;
        }
        lastArrivalAt = at;
        lastPeerAt = at;
        arrivals.push({ socket: mine, source: r.port, at: at });
        fineUntil[mine] = at + FINE_WINDOW_MS;
        if (!firstProbeAt) firstProbeAt = at;
        recordSocket(mine, r.port, at);
        if (r.port >= P_START && r.port <= P_END) peerSockets[r.port] = at;
        var v = votes[r.port] || (votes[r.port] = { distinct: 0, count: 0, first: at, last: at, sockets: Object.create(null) });
        v.count++;
        v.last = at;
        if (!v.sockets[mine]) { v.sockets[mine] = true; v.distinct++; }
        arrivalsSinceLog++;
        if (arrivals.length > 2500) arrivals.splice(0, arrivals.length - 2000);
        if (arrivalsSinceLog % 50 === 0) {
          log("watch", "peer pulses seen: " + arrivalsSinceLog + ", latest on our port "
            + mine + " from peer port " + r.port);
        }
      }
    });
  }

  function recordSocket(socketPort, source, at) {
    var list = socketArrivals[socketPort] || (socketArrivals[socketPort] = []);
    list.push({ source: source, at: at });
    if (list.length > 300) list.splice(0, list.length - 300);
  }

  function fineSockets() {
    var out = [];
    for (var i = 0; i < socketIndex.length; i++) {
      var s = socketIndex[i];
      if ((stuckUntil[s.port] || 0) > now()) continue;
      if (fineUntil[s.port] > now()) out.push(s);
    }
    return out;
  }

  function watchLoop() {
    var cursor = 0;
    var lastReport = 0;
    function step() {
      if (!running || state === "connected") return Promise.resolve();
      if (!socketIndex.length) return sleep(POLL_IDLE_MS).then(step);
      // This page must NOT stop reading while it transmits.  Both peers run
      // their attempts at the same time (measured: alice 15.2-27.0 s and bob
      // 16.5-28.3 s in one run overlapped almost exactly), so "the peer holds
      // its attempt back while we pulse" is not true and standing down meant
      // each side was blind for the whole of the other's payload.  Readings are
      // cheap (1-4 ms for the fine set); only the coarse sweep is skipped while
      // transmitting, because a late slot lands in the wrong cell.
      var t0 = now();
      var chain = Promise.resolve();
      // Fine sockets first: a socket that has seen pulses has to keep its
      // resolution, or the payload's slot timing blurs away.
      var fine = fineSockets();
      fine.forEach(function (s) { chain = chain.then(function () { return pollOne(s); }); });
      // While this page is *sending* its own payload nothing may delay a slot, so
      // the coarse slice is skipped; otherwise it stays as it was.  Shrinking it
      // to favour the fine sockets was measured to hurt: the coarse sweep is how
      // the wake burst's arrivals are found at all, and a slow sweep means the
      // payload's sockets never reach the fine schedule before slot 0.
      var sending = transmitting && (now() - transmitStartedAt < 30000);
      var want = sending ? 0 : COARSE_SLICE;
      var slice = [];
      var walked = 0;
      while (slice.length < want && walked < socketIndex.length) {
        var cand = socketIndex[cursor];
        cursor = (cursor + 1) % socketIndex.length;
        walked++;
        if ((stuckUntil[cand.port] || 0) > now()) continue;
        slice.push(cand);
      }
      slice.forEach(function (s) { chain = chain.then(function () { return pollOne(s); }); });
      return chain.then(function () {
        watchPasses++;
        if (now() - lastReport > 8000) {
          lastReport = now();
          log("watch", "pass " + watchPasses + ": " + socketIndex.length + " own sockets ("
            + fine.length + " fine), took " + (now() - t0) + " ms, "
            + Object.keys(stuckUntil).length + " stalled, last decode " + lastDecodeMs + " ms");
        }
        maybeAim();
        return sleep(Math.max(0, WATCH_CYCLE_MS - (now() - t0))).then(step);
      });
    }
    return Promise.resolve().then(step).catch(function (err) {
      log("watch", "loop error: " + String(err && err.message ? err.message : err));
      if (running && state !== "connected") return sleep(200).then(step);
    });
  }

  function onStream(decoded) {
    var parsed = parsePayload(decoded.bits);
    if (!parsed) {
      // tryDecode checksums before it hands anything over, so this is a bug
      // rather than a bad stream.
      log("link", "payload failed its checksum after being accepted");
      return;
    }
    receivedPayload = parsed;
    log("link", "payload decoded from peer " + parsed.id.toString(16)
      + ": final port " + parsed.port + ", reply ports " + parsed.replyPorts.join(","));
    emit({
      type: "peer-found",
      peer: { id: parsed.id.toString(16), address: peerAddress, port: parsed.port, state: "decoded" }
    });
    beginHandshake(parsed);
  }

  // Candidate targets for a payload: the peer's own ports, most recently seen
  // first.  A port the peer has just probed us from is a live socket of its, and
  // it polls all of them.  An enumeration answer is much weaker evidence - it
  // says the port was live when the sweep ran, which can be many seconds and
  // several closed connections ago.  Measured: with the enumeration's four
  // lowest ports picked first, every pulse of an attempt was aimed at sockets
  // that no longer existed and the receiver saw nothing at all.
  function aimTargets() {
    var seen = Object.create(null), out = [];
    function add(port) {
      var p = Number(port);
      if (!p || p < P_START || p > P_END) return;
      if (ownPorts[p] || seen[p]) return;
      seen[p] = true;
      out.push(p);
    }
    var recent = Object.keys(peerSockets).sort(function (a, b) { return peerSockets[b] - peerSockets[a]; });
    for (var i = 0; i < recent.length; i++) add(recent[i]);
    peerReplyPorts.forEach(add);
    peerEnumPorts.forEach(add);
    return out.slice(0, AIM_CANDIDATES);
  }

  // Six checks per target from six different sources.  The receiving side sees
  // those on whichever of its sockets they land on and marks each one fine, so
  // the payload that follows a few seconds later is read at full resolution.
  function wakePorts(targets) {
    // The wake has to land a few seconds before the payload so the peer is
    // reading its targeted sockets at full resolution when slot 0 arrives, and
    // that only happens if the checks actually go out then.  The sweep
    // connections still carry the sweep's queue (measured: a pulse fired behind
    // 24 queued checks went out 1540 ms late), so the wake is fired from the
    // same untouched pool the payload uses; the one shot it spends at each
    // target is filtered out of the payload pools by the per-target used list.
    var src = pulseSources();
    if (!src.length) src = sourceList();
    if (!src.length) return Promise.resolve(0);
    var fired = 0;
    var chain = Promise.resolve();
    targets.forEach(function (port, t) {
      for (var k = 0; k < WAKE_PULSES; k++) {
        var pc = src[(t * WAKE_PULSES + k * 17) % src.length];
        fired++;
        (function (targetPc, targetPort) {
          chain = chain.then(function () { return fire(targetPc, peerAddress, targetPort); });
        })(pc, port);
      }
    });
    return chain.then(function () {
      log("aim", "wake burst: " + fired + " checks at peer ports " + targets.join(","));
      return fired;
    });
  }

  function aimAt(targets) {
    sentPayloads++;
    sentAt = now();
    sentTargets = targets.slice(0);
    // Two payloads in the air at once read each other's pulses as their own
    // signal, so a retry waits a fresh random moment (a fixed offset would put
    // both sides back in step) and then re-checks that the window is still free.
    var jitter = Math.floor(Math.random() * ATTEMPT_JITTER_MS);
    log("aim", "attempt " + sentPayloads + " at " + targets.length + " peer ports, waiting "
      + jitter + " ms");
    return sleep(jitter).then(function () {
      if (!running || state === "connected") return null;
      if (lastPeerAt && now() - lastPeerAt < CARRIER_QUIET_MS) {
        log("aim", "the peer is still pulsing: holding this attempt back");
        sentAt = 0;
        return null;
      }
      for (var w = 0; w < targets.length; w++) wakePending[targets[w]] = now() + WAKE_LEAD_MS + 2000;
      return wakePorts(targets);
    }).then(function (woke) {
      if (woke === null) return null;
      return sleep(WAKE_LEAD_MS);
    }).then(function () {
      if (!running || state === "connected") return null;
      if (!running || state === "connected") return null;
      // A port that answered the wake burst is a port the peer watches.  The
      // payload is split across those only: a channel that nobody reads spends
      // the whole transmission on zeros.
      // "Answered the wake burst" has to be read from the peer's ports, not from
      // our own echo window: the answer to a wake pulse lands on the socket that
      // sent it, which is polled on the coarse schedule, so a 80 ms echo test can
      // never see it and reported "none answered" even when every target had.
      // Any traffic from a port since this attempt began - an answer to the wake,
      // or one of the peer's own pulses - proves the socket is live and polled.
      var live = targets.filter(function (p) {
        return (wokeConfirmed[p] || 0) > sentAt || (peerSockets[p] || 0) > sentAt;
      });
      // Ports that answered the wake burst first: they are certainly alive.  The
      // rest fill the payload up to four channels.
      var use = live.slice(0, CHANNELS);
      for (var u = 0; u < targets.length && use.length < CHANNELS; u++) {
        if (live.indexOf(targets[u]) < 0) use.push(targets[u]);
      }
      if (use.length < CHANNELS) {
        log("aim", "only " + use.length + " peer ports to aim at: waiting for more");
        sentAt = 0;
        return null;
      }
      // The group a port ends up in is what decides which half of the payload it
      // carries, and which of the peer's sockets answer is not something we can
      // see, so each attempt rotates the ports through the groups.
      if (use.length > 2) {
        var rot = sentPayloads % use.length;
        use = use.slice(rot).concat(use.slice(0, rot));
        log("aim", "channels: " + use.length + " ports, groups " + use.slice(0, 2).join(",")
          + " | " + use.slice(2, 4).join(",") + " (" + (live.length ? live.length
          + " answered the wake burst" : "none answered the wake burst") + ")");
      }
      targets = use;
      sentTargets = use.slice(0);
      return buildPulsePool().then(function () {
        if (!running || state === "connected") return null;
        // Label every payload source with the socket that reaches the peer
        // before a single symbol is sent: the symbols are the source ports, and
        // an unlabelled source is a coin flip that writes a wrong nibble into a
        // real cell.
        return censusSources(pulseSources(), peerAddress);
      }).then(function (verified) {
        if (!running || state === "connected") return null;
        if (!verified) {
          log("pulses", "no payload source could be labelled: not transmitting");
          sentAt = 0;
          return null;
        }
        var bits = buildPayload();
        return sendPayload(bits, peerAddress, targets, pulseSources()).then(function (r) {
          if (r) log("pulses", "attempt " + sentPayloads + " done: " + r.fired + " pulses fired"
            + (r.dropped ? ", " + r.dropped + " had no free connection" : ""));
        });
      });
    });
  }

  function maybeAim() {
    if (!running || state === "connected" || aiming || sentPayloads >= MAX_ATTEMPTS) return;
    if (!peerAddress) {
      // A peer probing our sockets is a peer that has found us: learn where it
      // is from the port it probed from.
      if (firstProbeAt && !enumerating) {
        var ports = Object.keys(peerSockets);
        if (ports.length) {
          enumerating = true;
          log("link", "a peer is pulsing us from port " + ports[0] + ": locating its address");
          findAddressByPort(Number(ports[0])).then(function (a) {
            enumerating = false;
            if (a) peerAddress = a;
          });
        }
      }
      return;
    }
    if (sentAt && now() - sentAt < PAYLOAD_GAP_MS) return;
    // Carrier sense: while the peer is pulsing, this window is taken.  Our own
    // transmission's echoes stop with its last pulse, so this also keeps a retry
    // from starting on top of the attempt that just failed.
    if (lastPeerAt && now() - lastPeerAt < CARRIER_QUIET_MS) return;
    var targets = aimTargets();
    // Four channels are what a payload needs; two will do only once the wait for
    // more has gone on too long (a peer with few live sockets).
    var want = 4;
    if (targets.length < want) {
      if (now() - (firstAimReadyAt || (firstAimReadyAt = now())) < FOUR_PORT_WAIT_MS) return;
      want = 2;
    }
    if (targets.length < want) return;
    aiming = true;
    aimAt(targets).then(function () { aiming = false; });
  }

  // --------------------------------------------------- phase 3: pulse send
  // The symbol that (data slot, channel) carries: four bits of the payload, read
  // straight off the index.  null means the payload ends before it.
  function symbolAt(bits, slot, channel) {
    var at = (slot * CHANNELS + channel) * SYMBOL_BITS;
    if (at + SYMBOL_BITS > bits.length) return null;
    var v = 0;
    for (var b = 0; b < SYMBOL_BITS; b++) v = (v << 1) | (bits.charAt(at + b) === "1" ? 1 : 0);
    return v;
  }

  // Take the first source that has not already fired in this slot; a source that
  // is busy now goes to the back of its pool and serves a later slot instead.
  function takeFrom(pool, takenSlot) {
    if (!pool) return null;
    for (var t = 0; t < pool.length; t++) {
      var cand = pool.shift();
      if (takenSlot[cand.__acKey]) { pool.push(cand); continue; }
      takenSlot[cand.__acKey] = true;
      return cand;
    }
    return null;
  }

  function sourceList() {
    var out = [];
    var push = function (list) { for (var i = 0; i < list.length; i++) out.push(list[i]); };
    push(scanners);
    push(enumPcs);
    push(locatePcs);
    push(pulsePool);
    return out;
  }

  // What a payload may be fired from: connections built for the purpose, never
  // used for a sweep, an enumeration or a wake burst, so their send queue is
  // empty when their slot arrives.  A check from a connection that is still
  // draining a queue leaves late enough to miss its slot entirely (measured:
  // a pulse fired behind 24 queued checks went out 1540 ms later; with a
  // second to drain, 17-551 ms).
  function pulseSources() {
    var out = [];
    for (var i = 0; i < pulsePool.length; i++) out.push(pulsePool[i]);
    for (var j = 0; j < beacons.length; j++) out.push(beacons[j].pc);
    return out;
  }

  // ---------------------------------------------------------- the census
  // A connection owns two UDP sockets and Chrome picks which one carries a
  // check; the choice is not the one getStats lists first, and it is a coin flip
  // (measured: 94 arrivals split 55/39, 174 split 89/85).  A pulse's symbol *is*
  // the source port the receiver sees, so a source labelled with the wrong
  // socket carries a symbol nobody chose - and its preamble pulses vote for the
  // wrong channel.  The choice is a property of the destination address, not of
  // the target port (measured: 40 of 40 connections used one socket for a closed
  // port and both of the listener's sockets), and a connection's own statistics
  // report it (40 of 40 readbacks matched what the receiver saw).  So one check
  // at the census port, read back from the sender's side, labels a source for
  // every target the payload will use.
  function censusUsedPort(pc) {
    return pc.getStats().then(function (stats) {
      var rem = Object.create(null), loc = Object.create(null);
      stats.forEach(function (s) {
        if (s.type === "remote-candidate") rem[s.id] = s.port;
        if (s.type === "local-candidate") loc[s.id] = s.port;
      });
      var used = 0;
      stats.forEach(function (s) {
        if (s.type !== "candidate-pair") return;
        if (rem[s.remoteCandidateId] !== CENSUS_PORT) return;
        if (loc[s.localCandidateId]) used = loc[s.localCandidateId];
      });
      return used;
    }, function () { return 0; });
  }

  function censusSources(list, address) {
    // The label is a property of the connection and the peer's address, and it
    // outlives the check that measured it.  That matters: the census pair goes
    // to a port nothing answers on, so it fails and leaves the report within
    // seconds, and a later attempt that re-probed would label nothing at all
    // (measured in the first census run: 160 of 160 on attempt 1, 0 of 160 on
    // attempts 2 and 3).  A cache plus a one-time probe is also cheaper.
    var verified = 0, remembered = 0;
    var chain = Promise.resolve();
    list.forEach(function (pc) {
      if (pc.__acVerified) { remembered++; return; }
      chain = chain.then(function () {
        // Read back at once: the pair to a port nothing answers on fails and
        // leaves the report, and a source that cannot be labelled is a source
        // the payload must not use.
        return fire(pc, address, CENSUS_PORT).then(function () {
          return censusUsedPort(pc);
        }).then(function (port) {
          if (port) { pc.__acVerified = port; verified++; }
        });
      }).then(function () {
        // One source per turn: this page is also a receiver, and a census that
        // held the main thread for a second would freeze its own poll loop - the
        // pulses it is meant to read would all arrive as one late cluster.
        return sleep(0);
      });
    });
    return chain.then(function () {
      log("pulses", "census: " + (verified + remembered) + " of " + list.length
        + " payload sources labelled with the socket that reaches the peer"
        + (remembered ? " (" + remembered + " reused from an earlier attempt)" : ""));
      return verified + remembered;
    });
  }

  // One connection per pulse, and a connection may only ever send one check per
  // target - so a channel's pool is every source that has not fired at that
  // target yet, and no source is shared between two channels in the same slot.
  // Channels do not need separate senders - they only need different senders in
  // the same slot.  Every channel therefore gets the whole source list, rotated
  // so the channels start on different connections, and the slot loop keeps one
  // connection out of two channels at once.  Carving the list into four disjoint
  // slices is what starved every channel before: 92 pulses a channel needs 92
  // distinct senders, and a quarter of 262 is 65.
  function assignPools(address, targets, src) {
    if (!src) src = sourceList();
    for (var k = 0; k < src.length; k++) if (src[k].__acKey === undefined) src[k].__acKey = k;
    var pools = [];
    for (var c = 0; c < targets.length; c++) {
      var available = [];
      var offset = Math.floor(src.length * c / Math.max(1, targets.length));
      for (var i = 0; i < src.length; i++) {
        var pc = src[(offset + i) % src.length];
        if (pc.__acUsed && pc.__acUsed[pairKey(address, targets[c])]) continue;
        // A source whose sending socket has not been measured has no symbol to
        // carry: its class is a coin flip until the census labels it.
        if (!pc.__acVerified) continue;
        available.push(pc);
      }
      // The preamble is reserved first, out of the fullest class buckets.  It
      // used to insist that a preamble source's port class equal the channel
      // index - which left a quarter of the pool eligible per channel and, in
      // the measured run, reserved ten sources where twelve are needed.  The
      // staircase names the channel now, so a preamble pulse may come from any
      // port and the whole pool is eligible.
      var byClass = [];
      for (var q = 0; q < SYMBOL_CLASSES; q++) byClass.push([]);
      for (var j = 0; j < available.length; j++) {
        byClass[available[j].__acVerified % SYMBOL_CLASSES].push(available[j]);
      }
      // The preamble is paid for out of the fullest class buckets, never a thin
      // one: a symbol needs two sources per occurrence (one per copy) and a
      // bucket that the preamble emptied takes its symbol with it.
      var order = [];
      for (var q2 = 0; q2 < SYMBOL_CLASSES; q2++) order.push(q2);
      order.sort(function (a, b) { return byClass[b].length - byClass[a].length; });
      var preamble = [];
      for (var o = 0; o < order.length && preamble.length < PREAMBLE_SOURCES; o++) {
        var bucket = byClass[order[o]];
        for (var t2 = 0; t2 < bucket.length && preamble.length < PREAMBLE_SOURCES; t2++) {
          preamble.push(bucket.splice(t2, 1)[0]);
          t2--;
        }
      }
      pools.push({ preamble: preamble, byClass: byClass });
    }
    log("pulses", "sources per channel by class: "
      + pools.map(function (p) { return p.byClass.map(function (l) { return l.length; }).join("/"); }).join(" | ")
      + " (preamble reserved " + (pools.length ? pools[0].preamble.length : 0) + ")");
    return pools;
  }

  function sendPayload(bits, address, targets, sources) {
    var pools = assignPools(address, targets, sources);
    var channels = Math.min(targets.length, CHANNELS, pools.length);
    if (channels < CHANNELS) {
      // Every channel carries its own quarter of the payload, so a channel that
      // is missing would take those bits with it.
      log("pulses", "not enough channels (" + channels + ") for a payload");
      return Promise.resolve({ fired: 0, dropped: 0 });
    }
    var slots = (PREAMBLE_SLOTS + DATA_SLOTS) * PAYLOAD_REPEATS;
    var t0 = now();
    var fired = 0, dropped = 0;
    log("pulses", "sending " + bits.length + " bits as " + DATA_SLOTS + " symbols x "
      + channels + " channels x " + PAYLOAD_REPEATS + " copies to ports "
      + targets.slice(0, channels).join(",") + ", " + slots + " slots ("
      + (slots * SLOT_MS) + " ms)");
    // What was actually transmitted, so a receive-side read can be scored
    // against it instead of against a guess about what went wrong.
    var truth = bytesFromBits(bits, PAYLOAD_BODY_BYTES + CRC_BYTES);
    log("pulses", "payload truth " + truth.map(function (x) {
      return (x + 0x100).toString(16).slice(1);
    }).join(" ") + " (crc16 " + crcHex(truth.slice(0, PAYLOAD_BODY_BYTES)) + ")");
    transmitting = true;
    transmitStartedAt = now();
    return (function slot(k) {
      if (!running || k >= slots) { transmitting = false; return Promise.resolve({ fired: fired, dropped: dropped }); }
      return sleep(Math.max(0, t0 + k * SLOT_MS - now())).then(function () {
        var chain = Promise.resolve();
        var takenSlot = Object.create(null);
        var local = k % (PREAMBLE_SLOTS + DATA_SLOTS);
        for (var c = 0; c < channels; c++) {
          var value = -1;                       // -1: a preamble pulse, any port
          var shots = 1;
          if (local === 0) {
            shots = SYNC_SHOTS;                 // the burst every start is measured by
          } else if (local === MARKER_SLOT) {
            shots = MARKER_SHOTS;
          } else if (local >= STAIR_FIRST && local <= STAIR_LAST
            && (local - STAIR_FIRST) % 2 === 0) {
            // The staircase: one pulse in this channel's own slot, from its own
            // preamble sources, and nothing in the other channels' slots.
            if ((local - STAIR_FIRST) / 2 !== c) continue;
            shots = STAIR_SHOTS;
          } else if (local < PREAMBLE_SLOTS) {
            continue;                           // the gap that makes the grid readable
          } else {
            value = symbolAt(bits, local - PREAMBLE_SLOTS, c);
            if (value === null) continue;
          }
          // A data symbol goes once: the class buckets only hold so many
          // sources, and spending two on one symbol emptied them (measured: 150+
          // shots dropped for want of a source, so cells read back as zero).
          // Redundancy lives in the four copies instead - a nibble is carried
          // again by the next copy - and in this page's own preamble, which is
          // what binds channels and anchors the grid.
          for (var shot = 0; shot < shots; shot++) {
            var pc = value < 0
              ? takeFrom(pools[c].preamble, takenSlot)
              : takeFrom(pools[c].byClass[value], takenSlot);
            if (!pc) { dropped++; continue; }
            fired++;
            (function (source, port) {
              chain = chain.then(function () { return fire(source, address, port); });
            })(pc, targets[c]);
          }
        }
        return chain;
      }).then(function () { return slot(k + 1); });
    })(0);
  }

  // ------------------------------------------------- phase 4: pulse receive
  // Watching, waking and decoding live in phase 2: the whole point of the
  // rewrite is that the receiver watches every socket, so there is no separate
  // poll-the-polled-sockets loop any more.  These are the pieces the payload
  // decoder is built out of.

  // The payload's preamble: slot 0 and slot 5 fire on every channel, slots 1-4
  // carry the channel index twice.  The channel id is what lets a receiver bind
  // its own ports to channels without any prior agreement.
  // A payload is ~184 pulses.  Attempting to decode a stream that is still in
  // flight only ever reads its preamble, so wait for the arrival count a real
  // transmission produces (or for a quiet gap after a partial one).
  function streamReady() {
    if (arrivals.length >= 60) return true;
    if (arrivals.length < 25) return false;
    var last = arrivals[arrivals.length - 1].at;
    return now() - last > 1500;
  }

  function tryDecode() {
    if (!streamReady()) return null;
    var recent = [];
    for (var q = 0; q < arrivals.length; q++) {
      if (now() - arrivals[q].at <= ALIGN_WINDOW_MS) recent.push(arrivals[q]);
    }
    if (now() - lastAlignLog > 2000) {
      lastAlignLog = now();
      var perSocket = Object.create(null);
      for (var z = 0; z < recent.length; z++) {
        perSocket[recent[z].socket] = (perSocket[recent[z].socket] || 0) + 1;
      }
      var top = Object.keys(perSocket).sort(function (a, b) { return perSocket[b] - perSocket[a]; }).slice(0, 5);
      log("align", recent.length + " pulses in the last 4 s on "
        + Object.keys(perSocket).length + " of our sockets (top: "
        + top.map(function (p) { return p + "x" + perSocket[p]; }).join(" ") + ")");
    }

    // Where a transmission starts is not something worth guessing at: the
    // payload carries its own magic byte and CRC, so every plausible start is
    // tried and the first one that checksums out is the real one.  A wrong
    // candidate costs one comparison; the old rule - pick the single start that
    // looked best and blacklist it forever if it did not decode - threw the run
    // away as soon as the stream was read while it was still being sent.
    var starts = [];
    var maxAfter = 0;
    for (var i = 0; i < recent.length; i++) {
      var together = 1;
      for (var j = 0; j < recent.length; j++) {
        if (recent[j].socket !== recent[i].socket
          && Math.abs(recent[j].at - recent[i].at) <= SLOT_MS * 0.6) together++;
      }
      if (together < 2) continue;
      // Data slots fire every channel at once, so a data pulse looks exactly
      // like the sync.  What follows the sync is different: the staircase slots
      // are nearly empty while the whole copy's data still lies ahead.  A start
      // with nothing after it is the end of the transmission, not a start, so a
      // candidate has to have a populated copy behind it before it counts.
      var burst = 0, after = 0;
      var dataFrom = PREAMBLE_SLOTS * SLOT_MS;
      var dataTo = (PREAMBLE_SLOTS + DATA_SLOTS) * SLOT_MS;
      for (var q2 = 0; q2 < recent.length; q2++) {
        var dt = recent[q2].at - recent[i].at;
        if (Math.abs(dt) <= SLOT_MS * 0.5) burst++;
        else if (dt >= dataFrom && dt < dataTo) after++;
      }
      // How many pulses a populated copy puts in that window: four channels,
      // each firing once per data slot, so four pulses per slot.  Six of the
      // sixteen is a *partly* read copy - and a partly read copy is exactly what
      // a bad channel produces, which is the thing being diagnosed.  Demanding
      // 16 there meant a stream read at 60% produced no candidate start at all,
      // so nothing was ever tried and the run said only "no start".
      if (after > maxAfter) maxAfter = after;
      if (after < 6) continue;
      starts.push({ at: recent[i].at, together: together, burst: burst });
    }
    // Dense first: the sync fires four shots per channel where a data slot fires
    // one, so the biggest burst in the stream is where a copy begins.  (A
    // recency tiebreak was tried here and measured no better; the symbol-grid
    // read is dominated by which sockets the bindings land on, not by which
    // equally dense start is tried first.)
    starts.sort(function (a, b) { return (b.burst - a.burst) || (b.together - a.together); });
    var tried = 0;
    var best = null;
    var nearest = null;                             // most channels bound anywhere
    for (var s = 0; s < starts.length && s < ALIGN_CANDIDATES * 4; s++) {
      var t0 = starts[s].at;
      var bound = bindChannels(recent, t0);
      if (!nearest || bound.bound > nearest.bound) nearest = { t0: t0, bound: bound.bound, channelOf: bound.channelOf };
      if (!bound.complete) continue;                // a missing channel takes its bits with it
      tried++;
      // A single observed arrival is a poor grid origin: it carries both the
      // check's travel time and the delay of the poll that noticed it, and half
      // a slot of error is enough to read every pulse on the wrong side of a
      // boundary.  Re-bind and re-read against the phase the pulses themselves
      // agree on, then let the checksum choose between neighbouring grids.
      var channelOf = bound.channelOf;
      var channels = bound.channels;
      var origin = t0;
      var again = bound;
      var phase = phaseOf(t0, channelOf);
      var grids = phase === null ? [t0]
        : [t0 + phase, t0 + phase + SLOT_MS, t0 + phase - SLOT_MS];
      for (var g2 = 0; g2 < grids.length; g2++) {
        origin = grids[g2];
        if (g2) {
          again = bindChannels(recent, origin);
          if (again.complete) { channelOf = again.channelOf; channels = again.channels; }
        }
        var bits = extractBits(origin, channelOf);
        if (!best || channels > best.channels) {
          best = { t0: origin, channels: channels, channelOf: channelOf, bits: bits, cells: lastCells };
        }
        if (parsePayload(bits)) {
          lastT0 = origin;
          log("pulses", "aligned at +" + origin + " with " + channels + " channels ("
            + Object.keys(channelOf).map(function (p) { return p + "=c" + channelOf[p]; }).join(" ")
            + "), payload verified by checksum");
          return { bits: bits, channels: channels };
        }
      }
    }
    if (best) {
      // One line per second naming the closest start, so a run that never
      // checksums out still says how near it got and where the bits broke.
      if (now() - lastAlignLog > 1500) {
        lastAlignLog = now();
        var b = bytesFromBits(best.bits, PAYLOAD_BODY_BYTES + CRC_BYTES);
        var hex = [];
        for (var h = 0; h < b.length; h++) hex.push((b[h] + 0x100).toString(16).slice(1));
        // How many pulses each bound socket actually delivered says whether the
        // bits went wrong because a pulse was read in the wrong slot (timing) or
        // because it never arrived (the sender ran out of sources).
        var perChannel = [];
        for (var cp in best.channelOf) {
          var hits = 0;
          for (var a2 = 0; a2 < arrivals.length; a2++) {
            if (arrivals[a2].socket === Number(cp)) hits++;
          }
          perChannel.push(cp + "=c" + best.channelOf[cp] + ":" + hits);
        }
        // Where the bound sockets' pulses actually fall in the grid: an empty
        // symbol cell is either a pulse that never came or a slot count that
        // does not line up with the sender's, and this tells the two apart.
        var slotCount = Object.create(null);
        for (var a3 = 0; a3 < arrivals.length; a3++) {
          if (best.channelOf[arrivals[a3].socket] === undefined) continue;
          var sl = Math.round((arrivals[a3].at - best.t0) / SLOT_MS);
          if (sl < -2 || sl > TRANSMIT_SLOTS) continue;
          slotCount[sl] = (slotCount[sl] || 0) + 1;
        }
        var band = Object.keys(slotCount).map(Number).sort(function (a4, b4) { return a4 - b4; });
        var dense = Object.keys(slotCount)
          .sort(function (a5, b5) { return slotCount[b5] - slotCount[a5]; }).slice(0, 8)
          .map(function (key) { return key + ":" + slotCount[key]; });
        log("align", "closest start: " + best.channels + " channels (" + perChannel.join(" ")
          + "), grid slots " + (band.length ? band[0] + ".." + band[band.length - 1] : "none")
          + " densest " + dense.join(" ")
          + ", " + tried + "/" + starts.length + " starts tried, cells "
          + (best.cells ? best.cells.filled + "/" + best.cells.total + " filled ("
            + best.cells.perChannel.map(function (f2) { return f2 + " of " + DATA_SLOTS; }).join(" ")
            + ") over " + best.cells.copies + " copies" : "unknown")
          + ", read bytes " + hex.join(" ")
          + " (want magic " + PAYLOAD_MAGIC.toString(16) + "), crc16 "
          + "read " + b.slice(PAYLOAD_BODY_BYTES, PAYLOAD_BODY_BYTES + CRC_BYTES).map(function (x3) {
            return (x3 + 0x100).toString(16).slice(1);
          }).join("") + " vs " + crcHex(b.slice(0, PAYLOAD_BODY_BYTES))
          + ", phase " + (phaseOf(best.t0, best.channelOf) === null ? "none"
            : Math.round(phaseOf(best.t0, best.channelOf)) + " ms"));
      }
      return null;
    }
    if (now() - lastAlignLog > 1000) {
      lastAlignLog = now();
      // An empty candidate list has to say what it saw, or the run only reports
      // that it failed: densest 4 s of arrivals behind any start candidate.
      if (!starts.length) {
        var busiest = 0;
        for (var b2 = 0; b2 < recent.length; b2++) {
          var n2 = 0;
          for (var b3 = 0; b3 < recent.length; b3++) {
            var d2 = recent[b3].at - recent[b2].at;
            if (d2 >= 0 && d2 < 4000) n2++;
          }
          if (n2 > busiest) busiest = n2;
        }
        statusLine = "align: no start candidate in " + recent.length + " pulses (busiest 4 s " + busiest
          + ", most behind one start " + maxAfter + ")";
      }
      var near = nearest
        ? ("best binding " + nearest.bound + "/" + CHANNELS + " ("
          + Object.keys(nearest.channelOf).map(function (p) { return p + "=c" + nearest.channelOf[p]; }).join(" ") + ")")
        : "no binding at all";
      log("align", "no complete start in " + recent.length + " recent pulses: " + near);
    }
    return null;
  }

  // Where the pulses really sit inside their slot, in milliseconds: the densest
  // cluster of offsets is the transmission's phase.  A tight cluster means the
  // jitter is small compared to the slot, which is what makes a timing channel
  // readable at all, so this doubles as the diagnostic for a drifting grid.
  function phaseOf(t0, channelOf) {
    var bins = [];
    var i;
    for (i = 0; i < SAMPLE_BINS; i++) bins.push([]);
    for (i = 0; i < arrivals.length; i++) {
      if (channelOf[arrivals[i].socket] === undefined) continue;
      var dt = arrivals[i].at - t0;
      if (dt < -SLOT_MS || dt > TRANSMIT_SLOTS * SLOT_MS) continue;
      var off = ((dt % SLOT_MS) + SLOT_MS) % SLOT_MS;
      bins[Math.floor(off / (SLOT_MS / SAMPLE_BINS))].push(off);
    }
    var bestBin = -1, bestCount = 0, total = 0;
    for (i = 0; i < SAMPLE_BINS; i++) {
      total += bins[i].length;
      if (bins[i].length > bestCount) { bestCount = bins[i].length; bestBin = i; }
    }
    if (!total || bestCount < 6) return null;
    var sum = 0, n = 0;
    for (i = 0; i < bins[bestBin].length; i++) { sum += bins[bestBin][i]; n++; }
    return sum / n;
  }

  // Read the payload back.  The pulse that landed on a bound socket in a data
  // slot names its own source port, and the low bits of that port are the
  // symbol: the value is exact, and the slot only decides which symbol index it
  // belongs to.  Every copy of the payload carries the same 76 symbols, so they
  // are read into one set of cells and the copies vote - measured, a single copy
  // leaves about a third of the cells empty (the pulse was late, lost or read a
  // slot away), and a fourth power of that is what decides a nibble.  Merged,
  // the same measurement leaves well under one cell in fifty empty.  Whether the
  // payload is right is the checksum's business, not this function's.
  function extractBits(t0, channelOf) {
    var copySlots = PREAMBLE_SLOTS + DATA_SLOTS;
    var cells = Object.create(null);
    for (var n = 0; n < arrivals.length; n++) {
      var ch = channelOf[arrivals[n].socket];
      if (ch === undefined || ch >= CHANNELS) continue;
      var slot = Math.round((arrivals[n].at - t0) / SLOT_MS);
      if (slot < PREAMBLE_SLOTS) continue;
      var copy = Math.floor(slot / copySlots);
      if (copy >= PAYLOAD_REPEATS) continue;
      var dataSlot = slot - copy * copySlots - PREAMBLE_SLOTS;
      if (dataSlot < 0 || dataSlot >= DATA_SLOTS) continue;
      var index = dataSlot * CHANNELS + ch;
      var value = arrivals[n].source % SYMBOL_CLASSES;
      var cell = cells[index] || (cells[index] = { best: -1, count: 0, values: [] });
      var seen = cell.values[value] = (cell.values[value] || 0) + 1;
      if (seen > cell.count) { cell.count = seen; cell.best = value; }
    }
    var bits = "";
    var filled = 0;
    var perChannelFilled = [];
    for (var c2 = 0; c2 < CHANNELS; c2++) perChannelFilled.push(0);
    for (var i = 0; i < DATA_SLOTS * CHANNELS; i++) {
      var cell2 = cells[i];
      var v = cell2 && cell2.best >= 0 ? cell2.best : 0;
      if (cell2 && cell2.best >= 0) { filled++; perChannelFilled[i % CHANNELS]++; }
      for (var b = SYMBOL_BITS - 1; b >= 0; b--) bits += ((v >> b) & 1) ? "1" : "0";
    }
    // Occupancy of the symbol grid as it was read back: 76 cells per copy, and a
    // cell with no arrival at all reads as a zero nibble, so this counts the
    // pulses that never made it (or were read a slot away) directly.
    lastCells = {
      filled: filled, total: DATA_SLOTS * CHANNELS,
      perChannel: perChannelFilled, copies: PAYLOAD_REPEATS
    };
    return bits;
  }

  // Which of our sockets carries which channel: a channel is identified by the
  // bit pattern in preamble slots 1-4, sent twice, and only a socket that shows
  // both the sync and the marker can be a channel at all.
  function bindChannels(list, t0) {
    // Which channel a socket carries is *where its staircase pulse landed*:
    // every channel fires one pulse in its own preamble slot, two slots apart.
    // Only slots at even offsets from the first staircase slot count, so a pulse
    // read a slot away cannot name a channel - and a socket that heard no
    // staircase pulse names no channel at all instead of voting at random, which
    // is what the port-class scheme did (measured in run 12: two of the four
    // channels bound to noise sockets, and 40 of 80 cells read as zero).
    var votesOf = Object.create(null);
    for (var s = 0; s < list.length; s++) {
      var a = list[s];
      var slot = Math.round((a.at - t0) / SLOT_MS);
      if (slot < STAIR_FIRST || slot > STAIR_LAST) continue;
      if ((slot - STAIR_FIRST) % 2 !== 0) continue;
      var ch = (slot - STAIR_FIRST) / 2;
      var byChannel = votesOf[a.socket] || (votesOf[a.socket] = []);
      byChannel[ch] = (byChannel[ch] || 0) + 1;
    }
    var candidates = [];
    for (var port in votesOf) {
      var votes = votesOf[port];
      var best = -1, bestCount = 0;
      for (var c = 0; c < CHANNELS; c++) {
        if ((votes[c] || 0) > bestCount) { bestCount = votes[c]; best = c; }
      }
      // One staircase pulse in the right slot is enough to name a channel.
      if (best < 0) continue;
      candidates.push({ port: Number(port), channel: best, count: bestCount });
    }
    // One socket per channel: two sockets claiming the same channel write into
    // the same symbols and destroy them.  The socket with the stronger vote wins.
    candidates.sort(function (a, b) { return b.count - a.count; });
    var taken = Object.create(null);
    var channelOf = Object.create(null);
    for (var k = 0; k < candidates.length; k++) {
      if (taken[candidates[k].channel]) continue;
      channelOf[candidates[k].port] = candidates[k].channel;
      taken[candidates[k].channel] = true;
    }
    var channels = 0, bound = 0;
    for (var p2 in channelOf) {
      channels = Math.max(channels, channelOf[p2] + 1);
      bound++;
    }
    // Four *distinct* sockets, one per channel: the highest index alone says
    // nothing (a single socket bound to channel 3 used to pass this test, and
    // three quarters of the payload read back as zeroes).
    return { channelOf: channelOf, channels: channels, bound: bound, complete: bound === CHANNELS };
  }

  function bitsFromBytes(bytes) {
    var out = "";
    for (var i = 0; i < bytes.length; i++) {
      for (var bit = 7; bit >= 0; bit--) out += ((bytes[i] >> bit) & 1) ? "1" : "0";
    }
    return out;
  }

  function bytesFromBits(bits, count) {
    var out = [];
    for (var i = 0; i + 8 <= bits.length && out.length < count; i += 8) {
      var v = 0;
      for (var b = 0; b < 8; b++) v = (v << 1) | (bits.charAt(i + b) === "1" ? 1 : 0);
      out.push(v);
    }
    return out;
  }

  function crc16(bytes) {
    var crc = 0xffff;
    for (var i = 0; i < bytes.length; i++) {
      crc ^= bytes[i] << 8;
      for (var b = 0; b < 8; b++) {
        crc = (crc & 0x8000) ? (((crc << 1) ^ 0x1021) & 0xffff) : ((crc << 1) & 0xffff);
      }
    }
    return crc & 0xffff;
  }

  function crcHex(bytes) {
    var v = crc16(bytes);
    return (v + 0x10000).toString(16).slice(1);
  }

  // The whole fingerprint travels: the DTLS identity check compares all 32
  // bytes, so the payload cannot send a prefix of it.
  function fingerprintBytes(fp) {
    var hex = String(fp).replace(/:/g, "");
    var out = [];
    for (var i = 0; i + 2 <= hex.length; i += 2) out.push(parseInt(hex.substr(i, 2), 16));
    return out;
  }

  function bytesToFingerprint(bytes) {
    var parts = [];
    for (var i = 0; i < bytes.length; i++) parts.push((bytes[i] + 0x100).toString(16).slice(1).toUpperCase());
    return parts.join(":");
  }

  function buildPayload() {
    var id = parseInt(selfId, 16);
    var body = [PAYLOAD_MAGIC, (id >> 8) & 0xff, id & 0xff, (finalPort >> 8) & 0xff, finalPort & 0xff];
    body = body.concat(fingerprintBytes(fingerprint));
    return bitsFromBytes(body) + bitsFromBytes(crc16Bytes(body));
  }

  function crc16Bytes(bytes) {
    var v = crc16(bytes);
    return [(v >> 8) & 0xff, v & 0xff];
  }

  function parsePayload(bits) {
    var bytes = bytesFromBits(bits, PAYLOAD_BODY_BYTES + CRC_BYTES);
    if (bytes.length < PAYLOAD_BODY_BYTES + CRC_BYTES) return null;
    var body = bytes.slice(0, PAYLOAD_BODY_BYTES);
    if (body[0] !== PAYLOAD_MAGIC) return null;
    var got = ((bytes[PAYLOAD_BODY_BYTES] || 0) << 8) | (bytes[PAYLOAD_BODY_BYTES + 1] || 0);
    if (crc16(body) !== got) return null;
    return {
      id: (body[1] << 8) | body[2],
      port: (body[3] << 8) | body[4],
      replyPorts: [],
      fingerprint: bytesToFingerprint(body.slice(5))
    };
  }

  // ---------------------------------------------------- phase 5: real link
  function buildFinalPc() {
    var pc = new RTCPeerConnection(Object.assign({ iceServers: stunServers }, withCert()));
    finalChannel = pc.createDataChannel("eagler-autoconnect", { ordered: true });
    return pc.createOffer().then(function (offer) {
      return pc.setLocalDescription({ type: "offer", sdp: mungeCreds(offer.sdp) });
    }).then(function () {
      return sleep(400);
    }).then(function () {
      return udpPorts(pc);
    }).then(function (rows) {
      rows.forEach(function (r) { ownPorts[r.port] = true; });
      var inWindow = rows.filter(function (r) { return r.port >= P_START && r.port <= P_END; });
      finalPc = pc;
      finalPort = inWindow.length ? inWindow[0].port : (rows.length ? rows[0].port : 0);
      log("final", "real connection waiting on port " + finalPort
        + (inWindow.length ? "" : " (outside the pulse window: reached via the payload)"));
      return { pc: pc, port: finalPort };
    });
  }

  function finishFinal(peerIp, peerPorts, peerFingerprint, isClient) {
    if (!finalPc) { log("final", "no final connection to complete"); return Promise.resolve(null); }
    if (finalRemoteApplied) return Promise.resolve(null);
    finalRemoteApplied = true;
    var answer = forgeAnswer(finalPc.localDescription.sdp, {
      fingerprint: peerFingerprint,
      setup: isClient ? "passive" : "active"
    });
    var targets = peerPorts.slice(0, 12);
    log("final", "probing peer ports " + targets.join(",") + " as DTLS " + (isClient ? "client" : "server"));
    return finalPc.setRemoteDescription({ type: "answer", sdp: answer }).then(function () {
      log("final", "remote description set with the peer fingerprint");
      var tries = 0;
      (function poke() {
        if (!running || tries > 8) return;
        tries++;
        var jobs = targets.map(function (port) { return fire(finalPc, peerIp, port); });
        Promise.all(jobs).then(function () {
          if (finalPc.connectionState === "connected" || (finalChannel && finalChannel.readyState === "open")) return;
          window.setTimeout(poke, 1200);
        });
      })();
      return new Promise(function (resolve) {
        var settled = false;
        function done(ok) {
          if (settled) return;
          settled = true;
          resolve(ok ? finalChannel : null);
        }
        if (finalChannel) {
          finalChannel.addEventListener("open", function () {
            log("final", "data channel open");
            done(true);
          });
          finalChannel.addEventListener("message", function (ev) { emit({ type: "message", data: ev.data }); });
        }
        finalPc.addEventListener("datachannel", function (e) {
          log("final", "peer opened a data channel");
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
      log("final", "remote description rejected: " + (err && err.message));
      return null;
    });
  }

  // ------------------------------------------------------------------ main
  function beginHandshake(parsed) {
    if (handshakeStarted) return;
    handshakeStarted = true;
    peerId = parsed.id;
    peerReplyPorts = parsed.replyPorts.slice(0);
    var isClient = parseInt(selfId, 16) < parsed.id;
    log("link", "peer " + parsed.id.toString(16) + " waits on port " + parsed.port
      + ", fingerprint " + parsed.fingerprint.slice(0, 17) + "...");
    log("link", "we are the DTLS " + (isClient ? "client" : "server"));
    var record = { id: parsed.id.toString(16), address: peerAddress, port: parsed.port, state: "handshake" };
    delete peers.pending;
    peers[record.id] = record;

    var ready = peerAddress ? Promise.resolve(peerAddress)
      : findAddressByPort(parsed.replyPorts[0] || parsed.port).then(function (a) {
        if (a) log("link", "peer address located at " + a);
        return a;
      });

    ready.then(function (address) {
      if (!address) {
        log("link", "cannot reach the peer: no address for its port");
        state = "failed";
        emit({ type: "failed", reason: "no-address" });
        return null;
      }
      peerAddress = address;
      return finishFinal(address, [parsed.port], parsed.fingerprint, isClient);
    }).then(function (channel) {
      if (channel) {
        state = "connected";
        record.state = "connected";
        log("connect", "connected over a direct data channel");
        emit({ type: "connected", peer: record });
        return;
      }
      if (state !== "failed") {
        state = "failed";
        record.state = "failed";
        log("connect", "handshake did not complete");
        emit({ type: "failed", reason: "handshake" });
      }
    });
  }

  // The decoder is the most expensive thing this page does, and it used to run
  // inside the poll chain - so a slow read delayed the very polls that timestamp
  // arrivals, and the socket's pulses came back as one late cluster (measured: a
  // grid whose densest slot held 43 arrivals, spanning only -2..44 of 58 slots).
  // It runs on its own timer now: the poll loop only polls.
  function decodeTick() {
    if (!running || receivedPayload || state === "connected") return;
    if (arrivals.length < 40) return;
    if (now() - lastDecodeAt < DECODE_EVERY_MS) return;
    lastDecodeAt = now();
    lastDecodeStartedAt = now();
    // The peer's payload lands in the middle of our own transmission, so a read
    // has to be affordable there too - one per tick, no more.  Off the poll
    // chain, a slow read can no longer delay the polls that timestamp arrivals.
    var own = transmitting && (now() - transmitStartedAt < 30000);
    var budget = (own || arrivals.length > 120) ? 1 : 2;
    for (var tries = 0; tries < budget && !receivedPayload; tries++) {
      var decoded = tryDecode();
      if (!decoded) break;
      onStream(decoded);
    }
    lastDecodeMs = now() - lastDecodeStartedAt;
  }

  var decodeTimer = 0;
  function startDecodeLoop() {
    if (decodeTimer) return;
    decodeTimer = window.setInterval(decodeTick, DECODE_EVERY_MS);
  }

  function start(options) {
    if (running) return Promise.resolve(state);
    var opts = options || {};
    if (opts.name) selfName = String(opts.name).slice(0, 24);
    if (opts.stun) stunServers = opts.stun;
    running = true;
    startedAt = now();
    state = "listening";
    if (!codeMode) {
      // The first tier: the code prompt is what the timer raises, and the
      // scan keeps running while it is up.
      clearFallbackTimer();
      fallbackTimer = window.setTimeout(maybeFallback, FALLBACK_AFTER_MS);
    }
    log("boot", "peer id " + selfId + " (" + selfName + ")"
      + (codeMode ? " [code mode]" : " [shared credentials]"));

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
        return Promise.all([
          buildFinalPc(),
          buildScanners(),
          buildListeners(startedAt + LISTENER_BUDGET_MS),
          buildBeacons()
        ]);
      })
      .then(function () {
        if (!haveDiscoverySocket()) {
          log("listeners", "no socket in the discovery window: this page cannot be found, only find others");
        }
        state = "scanning";
        // Watching starts with the sweep: the pulse sockets are polled lightly
        // while it runs, and heavy getStats polling here starved the sweep before.
        watchLoop();
        startDecodeLoop();
        return scanTargets();
      })
      .then(function (peer) {
        if (!peer) {
          log("scan", "no peer found (auto-connect unavailable here, manual codes still work)");
          if (state !== "connected") state = "linking";
          return null;
        }
        peerAddress = peer.address;
        state = "linking";
        maybeAim();
        peers.pending = { id: "pending", address: peer.address, port: peer.port, state: "linking" };
        emit({ type: "peer-found", peer: peers.pending });
        log("link", "found a peer at " + peer.address + ":" + peer.port);
        // Wake the discovered port straight away: the enumerate sweep takes
        // seconds, and the peer only learns our live sockets from a check.
        peerEnumPorts = [peer.port];
        return wakePorts([peer.port]).then(function () {
          return enumeratePeerPorts(peer.address);
        }).then(function (ports) {
          if (ports.length) peerEnumPorts = ports;
          log("ports", "aiming at " + peerEnumPorts.length + " of the peer's ports");
          return sleep(WAKE_LEAD_MS);
        }).then(function () {
          maybeAim();
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
    if (decodeTimer) { window.clearInterval(decodeTimer); decodeTimer = 0; }
    listeners.forEach(function (l) { closePc(l.pc); });
    beacons.forEach(function (b) { closePc(b.pc); });
    scanners.forEach(closePc);
    enumPcs.forEach(closePc);
    locatePcs.forEach(closePc);
    pulsePool.forEach(closePc);
    if (finalPc) closePc(finalPc);
    listeners = []; beacons = []; scanners = []; enumPcs = []; locatePcs = []; pulsePool = [];
    finalPc = null;
    statusLine = "stopped";
  }

  var AC = {
    version: VERSION,
    start: start,
    stop: stop,
    state: function () { return state; },
    status: function () { return statusLine; },
    self: function () {
      return {
        id: selfId, name: selfName, fingerprint: fingerprint, finalPort: finalPort,
        ports: listeners.concat(beacons).map(function (l) { return l.port; }),
        peerAddress: peerAddress, role: role
      };
    },
    peers: function () {
      var out = [];
      for (var k in peers) out.push(peers[k]);
      return out;
    },
    votes: function () {
      var out = [];
      for (var p in votes) out.push({ port: Number(p), distinct: votes[p].distinct, count: votes[p].count });
      return out.sort(function (a, b) { return b.distinct - a.distinct; });
    },
    diagnosis: function () {
      return {
        role: role, peerAddress: peerAddress, peerEnumPorts: peerEnumPorts,
        peerReplyPorts: peerReplyPorts, sentTargets: sentTargets,
        announced: announced, sentPayloads: sentPayloads, votes: AC.votes(),
        arrivals: arrivals.length, sources: sourceList().length
      };
    },
    phase: function () { return currentPhase; },
    onEvent: function (cb) { if (typeof cb === "function") subs.push(cb); },
    logs: function () { return logs; },
    send: function (data) {
      if (finalChannel && finalChannel.readyState === "open") {
        try { finalChannel.send(data); return true; } catch (e) { /* ignore */ }
      }
      return false;
    },
    // ------------------------------------------------------- code fallback
    code: function () { return currentCode; },
    codeMode: function () { return codeMode; },
    fallback: function () {
      return {
        active: fallbackRaised && state !== "connected",
        raised: fallbackRaised, code: currentCode, codeMode: codeMode,
        timeoutMs: FALLBACK_AFTER_MS
      };
    },
    /** Show this one: generate a code, apply it, and return it. */
    newCode: function () {
      var code = makeCode();
      startCode(code);
      return code;
    },
    /** Type the other player's code. */
    enterCode: function (code) {
      if (!sanitizeCode(code)) return false;
      startCode(code);
      return true;
    },
    /** Diagnostics: the credentials a code derives (never used by the game). */
    codeCreds: function (code) {
      var clean = sanitizeCode(code);
      return clean ? deriveCreds(clean) : Promise.resolve(null);
    },
    // ------------------------------------------------------ string bridge
    snapshotJson: function () {
      var list = AC.peers().map(function (p) {
        return { id: p.id, address: p.address, port: p.port, state: p.state };
      });
      return JSON.stringify({
        version: VERSION, state: state, phase: currentPhase, status: statusLine,
        code: currentCode, codeMode: codeMode, timeoutMs: FALLBACK_AFTER_MS,
        fallback: AC.fallback(), running: running, self: AC.self(), peers: list, lastError: lastError
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

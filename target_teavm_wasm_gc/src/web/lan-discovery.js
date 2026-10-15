/*
 * lan-discovery.js - automatic same-network peer discovery for the web client.
 *
 * Loaded as a classic script before the client's Wasm even starts, so the
 * ~1 second of presence + WebRTC setup happens while the game is still
 * loading and the peer links are already open by the time the title screen
 * appears.
 *
 * How it finds peers
 * ------------------
 * WebRTC alone cannot do this: a page cannot read its own LAN address (host
 * candidates are mDNS-obfuscated), cannot choose or scan ICE ports (Chrome
 * paces connectivity checks), and a peer's ICE agent silently drops checks
 * whose credentials it does not know.  What two players on the same network
 * do share before anyone types a code is the page they both loaded, so the
 * origin is the rendezvous: `/eagler-lan/v1/` (see tools/lan-server.mjs)
 * keeps a presence list per network scope and relays the small offer/answer/
 * candidate messages.  The game traffic itself never goes through it - every
 * peer link is a direct WebRTC data channel, host candidates first, STUN only
 * if the local path fails.
 *
 * When the origin does not offer the service (any plain static host) the
 * module stays parked: it probes once, logs the reason, retries slowly and
 * never interferes with the manual join-code flow.
 *
 * Runtime URL options
 *   ?lan=off            disable discovery
 *   ?lan=<base-url>      use another discovery service base (tests)
 *   ?lanroom=<name>      discovery room (default "lan")
 *   ?lanname=<name>      display name shown to other players
 *
 * API for the game layer: window.__eaglerLAN
 *   state() status() self() peers() peerCount() scope() serviceUrl()
 *   lastError() setName(n) send(peerId, msg) broadcast(msg) invite(peerId, p)
 *   onMessage(cb) onEvent(cb) onPeer(cb) start() stop() restart()
 *   // string bridge used by the Java side (net.lax1dude...lan.LANDiscovery)
 *   window.__eaglerLAN.snapshotJson()      -> JSON string
 *   window.__eaglerLAN.pollEventsJson()    -> JSON string (drains the queue)
 *   window.__eaglerLAN.sendJson(peerId, s) -> boolean
 */
(function () {
  "use strict";

  var VERSION = 1;
  var CHANNEL_NAME = "eagler-lan";
  var API_BASE_DEFAULT = "/eagler-lan/v1";
  // Same free public STUN servers the direct-connect room uses; only consulted
  // when the pure-LAN attempt fails.
  var STUN_SERVERS = [
    { urls: "stun:stun.l.google.com:19302" },
    { urls: "stun:stun1.l.google.com:19302" },
    { urls: "stun:stun.services.mozilla.com:3478" }
  ];
  var HEARTBEAT_MS = 8000;
  var POLL_WAIT_MS = 15000;
  var BEAT_MS = 4000;
  var PING_TIMEOUT_MS = 9000;      // no pong for this long -> the link is suspect
  var DEAD_TIMEOUT_MS = 25000;     // no pong for this long -> rebuild the link
  var FAILED_RETRY_MS = 60000;     // after MAX_LINK_ATTEMPTS, try again slowly
  var PROBE_TIMEOUT_MS = 2500;
  var LINK_TIMEOUT_MS = 12000;
  var MAX_LINK_ATTEMPTS = 4;
  var EVENT_QUEUE_MAX = 64;

  if (window.__eaglerLAN && window.__eaglerLAN.version === VERSION) {
    return; // already installed
  }

  var params = new URLSearchParams(window.location.search || "");
  var lanParam = params.get("lan");
  var disabled = lanParam === "off" || lanParam === "0" || lanParam === "false";
  var apiBase = (lanParam && lanParam !== "off" && lanParam.indexOf("http") === 0)
    ? lanParam.replace(/\/+$/, "")
    : API_BASE_DEFAULT;
  var room = sanitizeRoom(params.get("lanroom") || (window.eaglerLANOptions && window.eaglerLANOptions.room) || "lan");
  var storageKeyName = "eagler-lan-name";
  var storageKeyId = "eagler-lan-id";

  // ------------------------------------------------------------------ state
  var state = disabled ? "disabled" : "idle";
  var lastError = null;
  var serviceMissing = false;  // origin answered, but has no discovery service
  var service = null;          // health payload
  var self = null;             // { id, name, room, scope }
  var peers = Object.create(null); // id -> record
  var eventQueue = [];
  var messageSubs = [];
  var eventSubs = [];
  var running = false;
  var timers = { heartbeat: 0, poll: 0, beat: 0, probe: 0 };
  var backoff = 1000;
  var pollInFlight = false;
  var probeInFlight = false;

  function now() {
    return Date.now();
  }

  function sanitizeRoom(value) {
    value = String(value == null ? "lan" : value).toLowerCase().replace(/[^a-z0-9_-]/g, "");
    return value.slice(0, 32) || "lan";
  }

  function sanitizeName(value) {
    value = String(value == null ? "" : value).replace(/[\u0000-\u001f\u007f]/g, "").trim();
    return value.slice(0, 24);
  }

  function randomId() {
    var buf = new Uint8Array(8);
    (window.crypto || window.msCrypto).getRandomValues(buf);
    var hex = "";
    for (var i = 0; i < buf.length; i++) {
      hex += (buf[i] + 0x100).toString(16).slice(1);
    }
    return hex;
  }

  function localName() {
    var fromQuery = sanitizeName(params.get("lanname"));
    if (fromQuery) return fromQuery;
    if (window.eaglerLANOptions && window.eaglerLANOptions.name) {
      return sanitizeName(window.eaglerLANOptions.name);
    }
    try {
      var stored = window.localStorage.getItem(storageKeyName);
      if (stored) return sanitizeName(stored);
    } catch (e) { /* private mode */ }
    return "player-" + randomId().slice(0, 4);
  }

  function persistName(name) {
    try { window.localStorage.setItem(storageKeyName, name); } catch (e) { /* ignore */ }
  }

  function myName() {
    return (self && self.name) || preferredName;
  }
  var preferredName = localName();

  function log(kind, message) {
    try {
      var line = "LAN: " + kind + ": " + message;
      if (window.__log && typeof window.__log.push === "function") {
        window.__log.push(line);
        if (window.__log.length > 400) window.__log.splice(0, 100);
      }
      var fn = kind === "error" ? console.error : console.log;
      fn.call(console, line);
    } catch (e) { /* logging must never break the module */ }
  }

  function emitEvent(event) {
    eventQueue.push(event);
    if (eventQueue.length > EVENT_QUEUE_MAX) eventQueue.splice(0, eventQueue.length - EVENT_QUEUE_MAX);
    for (var i = 0; i < eventSubs.length; i++) {
      try { eventSubs[i](event); } catch (e) { /* subscriber errors are its own */ }
    }
  }

  // ------------------------------------------------------------------ http
  function fetchJson(url, options, timeoutMs) {
    var controller = typeof AbortController === "function" ? new AbortController() : null;
    var timer = 0;
    if (controller) {
      timer = window.setTimeout(function () { controller.abort(); }, timeoutMs || 8000);
    }
    var opts = options || {};
    if (controller) {
      opts = Object.assign({}, opts, { signal: controller.signal });
    }
    return window.fetch(url, opts).then(function (response) {
      if (timer) window.clearTimeout(timer);
      if (!response.ok) {
        var err = new Error("HTTP " + response.status);
        err.status = response.status;
        throw err;
      }
      return response.json();
    }, function (err) {
      if (timer) window.clearTimeout(timer);
      throw err;
    });
  }

  function postJson(path, body, timeoutMs) {
    return fetchJson(apiBase + path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    }, timeoutMs || 8000);
  }

  function sendBye(peerId) {
    try {
      var payload = JSON.stringify({ peerId: peerId });
      if (navigator.sendBeacon) {
        navigator.sendBeacon(apiBase + "/bye", new Blob([payload], { type: "application/json" }));
        return;
      }
      window.fetch(apiBase + "/bye", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: payload,
        keepalive: true
      }).catch(function () { /* leaving anyway */ });
    } catch (e) { /* ignore */ }
  }

  // ------------------------------------------------------------------ peers
  function emptyPeer(id, name) {
    return {
      id: id,
      name: name || ("player-" + id.slice(0, 4)),
      addr: null,
      state: "seen",      // seen -> connecting -> open -> failed
      rttMs: -1,
      firstSeen: now(),
      lastSeen: now(),
      lastBeat: 0,
      lastPong: 0,
      attempts: 0,
      nextRetryAt: 0,
      remoteGeneration: 0,   // newest offer generation seen from this peer
      link: null
    };
  }

  function publicPeer(record) {
    return {
      id: record.id,
      name: record.name,
      addr: record.addr,
      state: record.state,
      rttMs: record.rttMs,
      ageMs: now() - record.firstSeen
    };
  }

  function peersSnapshot() {
    var out = [];
    for (var id in peers) out.push(publicPeer(peers[id]));
    out.sort(function (a, b) { return a.id < b.id ? -1 : a.id > b.id ? 1 : 0; });
    return out;
  }

  function syncPeers(list) {
    var seen = Object.create(null);
    var joined = [];
    for (var i = 0; i < list.length; i++) {
      var entry = list[i];
      if (!entry || typeof entry.id !== "string" || (self && entry.id === self.id)) continue;
      seen[entry.id] = true;
      var record = peers[entry.id];
      if (!record) {
        record = peers[entry.id] = emptyPeer(entry.id, entry.name);
        joined.push(record);
      }
      if (record.name !== entry.name) record.name = entry.name;
      record.addr = entry.addr;
      record.lastSeen = now();
    }
    for (var id in peers) {
      if (!seen[id]) {
        // Peer vanished from presence: retire its link, keep nothing around.
        var gone = peers[id];
        log("info", "peer " + id.slice(0, 8) + " (" + gone.name + ") left the network");
        closeLink(gone, "left");
        delete peers[id];
        emitEvent({ type: "peer-left", peer: { id: gone.id, name: gone.name } });
      }
    }
    for (var j = 0; j < joined.length; j++) {
      var added = joined[j];
      log("info", "peer " + added.id.slice(0, 8) + " (" + added.name + ") is on this network");
      emitEvent({ type: "peer-found", peer: publicPeer(added) });
      connectPeer(added);
    }
    // Reconnect anything we know about that has no live link (e.g. after wake).
    // Peers that exhausted their attempts wait out a cooldown so an origin that
    // keeps a stale entry (or a peer that is not running the module) cannot make
    // this page rebuild a peer connection every heartbeat.
    for (var id2 in peers) {
      var rec = peers[id2];
      if (!rec.link && rec.state !== "connecting" && now() >= (rec.nextRetryAt || 0)) connectPeer(rec);
    }
  }

  function closeLink(record, reason) {
    var link = record.link;
    record.link = null;
    record.lastBeat = 0;
    record.rttMs = -1;
    if (link) {
      try { if (link.channel) link.channel.close(); } catch (e) { /* ignore */ }
      try { link.connection.close(); } catch (e) { /* ignore */ }
      link.cleanup();
    }
    if (record.state === "open") {
      record.state = "seen";
      emitEvent({ type: "peer-closed", peer: publicPeer(record), reason: reason || "closed" });
    }
  }

  // ------------------------------------------------------------- signaling
  function sendSignal(to, kind, payload) {
    if (!self) return Promise.resolve(false);
    return postJson("/signal", { from: self.id, to: to, kind: kind, payload: payload }, 6000)
      .then(function () { return true; }, function (err) {
        if (err && err.status === 404) {
          // Peer disappeared mid-handshake.
          var record = peers[to];
          if (record) {
            closeLink(record, "gone");
            delete peers[to];
          }
        }
        return false;
      });
  }

  function onSignal(from, kind, payload) {
    var record = peers[from];
    if (kind === "bye") {
      if (record) {
        closeLink(record, "peer said goodbye");
        delete peers[from];
      }
      return;
    }
    if (!record) {
      // A peer that is not in our list (joined between polls): accept it.
      record = peers[from] = emptyPeer(from, null);
      record.lastSeen = now();
      emitEvent({ type: "peer-found", peer: publicPeer(record) });
    }
    record.lastSeen = now();
    var gen = payload && typeof payload.g === "number" ? payload.g : 0;
    if (kind === "offer" && record.link && gen && record.remoteGeneration && gen > record.remoteGeneration) {
      // The sender abandoned its previous connection attempt and built a new
      // one. Our link can never complete against it, so start a fresh one.
      closeLink(record, "newer offer");
    }
    if (!record.link) {
      // Signals can arrive before the presence poll: create the link lazily.
      connectPeer(record);
    }
    var link = record.link;
    if (link) {
      if (kind === "offer" && gen) record.remoteGeneration = gen;
      link.applySignal(kind, payload);
    }
  }

  // ------------------------------------------------------------------ link
  // One peer connection per discovered peer. The lexicographically smaller id
  // offers, so both sides agree on the role without exchanging anything.
  function createLink(record) {
    var pc = new RTCPeerConnection({ iceServers: record.attempts > 0 ? STUN_SERVERS : [] });
    var link = {
      record: record,
      connection: pc,
      channel: null,
      initiator: !!(self && self.id < record.id),
      generation: record.attempts || 1,
      remoteSet: false,      // a remote description has been applied
      remoteClaimed: false,  // an incoming offer is being processed
      offered: false,        // the local offer has been created
      answerApplied: false,
      candidates: [],
      closed: false,
      timer: 0,
      cleanup: function () { /* replaced below */ }
    };

    // Signals for one peer must be applied strictly in order. They arrive in
    // batches (several per poll, or a duplicate after a retry), and applying
    // two answers or an answer before its offer throws "wrong state".
    var chain = Promise.resolve();
    function enqueue(task) {
      chain = chain.then(function () {
        if (link.closed) return null;
        return task();
      }).catch(function (err) {
        log("error", "signal handling failed: " + (err && err.message));
      });
      return chain;
    }

    function addCandidate(payload) {
      try {
        return pc.addIceCandidate({
          candidate: payload.candidate,
          sdpMid: payload.sdpMid == null ? "0" : payload.sdpMid,
          sdpMLineIndex: payload.sdpMLineIndex == null ? 0 : payload.sdpMLineIndex
        }).catch(function () { /* late candidate, harmless */ });
      } catch (e) {
        return Promise.resolve();
      }
    }

    // Candidates can beat the description that defines them; hold them back.
    function flushCandidates() {
      var queued = link.candidates;
      link.candidates = [];
      for (var i = 0; i < queued.length; i++) addCandidate(queued[i]);
    }

    var listeners = [];
    function on(target, name, handler) {
      target.addEventListener(name, handler);
      listeners.push([target, name, handler]);
    }
    link.cleanup = function () {
      link.closed = true;
      if (link.timer) window.clearTimeout(link.timer);
      for (var i = 0; i < listeners.length; i++) {
        try { listeners[i][0].removeEventListener(listeners[i][1], listeners[i][2]); } catch (e) { /* ignore */ }
      }
      listeners.length = 0;
    };

    link.sendSignal = function (kind, payload) { return sendSignal(record.id, kind, payload); };

    function announceOpen(channel) {
      record.state = "open";
      record.rttMs = -1;
      record.lastBeat = now();
      record.lastPong = now();
      record.attempts = 0;
      record.nextRetryAt = 0;
      try {
        channel.send(JSON.stringify({ t: "hello", id: self ? self.id : null, name: myName(), v: VERSION }));
      } catch (e) { /* ignore */ }
      log("info", "peer " + record.id.slice(0, 8) + " (" + record.name + ") connected");
      emitEvent({ type: "peer-open", peer: publicPeer(record) });
    }

    function acceptChannel(channel) {
      if (link.channel === channel) return;
      link.channel = channel;
      try { channel.binaryType = "arraybuffer"; } catch (e) { /* ignore */ }
      on(channel, "open", function () {
        if (link.channel !== channel || link.closed) return;
        if (record.state === "open") return;    // already announced
        // Introduce ourselves so the online list has a name to show.
        announceOpen(channel);
      });
      on(channel, "message", function (event) {
        if (link.channel !== channel || link.closed) return;
        handleMessage(record, event.data);
      });
      on(channel, "close", function () {
        if (link.channel !== channel) return;
        closeLink(record, "channel closed");
      });
      on(channel, "error", function () {
        if (link.channel !== channel) return;
        closeLink(record, "channel error");
      });
      if (channel.readyState === "open" && record.state !== "open") {
        announceOpen(channel);
      }
    }

    on(pc, "icecandidate", function (event) {
      if (link.closed || !event.candidate) return;
      var candidate = event.candidate;
      link.sendSignal("candidate", {
        candidate: candidate.candidate,
        sdpMid: candidate.sdpMid,
        sdpMLineIndex: candidate.sdpMLineIndex,
        usernameFragment: candidate.usernameFragment || null,
        g: link.generation
      });
    });

    on(pc, "connectionstatechange", function () {
      if (link.closed) return;
      var stateName = pc.connectionState;
      if (stateName === "failed" || stateName === "closed") {
        retryLink(record, "connection " + stateName);
      } else if (stateName === "disconnected") {
        // Transient on Wi-Fi, fatal if it does not recover: let the ping loop
        // decide, but do not wait for the browser's much slower "failed".
        record.disconnectedAt = now();
      } else {
        record.disconnectedAt = 0;
      }
    });

    on(pc, "datachannel", function (event) {
      if (link.closed || !event.channel) return;
      acceptChannel(event.channel);
    });

    if (link.initiator) {
      try {
        acceptChannel(pc.createDataChannel(CHANNEL_NAME, { ordered: true }));
      } catch (e) {
        log("error", "could not create the data channel: " + (e && e.message));
      }
    }

    link.applySignal = function (kind, payload) {
      if (link.closed) return;
      if (kind === "offer") {
        // One offer per link: a second one is a stale duplicate, not an
        // invitation to renegotiate. The sender retried; its newer link wins.
        if (link.remoteSet || link.remoteClaimed) return;
        link.remoteClaimed = true;   // claim it before the async apply
        enqueue(function () {
          link.candidates = [];
          return pc.setRemoteDescription({ type: "offer", sdp: payload.sdp })
            .then(function () { return pc.createAnswer(); })
            .then(function (answer) {
              return pc.setLocalDescription(answer).then(function () {
                return link.sendSignal("answer", { sdp: answer.sdp, g: link.generation });
              });
            })
            .then(function () { flushCandidates(); })
            .catch(function (err) {
              link.remoteClaimed = false;
              log("error", "offer handling failed: " + (err && err.message));
              retryLink(record, "offer failed");
            });
        });
      } else if (kind === "answer") {
        // Only the offerer applies an answer, and only once per link. A duplicate
        // arrives when the peer rebuilt its link and answered the same offer
        // twice: if the connection is already up that is a no-op, not a failure.
        if (!link.initiator || link.answerApplied) return;
        if (pc.connectionState === "connected"
            || (link.channel && link.channel.readyState === "open")) {
          return;
        }
        link.answerApplied = true;
        enqueue(function () {
          return pc.setRemoteDescription({ type: "answer", sdp: payload.sdp })
            .then(function () {
              link.remoteSet = true;
              flushCandidates();
            })
            .catch(function (err) {
              // A link that is already up - or whose signalling state is back
              // to "stable" - has an answer applied, and Chrome rejects a
              // second one with "Called in wrong state: stable". That is a late
              // duplicate (the peer rebuilt its link), not a failure, so it must
              // not read as one in the log or re-arm the answer for another
              // doomed attempt. Measured on the packaged client: two pages
              // meeting at boot logged `answer handling failed: ... Called in
              // wrong state: stable` while the link itself was fine.
              if (pc.connectionState === "connected"
                  || (link.channel && link.channel.readyState === "open")
                  || pc.signalingState === "stable") {
                link.answerApplied = true;
                log("info", "ignored a duplicate answer for " + record.id.slice(0, 8)
                    + " (the link is already up)");
                return;
              }
              link.answerApplied = false;
              log("error", "answer handling failed: " + (err && err.message));
              retryLink(record, "answer failed");
            });
        });
      } else if (kind === "candidate") {
        enqueue(function () {
          if (!link.remoteSet) {
            link.candidates.push(payload);
            return null;
          }
          return addCandidate(payload);
        });
      } else if (kind === "text") {
        handleMessage(record, payload);
      }
    };

    link.start = function () {
      if (!link.initiator || link.offered) return;
      link.offered = true;
      try {
        pc.createOffer().then(function (offer) {
          return pc.setLocalDescription(offer).then(function () {
            return link.sendSignal("offer", { sdp: offer.sdp, g: link.generation });
          });
        }).catch(function (err) {
          log("error", "offer creation failed: " + (err && err.message));
          retryLink(record, "offer creation failed");
        });
      } catch (e) {
        retryLink(record, "offer threw");
      }
    };

    link.timer = window.setTimeout(function () {
      if (!link.closed && record.state !== "open") retryLink(record, "timed out");
    }, LINK_TIMEOUT_MS);

    return link;
  }

  function connectPeer(record) {
    if (record.link || !running || !self) return;
    record.state = "connecting";
    record.attempts = (record.attempts || 0) + 1;
    var link = createLink(record);
    record.link = link;
    link.start();
  }

  function retryLink(record, reason) {
    var attempts = record.attempts || 0;
    log("info", "rebuilding the link to " + record.id.slice(0, 8) + " (" + reason + ")");
    closeLink(record, reason);
    if (attempts >= MAX_LINK_ATTEMPTS) {
      record.state = "failed";
      record.nextRetryAt = now() + FAILED_RETRY_MS;
      record.attempts = 0;   // the cooldown restarts the attempt budget
      log("info", "giving up on " + record.id.slice(0, 8) + " for now (" + reason + ")");
      emitEvent({ type: "peer-failed", peer: publicPeer(record), reason: reason });
      return;
    }
    record.state = "seen";
    var delay = Math.min(1000 * Math.pow(2, attempts - 1), 8000);
    window.setTimeout(function () {
      if (running && peers[record.id] === record) connectPeer(record);
    }, delay);
  }

  function handleMessage(record, data) {
    if (typeof data !== "string") return;
    var message;
    try {
      message = JSON.parse(data);
    } catch (e) {
      return;
    }
    if (!message || typeof message.t !== "string") return;
    if (message.t === "hello") {
      if (message.name) record.name = sanitizeName(message.name) || record.name;
      return;
    }
    if (message.t === "ping") {
      var channel = record.link && record.link.channel;
      if (channel && channel.readyState === "open") {
        try { channel.send(JSON.stringify({ t: "pong", s: message.s, at: now() })); } catch (e) { /* ignore */ }
      }
      return;
    }
    if (message.t === "pong") {
      if (typeof message.s === "number") record.rttMs = Math.max(0, now() - message.s);
      record.lastBeat = now();
      record.lastPong = now();
      record.disconnectedAt = 0;
      return;
    }
    emitEvent({ type: "message", peer: publicPeer(record), message: message });
    for (var i = 0; i < messageSubs.length; i++) {
      try { messageSubs[i](record.id, message); } catch (e) { /* subscriber errors are its own */ }
    }
  }

  // -------------------------------------------------------------- presence
  function probe() {
    if (probeInFlight) return Promise.resolve(false);
    probeInFlight = true;
    return fetchJson(apiBase + "/health", { method: "GET" }, PROBE_TIMEOUT_MS)
      .then(function (health) {
        probeInFlight = false;
        if (!health || health.service !== "eagler-lan") {
          throw new Error("not an eagler-lan service");
        }
        service = health;
        serviceMissing = false;
        return true;
      }, function (err) {
        probeInFlight = false;
        lastError = err && err.name === "AbortError" ? "discovery service did not answer" : (err && err.message) || "discovery service unavailable";
        // A 404/405/410 means the origin is there but is an ordinary static
        // host: that is the common case (python -m http.server, a CDN), and the
        // one case where the player can act on the message.
        serviceMissing = !!(err && (err.status === 404 || err.status === 405 || err.status === 410));
        return false;
      });
  }

  function register() {
    if (!running || !self) return Promise.resolve(false);
    return postJson("/presence", { peerId: self.id, name: preferredName, room: room }, 8000)
      .then(function (result) {
        self.id = result.peerId || self.id;
        self.scope = result.scope || null;
        self.room = result.room || room;
        try { window.sessionStorage.setItem(storageKeyId, self.id); } catch (e) { /* ignore */ }
        state = "online";
        lastError = null;
        backoff = 1000;
        if (!polling) startPolling();
        syncPeers(result.peers || []);
        return true;
      }, function (err) {
        lastError = (err && err.message) || "presence failed";
        if (err && err.status === 410) {
          self.id = randomId();
        }
        if (err && err.status === 503) {
          state = "unavailable";
          lastError = "too many peers on this network";
        }
        scheduleHeartbeat(Math.min(backoff *= 2, 20000));
        return false;
      });
  }

  function startPolling() {
    polling = true;
    pollOnce();
  }

  function pollOnce() {
    if (!running || !self || pollInFlight) return;
    pollInFlight = true;
    var url = apiBase + "/events?peerId=" + encodeURIComponent(self.id) + "&waitMs=" + POLL_WAIT_MS;
    fetchJson(url, { method: "GET" }, POLL_WAIT_MS + 8000).then(function (result) {
      pollInFlight = false;
      if (!running) return;
      var events = (result && result.events) || [];
      for (var i = 0; i < events.length; i++) {
        var event = events[i];
        if (event.type === "peers") {
          syncPeers(event.peers || []);
        } else if (event.type === "signal") {
          onSignal(event.from, event.kind, event.payload);
        }
      }
      window.setTimeout(pollOnce, 50);
    }, function (err) {
      pollInFlight = false;
      if (!running) return;
      if (err && err.status === 410) {
        // The server forgot us (restart): register again.
        state = "idle";
        register();
        return;
      }
      lastError = (err && err.message) || "event poll failed";
      window.setTimeout(pollOnce, 2000);
    });
  }

  function scheduleHeartbeat(delayMs) {
    if (timers.heartbeat) window.clearTimeout(timers.heartbeat);
    timers.heartbeat = window.setTimeout(function () {
      timers.heartbeat = 0;
      if (!running) return;
      register().then(function (ok) {
        scheduleHeartbeat(ok ? HEARTBEAT_MS : Math.min(backoff *= 2, 20000));
      });
    }, delayMs == null ? HEARTBEAT_MS : delayMs);
  }

  function beat() {
    if (timers.beat) window.clearTimeout(timers.beat);
    timers.beat = window.setTimeout(function () {
      timers.beat = 0;
      if (!running) return;
      for (var id in peers) {
        var record = peers[id];
        var channel = record.link && record.link.channel;
        if (record.state === "open" && channel && channel.readyState === "open") {
          // Only a pong proves the peer is still there: sending our own ping must
          // not count as liveness, or a page whose timers stalled would keep a
          // dead link forever. One missed window is not death either - ICE
          // recovers on its own after a hiccup and a rebuild costs a whole
          // handshake, so only sustained silence tears the link down.
          var silentForMs = now() - (record.lastPong || record.lastBeat || 0);
          if (silentForMs > DEAD_TIMEOUT_MS
              || (record.disconnectedAt && now() - record.disconnectedAt > DEAD_TIMEOUT_MS)) {
            retryLink(record, "peer stopped answering");
          } else {
            record.lastBeat = now();
            try { channel.send(JSON.stringify({ t: "ping", s: now() })); } catch (e) { /* ignore */ }
          }
        } else if (record.state !== "failed" && !record.link && now() >= (record.nextRetryAt || 0)) {
          connectPeer(record);
        }
      }
      beat();
    }, BEAT_MS);
  }

  // ---------------------------------------------------------------- lifecycle
  function startInternal() {
    if (running || disabled) return Promise.resolve(false);
    running = true;
    state = "probing";
    if (!self) {
      var stored = null;
      try { stored = window.sessionStorage.getItem(storageKeyId); } catch (e) { /* ignore */ }
      self = { id: stored && /^[0-9a-f]{16,32}$/.test(stored) ? stored : randomId(), name: preferredName, room: room, scope: null };
    }
    return probe().then(function (ok) {
      if (!ok) {
        state = "unavailable";
        log("info", "no discovery service on this origin (" + (lastError || "unknown reason") + "); start tools/lan-server.mjs to enable automatic discovery");
        scheduleProbeRetry();
        return false;
      }
      log("info", "discovery service online: scope " + (service && service.scope) + ", room " + room);
      return register().then(function (registered) {
        scheduleHeartbeat(HEARTBEAT_MS);
        beat();
        return registered;
      });
    });
  }

  function scheduleProbeRetry() {
    if (timers.probe) window.clearTimeout(timers.probe);
    timers.probe = window.setTimeout(function () {
      timers.probe = 0;
      if (!running || state === "online") return;
      startInternal();
    }, 30000);
  }

  function stopInternal(sendLeave) {
    if (!running) return;
    running = false;
    polling = false;
    if (timers.heartbeat) window.clearTimeout(timers.heartbeat);
    if (timers.beat) window.clearTimeout(timers.beat);
    if (timers.probe) window.clearTimeout(timers.probe);
    timers.heartbeat = timers.beat = timers.probe = 0;
    for (var id in peers) {
      closeLink(peers[id], "stopped");
      delete peers[id];
    }
    if (sendLeave && self) sendBye(self.id);
    state = disabled ? "disabled" : "idle";
  }

  var polling = false;

  // ---------------------------------------------------------------- messaging
  function sendTo(peerId, message) {
    var record = peers[peerId];
    if (!record || !record.link || !record.link.channel) return false;
    if (record.link.channel.readyState !== "open") return false;
    var text = typeof message === "string" ? message : JSON.stringify(message);
    if (text.length > 262144) return false;
    try {
      record.link.channel.send(text);
      return true;
    } catch (e) {
      return false;
    }
  }

  function broadcast(message) {
    var count = 0;
    for (var id in peers) {
      if (sendTo(id, message)) count++;
    }
    return count;
  }

  // ------------------------------------------------------------------- api
  function statusText() {
    switch (state) {
      case "disabled": return "Automatic LAN discovery is turned off (?lan=off)";
      case "idle": return "Automatic LAN discovery is idle";
      case "probing": return "Checking this page's origin for LAN discovery...";
      case "unavailable": return "Automatic LAN discovery unavailable here (" + (lastError || "no service") + ")"
        + (serviceMissing ? " - run: node tools/lan-server.mjs" : "");
      case "online": {
        var open = 0;
        var seen = 0;
        for (var id in peers) {
          seen++;
          if (peers[id].state === "open") open++;
        }
        return "Automatic LAN discovery: " + open + " connected, " + seen + " found on this network"
          + (self && self.scope ? " (" + self.scope + ")" : "");
      }
      default: return "Automatic LAN discovery: " + state;
    }
  }

  var api = {
    version: VERSION,
    start: function () { return startInternal(); },
    stop: function () { stopInternal(true); },
    restart: function () { stopInternal(true); startInternal(); },
    state: function () { return state; },
    status: statusText,
    self: function () { return self ? { id: self.id, name: myName(), room: self.room, scope: self.scope } : null; },
    peers: peersSnapshot,
    peerCount: function () { return Object.keys(peers).length; },
    openPeerCount: function () {
      var n = 0;
      for (var id in peers) {
        if (peers[id].state === "open") n++;
      }
      return n;
    },
    scope: function () { return (self && self.scope) || (service && service.scope) || null; },
    serviceUrl: function () { return apiBase; },
    lastError: function () { return lastError; },
    setName: function (name) {
      var clean = sanitizeName(name);
      if (!clean) return false;
      preferredName = clean;
      persistName(clean);
      if (self) self.name = clean;
      if (running) register();
      return true;
    },
    send: sendTo,
    broadcast: broadcast,
    invite: function (peerId, payload) {
      return sendTo(peerId, { t: "invite", v: VERSION, payload: payload === undefined ? null : payload });
    },
    onMessage: function (cb) { if (typeof cb === "function") messageSubs.push(cb); return cb; },
    offMessage: function (cb) {
      var i = messageSubs.indexOf(cb);
      if (i >= 0) messageSubs.splice(i, 1);
    },
    onEvent: function (cb) { if (typeof cb === "function") eventSubs.push(cb); return cb; },
    offEvent: function (cb) {
      var i = eventSubs.indexOf(cb);
      if (i >= 0) eventSubs.splice(i, 1);
    },
    // ---- string bridge for the Java platform layer -------------------------
    snapshotJson: function () {
      try {
        return JSON.stringify({
          version: VERSION,
          state: state,
          status: statusText(),
          service: apiBase,
          scope: api.scope(),
          self: api.self(),
          peers: peersSnapshot(),
          openPeers: api.openPeerCount(),
          peerCount: api.peerCount(),
          lastError: lastError
        });
      } catch (e) {
        return "{\"state\":\"error\",\"lastError\":" + JSON.stringify(String(e && e.message)) + "}";
      }
    },
    pollEventsJson: function () {
      try {
        var out = eventQueue;
        eventQueue = [];
        return JSON.stringify(out);
      } catch (e) {
        return "[]";
      }
    },
    sendJson: function (peerId, text) {
      return sendTo(peerId, String(text));
    },
    _debug: {
      probe: probe,
      register: register,
      signal: sendSignal,
      applySignal: onSignal,
      syncPeers: syncPeers,
      rawPeers: function () { return peers; }
    }
  };

  window.__eaglerLAN = api;

  if (!disabled) {
    window.addEventListener("pagehide", function () { stopInternal(true); });
    window.addEventListener("beforeunload", function () { stopInternal(true); });
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) {
        // Keep the links: the game keeps the tab visible anyway. Only stop the
        // heartbeat churn.
        return;
      }
      if (running && state === "online") register();
    });
    // Start immediately: this script is loaded before the client's Wasm, so
    // discovery finishes while the game is still booting.
    api.ready = startInternal();
  } else {
    api.ready = Promise.resolve(false);
    log("info", "automatic LAN discovery disabled by ?lan=off");
  }
})();

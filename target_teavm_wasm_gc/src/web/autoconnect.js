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
  // Kept at 4: the game-side bridge compares this number and gates every call on an
  // exact match, and the engine's new behaviour - starting its own discovery when
  // the page opens, reading a frame by merging its two copies - crosses no new API.
  // A bump here without a wasm rebuild would switch the whole tier off in the
  // shipped artifact, whose game half is the previously built one.
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
  // payload: magic(2) id(2) fingerprint(32) final port(2) crc32(4) = 42 bytes
  var PAYLOAD_MAGIC = 0xA53C;
  var PAYLOAD_BYTES = 42;
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
  // Sixteen, not twenty.  The socket channel pays for its accuracy in getStats()
  // passes: one per reader per sweep through a queue that runs one at a time, so
  // the receiver samples each of its sockets once every K x pass, and a pulse
  // connection has to outlive that or its arrival is never read (measured on a
  // loaded machine: 4-60 s per pass, against 20-60 ms on a quiet one).  K = 16
  // keeps the sweep five times shorter than K = 20 and still gives 4 bits per
  // pulse; it also leaves the connection budget room for retries.  Chrome's
  // lifetime cap is 500 connections per page with no refund on close, the
  // lottery costs ~14 connections per socket (288 for twenty, 224 for sixteen),
  // and a payload frame is 80 pulses - so three attempts only fit if the pool is
  // smaller.
  // Twelve, not sixteen: the frame is sent more than once per attempt now (see
  // FRAME_COPIES - the second copy is the redundancy that makes a single attempt
  // reliable) and a pulse is still a fresh connection, so the ~96 connections the
  // readers stop asking for are ~96 pulses a second copy can spend.  Twelve
  // readers still leave 12-14 in-window sockets, which is 3 bits per pulse and a
  // 107-pulse frame, and the lottery's own rate (~14 connections per socket) puts
  // twelve readers at ~168 connections worst case against ~224 for sixteen.
  var READER_TARGET = 12;       // reader connections = grid sockets
  // The lottery keeps ~7% of its attempts (run 17: 17 sockets from 8 x 32
  // created; run 16: 10 from 6 x 24), so one socket costs ~14 connections and
  // Chrome's 500-connection lifetime cap - with no refund on close - is what
  // actually sizes the carrier.  Twelve batches of 24 is ~288 worst case for
  // the readers, which is what leaves room for the source pool below.
  var LOTTERY_BATCH = 24;
  var LOTTERY_MAX_BATCHES = 8;
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
  // The probe readback does not stop at the first answer: the list it returns is
  // the sender's alphabet, and the peer decodes against its own socket list, so a
  // short list is a *different* alphabet, not a slow transmission.  It polls
  // while rounds still find sockets and stops after two empty ones; this is the
  // ceiling on that.
  var PROBE_WAIT_MS = 30000;
  // A readback with nothing in it yet is not a settled readback: the first answer
  // can take seconds to appear at all (see the stall rule below).  It is also the
  // ceiling on waiting for a wider one.  Waiting longer looks tempting - the list's
  // size is what a frame costs (8 columns is 3 bits per pulse and a 112-pulse copy,
  // 12 columns is 3.58 bits and 94) - but was tried and reverted: a readback that
  // keeps polling while the peer starts its own exchange reads back the peer's
  // short-lived *pulse* sockets too (31 and 33 sockets for a peer with 16 readers),
  // and a column the peer does not own is a pulse that never lands.  Measured the
  // other way: a readback settled at 6 of 12 sockets kept polling to its full 45 s
  // deadline for a socket that never came, and both peers paid it - 33 s spent to
  // shorten the frame by ~16 s.  Past this many milliseconds a settled list is
  // taken as it is and the frame is coded in base K instead (see baseKNeed).
  var PROBE_SETTLE_MIN_MS = 12000;
  // One check per port, and that is a measured choice, not a default: firing every
  // port twice (from a different scanner, so the second check is not a no-op) was
  // implemented to widen a short readback, and it does the opposite.  On this host
  // the peer's address is the machine's own, so any local socket bound there answers
  // a check - the readback then reads *closing* sockets and the peer's transient
  // connections as columns, which is item 26 of the findings, and the second shot
  // doubles the chances of catching one.  Measured on the shipped artifact with two
  // shots: the readback came back with 32 sockets for a peer that owns 14, so 20 of
  // the frame's columns were sockets nothing could land on, 55 of 148 pulses never
  // arrived and neither side decoded.  One shot read 6-12 of 12 in the same runs.
  // A wider alphabet is worth having - it is a shorter frame - but it has to be
  // earned by confirming the sockets, not by asking twice.
  var PROBE_SHOTS = 1;
  // A readback below this many sockets is not an alphabet the frame can be aimed
  // at, so it is probed once more rather than proceeded on.
  var PROBE_RETRY_WAIT_MS = 15000;
  // Rounds without a new socket before the readback calls the list settled (see the
  // stop rule in probeFromScanners).
  var PROBE_STALL_ROUNDS = 3;
  // A readback that yields fewer than two sockets is not an alphabet at all: the
  // socket channel fires at one socket per symbol and refuses to aim at a single
  // column.  A peer whose readers are not up yet answers nothing, so the probe
  // is repeated until it can be aimed at, up to this ceiling.
  var PROBE_RETRY_MS = 60000;
  var PROBE_RETRY_GAP_MS = 6000;
  // Two columns is the floor the channel can be aimed at, but the alphabet's size
  // *is* the frame's length: 2 columns is one bit per pulse, a 320-pulse frame and
  // 3 minutes of transmission.  Keep probing for a wider one while the peer's
  // readers are still answering their first checks.
  var PROBE_MIN_COLUMNS = 4;
  // The list is probed at most this many times per attempt (see probePeerPorts).
  var PROBE_WAVES = 2;
  // What the readback is *aimed* at: below this many sockets the frame is coded
  // across fewer than eight columns (under three bits per pulse), and a readback
  // that settles there is still polled until the deadline, because the checks are
  // already in flight and the peer's readers are what is late.
  var PROBE_TARGET_COLUMNS = 8;
  var BURST_FIRES = 40;          // fires per tick, so the stats thread keeps up
  var BURST_GAP_MS = 12;
  // The locate readback visits every scanner once per round and only then looks
  // at its deadline, so a round has to fit inside it on a loaded machine.
  var LOCATE_WAIT_MS = 30000;
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
  // Three, not one: one frame is one clean read of ~80 pulse arrivals, and a
  // loaded receiver misses some.  Each attempt is a fresh set of connections and
  // a fresh frame, so the odds compound instead of resetting.  This was 1, which
  // made a single unlucky read the end of the run ("no payload decoded after 1
  // attempts").
  var MAX_ATTEMPTS = 3;
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
  var EXCHANGE_POLL_MS = 200;   // reader cycle while a frame is in flight
  // How long this side waits for the peer's final socket to answer.  A frame is
  // two copies of ~65 s now, and one side routinely decodes a whole copy after
  // the other, so the wait has to cover the peer's exchange rather than a poke
  // burst (measured: with 25 s the side that was ready first reported "the final
  // connection did not come up" 700 ms before the peer dialled its port, and the
  // link then came up on a state machine that had already given up).  A connect
  // that arrives later than this is still reported - see `up()` in finishFinal -
  // so this is how long the *promise* waits, not how long the carrier tries.
  var CONNECT_TIMEOUT_MS = 90000;
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
  // selfId is a display string (and may be replaced by the game layer through
  // setIdentity with whatever it uses).  The payload has two bytes for it, so the
  // numeric form is kept here rather than coerced at every use: `"12bb" >> 8` is
  // 0, and that is what the peer received for every payload ever sent.
  var selfIdNum = (function () {
    var v = parseInt(selfId, 16);
    return (isFinite(v) && v > 0 && v <= 0xFFFF) ? v : 1;
  })();
  function refreshSelfIdNum() {
    var v = parseInt(selfId, 16);
    selfIdNum = (isFinite(v) && v > 0 && v <= 0xFFFF) ? v : 1;
  }
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
  // How long the final connection keeps poking the peer's advertised port.  It
  // has to cover the peer's own exchange: both sides run 64 s frames, so a side
  // that decoded on its first attempt can wait a whole attempt for the other.
  // Long enough for the peer's *retry*: a frame is ~82 s and a side that fails its
  // first attempt starts another, so a peer can decode two minutes after this side
  // did.  Measured on the shipped artifact: the side that decoded first let its
  // final connection go to "failed" (no traffic, consent expired) 90 s later, and
  // the peer's second attempt then dialled a dead socket.  The pokes are what keep
  // it alive, so they outlive the peer's whole attempt loop.
  var FINAL_POKE_MS = 300000;
  var connectedPeer = null;

  var arrivals = [];                    // { pc, port, source, at }
  var stuckUntil = Object.create(null);
  var ownWindowPorts = Object.create(null);
  var peerAddress = null;
  var peerPort = 0;                     // first peer socket seen probing us
  var proofPorts = [];                  // peer sockets this page has already proved
  var peerTargets = [];                 // peer sockets to aim at (probe readback)
  // True while the discovery sweep still has rounds to fire.  Discovery is over
  // as soon as a peer is found or it starts probing us, and the pump must stop
  // then - it is 130k checks over 24 connections, and it outlives the sweep's own
  // return by minutes if nobody tells it to stop.
  var sweepActive = false;
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
  // The page's time origin, so a stats timestamp given as "milliseconds since the
  // origin" can be put on the same clock as now() (Date.now()).
  var statsOrigin = (window.performance && performance.timeOrigin) || 0;

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

  // The code tier's budget is about a player who is looking for a peer, and the game
  // says so by naming them (see adoptName and start).  The module's own boot start
  // has no name, so this clock waits for the game's call rather than running from the
  // page's first moment: a page that has only been opened never raises the fallback,
  // and the game's prompt - which shows a code screen when this flag is set - cannot
  // interrupt somebody who is still on the title screen.
  function startFallbackClock() {
    if (codeMode || fallbackTimer || fallbackRaised || state === "connected") return;
    fallbackTimer = window.setTimeout(maybeFallback, FALLBACK_AFTER_MS);
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
    if (!pc) return Promise.resolve(false);   // a pool that was refreshed mid-burst
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
  // EWMA of one getStats() pass.  Every adaptive timing in this file is built on
  // it: how long the page takes to answer decides how often a reader can be
  // sampled, and therefore how long a pulse connection has to stay alive for the
  // peer to read it.
  var statsPassMs = 30;

  // Urgent reads first, but not forever.  Serving the high queue until it is
  // empty starves everything else when the reads are slow: measured on a loaded
  // machine with 16 readers timing out at 4 s each, the high queue was never
  // empty, no census job ever reached the slot, and the engine sat in phase 1.5
  // for three minutes without printing a single line.  A timed-out job is dropped
  // rather than run later: its caller has already given up, and running it would
  // spend the one slot on an answer nobody reads.
  var STATS_HIGH_BURST = 4;
  var statsServedHigh = 0;
  var statsDropped = 0;

  function pumpStats() {
    while (statsInFlight < STATS_MAX_IN_FLIGHT && (statsHigh.length || statsNormal.length)) {
      var job;
      if (statsHigh.length && (statsServedHigh < STATS_HIGH_BURST || !statsNormal.length)) {
        job = statsHigh.shift();
        statsServedHigh++;
      } else {
        job = statsNormal.shift() || statsHigh.shift();
        statsServedHigh = 0;
      }
      if (job.cancelAt && now() > job.cancelAt) {
        statsDropped++;
        logThrottled("stats", "dropped " + statsDropped + " read(s) whose caller timed out while"
          + " they waited for the queue slot", 3000);
        job.resolve(null);
        continue;
      }
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

  function withStats(fn, high, cancelAt) {
    return new Promise(function (resolve, reject) {
      (high ? statsHigh : statsNormal).push({ fn: fn, resolve: resolve, reject: reject, cancelAt: cancelAt || 0 });
      pumpStats();
    });
  }

  function pcStats(pc, high, timeoutMs) {
    var t0 = now();
    return withStats(function () {
      return withTimeout(pc.getStats(), STATS_JOB_TIMEOUT_MS);
    }, high, timeoutMs ? t0 + timeoutMs : 0).then(function (stats) {
      var took = now() - t0;
      if (took > 0 && took < STATS_JOB_TIMEOUT_MS) statsPassMs = statsPassMs * 0.7 + took * 0.3;
      return stats || EMPTY_STATS;
    });
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
  // When a packet was received on a pair, on the page's own clock.
  //
  // A reader's read time is not an arrival time: the read happens on the reader's
  // own schedule, and a poll can be seconds late (measured read latencies of 7 ms
  // to 2128 ms for pulses fired 600 ms apart) - which is why sorting arrivals by
  // read time put 14-24% of them out of the order they were fired in, and no
  // payload could ever decode.  The pair stats carry the receipt itself:
  // candidate-pair.lastPacketReceivedTimestamp is stamped when the packet is
  // processed, to the millisecond, in one clock for every reader.  Chrome has
  // reported this as epoch milliseconds, as milliseconds since the page's time
  // origin and (older builds) as seconds; all three are normalised here, and a
  // value that does not land in a plausible window is rejected rather than used.
  function receivedAtOf(value) {
    var t = typeof value === "number" ? value : Number(value);
    if (!isFinite(t) || t <= 0) return 0;
    if (t > 1e12) return t;                       // epoch milliseconds
    if (t < 1e6) return t * 1000 + statsOrigin;    // seconds since the origin
    return t + statsOrigin;                        // ms since the origin
  }

  function candidatesWithSockets(pc, timeoutMs) {
    return pcStats(pc, true, timeoutMs).then(function (stats) {
      var localOf = {}, portOf = {}, recvOf = {}, rows = [];
      stats.forEach(function (s) {
        if (s.type !== "candidate-pair" || !s.localCandidateId || !s.remoteCandidateId) return;
        localOf[s.remoteCandidateId] = s.localCandidateId;
        var at = receivedAtOf(s.lastPacketReceivedTimestamp);
        if (at && at > (recvOf[s.remoteCandidateId] || 0)) recvOf[s.remoteCandidateId] = at;
      });
      stats.forEach(function (s) {
        if (s.type === "local-candidate" && s.id) portOf[s.id] = s.port;
      });
      stats.forEach(function (s) {
        if (s.type === "remote-candidate") {
          var at = recvOf[s.id] || 0;
          // A receipt stamp has to be *this* read's: a reader polls its own pair
          // every 200 ms while a frame is in flight (2 s at most when a stats pass
          // is slow), so a real arrival's stamp is at most a couple of seconds old.
          // Chrome's pair timestamps are not always that - some pairs carry a
          // stamp seconds in the past - and a stale stamp is not a late arrival,
          // it is a *misordered* one: the frame is read in stamp order, so an
          // arrival dated 8 s early lands in the middle of the symbol sequence and
          // shifts everything after it, which no checksum can repair (measured with
          // the diag log: consecutive arrival stamps jumped backwards by 7962 ms).
          // 120 s was the old window, wide enough to accept exactly that.
          var plausible = at && at > now() - STALE_STAMP_MS && at < now() + 1000;
          rows.push({
            id: s.id, port: s.port, candidateType: s.candidateType,
            localPort: portOf[localOf[s.id]] || 0,
            receivedAt: plausible ? at : 0
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
  // How old a pair's "last packet received" stamp may be and still count as this
  // arrival's own time (see the note in candidatesWithSockets).
  var STALE_STAMP_MS = 3000;
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
      // Local offer only: the peer's answer (which is what carries the *real*
      // DTLS fingerprint) cannot be applied here, because the time to apply it is
      // after the payload has been decoded and the fingerprint is known - and a
      // second remote description on the same connection is not allowed.  The
      // local description alone is enough to gather the socket whose port the
      // payload advertises and to keep it for the whole run: applying the answer
      // later does not restart ICE or move that socket.
      return pc.createOffer().then(function (offer) {
        return pc.setLocalDescription({ type: "offer", sdp: mungeCreds(offer.sdp) });
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
    sweepActive = true;
    var pumpDone = false;
    var pumpGraceUntil = 0;
    var pumpCursor = 0;
    var batchCursor = 0;
    var pollCursor = 0;
    var pollRounds = 0;

    function pumpRound() {
      if (!running || found || !sweepActive || peerPort) { pumpDone = true; return Promise.resolve(); }
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
  function probeFromScanners(address, ports, label, deadlineMs, minColumns, shots) {
    if (!address || !scanners.length || !ports.length) return Promise.resolve([]);
    // Every port is fired `shots` times, each from a different scanner: the same
    // (source, target) pair is a no-op at the ICE layer (see fire), so a second
    // shot from the same connection would fire nothing at all.  A dropped packet
    // costs a column, and a lost column is a longer frame for both peers.
    var want = Math.max(1, shots || 1);
    var targets = [];
    for (var r = 0; r < want; r++) {
      for (var p = 0; p < ports.length; p++) {
        targets.push({ port: ports[p], scanner: (p + r * 7 + (r ? 3 : 0)) % scanners.length });
      }
    }
    var i = 0;
    function burst() {
      var n = 0;
      while (i < targets.length && n < BURST_FIRES) {
        fire(scanners[targets[i].scanner], address, targets[i].port);
        i++; n++;
      }
      if (i >= targets.length) return Promise.resolve();
      return sleep(BURST_GAP_MS).then(burst);
    }
    var found = Object.create(null);
    log("ports", label + ": " + targets.length + " check(s) over " + scanners.length
      + " scanner(s) (~" + Math.ceil(targets.length / scanners.length) + " each, "
      + want + " shot(s) per port)");
    return burst().then(function () {
      var started = now();
      var deadline = started + deadlineMs;
      var lastCount = -1;
      var stalled = 0;
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
          // readers, and this list is the sender's *alphabet* - the peer numbers
          // its own sockets and expects the same list in the same order, so a
          // truncated readback is not a slow transmission, it is a different
          // alphabet and the CRC never matches (measured: 9 of 17 sockets read
          // back in a 15 s deadline, and three exchange frames decoded nothing).
          // The stop condition is therefore progress, not a clock: keep reading
          // while a round still finds a socket, and stop after two that find
          // none.  The ceiling is the deadline the caller passes.
          var count = Object.keys(found).length;
          if (count > lastCount) {
            lastCount = count;
            stalled = 0;
          } else {
            stalled++;
          }
          var elapsed = now() - started;
          // Two rounds with no new socket is only a *settled* readback once
          // something was found and the connection has had time to work through
          // its pairs: a check is sent when Chrome's ICE gets to it, not when
          // the candidate is added, and on a busy page that first answer can be
          // seconds away (measured: a readback that stopped after 2.9 s and 0
          // sockets in a run whose peer had 17).
          if (count === 0 && elapsed < PROBE_SETTLE_MIN_MS) {
            stalled = 0;
          }
          // A short readback has not stopped growing, it has stopped answering
          // *yet*: every check was already fired when this poll started, and ICE
          // paces its queued checks over the next minute (item 6 measured probe
          // arrivals 66 s after the probe), so waiting longer costs time and no
          // connections.  And the list's size *is* the frame's length - a settled
          // 4-socket readback is a 160-pulse copy at two bits per pulse, which is
          // past any hole-filling, while the same sockets in base 4 every pulse
          // still carries two bits and twelve sockets in base 12 is a 90-pulse
          // copy - so a readback below the target keeps polling to the deadline.
          var settled = stalled >= PROBE_STALL_ROUNDS
            && (count > 0 || elapsed >= PROBE_SETTLE_MIN_MS);
          var wide = !minColumns || count >= minColumns;
          // A settled list is taken once it is wide enough *or* once waiting for a
          // wider one has cost as much as the pixels it saves: a 6-socket list is a
          // 130-pulse copy and a 12-socket list a 94-pulse one, so a socket is
          // worth ~2 x 36 x gap ms of frame and no more than that of polling.
          if (settled && (wide || elapsed >= PROBE_SETTLE_MIN_MS)) {
            log("ports", label + ": readback settled at " + count + " socket(s) after "
              + Math.round(elapsed) + " ms (" + stalled + " round(s) with no new one"
              + (wide ? "" : "; the frame will be coded in base " + count) + ")");
            return;
          }
          if (now() > deadline) {
            log("ports", label + ": readback hit its " + Math.round(elapsed / 1000)
              + " s deadline with " + count + " socket(s)"
              + (wide ? "" : "; the frame will be coded in base " + count));
            return;
          }
          return sleep(600).then(poll);
        });
      })();
    }).then(function () {
      var out = Object.keys(found).map(Number).sort(function (a, b) { return a - b; });
      log("ports", label + " answered with " + out.length + " socket(s): " + (out.join(",") || "none"));
      return out;
    });
  }

  // A note on a lever that was tried and reverted here: *confirming* the readback
  // before announcing it, by re-firing one check per candidate from a fresh
  // out-of-window connection and dropping the ports that do not answer a second time.
  // It is the obvious answer to a phantom column (a socket that was already going
  // away still answers the probe once, and the peer can then never observe that
  // column - see item 26), and it does catch one.  It is not shippable as measured:
  // in the run that produced it the readback was *exact* (11 sockets for a peer with
  // 11, 12 for a peer with 12) and the confirmation still dropped four live reader
  // sockets after 15 s of polling, which is a 120-pulse frame instead of a 94-pulse
  // one - a worse trade than the rare dead port it removes.  The honest reading is
  // that "did this socket answer a second time" is not a reliable question on this
  // host: ICE paces the checks a connection carries, and a live socket whose answer
  // is merely late is indistinguishable from one that has gone.
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
  // `seeds` are sockets this page has already *proved* carry the peer: the port
  // the sweep answered from (its pair succeeded) or the port that probed us (its
  // arrival is in the arrival list).  They are unioned into the probe result: a
  // probe readback is a snapshot of which pairs ICE has got to yet, so it can
  // come back with one socket or none while the peer has seventeen, and the
  // alphabet needs at least two.
  function probePeerPorts(address, seeds) {
    var ports = [];
    for (var p = W_START; p <= W_END; p++) ports.push(p);
    var wave = 0;
    var set = Object.create(null);
    function run() {
      wave++;
      return probeFromScanners(address, ports, "peer socket probe",
        wave === 1 ? PROBE_WAIT_MS : PROBE_RETRY_WAIT_MS,
        PROBE_TARGET_COLUMNS, PROBE_SHOTS).then(function (found) {
        for (var i = 0; i < found.length; i++) set[found[i]] = true;
      for (var s = 0; s < (seeds || []).length; s++) {
        if (!seeds[s]) continue;
        // A seed only names a symbol column if it is a *reader* socket: the window
        // is the peer's identity, the probe fires at in-window ports only, and the
        // receiver reads arrivals on its readers.  The responder path seeds the
        // port the peer's first check came from, which is often one of its scanners
        // or probes (measured: 55334, out of window) - unioning that in adds a
        // column no pulse can ever land on, and the sweep then announces a list
        // with a member the peer cannot receive on, which costs the pulses aimed
        // at it - a different alphabet is not at stake any more, only lost pulses.
        if (seeds[s] < W_START || seeds[s] > W_END) {
          log("ports", "peer socket probe: ignoring seed " + seeds[s]
            + " - outside the " + W_START + "-" + W_END + " reader window");
          continue;
        }
        if (!set[seeds[s]]) log("ports", "peer socket probe missed " + seeds[s]
          + ", which this page has already seen the peer on: seeding it in");
        set[seeds[s]] = true;
      }
      peerTargets = Object.keys(set).map(Number).sort(function (a, b) { return a - b; });
      // The list *is* the alphabet, so a readback too short to be one is probed
      // again rather than proceeded on: the frame's whole length is decided here,
      // and one more wave costs checks that are free next to the frame it shortens.
      if (peerTargets.length < PROBE_MIN_COLUMNS && wave < PROBE_WAVES) {
        log("ports", "peer socket probe: " + peerTargets.length
          + " socket(s) is not an alphabet to code a frame in; probing again");
        return run();
      }
      return peerTargets;
      });
    }
    return run();
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
  // The label costs one stats read per connection, and a loaded page answers
  // those slowly.  This used to fire once, sleep 2 s and read once: a slow pass
  // then labelled a handful of sockets and the exchange ran on a three-socket
  // alphabet that could never decode a payload - the run's own log said
  // "census: reader sockets: 3 of 20 labelled" and it carried on regardless.
  // Now it keeps asking until every connection is labelled or the deadline
  // passes, and re-fires for whatever is still unlabelled: a pair that has
  // already carried a check produces no new answer, so each round uses the next
  // port above the window (nothing binds those either).
  var CENSUS_DEADLINE_MS = 45000;
  function censusConnections(pcs, label) {
    if (!peerAddress || !pcs.length) return Promise.resolve(0);
    var deadline = now() + CENSUS_DEADLINE_MS;
    var roundIndex = 0;
    function done() {
      var n = 0;
      pcs.forEach(function (pc) { if (pc.__acChosen) n++; });
      log("census", label + ": " + n + " of " + pcs.length + " labelled"
        + (n < pcs.length ? " (census deadline after " + roundIndex + " round(s))" : ""));
      return n;
    }
    function round() {
      var pending = [];
      for (var i = 0; i < pcs.length; i++) if (!pcs[i].__acChosen) pending.push(pcs[i]);
      if (!pending.length || now() > deadline) return Promise.resolve(done());
      var censusPort = CENSUS_PORT - (roundIndex % 64);
      roundIndex++;
      logThrottled("census", label + ": round " + roundIndex + " - firing " + pending.length
        + " check(s) at port " + censusPort + " (stats pass " + Math.round(statsPassMs) + " ms)", 1000);
      var fired = Promise.resolve();
      pending.forEach(function (pc) {
        fired = fired.then(function () { return fire(pc, peerAddress, censusPort); });
      });
      return fired.then(function () {
        logThrottled("census", label + ": round " + roundIndex + " fired", 1000);
        return sleep(CENSUS_WAIT_MS);
      }).then(function () {
        return mapLimited(pending, STATS_BATCH, function (pc) {
          return usedLocalPorts(pc).then(function (map) {
            var chosen = map[censusPort] || 0;
            if (chosen && !pc.__acChosen) pc.__acChosen = chosen;   // the pair is pruned later; keep the label
          });
        });
      }).then(function () {
        logThrottled("census", label + ": round " + roundIndex + " read back", 1000);
        return round();
      });
    }
    return round();
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

  // Every in-window socket this page owns.  This - not the census label and not
  // the peer's list - is the alphabet a received pulse is indexed against: the
  // peer probes our in-window ports and numbers the ones that answer, so the
  // sorted list of our own in-window sockets is the same list in the same order,
  // and a pulse that landed on socket X decodes as X's index.
  function ownSocketAlphabet() {
    var set = Object.create(null);
    for (var i = 0; i < readers.length; i++) {
      var win = readers[i].win || [];
      for (var j = 0; j < win.length; j++) if (inWindow(win[j])) set[win[j]] = true;
    }
    var out = [];
    for (var p in set) out.push(Number(p));
    out.sort(function (a, b) { return a - b; });
    return out;
  }

  // -------------------------------------------------------- readers: arrivals
  function pollReader(reader) {
    if ((reader.stuckUntil || 0) > now()) return Promise.resolve();
    var t0 = now();
    return withTimeout(candidatesWithSockets(reader.pc, reader.timeoutMs || POLL_TIMEOUT_MS),
        reader.timeoutMs || POLL_TIMEOUT_MS).then(function (rows) {
      var took = now() - t0;
      if (!rows) {
        // Backoff and timeout belong to the reader, not to a socket key: the old
        // `stuckUntil[reader.win[0]]` let one slow report delay every reader in
        // the same cycle and then skipped the key for everyone.
        reader.fails = (reader.fails || 0) + 1;
        var inFrame = exchange.active && !exchange.done;
        reader.stuckUntil = now() + (inFrame ? EXCHANGE_POLL_MS
          : Math.min(STUCK_BACKOFF_MS * reader.fails, STUCK_BACKOFF_MS_MAX));
        reader.timeoutMs = inFrame ? Math.min(POLL_TIMEOUT_MS, POLL_TIMEOUT_MS_MAX)
          : Math.min((reader.timeoutMs || POLL_TIMEOUT_MS) + 750, POLL_TIMEOUT_MS_MAX);
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
        var observedAt = now();
        // The arrival's own clock when the pair can supply it (see
        // receivedAtOf), the read time only as a fallback.
        var at = r.receivedAt || observedAt;
        // The socket a pulse landed on: reported per pair when available, and
        // otherwise unambiguous because a reader aims at one port.
        var port = r.localPort || (reader.win.length === 1 ? reader.win[0] : 0);
        if (!port) continue;
        arrivals.push({ reader: reader, port: port, source: r.port, at: at, obsAt: observedAt });
        // The settle gate below asks "has anything arrived lately?", so it must not
        // be dragged backwards by a stamp that is older than the arrival itself.
        if (at > lastArrivalAt) lastArrivalAt = at;
        if (DIAG) log("diag", "arrival seq=" + arrivals.length + " port=" + port
          + " source=" + r.port + " at=" + at + " obs=" + observedAt
          + " precise=" + (r.receivedAt ? 1 : 0) + " readerwin=" + reader.win.join("/"));
        if (arrivals.length > 6000) arrivals.splice(0, arrivals.length - 4000);
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
      // The cycle follows the measured pass cost: asking for a read every 250 ms
      // while one read takes four seconds queues sixteen jobs a second that
      // cannot be served, and the backlog is what starved the census and the
      // probe (and, on the read side, dated arrivals against reads that were
      // issued long before them).
      var cycle = Math.max(POLL_CYCLE_MS, Math.min(2000, statsPassMs));
      // While a frame is in flight the readers are the *clock* of the decode: a
      // pulse's symbol is only readable in the order it was fired, and a reader
      // that is backed off past the pulse gap reports its arrival after the next
      // pulse's (measured: 15-21% of the sampled pulses read out of order at a
      // 600 ms gap, all of them on readers that had just backed off).  During the
      // exchange the readers poll tight and do not back off, which is affordable
      // because the frame is the only thing running: the peer's own census, probe
      // and sweep are all over by then.
      if (exchange.active && !exchange.done && cycle > EXCHANGE_POLL_MS) cycle = EXCHANGE_POLL_MS;
      if (preparing) return sleep(cycle).then(step);
      return pollReader(reader).then(function () {
        return sleep(cycle).then(step);
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

  // CRC-32 (IEEE), not CRC-16.  The hole search is the only judge of a frame that
  // arrived short, and it accepts a candidate on the checksum alone.  A 16-bit
  // checksum accepts a wrong candidate once in 65536 tries, so a copy whose search
  // enumerates 20000 candidates comes back with a 30% chance of being *wrong* - and
  // the magic bytes cannot help, because the holes sit behind them and every
  // candidate carries the same magic.  That is not hypothetical: the first run of
  // tmp/autoconnect/test-decode.mjs after the base-K change returned a payload with
  // another fingerprint's 32 bytes from a four-hole base-12 copy (8466 candidates,
  // no false-accept luck needed - 13% per such copy), and a wrong fingerprint burns
  // the attempt on a socket that will never answer.  CRC-32 takes that to 1 in 2^32
  // per candidate, which is what makes the enumeration safe to run at all.
  function crc32(bytes) {
    var crc = -1;                                   // 0xFFFFFFFF, as a signed int
    for (var i = 0; i < bytes.length; i++) {
      crc = crc ^ (bytes[i] & 0xFF);
      for (var j = 0; j < 8; j++) crc = (crc >>> 1) ^ (0xEDB88320 & -(crc & 1));
    }
    return (crc ^ -1) >>> 0;
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
    body.push((selfIdNum >> 8) & 0xFF, selfIdNum & 0xFF);
    var fp = fingerprintBytes(fingerprint);
    for (var i = 0; i < 32; i++) body.push(fp[i] || 0);
    body.push((finalPort >> 8) & 0xFF, finalPort & 0xFF);
    var crc = crc32(body);
    body.push((crc >>> 24) & 0xFF, (crc >>> 16) & 0xFF, (crc >>> 8) & 0xFF, crc & 0xFF);
    return { bytes: body, bits: bitsFromBytes(body) };
  }

  function parsePayload(bits) {
    if (!bits || bits.length < PAYLOAD_BITS) return null;
    var bytes = bytesFromBits(bits, PAYLOAD_BYTES);
    if (((bytes[0] << 8) | bytes[1]) !== PAYLOAD_MAGIC) return null;
    var body = bytes.slice(0, bytes.length - 4);
    var crc = ((bytes[bytes.length - 4] << 24) | (bytes[bytes.length - 3] << 16)
      | (bytes[bytes.length - 2] << 8) | bytes[bytes.length - 1]) >>> 0;
    if (crc32(body) !== crc) return null;
    var fp = [];
    for (var i = 0; i < 32; i++) fp.push(bytes[4 + i]);
    var port = (bytes[36] << 8) | bytes[37];
    if (!port) return null;
    // The final connection is built out of the window by construction (that is
    // what keeps it from answering a probe), so a payload advertising an in-window
    // port is not a payload.  It is also what makes the hole search safe: a bare
    // CRC16 accepts a wrong 4096-candidate frame about once in sixteen, and this
    // takes that to about one in a thousand frames.
    if (inWindow(port)) return null;
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
  var SYM = { t0: 0, cols: [], perPulse: 0, pulses: [], seen: {}, settled: 0, frameMs: 0, bestStream: 0, peerCols: null, peerKey: "" };
  // The socket channel is the carrier unless a run is explicitly asked for the
  // legacy grid (window.__eaglerAC.start({ socket: false })).
  // Diagnostic, off unless the page asks for it (`...&acdiag=1`): one line per
  // arrival (which of our sockets it landed on, the source port it came from,
  // and the order the read reported it in) and one per sampled outgoing pulse
  // (its index, symbol and the ports its own connection owns).  A harness can
  // then pair arrivals to pulses by source port and measure - without guessing -
  // whether the order the reader reports is the order the sender fired.
  var DIAG = /(^|[?&])acdiag=1(&|$)/.test(location.search || "");
  var useSocketChannel = true;
  var SYM_SETTLE_MS = 250;      // a pulse is read this long after the last one
  // Two receipts on one socket closer than this are one pulse (its two local
  // sockets), not two symbols: the pulse gap is never this small.
  // Wide enough for the two copies of a pulse (ICE may pace the two sockets a
  // few hundred ms apart) and narrower than the pulse gap, so it can never merge
  // two symbols: pulses are fired PULSE_GAP_MS apart (600 ms at the floor).
  var PULSE_DUP_MS = 400;
  // How many stray arrivals the decoder may skip when the contiguous pass fails,
  // and over how many start positions it may look for them.
  var DECODE_SKIPS = 3;
  var DECODE_STARTS = 12;
  // How many arrival bursts one read offers the decoder: a copy of the frame is a
  // burst, the sweep's rounds are others, and the whole span is tried last.
  var DECODE_BURSTS = 4;
  // Operations the skip pass may spend per candidate alphabet (a few hundred
  // thousand symbol steps run in well under a second).
  var DECODE_BUDGET = 400000;
  // What one read may spend decoding in total, across every (burst, candidate)
  // attempt: the read loop polls every 400 ms and the readers have to get their
  // stats passes in, so a read never occupies the page indefinitely.
  var DECODE_READ_BUDGET = 4000000;
  var decodeOpsLeft = DECODE_READ_BUDGET;
  // Floor for the adaptive gap below (see pulseGapMs).  It is the frame's clock:
  // a copy is `need` x gap long, so 112 pulses at 600 ms is 67 s and the two
  // copies 136 s of a 186 s run.  The gap only has to clear the *reader's* read
  // cadence - a pulse cannot be reported before the first poll that follows it,
  // and the reader round over twelve readers costs ~250-400 ms - and 600 was
  // twice that.  450 keeps more than a cadence of margin, stays above
  // PULSE_DUP_MS (the window that folds a pulse's two arrivals into one symbol),
  // and any read that still puts a pulse in the neighbouring slot is repaired by
  // the two merged copies (see decodeSlottedPair).
  var SYM_PULSE_GAP_MS = 450;
  // A pulse connection only has to live until the peer's reader has sampled its
  // arrival.  The old fixed 700 ms at an 80 ms gap assumed a ~53 ms report
  // lattice, measured on a quiet page; a loaded page answers a stats pass in
  // seconds, so the read missed the arrival and no payload was ever decoded (the
  // logs of the failing runs are full of "reader stat poll timed out (4200 ms)").
  // Both numbers are derived from the page's own measured pass cost: the peer's
  // sweep visits each of its sockets once every K x pass, so a pulse has to
  // outlive three sweeps, and the train must not run far ahead of the reads that
  // date it.  Leaving pulses open is what made the reads slow, hence the cap.
  function peerColumnCount() {
    return Math.max(2, SYM.cols.length || READER_TARGET);
  }
  function pulseLifeMs() {
    return Math.min(8000, Math.max(1250, 3 * peerColumnCount() * statsPassMs));
  }
  // The gap has to clear the *reader's* poll cadence, not the sender's stats
  // cost.  Each reader polls its own connection on its own clock, so the moment a
  // reader reports an arrival is its own poll time - a pulse cannot be observed
  // before the first poll after it lands.  If the gap is shorter than that
  // cadence, the read timestamps of two consecutive pulses can invert, and the
  // receiver's stream is no longer the sender's symbol sequence.  Measured with
  // the diag lines (analyse-order.py, out/ac-diag1.log): an 80 ms gap gave a
  // 15-374 ms read latency and 24-28% of the sampled pulses out of order - which
  // no alphabet, CRC or start offset can repair, so 320 bits never decoded.
  // G > C is the condition that makes the order exact: pulse k is always reported
  // before pulse k+1 once the gap exceeds the cadence the readers actually run at.
  function readerCadenceMs() {
    return Math.max(POLL_CYCLE_MS, Math.min(2000, statsPassMs)) + Math.min(600, statsPassMs);
  }
  function pulseGapMs() {
    var cadence = readerCadenceMs();
    return Math.min(1500, Math.max(SYM_PULSE_GAP_MS, Math.round(cadence * 1.6)));
  }
  var lastStreamReadAt = 0;
  var STREAM_READ_MS = 400;          // a stream read is not free
  var STREAM_LOOKBACK_MS = 30000;    // a peer frame that started before ours

  // Bits per pulse = floor(log2(columns)), so the symbol always indexes a real
  // column and the alphabet is a power of two (a symbol outside it would aim at
  // an undefined port).  Six sockets -> 2 bits, ten -> 3, sixteen -> 4.
  function symPerPulse(K) {
    if (K < 2) return 0;
    var b = 0, v = 1;
    while (v * 2 <= K) { v *= 2; b++; }
    return b;
  }

  // ------------------------------------------------- base-K on one big integer
  // The alphabet is the peer's column list and a symbol is the *index* of the
  // column a pulse landed on, so K columns carry log2(K) bits per pulse and no
  // power of two is needed.  Reading the payload out of a power-of-two *prefix*
  // of the list (8 of 12 sockets, 3 bits per pulse) wastes a third of the
  // alphabet, and it lengthens the frame exactly when the readback is short:
  // seven sockets read back is a 160-pulse copy at two bits and a 114-pulse copy
  // in base 7, and the pulse count is the whole reliability question (a copy is
  // decodable while it loses no more than a few pulses).  So the payload is one
  // integer in base K, most significant digit first, padded to `need` digits.
  var baseKNeedCache = Object.create(null);
  // The number of base-K digits that covers every PAYLOAD_BITS-bit value: the
  // smallest n with K^n >= 2^PAYLOAD_BITS.
  function baseKNeed(K) {
    var cached = baseKNeedCache[K];
    if (cached) return cached;
    var limit = Math.pow(2, PAYLOAD_BITS), v = 1, n = 0;
    while (v < limit) { v *= K; n++; }
    baseKNeedCache[K] = n;
    return n;
  }
  // The payload as an arbitrary-precision integer in base 2^16, least significant
  // word first.  PAYLOAD_BITS is a multiple of 16, so every word is full.
  function bitsToWords(bits) {
    var words = [];
    for (var end = bits.length; end > 0; end -= 16) {
      var start = Math.max(0, end - 16), w = 0;
      for (var i = start; i < end; i++) w = (w << 1) | (bits[i] ? 1 : 0);
      words.push(w);
    }
    return words;
  }
  function wordsToBits(words, bitCount) {
    var bits = new Array(bitCount);
    for (var i = 0; i < bitCount; i++) bits[i] = 0;
    var pos = bitCount - 1;
    for (var w = 0; w < words.length && pos >= 0; w++) {
      var v = words[w];
      for (var b = 0; b < 16 && pos >= 0; b++) { bits[pos--] = v & 1; v >>= 1; }
    }
    return bits;
  }
  // Payload bits -> exactly `need` base-K digits, most significant first.  One
  // running remainder per word keeps every intermediate under 2^53 for K <= 64.
  function payloadToDigits(bits, K) {
    var need = baseKNeed(K);
    var words = bitsToWords(bits);
    var digits = [];
    while (digits.length < need) {
      var rem = 0;
      for (var i = words.length - 1; i >= 0; i--) {
        var cur = rem * 65536 + words[i];
        words[i] = Math.floor(cur / K);
        rem = cur - words[i] * K;
      }
      while (words.length && words[words.length - 1] === 0) words.pop();
      digits.push(rem);
    }
    digits.reverse();
    return digits;
  }
  // `need` base-K digits -> the payload's bits, or null when a digit is out of
  // range for this K (an arrival that is not a column is filtered before this).
  function digitsToWords(digits, K) {
    var words = [];
    for (var i = 0; i < digits.length; i++) {
      var d = digits[i];
      if (!(d >= 0 && d < K)) return null;
      var carry = d;
      for (var j = 0; j < words.length; j++) {
        var cur = words[j] * K + carry;
        words[j] = cur & 0xFFFF;
        carry = (cur - words[j]) / 65536;
      }
      if (carry) words.push(carry);
    }
    return words;
  }
  function digitsToBits(digits, K) {
    var words = digitsToWords(digits, K);
    return words ? wordsToBits(words, PAYLOAD_BITS) : null;
  }
  // Word arithmetic for the hole search: a candidate fill is the base value plus one
  // multiple of a power of K per hole, so a candidate is re-derived in ~500
  // operations instead of a multiply-accumulate per digit (which the enumeration
  // pays twenty thousand times over).
  function wordsTimesSmall(w, m) {
    var out = [], carry = 0;
    for (var i = 0; i < w.length; i++) {
      var cur = w[i] * m + carry;
      out.push(cur & 0xFFFF);
      carry = (cur - out[i]) / 65536;
    }
    while (carry) { out.push(carry & 0xFFFF); carry = (carry - (carry & 0xFFFF)) / 65536; }
    return out;
  }
  function wordsAdd(a, b) {
    var out = [], carry = 0;
    var n = Math.max(a.length, b.length);
    for (var i = 0; i < n || carry; i++) {
      var cur = (i < a.length ? a[i] : 0) + (i < b.length ? b[i] : 0) + carry;
      out.push(cur & 0xFFFF);
      carry = (cur - out[i]) / 65536;
    }
    return out;
  }
  function wordsOfPow(K, exp) {
    var w = [1];
    for (var e = 0; e < exp; e++) w = wordsTimesSmall(w, K);
    return w;
  }

  // -------------------------------------- the column list, told and not guessed
  // A pulse's symbol is *which of the receiver's own sockets* it lands on, so the
  // sender has to number the peer's sockets and the receiver has to number its
  // own the same way.  Deriving that list twice - the sender from its probe
  // readback, the receiver from the sockets that look busy - is what kept the CRC
  // from ever matching: they are two estimates of one list, not one list, and a
  // list that differs by a single member is a different alphabet, which no
  // checksum can repair (measured: the sender aimed at 10-13 sockets, 3 bits per
  // pulse, while the receiver's busy-socket filter carried 15-17, 3 to 4 bits per
  // pulse, so every frame was read against the wrong numbering).
  //
  // So the list is transmitted.  Before every frame the sender sweeps its columns
  // in ascending order, one pulse each, three times over.  An arrival names the
  // port it landed on absolutely, so a sweep can be read without an alphabet of
  // its own, and the receiver ends up holding exactly the list the sender aims
  // at.  The silences make the sweep distinguishable from the frame: 400 ms
  // between the pulses of a round, 2800 ms between rounds and before the frame,
  // so a burst of arrivals is one round or one frame, and a round is short while
  // a frame is not.
  var ANNOUNCE_ROUNDS = 3;
  // A column that arrived this many times was seen in that many of the sweep's
  // rounds - the check that catches a *partial* sweep read (see readColumnSweep).
  var ANNOUNCE_CONFIRM = 2;
  var ANNOUNCE_GAP_MS = 300;        // between the pulses of one sweep round
  var ANNOUNCE_SILENCE_MS = 2000;   // between rounds, and before the frame
  var ANNOUNCE_SPLIT_MS = 1900;     // silence that ends a burst (see arrivalBursts)
  var ANNOUNCE_MAX_ENTRIES = 32;    // a sweep round is at most this many pulses
  // The frame is transmitted more than once per attempt.  A pulse the receiver's
  // stats pass never reports shifts every symbol after it, and no checksum can
  // repair a frame read against a shifted sequence (measured: one side decoded a
  // frame at position 0 while the other logged 109 arrivals for a 107-pulse
  // frame and never decoded it).  The decoder searches every start offset, so a
  // clean second copy is read even when the first one is not.
  // More than one copy per attempt.  A second copy was tried and reverted once,
  // while a pulse *was* a fresh connection and the readers spent ~288 connections:
  // two copies of 107 pulses each pushed both pages past Chrome's no-refund
  // 500-connection lifetime cap and the *final* handshake failed instead (measured:
  // both sides decoded two complete copies and then both final connections went to
  // "failed" two milliseconds after "connected").  The readers now ask for twelve
  // (READER_TARGET) instead of sixteen, which is ~96 connections a second copy can
  // spend, so the redundancy sits inside the attempt rather than only in the retry
  // loop: a frame damaged by a hole or a stray arrival costs one copy, not the run.
  // Each copy is separated from the next by a silence longer than any gap inside a
  // frame, so the receiver reads them as separate bursts and decodes each on its
  // own.
  var FRAME_COPIES = 2;
  var COPIES_GAP_MS = 2600;         // between copies: longer than any gap inside one

  // A pulse connection must own no *in-window* socket.  Every other connection in
  // this engine is forced out of the window by construction, and pulses were the
  // exception: they are made on demand, one per symbol, and the OS puts their
  // ephemeral sockets wherever it likes - the window (50000-50511) is inside the
  // ephemeral range, so about one in thirty lands in it.  Such a socket answers a
  // peer's probe exactly like a reader socket, which is a *wrong* column in the
  // peer's announced list (measured: with the readback still running when the
  // peer's sweep started, it read back 31 and 33 of the peer's 16 reader sockets,
  // so the announced list named sockets the peer did not own and the frame landed
  // 18 of 160 pulses).
  // It was tried the other way round - force every pulse connection out of the
  // window with makeOutOfWindow(1) - and reverted, because it is paid for in
  // dropped pulses: that check needs a stats pass per connection, the pass costs
  // seconds with a couple of hundred connections open, and under that load the
  // out-of-window retries ran out of rounds and the pulse was never fired at all
  // (measured on the shipped artifact: "out-of-window pool: 0/1" between pulses
  // for a whole frame, and 6 of 107 pulses never arrived on the peer).  A missed
  // pulse is a hole in the frame; a socket in the window only matters if the peer
  // happens to probe while it lives, and the readback settles before the exchange
  // starts besides.  So: one connection, no port check, and the pulse is what is
  // protected.
  function pulseConnection() {
    return makeSharedPc();
  }

  // One pulse on its own connection: fire, keep the connection long enough for
  // the peer's reader to report the arrival, then let it go.
  function pulseOnce(port, life) {
    return pulseConnection().then(function (pc) {
      if (!pc) return false;
      var sent = fire(pc, peerAddress, port);
      window.setTimeout(function () { closePc(pc); }, life);
      return sent;
    });
  }

  // Tell the peer which of its sockets this side will aim at - one list pulse, fired
  // from one of our *reader* sockets.
  //
  // The sweep and the frame are the same mechanism - a connection fired at a column -
  // so nothing about an arrival says which of the two sent it, and that is what the
  // frame's read pays for: the peer's sweep lands inside our frame (measured on the
  // shipped artifact: 48 extra arrivals over a 188-pulse frame, 24 of them the peer's
  // sweep, read against an alphabet that was exactly right, and no decode).  The
  // socket class is the one thing the sender controls.  A list pulse leaves from a
  // reader, whose socket is in the window by construction; a frame pulse leaves from
  // whatever ephemeral socket the OS hands a fresh connection, which is in the window
  // about three times in a hundred.  So an arrival whose source is in the window is
  // list traffic, and the frame's read drops it - at the price of the three frame
  // pulses in a hundred whose own socket happens to land in the window, which is what
  // the slot read and the two merged copies are for.  Firing from the readers also
  // costs no connections: the sweep used to spend one per pulse.
  //
  // The stride keeps every (socket, target) pair distinct across the sweep's rounds,
  // because the same pair fired twice is silently a no-op at the ICE layer (see
  // fire) - that would lose a list pulse rather than send it twice.
  function listPulse(col, round, colIndex, life) {
    var R = readers.length;
    var pc = null;
    if (R >= 3) {
      var reader = readers[(colIndex + round * 7) % R];
      if (reader && reader.pc) pc = reader.pc;
    }
    if (!pc) return pulseOnce(col, life);
    return fire(pc, peerAddress, col).then(function (sent) {
      return sent === false ? pulseOnce(col, life) : sent;
    });
  }

  function announceColumns(cols, gen) {
    var life = pulseLifeMs();
    var started = now();
    var fired = 0;
    var chain = Promise.resolve();
    for (var r = 0; r < ANNOUNCE_ROUNDS; r++) {
      for (var k = 0; k < cols.length; k++) {
        chain = chain.then(function (port, colIndex, round) {
          return function () {
            if (gen !== exchange.gen || !running || state === "connected") return null;
            return listPulse(port, round, colIndex, life).then(function () {
              fired++;
              return sleep(ANNOUNCE_GAP_MS);
            });
          };
        }(cols[k], k, r));
      }
      chain = chain.then(function () {
        if (gen !== exchange.gen || !running || state === "connected") return null;
        return sleep(ANNOUNCE_SILENCE_MS);
      });
    }
    return chain.then(function () {
      var want = cols.length * ANNOUNCE_ROUNDS;
      log("sym", "column list sent: " + cols.length + " socket(s) x " + ANNOUNCE_ROUNDS
        + " round(s) = " + fired + " of " + want + " pulse(s) in " + (now() - started)
        + " ms (" + ANNOUNCE_GAP_MS + " ms apart, " + ANNOUNCE_SILENCE_MS
        + " ms of silence between rounds)" + (fired < want ? " - cut short" : ""));
      return fired === want;
    });
  }

  function transmitSocket() {
    var gen = ++exchange.gen;
    var payload = buildPayload();
    var cols = SYM.cols.slice();
    if (cols.length < 2) {
      log("grid", "no peer socket to aim at (" + cols.length + " column(s))");
      return Promise.resolve(false);
    }
    // Every socket the probe settled on carries a digit, not just a power-of-two
    // prefix of them (see payloadToDigits): the frame is as short as the list can
    // make it, which is the one thing that decides whether a copy survives.
    var K = cols.length;
    var n = baseKNeed(K);
    var gap = pulseGapMs();
    var life = pulseLifeMs();
    // The sweep has to be inside the frame's budget: the receiver only decodes a
    // frame it has the list for, and the list is sent first.
    var announceMs = ANNOUNCE_ROUNDS * (cols.length * ANNOUNCE_GAP_MS + ANNOUNCE_SILENCE_MS);
    SYM.t0 = now();
    SYM.cols = cols;
    SYM.perPulse = Math.round(Math.log(K) / Math.LN2 * 100) / 100;
    SYM.pulses = [];
    SYM.seen = Object.create(null);
    SYM.bestStream = 0;
    // The payload's digits, one per pulse, most significant first.
    var symbols = payloadToDigits(payload.bits, K);
    SYM.copies = FRAME_COPIES;
    SYM.frameMs = announceMs + FRAME_COPIES * n * gap
      + (FRAME_COPIES - 1) * COPIES_GAP_MS + life + 2500;
    log("sym", "transmitting " + payload.bits.length + " bits as " + n + " pulse(s) x "
      + (Math.round(Math.log(K) / Math.LN2 * 100) / 100) + " bit(s) in base " + K
      + " over " + cols.length + " peer socket(s), " + FRAME_COPIES
      + " copy/copies; " + gap + " ms apart, kept " + life + " ms (stats pass "
      + Math.round(statsPassMs) + " ms) - after a " + Math.round(announceMs / 1000)
      + " s column sweep: " + cols.join(","));
    var i = 0;
    var copy = 0;
    var copyStarted = now();
    var pulsesAtCopyStart = 0;
    function tick() {
      if (i >= n || !running || state === "connected" || gen !== exchange.gen) {
        var fired = SYM.pulses.length - pulsesAtCopyStart;
        log("sym", "frame copy " + (copy + 1) + " of " + FRAME_COPIES + ": " + fired
          + " of " + n + " pulse(s) in " + (now() - copyStarted) + " ms");
        return Promise.resolve(fired);
      }
      // One pulse per tick, on its own fresh connection: arrivals are read back in
      // time order, so two pulses in flight at once could swap order and corrupt
      // two symbols at once.  A connection carries one pulse because a second
      // check from the same connection to the same target is silently no-op - and
      // because carrying a whole pass over the column list on one connection was
      // measured to lose half the pulses in the real client (see section 5 of
      // tmp/autoconnect/SERVERLESS-LAN-FINAL.md).
      var sym = symbols[i];
      var target = cols[sym];
      var rec = { i: i, sym: sym, target: target, at: 0, pc: null };
      i++;
      return pulseConnection().then(function (pc) {
        if (!pc) return false;
        rec.pc = pc;
        rec.at = now();
        SYM.pulses.push(rec);
        return fire(pc, peerAddress, target).then(function (sent) {
          window.setTimeout(function () { closePc(pc); }, life);
          return sent;
        });
      }).then(function () {
        if (DIAG && rec.pc && rec.i % 10 === 0) {
          // Sampled, not every pulse: this is a stats read, and the frame's own
          // timing is what is being measured.
          udpPorts(rec.pc).then(function (ports) {
            log("diag", "pulse i=" + rec.i + " sym=" + rec.sym + " target=" + rec.target
              + " src=" + ports.join(",") + " at=" + Date.now());
          });
        }
        return sleep(gap);
      }).then(tick);
    }
    function runCopy() {
      i = 0;
      copyStarted = now();
      pulsesAtCopyStart = SYM.pulses.length;
      return tick().then(function (sent) {
        copy++;
        if (copy >= FRAME_COPIES || !sent || !running || state === "connected"
          || gen !== exchange.gen) return sent;
        log("sym", "sending the payload again after " + COPIES_GAP_MS + " ms of silence:"
          + " a frame the receiver read short or with a stray in it costs one copy, and"
          + " the decoder reads each copy as its own burst");
        return sleep(COPIES_GAP_MS).then(runCopy);
      });
    }
    // The list first, then the frames it numbers: a receiver that never saw the
    // sweep has no numbering to read a frame against.
    return announceColumns(cols, gen).then(runCopy);
  }

  // The peer's pulse stream, in arrival order and deduplicated by the source
  // port that carried each check (a pulse connection owns two ports and only one
  // of them carries the check).  Nothing here is timed against our own pulse
  // schedule any more: the stream is the peer's symbols, and the reader below
  // finds the payload inside it by content.
  function collectSocketArrivals() {
    var out = [];
    var seen = Object.create(null);
    // The *read* time first, and the pair's own receipt stamp only as a tie-break
    // inside one read.  The read time is this page's own clock and it is monotonic;
    // Chrome's per-pair receipt stamps are not - some pairs carry a stamp seconds
    // in the past (measured: consecutive arrival stamps jumping backwards by 7.9 s,
    // and a single 2 s stale stamp splitting a sweep round of 8 into bursts of 5
    // and 3, which is a sweep this reader then misreads).  A pulse is 600 ms and a
    // reader polls every 200 ms while a frame is in flight, so the read order is
    // the send order; the stamp is finer-grained and is the only order there is
    // for two arrivals that came back in the same read.
    var list = arrivals.slice().sort(function (a, b) {
      var d = (a.obsAt || a.at) - (b.obsAt || b.at);
      return d !== 0 ? d : (a.at - b.at);
    });
    var bySource = Object.create(null);
    for (var k = 0; k < list.length; k++) {
      var s = list[k].source;
      bySource[s] = (bySource[s] || 0) + 1;
    }
    var dropped = 0;
    var collapsed = 0;
    SYM.lastByPort = Object.create(null);
    for (var i = 0; i < list.length; i++) {
      var a = list[i];
      if (SYM.t0 && a.at < SYM.t0 - STREAM_LOOKBACK_MS) continue;
      var key = a.port + "/" + a.source;
      if (seen[key]) continue;
      seen[key] = true;
      // A pulse is one check from one connection, so its source port appears in
      // exactly one arrival.  Everything else on the wire is a multi-fire - the
      // 512-check socket probe, the sweep, the locate readback, the census - and
      // each of those source ports appears on *every* socket of ours it could
      // reach.  They are junk in the symbol stream and they arrive for a minute
      // after the probe (Chrome paces the checks its ICE has queued).  Measured:
      // 44 probe arrivals landed in the peer's stream 66 s after the probe, and a
      // frame read carries 9-35 of them.
      if (bySource[a.source] > 1) { dropped++; continue; }
      // And a symbol is an arrival on a socket of ours that the peer chose on
      // purpose: ours are the in-window reader sockets, and everything else this
      // page owns was forced out of the window by construction.  The window is
      // the second filter, and it is the one that holds when a check was fired
      // once but aimed at something that is not a receiver (a census check at a
      // throwaway port, a probe that read one of our out-of-window sockets).
      // An arrival on one of our own in-window sockets is a column, whether or not
      // that socket is in the registered set: the probe readback is a *snapshot*
      // of the peer's readers, and a reader can gain another in-window socket after
      // the snapshot was taken - the peer aims its whole frame at it, and if this
      // page filters those arrivals out because its own registration is stale, the
      // column can never gather the repeats that would put it in a candidate list.
      // Measured on the shipped artifact: a peer announced twelve sockets, this
      // side could derive only nine of them from arrivals (three sockets' arrivals
      // arrived on in-window sockets that were not in its registered set), read 221
      // of 212 arrivals of the frame and decoded nothing - a loop it could not
      // leave, because the arrivals that would have confirmed the list were the
      // arrivals being dropped.  The window is the whole rule for arrivals a reader
      // reports (a reader only ever reports its own sockets), so nothing else is
      // filtered here any more.
      var isColumn = inWindow(a.port)
        || ownWindowPorts[a.port] === true
        || !!(SYM.peerCols && SYM.peerCols.indexOf(a.port) >= 0);
      if (!isColumn) { dropped++; continue; }
      // One pulse, two arrivals: a pulse connection owns two local UDP sockets
      // and ICE checks the remote candidate from both, so the same check lands on
      // the peer's socket twice - with the same destination port and different
      // source ports, read by the same reader (measured: 218 single-source
      // arrivals for a 107-pulse frame, and the 2:1 ratio is what no decoder can
      // read, because it takes consecutive arrivals as consecutive symbols).
      // Both copies of one pulse are stamped with the same receipt second - the
      // two checks leave together - so everything landing on the same socket
      // within a short window is one pulse.  (Pulses of *different* symbols are
      // hundreds of ms apart; two pulses can share a socket only if the symbol
      // repeated, and then they are still separated by the gap.)
      var prev = SYM.lastByPort[a.port];
      if (prev !== undefined && a.at - prev < PULSE_DUP_MS) { collapsed++; continue; }
      SYM.lastByPort[a.port] = a.at;
      // The sweep's pulses leave from a reader socket - in the window by
      // construction - and a frame's pulse leaves from whatever socket the OS hands
      // the fresh connection it was fired on, which is outside the window about 97
      // times in a hundred.  So the source class says which read an arrival belongs
      // to, and the frame's read drops the list's.  The *list* read keeps every
      // arrival, because the sweep is exactly what it is looking for.
      a.list = inWindow(a.source);
      out.push(a);
    }
    if (dropped && dropped > (SYM.droppedSeen || 0)) {
      SYM.droppedSeen = dropped;
      log("sym", "stream: dropped " + dropped + " arrival(s) from multi-fire connections"
        + " or aimed outside the reader window (probe/sweep/census traffic), kept "
        + out.length + " pulse candidate(s)");
    }
    if (collapsed && collapsed > (SYM.collapsedSeen || 0)) {
      SYM.collapsedSeen = collapsed;
      log("sym", "stream: collapsed " + collapsed + " arrival(s) that shared a socket and a read"
        + " (the two local sockets of one pulse), leaving " + out.length + " symbol(s)");
    }
    // How close the stream is to the frame the peer actually fired: a decoder
    // that needs consecutive symbols is exact only when these two numbers agree.
    var framePulses = SYM.pulses ? SYM.pulses.length : 0;
    if (framePulses && out.length !== SYM.lastKept) {
      SYM.lastKept = out.length;
      logThrottled("sym", "stream: " + out.length + " symbol(s) for a " + framePulses
        + "-pulse frame (" + (out.length === framePulses ? "exact"
        : (out.length > framePulses ? out.length - framePulses + " extra"
          : framePulses - out.length + " missing")) + ")", 1500);
    }
    SYM.seen = seen;
    return out;
  }

  // Read a payload out of a frame that is missing a pulse or two.
  //
  // A pulse costs a fresh connection, and under load Chrome silently drops some of
  // those checks: measured on this engine, a 107-pulse frame whose sender logged
  // "107 of 107 pulse(s)" was read back as 105 arrivals - "2 pulse(s) never
  // arrived".  A frame read short cannot be decoded at all: every symbol after the
  // hole is shifted by one, and the CRC then rejects a frame that was otherwise
  // perfect.
  //
  // The sender's cadence is fixed and the receipts are dated, so the arrivals say
  // where the holes are: consecutive arrivals are one gap apart, and a gap of
  // about twice that is one pulse that never came.  Which symbol it carried is not
  // in the arrivals, so it becomes a variable and the CRC decides - at most
  // DECODE_HOLES holes of K symbols each - 32768 candidates at K = 8 - and only
  // on a read that has a complete frame's worth of arrivals in hand, so the cost
  // is paid once per copy rather than per poll.  The payload's own port check (see
  // parsePayload) keeps the false-candidate rate at 8^5 / 2^16 x 0.8% instead of
  // one in two.
  var DECODE_HOLES = 5;
  // The search enumerates K^holes digit combinations, so the allowance moves with
  // the alphabet: 8 columns may lose five pulses of a 107-pulse copy (4.7%) and 12
  // columns three of a 90-pulse copy (3.3%), while the search itself stays at or
  // under 32768 candidates either way.
  function holesAllowed(K) {
    var h = 0, v = 1;
    while (h < DECODE_HOLES && v * K <= 32768) { v *= K; h++; }
    return h;
  }
  // The slots of one read: slot q is pulse q of the copy that was read, taken from
  // the arrival *times* (the sender's cadence, measured off the read times for the
  // same reason the stream is ordered by them - see collectSocketArrivals - because
  // a stale receipt stamp would read as a hole that is not there) rather than from
  // the position an arrival has in the stream.  A pulse that never arrived leaves
  // its slot empty (-1); a second arrival in a slot that is already filled is a
  // stray and changes nothing about the symbol that was sent - which is why a slot
  // read survives the two arrivals a pulse leaves on its socket, and why a reader
  // that reports two pulses in the opposite order to the one they were sent in
  // still puts each of them in its own slot.
  function slotDigits(frame, cols, leadHoles) {
    var n = frame.length;
    if (n < 4) return null;
    var when = function (a) { return a.obsAt || a.at; };
    var gaps = [];
    for (var i = 1; i < n; i++) gaps.push(when(frame[i]) - when(frame[i - 1]));
    var sorted = gaps.slice().sort(function (a, b) { return a - b; });
    var gap = sorted[sorted.length >> 1];          // the sender's cadence, measured
    if (!(gap > 150)) return null;
    var byCol = Object.create(null);
    for (var c = 0; c < cols.length; c++) byCol[cols[c]] = c;
    var slots = [];
    for (var s = 0; s < leadHoles; s++) slots.push(-1);   // the first pulse was lost
    var at0 = when(frame[0]) - (leadHoles ? gap : 0);
    for (var a = 0; a < n; a++) {
      var col = byCol[frame[a].port];
      if (col === undefined) continue;             // not a column: not a symbol
      var k = Math.round((when(frame[a]) - at0) / gap);
      if (k < 0) continue;
      while (slots.length < k) slots.push(-1);     // a slot nothing landed in
      if (slots.length === k) slots.push(col);
    }
    return { slots: slots, gap: gap };
  }

  function decodeSlotted(frame, cols, leadHoles) {
    var K = cols.length;
    var need = baseKNeed(K);
    var read = slotDigits(frame, cols, leadHoles);
    if (!read) return null;
    var slots = read.slots;
    var gap = read.gap;
    if (slots.length < need) return null;          // the tail is still arriving
    var holes = [];
    for (var q = 0; q < need; q++) if (slots[q] < 0) holes.push(q);
    if (!holes.length || holes.length > holesAllowed(K)) return null;
    var total = Math.pow(K, holes.length);
    // The base value is this read with every hole read as a zero digit, and each
    // hole adds one multiple of its own power of K - so a candidate costs a small
    // multiply-add per hole and one pass to bits, not a reconstruction per digit.
    var digits = new Array(need);
    for (var q2 = 0; q2 < need; q2++) digits[q2] = slots[q2] < 0 ? 0 : slots[q2];
    var base = digitsToWords(digits, K);
    if (!base) return null;
    var places = [];
    for (var hp = 0; hp < holes.length; hp++) {
      places.push(wordsOfPow(K, need - 1 - holes[hp]));
    }
    for (var cand = 0; cand < total; cand++) {
      // This is the only unbounded work the decoder does, and a copy damaged past
      // what a hole search can repair would otherwise be re-enumerated on every
      // poll until the copy ends - so every candidate is charged to the read's
      // budget and the search stops when the read has spent it all.
      decodeOpsLeft -= need + 24;
      if (decodeOpsLeft < 0) return null;
      var t = cand, value = base;
      for (var h = holes.length - 1; h >= 0; h--) {
        var d = t % K;
        t = Math.floor(t / K);
        if (d) value = wordsAdd(value, wordsTimesSmall(places[h], d));
      }
      var parsed = parsePayload(wordsToBits(value, PAYLOAD_BITS));
      if (parsed) return { parsed: parsed, holes: holes.slice(), cand: cand, gap: gap };
    }
    return null;
  }

  // The two copies of one frame are the same digit sequence sent twice, so a read
  // that holds both of them is an erasure code rather than two chances.  Each
  // copy's holes are filled from the other copy, a slot the two disagree on is left
  // to the CRC (a stray arrival on one side, or a read whose own clock put a pulse
  // in the neighbouring slot), and only a slot *neither* copy carried is unknown.
  //
  // That is the failure both failing runs of the shipped artifact ended in: a copy
  // that lost four pulses of a 130-pulse frame is undecodable on its own - the slot
  // read needs `need` slots and the contiguous read needs every arrival in order -
  // while the same two copies merged decode on the first candidate.  The copies are
  // aligned by pulse index, so the search tries the four ways their first pulses
  // can be missing.
  var DECODE_PAIR_CANDIDATES = 2048;
  function decodeSlottedPair(a, b, cols) {
    var K = cols.length;
    var need = baseKNeed(K);
    if (!need || !a || !b || a === b) return null;
    for (var la = 0; la < 2; la++) {
      for (var lb = 0; lb < 2; lb++) {
        var A = slotDigits(a, cols, la);
        var B = slotDigits(b, cols, lb);
        if (!A || !B) continue;
        if (A.slots.length < need || B.slots.length < need) continue;
        var digits = new Array(need);
        var loose = [];        // slots that are not one agreed digit, and their values
        var holes = 0;
        var clashes = 0;
        var total = 1;
        var over = false;
        for (var q = 0; q < need; q++) {
          var x = A.slots[q], y = B.slots[q];
          if (x >= 0 && y >= 0) {
            if (x === y) { digits[q] = x; continue; }
            digits[q] = x < y ? x : y;
            loose.push({ q: q, values: x < y ? [x, y] : [y, x] });
            clashes++;
            total *= 2;
          } else if (x >= 0 || y >= 0) {
            digits[q] = x >= 0 ? x : y;
          } else {
            var all = [];
            for (var v = 0; v < K; v++) all.push(v);
            digits[q] = 0;
            loose.push({ q: q, values: all });
            holes++;
            total *= K;
          }
          if (total > DECODE_PAIR_CANDIDATES) { over = true; break; }
        }
        if (over) continue;
        if (!loose.length) {
          // Both copies carried every slot and agreed on all of them: there is
          // nothing to search, and the CRC decides on this one reading.
          var straight = parsePayload(digitsToBits(digits, K));
          if (straight) return { parsed: straight, holes: 0, clashes: 0, cand: 0, total: 1 };
          continue;
        }
        var base = digitsToWords(digits, K);
        if (!base) continue;
        // The candidate is the base with one digit changed per loose slot: the base
        // already holds each slot's *lowest* value, so a candidate only ever adds
        // (value - lowest) x K^place, exactly as the hole search does.
        var places = [];
        for (var s = 0; s < loose.length; s++) {
          places.push(wordsOfPow(K, need - 1 - loose[s].q));
        }
        for (var cand = 0; cand < total; cand++) {
          decodeOpsLeft -= need + 16 * loose.length;
          if (decodeOpsLeft < 0) return null;
          var t = cand, value = base;
          for (var s2 = loose.length - 1; s2 >= 0; s2--) {
            var vals = loose[s2].values;
            var d = t % vals.length;
            t = Math.floor(t / vals.length);
            var delta = vals[d] - vals[0];
            if (delta > 0) value = wordsAdd(value, wordsTimesSmall(places[s2], delta));
          }
          var parsed = parsePayload(wordsToBits(value, PAYLOAD_BITS));
          if (parsed) {
            return { parsed: parsed, holes: holes, clashes: clashes, cand: cand, total: total };
          }
        }
      }
    }
    return null;
  }

  // Read a payload out of a stream of base-K digits: for every position, try to
  // take the next `need` arrivals as digits of `alphabet` and let the CRC decide.
  function decodeStream(stream, alphabet, budget) {
    var K = alphabet.length;
    var need = baseKNeed(K);
    var cols = [];
    for (var c = 0; c < stream.length; c++) cols.push(alphabet.indexOf(stream[c].port));
    var digits = new Array(need);
    for (var start = 0; start + need <= stream.length; start++) {
      decodeOpsLeft -= need;
      if (decodeOpsLeft < 0) return null;
      var usable = true;
      for (var i = 0; i < need; i++) {
        var col = cols[start + i];
        if (col < 0) { usable = false; break; }
        digits[i] = col;
      }
      if (!usable) continue;
      var parsed = parsePayload(digitsToBits(digits, K));
      if (parsed) return { parsed: parsed, start: start, alphabet: alphabet };
    }
    // Second pass, for a stream that carries a few arrivals the sender never
    // fired: a frame is a fresh connection per pulse, and the wire carries stray
    // single-check traffic too (measured: 9-27 extra symbols in a 107-pulse
    // frame, and every one of them shifts the symbol sequence for the rest of
    // the read, so the contiguous pass above can only succeed when the strays sit
    // at the edges).  This pass allows up to DECODE_SKIPS arrivals to be skipped,
    // over a handful of start positions, with a hard operation budget so a loaded
    // page never spends a whole frame inside the decoder.
    if (budget <= 0) return null;
    var spills = [];                    // backtracking, not copying: this is the hot loop
    var ops = 0;
    var maxStart = Math.min(stream.length - need, DECODE_STARTS);
    for (var s2 = 0; s2 <= maxStart; s2++) {
      var hit = walk(s2, 0, DECODE_SKIPS);
      if (hit) return { parsed: hit, start: s2, alphabet: alphabet };
      if (ops > budget) break;
    }
    function walk(pos, k, left) {
      if (k === need) return parsePayload(digitsToBits(spills, K)) || null;
      if (pos >= stream.length) return null;
      if (++ops > budget) return null;
      decodeOpsLeft -= 2;
      if (decodeOpsLeft < 0) return null;
      // Consume this arrival as the next digit.
      var col = cols[pos];
      if (col >= 0) {
        spills.push(col);
        var got = walk(pos + 1, k + 1, left);
        if (got) return got;
        spills.pop();
      }
      // Or skip it (a stray arrival), if the budget allows.
      if (left > 0) return walk(pos + 1, k, left - 1);
      return null;
    }
    return null;
  }

  // Split a stream into bursts: runs of arrivals with no silence between them.
  // The sender leaves a silence longer than any gap inside a frame (1900 ms)
  // between its sweep rounds and before the frame, so a burst is one sweep round
  // or one frame.  Returns half-open index ranges into `stream`.
  function arrivalBursts(stream) {
    var out = [];
    var from = 0;
    for (var i = 1; i < stream.length; i++) {
      if (stream[i].at - stream[i - 1].at >= ANNOUNCE_SPLIT_MS) {
        out.push({ from: from, to: i });
        from = i;
      }
    }
    if (stream.length) out.push({ from: from, to: stream.length });
    return out;
  }

  // The peer's column list, and the frame that follows it, out of one arrival
  // stream.  A sweep round is an ascending run of ports in its own burst and at
  // most ANNOUNCE_MAX_ENTRIES long; a frame is neither (it is long, and its
  // symbols are a hash of the payload, not a sweep).  The longest bursts are the
  // rounds, and they are unioned rather than intersected: a pulse the reader
  // missed in one round is still in the others, and every member matters - a list
  // with a missing member is a different alphabet, which no checksum can repair.
  // The list read as *first appearances*: a sweep round is an ascending run of the
  // peer's sockets, three rounds of the same list, and the frame that follows
  // repeats those same sockets - so the order in which the columns appear for the
  // first time is the ascending list itself, whatever the reads did to the bursts.
  // That is what the burst rule below cannot survive: three rounds of four pulses
  // that a busy page's stats queue reports in one read are not three bursts of
  // four, they are one burst of twelve that does not ascend, and the reader then
  // holds no list at all (measured on the shipped artifact: the side that
  // announced 3 rounds of 4 pulses never saw a column list on the other side,
  // while 8 pulses per round read fine - and with no list there is no alphabet, so
  // no frame can be read however clean it is).
  // The lists the sweep may have announced, best first.
  //
  // The sweep fires every column ANNOUNCE_ROUNDS times, so the list can be
  // checked against its own repeats: a column that arrived at least
  // ANNOUNCE_CONFIRM times was seen in that many rounds, and a single arrival of a
  // stray - the queued ICE checks the stream always carries - was not.  That is
  // the check the ascending run of first appearances cannot make: a *partial* read
  // of the sweep is still ascending and still a plausible length, so a shorter
  // power of two was accepted as the alphabet and the frame that followed was read
  // against a different index space (measured on the shipped artifact: `peer
  // column list: 4 socket(s) from 1 round(s) of 4 pulse(s)` while the sender
  // announced 8, and 234 of 320 arrivals decoded nothing).  Every candidate is
  // offered to the decoder and the CRC picks between them, so a wrong list fails
  // to decode - a retry instead of a bad alphabet.
  function readColumnSweep(stream) {
    var seenPort = Object.create(null);
    var order = [];
    var count = Object.create(null);
    for (var i = 0; i < stream.length; i++) {
      var p = stream[i].port;
      count[p] = (count[p] || 0) + 1;
      if (seenPort[p]) continue;
      seenPort[p] = true;
      order.push(p);
    }
    var out = [];
    function add(cols, note) {
      if (!cols || cols.length < 2 || cols.length > 64) return;
      var key = cols.join(",");
      for (var i = 0; i < out.length; i++) if (out[i].key === key) return;
      out.push({ cols: cols, key: key, note: note });
    }
    // The list the sweep's own repeats confirm.  A column of the real list is hit
    // by every sweep round and, once the frame arrives, by many of its pulses; a
    // stray arrival is hit once or twice.  A fifth of the busiest socket is the
    // same floor the doc's item 4 used for the same reason, and it is what drops a
    // stray that happened to arrive twice while keeping every real column.
    var maxCount = 0;
    for (var c in count) if (count[c] > maxCount) maxCount = count[c];
    var hot = [], confirmed = [];
    var threshold = Math.max(ANNOUNCE_CONFIRM, Math.ceil(maxCount * 0.35));
    for (var c2 in count) {
      var port = Number(c2);
      if (count[c2] >= ANNOUNCE_CONFIRM) confirmed.push(port);
      if (count[c2] >= threshold) hot.push(port);
    }
    confirmed.sort(function (a, b) { return a - b; });
    hot.sort(function (a, b) { return a - b; });
    // The longest strictly ascending run of first appearances: the read the
    // accepted run of this carrier took, kept as the last candidate because a
    // partial read of it is exactly the plausible-but-wrong alphabet above.
    var bestFrom = 0, bestLen = 0, from = 0;
    for (var j = 1; j <= order.length; j++) {
      if (j < order.length && order[j] > order[j - 1]) continue;
      if (j - from > bestLen) { bestLen = j - from; bestFrom = from; }
      from = j;
    }
    add(hot, "the sockets the repeats confirm");
    add(confirmed, "every socket seen twice");
    if (bestLen >= 2) add(order.slice(bestFrom, bestFrom + bestLen), "first appearances");
    return out;
  }

  // Decode the peer's pulse stream by content, not by clock.
  //
  // The arrivals at our readers *are* the peer's pulses, in order (our own
  // pulses never come back to our own readers), and each arrival names the socket
  // of ours it landed on - so the stream of sockets is the peer's symbol stream.
  // Indexing it against our own sorted in-window sockets recovers the symbol the
  // sender chose, because that is the same list the sender probed and numbered.
  //
  // What this replaced indexed the arrivals against SYM.cols - the *peer's*
  // sockets - so nearly every arrival read as "not in the alphabet", the decode
  // filled zeros, and the CRC rejected it: no run of this engine, in any log in
  // tmp/autoconnect/, ever decoded a payload over the socket channel.
  //
  // Reading by content also removes a timing requirement the old reader could
  // not meet: it attributed arrivals to *our own* pulse times, so a peer whose
  // pulses started one slot earlier shifted every symbol after it, and a missed
  // pulse did the same.  Now a missed pulse costs the window it sits in, and a
  // frame that started seconds before or after ours is still readable.
  function tryDecodeSocket() {
    // Decoding needs arrivals, not a transmission of our own.  Gating this on
    // SYM.cols (our own transmit's peer-socket list) meant the side whose probe
    // came back short decoded *nothing* even when the peer's whole frame was
    // sitting in its arrival list - its own list was empty, so the reader never
    // ran (measured: one side read 133 arrivals from the peer's 107 pulses and
    // logged "no payload" three times over, with the exception thrown into the
    // other side's empty channel).
    if (!running || !arrivals.length) return null;
    if (now() - lastArrivalAt < SYM_SETTLE_MS) return null;      // let the tail land
    if (now() - lastStreamReadAt < STREAM_READ_MS) return null;  // reads are not free
    lastStreamReadAt = now();
    decodeOpsLeft = DECODE_READ_BUDGET;
    var stream = collectSocketArrivals();
    SYM.settled = stream.length;
    if (stream.length < 8) return null;
    // The peer announces the list it aims at before it aims at it, so the
    // alphabet is not inferred here - it is read off the air.  Both sides then
    // number the same sockets in the same order, which is the only way the CRC
    // can ever match (see the note above transmitSocket).
    var candidates = readColumnSweep(stream);
    var own = ownSocketAlphabet();
    var lists = [];
    for (var ci = 0; ci < candidates.length; ci++) {
      var cand = candidates[ci];
      // Every announced column has to be one of our own in-window sockets: the
      // sweep lands on our readers by construction, so a column that is not ours
      // means the read mixed something else into the list.
      var foreign = 0;
      for (var oi = 0; oi < cand.cols.length; oi++) {
        if (own.indexOf(cand.cols[oi]) < 0) foreign++;
      }
      if (foreign) {
        logThrottled("sym", "a peer column list candidate names " + foreign
          + " socket(s) that are not ours - not the peer's list", 3000);
        continue;
      }
      lists.push(cand);
    }
    // And one list the sweep cannot carry: this page's own in-window sockets.  The
    // peer numbers the sockets its probe read, and a two-shot readback reads them
    // all - so the sender's list *is* this list, and a sweep read that lost a column
    // or two then still has a candidate that is right instead of three that are
    // plausible and wrong (measured: the side whose sweep arrived complete was the
    // side that decoded).  A wrong list costs one failed decode, the CRC decides.
    if (own.length >= 2 && own.length <= 64) {
      var ownKey = own.join(",");
      var already = false;
      for (var ci2 = 0; ci2 < lists.length; ci2++) if (lists[ci2].key === ownKey) already = true;
      if (!already) {
        lists.push({ cols: own, key: ownKey,
          note: "every in-window socket this page owns (the peer's probe read them all)" });
      }
    }
    if (!lists.length) return null;
    // Every socket any candidate names counts as a possible column for the arrival
    // filter - not just the first candidate's - so a column that only the second
    // candidate carries is still allowed to gather the repeats that would move it
    // into the first one.
    var announced = [];
    for (var ai = 0; ai < lists.length; ai++) {
      for (var aj = 0; aj < lists[ai].cols.length; aj++) {
        if (announced.indexOf(lists[ai].cols[aj]) < 0) announced.push(lists[ai].cols[aj]);
      }
    }
    SYM.peerCols = announced;
    if (lists[0].key !== SYM.peerKey) {
      SYM.peerKey = lists[0].key;
      log("sym", "peer column list: " + lists[0].cols.length + " socket(s) ("
        + lists[0].note + "), " + lists.length + " candidate(s) from " + stream.length
        + " arrival(s): " + lists[0].key);
    }
    // The frame arrives as bursts: the payload is sent more than once per attempt
    // and each copy is separated from the next by a silence longer than any gap
    // inside a copy.  A copy is the longest run of arrivals there is, so the bursts
    // are tried longest first and the whole span last (a copy whose tail ran into
    // the next one).
    // The frame's arrivals: everything except the peer's list pulses (see a.list in
    // collectSocketArrivals) and, of course, our own traffic.
    var frame = [];
    for (var fi = 0; fi < stream.length; fi++) if (!stream[fi].list) frame.push(stream[fi]);
    var pieces = arrivalBursts(frame);
    pieces.sort(function (a, b) { return (b.to - b.from) - (a.to - a.from); });
    var tried = [];
    for (var pi = 0; pi < pieces.length && tried.length < DECODE_BURSTS; pi++) {
      if (pieces[pi].to - pieces[pi].from < 4) continue;
      tried.push(frame.slice(pieces[pi].from, pieces[pi].to));
    }
    if (frame.length >= 4) tried.push(frame);
    // The copies before the bursts: the two longest bursts of a frame read are its
    // two copies, and merged they repair what neither carries alone (see
    // decodeSlottedPair).  It is the cheapest and the strongest read there is, so
    // it goes first.
    var need0 = baseKNeed(lists[0].cols.length);
    var big = [];
    for (var gi = 0; gi < pieces.length; gi++) {
      if (pieces[gi].to - pieces[gi].from >= Math.max(6, Math.floor(need0 * 0.4))) {
        big.push(pieces[gi]);
      }
    }
    big.sort(function (a, b) { return a.from - b.from; });
    for (var pi2 = 0; pi2 + 1 < big.length; pi2++) {
      var copyA = frame.slice(big[pi2].from, big[pi2].to);
      var copyB = frame.slice(big[pi2 + 1].from, big[pi2 + 1].to);
      for (var li2 = 0; li2 < lists.length; li2++) {
        var pair = decodeSlottedPair(copyA, copyB, lists[li2].cols);
        if (pair) {
          log("sym", "payload decoded by merging the two copies (" + copyA.length + " + "
            + copyB.length + " arrival(s): " + pair.holes + " slot(s) only one of them"
            + " carried, " + pair.clashes + " the two disagree on, candidate " + pair.cand
            + " of " + pair.total + ") against the " + lists[li2].cols.length
            + "-socket list (" + lists[li2].note + ")");
          return pair.parsed;
        }
      }
    }
    // Every (burst, candidate) pair shares one read's decode budget, so a page
    // never spends a whole frame inside the decoder on a single poll.
    var share = Math.max(60000, Math.floor(DECODE_BUDGET / (tried.length * lists.length)));
    for (var ti = 0; ti < tried.length; ti++) {
      var burst = tried[ti];
      for (var li = 0; li < lists.length; li++) {
        var cols = lists[li].cols;
        var K = cols.length;
        var hit = decodeStream(burst, cols, share);
        if (hit) {
          log("sym", "payload decoded from " + burst.length + " arrival(s) at position "
            + hit.start + " of burst " + (ti + 1) + " of " + tried.length + " against"
            + " the " + K + "-socket list (" + lists[li].note + ", "
            + (Math.round(Math.log(K) / Math.LN2 * 100) / 100) + " bit(s) per pulse, "
            + baseKNeed(K) + " pulse(s) per copy): " + cols.join(","));
          return hit.parsed;
        }
        // Nothing contiguous fits, so try this burst as a *slotted* read: the
        // cadence is in the receipts, so a pulse that never arrived is a hole, and
        // the CRC picks the digit that fills it.
        var filled = decodeSlotted(burst, cols, 0) || decodeSlotted(burst, cols, 1);
        if (filled) {
          log("sym", "payload decoded by filling " + filled.holes.length
            + " hole(s) in a " + burst.length + "-arrival burst " + (ti + 1) + " of "
            + tried.length + " (candidate " + filled.cand + " of "
            + Math.pow(K, filled.holes.length) + ", cadence measured at " + filled.gap
            + " ms) against the " + K + "-socket list (" + lists[li].note + ")");
          return filled.parsed;
        }
      }
    }
    if (frame.length > SYM.bestStream) {
      SYM.bestStream = frame.length;
      // Against the count the sender actually fired, so a failing run says whether
      // the frame was read short (a pulse never arrived), long (stray arrivals) or
      // complete but misordered - three different defects.
      var need = baseKNeed(lists[0].cols.length);
      var sent = need * (SYM.copies || FRAME_COPIES);
      logThrottled("sym", "read " + frame.length + " arrival(s), no payload yet; best"
        + " candidate: " + lists[0].note + " = "
        + lists[0].cols.length + " socket(s), "
        + (Math.round(Math.log(lists[0].cols.length) / Math.LN2 * 100) / 100)
        + " bit(s) per pulse, " + need + " pulse(s) per copy x "
        + (SYM.copies || FRAME_COPIES) + " = " + sent + " sent: "
        + (frame.length === sent ? "all accounted for"
          : (frame.length > sent ? frame.length - sent + " extra arrival(s)"
            : sent - frame.length + " pulse(s) never arrived"))
        + ")", 3000);
    }
    return null;
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
    // The final connection is built at boot so that it has a port to advertise
    // inside the payload; its answer is applied *here*, once, with the peer's real
    // DTLS fingerprint.  (Doing it at boot means a second remote description here,
    // which Chrome rejects: "Called in wrong state: stable"; renegotiating first
    // instead moves the socket out from under the advertised port and the peer's
    // pokes land nowhere - measured: "connection state failed" 63 ms after
    // connecting.)
    return finalPc.setRemoteDescription({ type: "answer", sdp: answer }).then(function () {
      if (peerIp && peerPortNumber) {
        // Keep poking instead of firing twelve times and giving up after ~11 s.
        // The two sides decode their own payload on their own schedule - a frame
        // takes about a minute, and one side is routinely an attempt ahead of the
        // other - so the peer may not have applied its fingerprint (or brought
        // its final connection up) when this side first learns the address.  The
        // poke is what makes the peer's final socket answer us at all, so it has
        // to outlive the other side's exchange, not our own.
        var until = now() + FINAL_POKE_MS;
        (function poke() {
          if (!running || now() > until) return;
          fire(finalPc, peerIp, peerPortNumber).then(function () {
            if (finalChannel && finalChannel.readyState === "open") return;
            if (finalPc.connectionState === "connected") return;
            window.setTimeout(poke, 1200);
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
        // Every path that sees the link up goes through here, whether or not the
        // promise below was already settled: a channel that opens after the wait
        // expired is still the link the peer is waiting on, and the pokes keep
        // running, so it can arrive long after this side stopped waiting for it
        // (measured: one side's final connection came up 158 s in, 700 ms after
        // the other side had been told it failed).  The failure path reports once.
        function up(why) {
          if (state !== "connected") {
            state = "connected";
            if (connectedPeer) connectedPeer.state = "connected";
            log("connect", "connected over a direct data channel (" + why + ")"
              + (settled ? ", after the connect window had closed" : ""));
            emit({ type: "connected", peer: connectedPeer });
          }
          done(true);
        }
        if (finalChannel) {
          finalChannel.addEventListener("open", function () {
            log("connect", "data channel open");
            up("our data channel opened");
          });
          finalChannel.addEventListener("message", function (ev) {
            emit({ type: "message", data: ev.data });
          });
        }
        finalPc.addEventListener("datachannel", function (e) {
          log("connect", "peer opened a data channel");
          var ch = e.channel;
          ch.addEventListener("open", function () { up("the peer's data channel opened"); });
          ch.addEventListener("message", function (ev) { emit({ type: "message", data: ev.data }); });
        });
        finalPc.addEventListener("connectionstatechange", function () {
          log("final", "connection state " + finalPc.connectionState);
          if (finalPc.connectionState === "failed") done(false);
        });
        window.setTimeout(function () { done(false); }, CONNECT_TIMEOUT_MS);
        // The pokes continue after this timeout (see the loop above): a later
        // arrival from the peer is still able to bring the link up, and
        // afterFinal() only reports failure once.  If it does come up late, the
        // event below is what reports it.
        finalPc.addEventListener("connectionstatechange", function () {
          if (finalPc.connectionState === "connected" && finalChannel
              && finalChannel.readyState === "open") up("the connection came up late");
        });
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
    // The two sides have to pick *opposite* DTLS roles, and both used to pick
    // "server": selfId is a hex *string* while the payload carries a 16-bit
    // number, so `selfId < parsed.id` compared "12bb" with a number and was false
    // on both sides - the handshake then had two servers, and the link failed
    // with "connection state failed" a second after the first payloads ever
    // decoded (measured: both peers logged "DTLS server - peer port <the other's
    // real final port>").  Compare in one number space, and break a tie - which
    // a 16-bit id can produce - on the final ports, which are always distinct.
    var mine = selfIdNum;
    var isClient = mine !== parsed.id ? (mine < parsed.id)
      : (finalPort < parsed.port);
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
    // The legacy carrier needs the window to itself; the socket channel does not
    // (a receiver only ever sees the peer's pulses on its own readers).  It used
    // to be gated on a quiet arrival window as well - which deadlocks into
    // serialization, because the peer's *frame* is itself a stream of arrivals
    // every 600 ms, so the second side waits out the first side's whole frame
    // (measured: 67 s of waiting, and the run then had no budget left).  Junk in
    // the stream is filtered by source in collectSocketArrivals instead.
    if (!socketReady && now() - lastArrivalAt < QUIET_MS) return;
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
    }).then(function () {
      // The peer's frame and ours are in flight at the same time, so the read starts
      // with the transmission instead of after it.  Waiting for our own two copies to
      // finish before looking at the peer's costs the link the *sum* of the two
      // frames rather than the longer of them, and it is what the passing runs of the
      // two-copy design paid: on the shipped artifact both sides' copies were complete
      // at +155 s and +158 s, and the side that had a decodable payload in hand logged
      // its decode only at +155 s - when its own transmission finished.  The read is
      // the one that can end the attempt early, so it is the one that is raced.
      var read = waitForPayload(exchangeBudget());
      var sent = transmitSocket();
      // Only a *payload* ends the attempt early.  A read that ran out of budget while
      // this side is still transmitting is not an answer, and taking it as one starts
      // the next attempt on top of a running frame (measured: two transmissions at
      // once, a copy logged as "-67 of 67 pulse(s)", and both sides reading nothing).
      return Promise.race([
        read.then(function (parsed) {
          return parsed ? parsed : sent.then(function () { return read; });
        }),
        sent.then(function () { return read; })        // ours ended first: read on
      ]);
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

  // What one read is given: our own frame's length with a quarter of slack.  Both
  // sides code the same payload over their own view of the other's sockets, so the
  // peer's frame is about this long, and it has to be long enough for the read to
  // outlive it on a loaded page - never less than the carrier's old budget.
  function exchangeBudget() {
    var K = SYM.cols.length;
    if (K < 2) return EXCHANGE_BUDGET_MS;
    var n = baseKNeed(K);
    var est = ANNOUNCE_ROUNDS * (K * ANNOUNCE_GAP_MS + ANNOUNCE_SILENCE_MS)
      + FRAME_COPIES * n * pulseGapMs() + (FRAME_COPIES - 1) * COPIES_GAP_MS;
    return Math.max(EXCHANGE_BUDGET_MS, Math.round(est * 1.25));
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

  // The sweep's 24 scanners carried a hundred thousand checks between them, so a
  // single stats read of one of those connections takes seconds (measured: a
  // readback round of 24 of them did not finish inside the locate's own 15 s
  // deadline, and the engine then sat in phase 1.5 for minutes with nothing else
  // logged).  Discovery is over by the time the probe needs connections, so the
  // heavy ones are dropped here and six fresh out-of-window ones do the probing.
  // Out of the window is required: an in-window socket would answer the peer's
  // own probe and be read as a payload column.
  function refreshProbePool() {
    // One connection per ~21 of the peer's 512 window ports, not per 85: the
    // readback reads *succeeded pairs*, and a pair is only checked once Chrome's
    // ICE has paced its way to it, so the more pairs a single connection carries
    // the longer its list takes to fill in.  Six connections (85 each) came back
    // with 7, 9 or 0 of the peer's 17 sockets in different runs - the same
    // engine, the same machine - and every one of those numbers is the sender's
    // alphabet, so the run's outcome was a coin toss.
    var PROBE_CONNECTIONS = 24;
    // The sweep is over the moment this is called, and it has to be *told* so:
    // its pump fires a queued round every SCAN_ROUND_MS until all 254 are done
    // (~2.7 minutes of 130k checks), and after the swap it would push all of that
    // through the fresh probe connections.  Measured in a run where it did: the
    // probe pool read like a sweeper (seconds per stats pass), the readback
    // missed its own 15 s deadline, and the run reported "peer socket probe
    // answered with 0 socket(s)" 213 s later.
    sweepActive = false;
    return makeOutOfWindow(PROBE_CONNECTIONS).then(function (pcs) {
      // Swapped, not emptied in place: the sweep's own pump may still be firing
      // its last burst, and an empty array there indexes to undefined and throws
      // (measured: "Cannot read properties of undefined (reading '__acUsed')").
      var old = scanners;
      scanners = pcs;
      old.forEach(closePc);
      log("ports", "probe pool: " + pcs.length + " fresh connection(s); the sweep's heavy"
        + " scanners are closed before any readback");
      return pcs;
    });
  }

  // A reader connection owns whatever local sockets it has *now*, and the lottery
  // registered the ones it had when the connection was kept.  A socket that turns
  // up afterwards is unknown to ownWindowPorts - and ownWindowPorts is what tells
  // the probe which in-window sockets are ours.  That matters most in the harness,
  // where both peers are on one machine: a check to the machine's LAN address is
  // answered by any local socket bound there, so one of *our* unregistered reader
  // sockets reads back as a socket of the peer's, and the announced column list
  // then names columns that are not the peer's readers at all (measured: a probe
  // reading back 28 and 33 sockets from a peer whose readers were 16 and 13).
  function refreshReaderPorts() {
    var known = Object.create(null);
    for (var i = 0; i < readers.length; i++) {
      for (var j = 0; j < readers[i].win.length; j++) known[readers[i].win[j]] = true;
    }
    var added = 0;
    return Promise.all(readers.map(function (r) {
      return waitForPorts(r.pc).then(function (ports) {
        var win = windowPorts(ports);
        for (var k = 0; k < win.length; k++) {
          if (known[win[k]]) continue;
          known[win[k]] = true;
          added++;
          r.win.push(win[k]);
          ownWindowPorts[win[k]] = true;
        }
      });
    })).then(function () {
      if (added) log("readers", "re-read " + readers.length + " reader connection(s) before"
        + " the probe: " + added + " socket(s) appeared after the lottery registered them,"
        + " and they are ours - the probe must not read them back as the peer's");
      return added;
    });
  }

  function prepare() {
    preparing = true;
    return refreshReaderPorts().then(function () {
      return refreshProbePool();
    }).then(function () {
      return censusColumns();
    }).then(function () {
      // The probe readback is a snapshot of which of the peer's sockets ICE has
      // answered on *by now*, and two pages opened together boot ~10 s apart, so
      // the side that gets the peer's address first can probe a peer whose
      // readers do not exist yet.  Measured in a stock Chrome (no browser flags)
      // run: one side read back 10 sockets and the other 0 - it seeded the one
      // out-of-window port that had probed it (a scanner the peer closes), ran a
      // frame at "1 column" and lost the exchange.  A readback with fewer than
      // two sockets is now retried until it is usable, or until the ceiling
      // passes; it is never used as an alphabet.
      var probeStarted = now();
      var probeAttempts = 0;
      return (function probeUntilUsable() {
        probeAttempts++;
        return probePeerPorts(peerAddress, proofPorts).then(function (targets) {
          var elapsed = now() - probeStarted;
          if (targets.length >= PROBE_MIN_COLUMNS || elapsed >= PROBE_RETRY_MS) {
            if (probeAttempts > 1) log("ports", "peer socket probe: " + targets.length
              + " socket(s) on probe " + probeAttempts + " - the alphabet is usable");
            if (targets.length < 2) log("ports", "peer socket probe: " + probeAttempts
              + " probe(s) over " + Math.round(elapsed / 1000) + " s left "
              + targets.length + " socket(s); the socket channel needs two");
            return targets;
          }
          log("ports", "peer socket probe: " + targets.length + " socket(s) on probe "
            + probeAttempts + " (" + PROBE_MIN_COLUMNS + " preferred) - the peer's readers"
            + " may not be up yet, probing again");
          return sleep(PROBE_RETRY_GAP_MS).then(probeUntilUsable);
        });
      })();
    }).then(function () {
      // The socket channel's alphabet is the *whole* settled readback, sorted
      // (probePeerPorts sorts), which is the same list the peer derives from
      // gridSockets().  The frame codes the payload in base K, so every socket
      // carries a digit and none of the list is thrown away (see payloadToDigits),
      // and this exact list is what the peer is told by the sweep before every
      // frame, so the two sides never work from different lists.
      SYM.cols = peerTargets.length >= 2 && peerTargets.length <= 64
        ? peerTargets.slice() : [];
      if (peerTargets.length < 2) log("ports", "only " + peerTargets.length
        + " peer socket(s); the socket channel needs at least two");
      releaseProbes();
      if (useSocketChannel && SYM.cols.length >= 2) {
        // Socket channel: the pulse connections are made on demand, so the page
        // keeps only its readers.  That is what makes the read cheap - the pool
        // is what the old grid needed to pay for its pulses.
        log("census", "socket channel ready: " + gridSockets().length + " own socket(s), "
          + SYM.cols.length + " peer socket(s), base " + SYM.cols.length + " = "
          + (Math.round(Math.log(SYM.cols.length) / Math.LN2 * 100) / 100)
          + " bit(s) per pulse, " + baseKNeed(SYM.cols.length)
          + " pulse(s) per copy; no source pool (" + sources.length
          + " connection(s) released)");
        sources.forEach(closePc);
        sources = [];
        pulsePool = [];
        exchangeRounds = baseKNeed(SYM.cols.length);
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

  // The name is what the peer lists show and one field of the payload, so it is
  // adopted whenever the game learns it - see start, which is usually called at an
  // engine that the page already started by itself.
  function adoptName(name) {
    var next = String(name).slice(0, 24);
    if (!next || next === selfName) return;
    selfName = next;
    log("boot", "name is now " + selfName);
    startFallbackClock();
  }

  function start(options) {
    if (options && options.socket === false) useSocketChannel = false;
    else if (options && options.socket === true) useSocketChannel = true;
    var opts = options || {};
    // The engine starts by itself when the page opens (see bootAutoStart), so the
    // game's own start() normally arrives at an engine that is already
    // discovering.  That is not a no-op: the name is the one thing the sleeping
    // page cannot know, and it is carried in the payload - so it is taken and the
    // discovery keeps running, instead of the call being ignored and every peer
    // announcing the placeholder name.
    if (running) {
      if (opts.name) adoptName(opts.name);
      return Promise.resolve(state);
    }
    if (opts.name) selfName = String(opts.name).slice(0, 24);
    if (opts.stun) stunServers = opts.stun;
    running = true;
    startedAt = now();
    state = "listening";
    if (!codeMode) startFallbackClock();
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
          proofPorts = [peer.port];
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
        // The locate readback is a stats pass per probe connection, and it runs
        // on the sweep's scanners (the only connections that have already talked
        // to every host in the two /24s): the readers idle through it, because
        // they would otherwise hold the one queue slot the readback is waiting
        // for.  That starvation is what left this phase spinning for minutes.
        preparing = true;
        return findAddressByPort(peerPort).then(function (address) {
          preparing = false;
          if (!address) { log("locate", "the peer's address was not found"); return null; }
          peerAddress = address;
          proofPorts = [peerPort];
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
    // Test seam for tmp/autoconnect/test-decode.mjs, which exercises the payload
    // encoder and the stream decoder without WebRTC (the same shape
    // lan-discovery.js exposes as `_debug`).
    _debug: {
      setIdentity: function (id, fp, port) {
        selfId = id; refreshSelfIdNum(); fingerprint = fp; finalPort = port;
      },
      buildPayload: function () { return buildPayload(); },
      parsePayload: function (bits) { return parsePayload(bits); },
      baseKNeed: baseKNeed,
      payloadToDigits: payloadToDigits,
      digitsToBits: digitsToBits,
      decodeStream: decodeStream,
      decodeSlotted: decodeSlotted,
      slotDigits: slotDigits,
      decodeSlottedPair: decodeSlottedPair,
      resetDecodeBudget: function () { decodeOpsLeft = DECODE_READ_BUDGET; },
      holesAllowed: holesAllowed,
      readColumnSweep: readColumnSweep,
      ownSocketAlphabet: ownSocketAlphabet,
      symPerPulse: symPerPulse,
      setReaders: function (wins) {
        readers = wins.map(function (win) { return { pc: null, ports: win, win: win }; });
      },
      statsCostMs: function () { return statsPassMs; }
    },
    startJson: function (optsJson) {
      var opts = null;
      try { opts = optsJson ? JSON.parse(optsJson) : null; } catch (e) { opts = null; }
      start(opts || {});
      return true;
    }
  };
  window.__eaglerAC = AC;

  // The page opens into discovery.  This module used to sit idle until a player
  // opened a multiplayer screen, which spends the first minute of every session on
  // work that has nothing to do with the game: the reader pool, the address scan
  // and the column sweep are all done - or nearly done - while the title screen is
  // still up, so opening the peers screen shows peers instead of a spinner, and two
  // players who open the page at the same time never wait for each other at all.
  // It starts on DOMContentLoaded so it never competes with the wasm's own first
  // bytes, and it is the module's own start: the game's later call only supplies
  // the name (see start).  A page that means to drive the engine itself sets
  // window.__acNoBootStart before this script is evaluated.
  function bootAutoStart() {
    if (window.__acNoBootStart || running) return;
    start({});
  }
  try {
    if (typeof document !== "undefined" && document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", bootAutoStart);
    } else if (typeof document !== "undefined" && typeof window.setTimeout === "function") {
      window.setTimeout(bootAutoStart, 0);
    }
  } catch (e) {
    // A host with no document (the decode harness, a worker) drives the engine
    // itself through window.__eaglerAC.start.
  }
})();

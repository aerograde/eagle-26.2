# Serverless LAN autoconnect — final findings and how to reproduce

Two Eaglercraft clients, one LAN, **no discovery server and no relay of any kind**:
the two pages find each other through ICE connectivity checks alone and build a
direct data channel. This is the pass that took that path from "never gets past
discovery" to a working handshake, and this document is the record of what was
measured, what was fixed, and what is still not reliable.

**What made the CRC match: the alphabet is sent, not estimated.** A pulse's symbol
is *which of the peer's sockets* it lands on, so both sides have to number the same
list of sockets the same way, and until this pass each side derived that list on its
own - the sender from its probe readback, the receiver from the sockets that looked
busy - which are two different estimates and never the same list (item 13). The
sender now sweeps its columns once per frame, in ascending order, three times over,
and the receiver reads the *port numbers* off the arrivals, which needs no alphabet
at all. Item 14 adds the second half: a frame that arrives missing a pulse or two is
read as a slotted stream, with the CRC choosing the symbol that fills each hole.

Engine under test: [`project/target_teavm_wasm_gc/src/web/autoconnect.js`](../target_teavm_wasm_gc/src/web/autoconnect.js)
(inlined into the single-file client by `wasm-toolchain/build-single-html.js`).

## 1. What the serverless path does

| phase | mechanism |
| --- | --- |
| readers | each page opens `READER_TARGET` connections whose UDP sockets land by lottery inside a shared 512-port window (50000-50511). Those sockets are its "grid" and its identity on the wire. |
| discovery | a sweep fires checks at every host in the local `/24`s × the window ports, then reads back which pairs succeeded (a peer's reader sockets answer). If a peer is probing *us* first, its source port identifies it and a locate readback finds its address. |
| census | each reader fires one check at a throwaway port of the peer's address; reading back which of its own sockets carried it labels the socket (16/16 in every run since the fix). |
| probe | the page fires one check at each of the peer's 512 window ports and reads back which answered. Everything this page owns that is not a reader is forced out of the window, and the reader sockets are re-read just before the probe, so an in-window port that answers is a reader socket. |
| columns | the list a frame is aimed at is the power-of-two prefix of that candidate list, and it is **transmitted** before the frame: one pulse per column, ascending, three rounds, with a silence between the rounds that no frame pulse can produce. This is what makes the CRC match: the receiver reads the *port numbers* off the arrivals - which needs no alphabet of its own - and both sides then number the same sockets in the same order. |
| frame | the payload (magic, 16-bit id, 32-byte DTLS fingerprint, final port, CRC16 = 320 bits) is sent as a train of pulses, one per fresh connection, each aimed at the column whose index is the symbol being sent. |
| decode | the peer reads its readers' stats: every arrival names the socket it landed on (the symbol). The stream is ordered by the *read* time (Chrome's per-pair receipt stamps are not monotonic), and the payload is found by content with the CRC as the only judge - contiguous first, then allowing stray arrivals to be skipped, then as a *slotted* read in which a gap of twice the measured cadence is a hole whose missing symbol the CRC picks. |
| handshake | the decoded payload carries the peer's final-connection port and DTLS fingerprint; both sides apply it, poke each other's final socket and open the data channel. DTLS roles are split by the (now numeric) peer id. |

## 2. Defects found and fixed in this pass

Each row is a defect that a run of the real engine produced, with the measurement
that identified it; the fixes are in the engine file above.

1. **The sweep kept firing after the probe pool replaced it.** The discovery pump
   queues 254 rounds (~130k checks, ~2.7 min) and was told to stop only by `found`:
   when the sweep ended early (peer seen probing us) it kept pushing rounds into the
   *fresh probe pool*, saturating the page's ICE thread. Measured: census 2 of 16
   after 291 s, probe "answered with 0 socket(s)" after 213 s. Fix: `sweepActive`
   flag set false by `refreshProbePool()`. Measured after: census 16 of 16 in 5.4 s.
2. **The census gave up after one pass.** It now polls to a deadline per round
   until every pending connection is labelled (or the deadline passes).
3. **The probe readback stopped on the clock, and its result *is* the alphabet.**
   15 s or "two empty rounds" cut the list at 9 of 17 sockets - a truncated list is
   a *different* alphabet, not a slow transmission. Fix: poll until a round finds
   nothing new (with a minimum settle window), and split the 512 fires over 24
   connections instead of 6 (21 pairs each, not 85: ICE paces checks, so a
   connection with 85 pairs takes far longer to fill in). Measured: 0/5/7/9 of 17
   before, 15 and 16 after.
4. **The decoder indexed arrivals against the peer's sockets.** Now
   content-addressed: the sockets the pulses actually landed on, and - crucially -
   only the **hot** ones (hit ≥ max(3, 20% of the busiest)), because a symbol's
   socket is aimed at ~13 times per frame while stray traffic hits a socket once or
   twice. Measured: with every observed socket in the alphabet 1 of 7 sampled    pulses mapped back to its symbol; with the hot sockets, 10 of 10. (Superseded
    by item 13: the receiving side no longer infers anything.)
5. **The pulse gap was shorter than the reader's own clock.** Arrivals were dated
   by the *read* time, and the readers poll on independent clocks every 250-2000 ms,
   so pulses 80 ms apart came back 24-28% out of order and no payload could ever
   decode. Two fixes: (a) the gap is now 1.6 × the reader cadence (floor 600 ms),
   and (b) arrivals are dated by the pair's own `lastPacketReceivedTimestamp`
   (`receivedAtOf()` normalises Chrome's epoch-ms / origin-ms / seconds forms).
   Measured after: **0 inversions of 45 and of 21 pairs**, receipt-to-read latency
   min 1 ms / median ~60 ms.
6. **The stream carried trailer-socket duplicates and probe traffic.** A pulse
   connection owns two local UDP sockets and ICE checks the remote candidate from
   both (two arrivals, one pulse); and the 512-check probe keeps leaking arrivals
   into the peer's stream for a minute because ICE paces its queued checks
   (measured: 44 probe arrivals 66 s after the probe ended). Fixes: keep only
   arrivals whose source port appears exactly once (a pulse is one check from a
   fresh connection; probe/sweep/locate connections fire many checks and repeat
   their ports), then collapse arrivals on the same socket within `PULSE_DUP_MS`
   (400 ms - below the 600 ms gap, so two symbols can never merge).
7. **The decoder was gated on our own transmission.** `tryDecodeSocket()` started
   with `if (!SYM.cols.length)`, so the side whose probe came back short never ran
   the decoder at all (measured: 133 arrivals from the peer's frame in hand, three
   "no payload" logs, decoder never entered).
8. **Both sides chose the same DTLS role.** `selfId` is a hex *string* while the
   payload carries two bytes, so `selfId >> 8` was 0 for every payload ever sent and
   `selfId < parsed.id` was false on both sides: both became the DTLS *server*, and
   the first run that ever decoded a payload died a second later ("connection state
   failed"). Fixes: a numeric `selfIdNum` in the payload, and the role split
   compares numbers, breaking an id tie on the final ports.
9. **The final connection applied a second remote description.** The final pc is
   built at boot (it has to, its port is advertised in the payload) with a forged
   answer carrying a dummy fingerprint, so it is in state `stable`; applying the
   real answer there threw "Called in wrong state: stable". Fix: renegotiate (fresh
   offer, same shared credentials → same sockets) before applying it.
10. **The final link's pokes expired before the peer was ready.** Twelve pokes over
    ~11 s while a frame takes ~64 s and the two sides decode on their own schedule:
    the side that decoded first stopped knocking before the other side could answer.
    Now it keeps poking for `FINAL_POKE_MS` (150 s) and reports a late connection.
11. **A probe readback with nothing in it was used as an alphabet.** The probe is
    a snapshot of which of the peer's sockets ICE has answered on *yet*, and two
    pages opened together boot ~10 s apart, so the side that gets the peer's
    address first can probe a peer whose readers do not exist yet. Measured in a
    stock Chrome (no feature flags) run: one side read back 10 sockets, the other
    0 - it seeded the one out-of-window port that had probed it (a scanner the
    peer closes), ran its attempt at "1 column" and reported `no peer socket to
    aim at`. Fix: seeds outside the reader window are ignored (logged), and the
    probe is repeated every `PROBE_RETRY_GAP_MS` (6 s) while it yields fewer than
    `PROBE_MIN_COLUMNS` (4) sockets, up to `PROBE_RETRY_MS` (60 s).
12. **The two sides each *estimated* the alphabet, and they disagreed.** The
    sender's alphabet is its probe readback (10 and 13 sockets in the acceptance
    run, 3 bits per pulse); the receiver's is the sockets its readers saw pulses
    on, filtered to the "hot" ones (hit >= max(3, 20% of the busiest)). Twenty-odd
    strays per frame put three or more hits on sockets no pulse was ever fired at,
    so the receiver's candidates came out at **15-17 sockets - 3 and 4 bits per
    pulse** - against the sender's 3. That is a different index space *and* a
    different frame length, so no CRC can match: measured on the shipped artifact
    as `no payload yet (17-socket alphabet, 4 bit(s) per pulse)` while the peer
    transmitted `320 bits as 107 pulse(s) x 3 bit(s)`. This is the remaining
    structural gap (see section 5): the alphabet must stop being two independent
    statistical estimates. Fixed by item 13.
13. **The alphabet was still two estimates, so the CRC could never match.** The
    sender aimed at its probe readback (10-13 of the peer's sockets, 3 bits per
    pulse) while the receiver indexed its own arrivals against a *filtered* view of
    the same quantity (15-17 sockets, 3 and 4 bits per pulse): different index
    spaces *and* different frame lengths. Measured on the shipped artifact as
    `no payload yet (17-socket alphabet, 4 bit(s) per pulse)` while the peer
    transmitted `320 bits as 107 pulse(s) x 3 bit(s)`. Fix: the list is **sent**.
    Before every frame the sender sweeps its columns in ascending order, one pulse
    each, three times over (18 s), with 400 ms between pulses and 2800 ms of
    silence between rounds; each round is its own burst of arrivals, and a burst
    that ascends and is short is a sweep round while the frame is neither. An
    arrival names the port it landed on absolutely, so the sweep is readable with
    no alphabet at all, and the receiver ends up holding exactly the sender's list.
    The rounds are *unioned* (a pulse the reader missed in one round is still in
    the others) and a union that is not a power of two long is a misread the reader
    walks past. Measured: `column list sent: 8 socket(s) x 3 round(s) = 24 of 24
    pulse(s)` and on the other side `peer column list: 8 socket(s) from 3 round(s)
    of 8 pulse(s): 50020,50077,...` - the same eight ports.
14. **A frame read short was undecodable, and frames do get read short.** A pulse
    is one fresh connection and a hole shifts every symbol after it, so a frame
    missing one pulse was lost even though the CRC would have verified the rest.
    Measured on the shipped artifact: `read 101 arrival(s) after the sweep ...
    6 pulse(s) never arrived`. Two fixes. (a) The decoder now reads the frame as a
    *slotted* stream: the sender's cadence is measured from the arrivals
    themselves (median gap, 605 ms measured), a gap of about twice it is a hole,
    and the CRC picks the missing symbol out of at most 8^3 candidates. Measured
    in the acceptance run: `payload decoded by filling 2 hole(s) in a 105-arrival
    frame (candidate 50 of 64, cadence measured at 605 ms)`. (b) A pulse connection
    is *not* forced out of the window (that was tried and reverted: the check costs
    a stats pass per connection, and under the artifact's load the retries ran out
    of rounds and dropped 6 of 107 pulses - a hole is worse than a socket in the
    window). What keeps the probe honest instead: every other connection is
    out-of-window by construction, and the reader sockets are re-read (and
    re-registered in `ownWindowPorts`) right before the probe, because a reader can
    gain another local candidate after the lottery sampled it - and on one machine
    (both windows in the harness) one of *our* unregistered sockets answers the
    peer's probe as if it were the peer's (`probe answered with 28 and 33
    socket(s)` for a peer with 13 readers, which is what a longer probe settle
    exposed).
15. **A receipt stamp seconds in the past reordered the frame.** Arrivals were
    ordered by the pair's own `lastPacketReceivedTimestamp`, which is not monotonic:
    the diag log shows consecutive arrival stamps jumping *backwards* by 7962 ms,
    and one 2 s stale stamp splits a sweep round of 8 into bursts of 5 and 3. An
    arrival dated seconds early lands in the middle of the symbol sequence and
    shifts everything after it - the same damage as a stray arrival, with no
    checksum able to repair it. Fix: order the stream by the *read* time (this
    page's own monotonic clock; a reader polls every 200 ms while a frame is in
    flight and a pulse is 600 ms apart, so read order is send order) and use the
    receipt stamp only to break a tie inside one read. Stamps older than
    `STALE_STAMP_MS` (3 s) are not used at all.
16. **The final connection was given up on, and then let die.** Two separate bugs
    in one wait. (a) One side's `finishFinal` promise timed out (25 s), reported
    "the final connection did not come up" and set `state = "failed"`, and when the
    peer - a whole frame behind - dialled the advertised port a moment later, the
    channel *did* open but nothing moved the state machine back: measured
    `final: connection state connected` 158 s in, 2 ms after `failed`. The timeout
    is now 90 s and every path that sees the link up reports it through one `up()`
    call, whether or not the promise was already settled. (b) The pokes that keep
    the final socket alive stopped after 150 s, so a peer that decoded on its
    second attempt dialled a socket whose connection had already gone to `failed`
    (measured on the shipped artifact: `final: connection state failed` at 340 s
    while the peer was still retrying). `FINAL_POKE_MS` is now 300 s.

## 3. How to reproduce

Engine-level harness (two Chromium processes, two engine pages, no discovery
server - `peer.html` loads `autoconnect.js` directly):

```bash
cd ~/eagle/project
python3 -m http.server 8124 --bind 0.0.0.0 &          # any static server
python3 tmp/autoconnect/run-peers.py 8124 240000 > out/ac-run.log 2>&1
```

With per-arrival/per-pulse diagnostics (one line per arrival and every tenth
pulse, enough to pair arrivals to pulses by source port):

```bash
ACDIAG=1 python3 tmp/autoconnect/run-peers.py 8124 300000 > out/ac-diag.log 2>&1
python3 tmp/autoconnect/analyse-order.py  out/ac-diag.log   # order + read latency
python3 tmp/autoconnect/analyse-symbols.py out/ac-diag.log  # symbol mapping per pulse
python3 tmp/autoconnect/analyse-sweep.py out/ac-diag.log    # sweep, holes, strays
```

Artifact-level acceptance test (the shipped single file, two real Chrome windows,
plain static HTTP, engine started as the LAN screen starts it):

```bash
cd ~/eagle/project
python3 -m http.server 8125 --bind 0.0.0.0 --directory . &
python3 tmp/autoconnect/artifact-two-chrome.py 8125 400000
```

That script passes **no browser feature flags**. `tmp/autoconnect/lna-probe.py`
measures whether any are needed (it boots two stock Chrome processes against the
same static server and waits for the peer): on Chrome 154 the two engines
discovered each other, census-labelled 16 of 16 readers and read back 10 peer
sockets with the default command line, so the
`--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessChecks` the
script used to pass was unnecessary and is gone. Plain HTTP from a LAN address is
not a secure context, and neither `fetch`/`WebSocket` nor the RTCPeerConnection
used here is gated on a permission in that configuration.

The artifact itself is packaged from the engine file with:

```bash
cd ~/eagle/project
cp target_teavm_wasm_gc/src/web/autoconnect.js target_teavm_wasm_gc/build/web/autoconnect.js
node wasm-toolchain/build-single-html.js --skip-build --fast-package \
  --output dist/direct-connect.html
```

`--skip-build` is what a JavaScript-only change needs: the linker's reuse guards
refuse `--reuse-client` while `target_teavm_wasm_gc/src` is newer than
`classes.wasm`, and no Wasm can change because of a web/ edit. `--skip-build`
packages the already assembled `target_teavm_wasm_gc/build/web`, which is why the
engine is copied there first.

Confirm the packaged engine is the file you edited - a byte comparison, not a
marker grep:

```bash
cd ~/eagle/project
node -e 'const fs=require("fs"),h=fs.readFileSync("dist/direct-connect.html","utf8"),\
 s=fs.readFileSync("target_teavm_wasm_gc/src/web/autoconnect.js","utf8");\
 const i=h.indexOf(s.slice(0,120)), j=h.indexOf(s.slice(s.length-120));\
 console.log("inlined engine verbatim:", i>0 && h.slice(i,j+120)===s)'
```

Package and ship in one unattended chain - `tmp/autoconnect/ship.sh` runs the
linker above and, on success, commits and pushes it. It refuses to commit if the
engine is not inlined verbatim, and with `EXPECT_SHA` set it also refuses unless the
packaged file is the byte-identical artifact the acceptance run tested:

```bash
cd ~/eagle/project
EXPECT_SHA=$(shasum -a 256 dist/direct-connect.html | cut -d' ' -f1) \
  bash tmp/autoconnect/ship.sh          # linker && commit && push, in one command
```

Reading the logs, in order: `ports: peer socket probe answered with N socket(s)`,
`census: socket channel ready: ... M peer socket(s), B bit(s) per pulse`,
`sym: transmitting ... after a N s column sweep: <ports>`,
`sym: column list sent: K socket(s) x 3 round(s) = 24 of 24 pulse(s)` (the sender)
and `sym: peer column list: K socket(s) from R round(s) of K pulse(s): <ports>`
(the receiver - the two port lists have to be the same list), `sym: frame copy 1 of
1: 107 of 107 pulse(s)`, `sym: read N arrival(s) after the sweep, no payload yet
(... X pulse(s) never arrived)` (this line says outright whether the frame was read
short, long or complete), then `sym: payload decoded ...` - possibly `by filling N
hole(s)` - and `final: DTLS client/server - peer port P`, `final: connection state
connected`, `connect: connected over a direct data channel`.

## 4. State: what is verified, what is still flaky

Verified working, in runs of the real engine (Chromium), and **on the shipped
single-file artifact in stock Google Chrome 154** with no feature flags, plain
static HTTP from the LAN address, no discovery server, no relay:

* discovery with no server: sweep find, locate by source port, peer address;
* census labelling 16/16 readers in ~2 s;
* the column sweep on both sides, three rounds of eight columns, and the same
eight ports on the other side: `column list sent: 8 socket(s) x 3 round(s) = 24
  of 24 pulse(s)` / `peer column list: 8 socket(s) from 2 round(s) of 8 pulse(s):
  50020,50077,50104,50113,50175,50209,50216,50232`;
* a probe readback that is the peer's readers: 13 and 14 sockets for a peer with 15
  and 16 (with the first attempt coming back with one socket and being retried,
  which is what the retry loop is for);
* **a payload decode on both sides of the shipped artifact, and a real link**:
  bob read a frame that was two pulses short and the CRC filled them
  (`payload decoded by filling 2 hole(s) in a 105-arrival frame (candidate 50 of
  64, cadence measured at 605 ms) against the announced 8-socket list`), alice read
  hers clean (`payload decoded from 107 arrival(s) at position 0 against the
  announced 8-socket list`), both took their DTLS roles, both moved to
  `final: connection state connected` and `connect: connected over a direct data
  channel`, and a message crossed: `MESSAGE DELIVERED: alice -> bob`,
  `VERDICT: connected in both windows`;
* the same end-to-end result in the engine harness: both peers `"state":
  "connected"`, `"failure": null`, and the invite/answer round trip complete
  (`inviteAnswerComplete: true`, `answer:EGC-a-EGC-o-0b`), which is a message the
  game layer's own codec would carry;
* the engine harness's own earlier form of that result, before the sweep: one side
  decoded and dialled the peer's real port - that run is where the DTLS role bug
  (item 8) was found.

Still flaky, and the reason this is not "100%" yet:

* per-attempt success is still a coin toss per side. The acceptance run above
  needed a hole-filled read on one side and a clean read on the other; the previous
  artifact run (same build path, engine before items 13-16) decoded on one side
  only and ended `VERDICT: NOT connected`. The engine tries three times per side
  (~83 s per attempt: 18 s sweep + 64 s frame) and both sides must succeed.
* a frame's stream still carries **strays** the sender never fired - the queued ICE
  checks of probe/sweep connections, 9-35 per frame, plus the trailer sockets of a
  pulse (two arrivals for one pulse when ICE paces them past `PULSE_DUP_MS`). The
  decoder skips up to `DECODE_SKIPS` of them from a handful of start offsets and the
  slotted read tolerates them in a different way, but a stray that lands *on a
  column socket* inside the frame is indistinguishable from a symbol and costs that
  read.
* the connection budget: a pulse is a fresh connection and Chrome's 500-connection
  lifetime cap has no refund, so a page that has paid for its reader lottery (~14
  connections per reader socket) can afford about one 107-pulse frame. A second
  copy inside the attempt was tried and reverted for exactly this reason (both
  sides decoded two copies and *then* both final connections went to `failed` two
  milliseconds after `connected`).

Where the next work goes, in order of expected value:

1. shrink the frame. The payload is 320 bits because 32 of its 40 bytes are the
   DTLS certificate fingerprint, and 8 columns is 3 bits per pulse, so a frame is
   107 pulses of mostly-fingerprint. Two independent levers: (a) base-K coding -
   read the payload as one big integer and emit it in base `K` for the *actual*
   column count (a 14-column sweep is 3.8 bits per pulse, 84 pulses, and it also
   stops wasting the columns a probe read back but the power-of-two prefix
   discarded); (b) send the fingerprint only to the side that has to verify it (the
   DTLS client), which drops the server's payload to ~8 bytes - 22 pulses, ~18 s.
2. or identify the strays at their source: log every `fire()` in the engine (tag,
   address, port, source ports, time) under the diag flag and diff it against the
   receiver's arrival list for one frame. That turns the hypothesis above into a
   measurement in one run, and it is the only way to tell a queued probe check from
   something nothing in this engine fired.
3. or name the strays outright instead of filtering them: with the sweep in place
   the engine knows every port it will ever aim at, so an arrival whose port is not
   a column is not a symbol at all - it can be dropped at the reader (`pollReader`)
   rather than inferred later from `bySource` counts and `PULSE_DUP_MS` windows,
   which are the two heuristics that still guess.
4. and verify on two machines. Both peers in every run above are on one host, and
   that is why the probe can read one of *our* sockets back as the peer's (a check
   to the machine's LAN address is answered by any local socket bound there). Two
   machines cannot do that, so the readback there is a stricter test than anything
   the harness can run - and it is the deployment case.

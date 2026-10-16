# Serverless LAN autoconnect — final findings and how to reproduce

Two Eaglercraft clients, one LAN, **no discovery server and no relay of any kind**:
the two pages find each other through ICE connectivity checks alone and build a
direct data channel. This is the pass that took that path from "never gets past
discovery" to a working handshake, and this document is the record of what was
measured, what was fixed, and what is still not reliable.

Engine under test: [`project/target_teavm_wasm_gc/src/web/autoconnect.js`](../target_teavm_wasm_gc/src/web/autoconnect.js)
(inlined into the single-file client by `wasm-toolchain/build-single-html.js`).

## 1. What the serverless path does

| phase | mechanism |
| --- | --- |
| readers | each page opens `READER_TARGET` connections whose UDP sockets land by lottery inside a shared 512-port window (50000-50511). Those sockets are its "grid" and its identity on the wire. |
| discovery | a sweep fires checks at every host in the local `/24`s × the window ports, then reads back which pairs succeeded (a peer's reader sockets answer). If a peer is probing *us* first, its source port identifies it and a locate readback finds its address. |
| census | each reader fires one check at a throwaway port of the peer's address; reading back which of its own sockets carried it labels the socket (16/16 in every run since the fix). |
| probe | the page fires one check at each of the peer's 512 window ports and reads back which answered: that set is the peer's sockets, i.e. the **alphabet** the pulses index into. |
| frame | the payload (magic, 16-bit id, 32-byte DTLS fingerprint, final port, CRC16 = 320 bits) is sent as a train of pulses, one per fresh connection, each aimed at the socket whose index is the symbol being sent. |
| decode | the peer reads its readers' stats: every arrival names the socket it landed on (the symbol) and the pair stat gives the receipt time (the order). Sliding-window read, CRC decides. |
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
   twice. Measured: with every observed socket in the alphabet 1 of 7 sampled
   pulses mapped back to its symbol; with the hot sockets, 10 of 10.
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
    statistical estimates.

## 3. How to reproduce

Engine-level harness (two Chromium processes, two engine pages, no discovery
server - `peer.html` loads `autoconnect.js` directly):

```bash
cd ~/eagle/project
python3 -m http.server 8124 --bind 0.0.0.0 &          # any static server
python3 tmp/autoconnect/run-peers.py 8124 300000 > out/ac-run.log 2>&1
```

With per-arrival/per-pulse diagnostics (one line per arrival and every tenth
pulse, enough to pair arrivals to pulses by source port):

```bash
ACDIAG=1 python3 tmp/autoconnect/run-peers.py 8124 300000 > out/ac-diag.log 2>&1
python3 tmp/autoconnect/analyse-order.py  out/ac-diag.log   # order + read latency
python3 tmp/autoconnect/analyse-symbols.py out/ac-diag.log  # symbol mapping per pulse
```

Artifact-level acceptance test (the shipped single file, two real Chrome windows,
plain static HTTP, engine started as the LAN screen starts it):

```bash
cd ~/eagle/project
python3 -m http.server 8125 --bind 0.0.0.0 --directory . &
python3 tmp/autoconnect/artifact-two-chrome.py 8125 300000
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

Reading the logs: `ports: peer socket probe answered with N socket(s)`,
`census: socket channel ready: ... M peer socket(s)`, `sym: transmitting ... as N
pulse(s) x B bit(s)`, `sym: stream: ... symbol(s) for a N-pulse frame` (the two
numbers agreeing is what a decode needs), `sym: payload decoded ... (B bit(s) per
pulse, X-socket hot-socket alphabet)`, then `final: DTLS client/server - peer port
P`, `final: connection state connected`, `connect: connected over a direct data
channel`.

## 4. State: what is verified, what is still flaky

Verified working, repeatedly, in runs of the real engine:

* the whole path to the frame in **stock Chrome with no flags**, plain static HTTP
  from the LAN address (`lna-probe.py`, two processes: discovery, census 16 of 16,
  probe 10 peer sockets);
* discovery with no server: sweep find, locate by source port, peer address;
* census labelling 16/16 readers in ~2 s;
* a clean alphabet on both sides after the seed fix: `probe answered with 10
  socket(s)` and `... with 13 socket(s)`, the out-of-window seed logged and
  ignored, and both sides then transmitting `320 bits as 107 pulse(s) x 3 bit(s)`
  - before that fix the same phase produced a 2-column, 1-bit, 320-pulse alphabet
  (a 192 s frame);
* the probe returning the peer's socket set (15-16 of 17) instead of 0-9;
* frames whose symbol channel is exact: `arrival port == pulse target` 10 of 10
  and symbol index == sender's symbol 10 of 10 (hot-socket alphabet);
* arrival ordering exact (0 inversions) once the gap exceeds the reader cadence and
  arrivals are dated by the pair's receipt timestamp;
* **a full payload decode** in the shipped artifact (`payload decoded from 107
  arrival(s) at position0, 3 bit(s) per pulse, 8-socket hot-socket alphabet`) with
  the peer's real final port in the payload, which is what the fixes in rows 8 and
  9 were measured against - it is what made the decoding side take the DTLS server
  role and dial the peer's real port.

Still flaky, and the reason this is not "100%" yet. The last acceptance run of the
shipped artifact (sha256 `9456b043cf1702b7d6f88d78f3a8826fd0201f3bedfe2689111f614e15718829`,
75,872,903 bytes, built by the commands in section 3) ran in stock Chrome with no
flags, plain static HTTP, and ended `VERDICT: NOT connected: {'alice': 'failed',
'bob': 'failed'}`: both sides discovered, censused, probed a clean alphabet and
transmitted full 107-pulse frames, and neither decoded. The blocker is defect 12
above and the first item of section 5: the two sides' alphabets disagreed.


* a frame's stream carries **9-35 stray single-check arrivals** the sender never
  fired, so the contiguous decoder only succeeds when they fall outside the frame's
  span: an insertion in the middle shifts the symbol sequence and the CRC rejects
  an otherwise perfect read. Measured on the shipped artifact, one frame, both
  sides: 131/134/137 and 135/136/139/142 arrivals for a 107-pulse frame with no
  decode, then - a later read of that same frame - **107 arrivals, exact**, and a
  decode: `payload decoded from 107 arrival(s) at position0 (3 bit(s) per pulse,
  8-socket hot-socket alphabet)`, followed by `final: DTLS server - peer port
  51640`, the peer's real advertised port. The count *falls* over time because this
  filter drops an arrival once its source port has fired a second time, and the
  strays are the queued ICE checks of probe/sweep connections that Chrome had not
  sent when the first read looked. The skip-tolerant pass (`DECODE_SKIPS` = 3 over
  the first `DECODE_STARTS` positions, with an operation budget) covers the
  interior case; against 20+ strays it has not been enough yet.
* consequence: per-attempt success is a coin toss. In that same artifact run one
  side decoded a clean frame and applied the peer's real final port, while the
  other side ran out of attempts (attempt 1: 24-30 extra; attempt 2: 9 -> 2
  missing as the peer finished transmitting; attempt 3: 0 arrivals at all). The
  engine tries three times per side and both sides must succeed for a link.

Where the next fix goes, in order of expected value:

1. tie the alphabet to something both sides *know* instead of estimating it.
   Either the receiver reads its own reader list (`gridSockets()`, exact, no
   strays, no filter) and the sender's probe is driven to completeness - retry
   until no new socket appears for ~15 s, which the retry loop above now makes
   possible - or the decoder separates pulses from strays by *time*: the sender's
   cadence is exact (600 ms, receipts dated to 1 ms) while a stray lands on no
   slot, and the same slot map shows which pulses never arrived, so a hole can be
   filled by trying the alphabet with the CRC as the judge. Both remove the
   15-17-vs-10-13 mismatch of row 12; the timing version also removes the stray
   problem, because an arrival off the cadence is not a symbol at all.
2. or identify the strays at their source: log every `fire()` in the engine (tag,
   address, port, source ports, time) under the diag flag and diff it against the
   receiver's arrival list for one frame. That turns the hypothesis above into a
   measurement in one run, and it is the only way to tell a queued probe check from
   something nothing in this engine fired.
3. or stop depending on the exact sequence: send the payload twice per frame with
   a known offset, or send each symbol twice back to back and let the decoder try
   both alignments; a duplicate-tolerant read costs one frame of wall clock, which
   the 3-attempt loop can afford now that a frame is ~64 s rather than ~20 min.
4. or shrink the payload. 32 of the 40 bytes are the DTLS certificate fingerprint;
   if the fingerprint were transmitted only to the side that has to verify it
   (the DTLS client), the DTLS server's payload would be ~8 bytes, the frame would
   be 16-24 pulses, and at a 600 ms gap the whole exchange would fit in ~15 s.

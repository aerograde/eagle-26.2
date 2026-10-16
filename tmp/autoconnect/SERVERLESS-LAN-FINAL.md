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
python3 tmp/autoconnect/artifact-two-chrome.py 8124 300000
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

* discovery with no server: sweep find, locate by source port, peer address;
* census labelling 16/16 readers in ~2 s;
* the probe returning the peer's socket set (15-16 of 17) instead of 0-9;
* frames whose symbol channel is exact: `arrival port == pulse target` 10 of 10
  and symbol index == sender's symbol 10 of 10 (hot-socket alphabet);
* arrival ordering exact (0 inversions) once the gap exceeds the reader cadence and
  arrivals are dated by the pair's receipt timestamp;
* **a full payloak decode on both sides in the same run** (`payload decoded ... 3
  bit(s) per pulse, 8-socket hot-socket alphabet`), with the peer's real final port
  in the payload, which is what the fix in row 8 and 9 was measured against.

Still flaky, and the reason this is not "100%" yet:

* a frame's stream carries **9-27 stray single-check arrivals** the sender never
  fired. The contiguous decoder can absorb them only when they sit at the edges of
  the frame's block; an insertion in the middle shifts the symbol sequence and the
  CRC then rejects an otherwise perfect read. The second, skip-tolerant pass
  (`DECODE_SKIPS` = 3, over the first `DECODE_STARTS` positions, with an operation
  budget) is in the engine to cover exactly that case, and it needs a run where the
  strays are interior to be scored.
* consequence: per-attempt success is a coin toss (one of three recent runs decoded
  on both sides; the other two exhausted their attempts). The engine retries three
  times per side, and both sides must succeed for a link.

Where the next fix goes, in order of expected value:

1. identify the strays at their source. Every arrival the sender did not fire comes
   from a connection that fired exactly once; the probe/sweep/locate connections
   are already filtered by repeat count, so these are new connections nothing in
   the frame fired. Instrument the peer page to log every `fire()` it makes
   (address, port, source ports, time) and diff that against the receiver's
   arrivals for one frame - that names them in one run.
2. or stop depending on the exact sequence: send the payload twice per frame with
   a known offset, or send each symbol twice back to back and let the decoder try
   both alignments; a duplicate-tolerant read costs one frame of wall clock, which
   the 3-attempt loop can afford now that a frame is ~64 s rather than ~20 min.
3. or shrink the payload. 32 of the 40 bytes are the DTLS certificate fingerprint;
   if the fingerprint were transmitted only to the side that has to verify it
   (the DTLS client), the DTLS server's payload would be ~8 bytes, the frame would
   be 16-24 pulses, and at a 600 ms gap the whole exchange would fit in ~15 s.

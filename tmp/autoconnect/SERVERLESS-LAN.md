# Serverless LAN play (`autoconnect.js`) — mechanism, defects, fixes, and how to reproduce

Status: the engine's phases run with **no server of any kind**, and the defects that
kept a data channel from ever forming are fixed in
`target_teavm_wasm_gc/src/web/autoconnect.js`. The exchange's read step is still
bounded by the page's `getStats()` cost, so the outcome on a loaded machine is
measured below rather than asserted. See "Verification" for exactly what passed.

Written 2026-10-15 against `6bac39c` and the working tree of that day.

## 1. Two peer sources, only one of which needs the server

| Source | Needs a server? | Evidence |
| --- | --- | --- |
| `lan-discovery.js` + `LANDiscovery` (the origin-based one, which produced the verified invite flow in `tmp/lan-verify/FINDINGS.md`) | **Yes** — it registers at `POST /eagler-lan/v1/presence` on the page's own origin, which is what `tools/lan-server.mjs` serves | Served `dist/direct-connect.html` from `python3 -m http.server`: both pages report `Automatic LAN discovery unavailable here (HTTP 404) - run: node tools/lan-server.mjs`, and the server log shows the only origin hits are the page and the `/health` probes |
| `autoconnect.js` + `AutoConnect` (the plan's serverless tier) | **No** — the module inlined in the shipped artifact contains no `fetch`, `XMLHttpRequest`, `WebSocket` or `sendBeacon`, and never mentions `/eagler-lan`; it is ICE-only with shared credentials | Extracted the inline block from `dist/direct-connect.html` (96,999 chars) and counted: 0 occurrences of each |

So "does it work without `lan-server.mjs`?" is answered per tier, and the tier the
plan promised is the second one. On the shipped artifact, with nothing but a static
host, that tier boots and runs: `boot: peer id … (alice) [shared credentials (AC24)]`,
`readers: port lottery: …`.

## 2. How the serverless carrier works, in one page

1. **Phase 0 — pool.** A "certificate" is generated once (`RTCPeerConnection.generateCertificate`).
   *Readers* are connections whose OS-chosen UDP socket lands inside a fixed window
   (`W_START = 50000`, 512 ports) — the port lottery. Everything else (scanners,
   probe pool, final connection) is deliberately forced *outside* that window, so a
   peer's probe can only ever answer with a reader socket.
2. **Shared credentials.** Every connection's SDP is rewritten to the same
   `ice-ufrag`/`ice-pwd`, and a forged answer (same credentials, placeholder
   fingerprint, no candidates) is applied locally. Two ICE agents with the same
   credentials therefore validate each other's connectivity checks with no
   signalling at all.
3. **Phase 1 — discovery.** Connections fired at `192.168.0.1-254` (and `192.168.1.x`)
   on the windowed ports; whichever address:port answers is the peer, and the
   *source port* of its own probe gives its address back (candidate addresses are
   redacted by Chrome, ports are not).
4. **Phase 1.5 — sockets.** A probe of the 512 windowed ports reads back the peer's
   live reader sockets; the receiver labels each of its own sockets by firing one
   check at an unbound port and reading `candidate-pair.localCandidateId` (the
   "census").
5. **Phase 2 — the payload.** The DTLS fingerprint cannot be known in advance (a
   certificate is per page), so 40 bytes — magic, id, 32-byte fingerprint, final
   port, CRC-16 — travel as *pulses*: one fresh connection per pulse, aimed at one
   of the peer's sockets. The **symbol is the socket the pulse landed on** (20
   columns = 4 bits, 320 bits = 80 pulses). The receiving page reads
   `candidate-pair` rows from its readers and turns the arrival order into symbols.
6. **Phase 3.** Both apply the peer's real fingerprint, DTLS handshakes, and the
   pre-created data channel opens.

## 3. Defects found (all fixed in this pass)

Each one is a real cause, not a theory: the first three between them explain why
**no log in `tmp/autoconnect/` contains a decoded payload**, and the last three why
several phases sat silent for minutes.

1. **The receiver indexed its arrivals against the wrong socket list.** `tryDecodeSocket`
   read `col = SYM.cols.indexOf(a.port)`, where `a.port` is *our* local socket and
   `SYM.cols` is the *peer's* socket list. Almost every arrival therefore read as
   "not in the alphabet", the decode filled zeros, and the CRC rejected it. The
   alphabet must be our own sorted in-window sockets — the same list the sender
   probed and numbered. Now `ownSocketAlphabet()`, with the same single-removal
   hypotheses the legacy grid decoder uses for a lost list pulse.
2. **The read was positional against our own pulse timeline.** Each of our pulse
   fire times was a slot for the peer's arrivals, so a peer whose frame started one
   slot earlier shifted every symbol after it, and one missed pulse did the same —
   and all 80 had to be read clean. Now the decode is content-addressed: slide a
   window over the arrival stream, read symbols, and let the CRC decide. A missed
   pulse costs the window it sits in, and a frame that started seconds before or
   after ours is still readable.
3. **Pulse pacing and lifetime were constants sized for a quiet page** (`80 ms`
   apart, closed after `700 ms`). A loaded page answers `getStats()` in 0.3–4 s, so
   the arrival was never sampled before the pair went away. Both numbers are now
   derived from the measured pass cost (`statsPassMs`, an EWMA): the pulse gap
   tracks the pass cost, and a pulse outlives three receiver sweeps.
4. **The stats queue starved everything but the readers.** Urgent reads were served
   until the urgent queue was empty, and a timed-out caller left its job in the
   queue. With 16 readers timing out at 4 s each the urgent queue was never empty,
   so **no census job ever reached the sole queue slot** — the run sat in phase 1.5
   for three minutes without printing a single line. Now: four urgent jobs, then
   one normal job, and a job whose caller has already timed out is dropped instead
   of run later. The reader cycle also follows the measured pass cost instead of a
   fixed 250 ms, so the queue is no longer asked for work it cannot serve.
5. **The census fired once, slept 2 s and read once.** On a slow pass it labelled a
   handful of sockets and the exchange then ran on a three-socket alphabet — its own
   log line said `census: reader sockets: 3 of 20 labelled` and nothing stopped it.
   Now it re-fires and re-reads until every connection is labelled or a 45 s
   deadline passes (each round uses the next unbound port above the window, since a
   pair that has already carried a check produces no new answer).
6. **One attempt only.** `MAX_ATTEMPTS = 1` made a single unlucky read terminal
   (`no payload decoded after 1 attempts`). Now 3.
7. **The locate/probe readbacks competed with the readers** for the single queue
   slot, so they outlived their own deadline (measured: `locate:` never completing
   within 15 s while 16 readers queued polls). The readers idle while `preparing`
   is set — the census already did this, and the locate phase now sets it too.
   `LOCATE_WAIT_MS` is 30 s because the readback consults its deadline only after a
   full round over the scanners.
8. **The sweep's heavy scanners were reused for the probe.** Each had fired
   thousands of checks, and one stats read of such a connection takes seconds.
   The probe pool is now six fresh out-of-window connections, built before the swap
   and swapped atomically (emptying the array in place let the sweep's own pump
   index `undefined` and throw `Cannot read properties of undefined (reading
   '__acUsed')`).

## 4. How to reproduce

```sh
# 1. Serve the repo (any static server; no discovery routes are needed at all)
cd project && python3 -m http.server 8124 --bind 127.0.0.1

# 2. Two pages, one engine each, as separate browser processes
python3 tmp/autoconnect/run-peers.py 8124 420000     # port, terminal timeout ms
```

`tmp/autoconnect/peer.html` loads `../../target_teavm_wasm_gc/src/web/autoconnect.js`,
starts it, and performs the offer/answer-shaped message exchange the game does over
the channel once it opens. Every engine line is printed as `[alice]`/`[bob]` with
milliseconds since start. The run is terminal when a peer reaches `connected`, on a
`failed` event, or after the timeout.

What to look for, in order:

```
boot: peer id … (alice) [shared credentials (AC24)]
readers: port lottery: 16/16 reader connection(s) after N batch(es)
scan: peer found at 192.168.0.100:<port>
locate: port <port> answered at 192.168.0.100
census: reader sockets: 16 of 16 labelled
census: socket channel ready: 16 own socket(s), 16 peer socket(s), 4 bit(s) per pulse
grid: window quiet - exchange attempt 1 of 3
sym: transmitting 320 bits as 80 pulse(s) x 4 bit(s) over 16 peer socket(s); <gap> ms apart, kept <life> ms (stats pass <n> ms)
sym: payload decoded from … arrival(s) at position … (4 bit(s) per pulse, 16-socket alphabet)
final: DTLS client - peer port <n>
connect: data channel open
```

For the shipped client (no server at all): serve `project/dist/direct-connect.html`
from the same static origin and start two pages with `?lanname=alice` / `?lanname=bob`;
`window.__eaglerLAN.status()` shows the origin tier parked at HTTP 404 while
`window.__eaglerAC.version` is 4 and `window.__eaglerAC.start({name})` runs the
serverless engine. `window.__eaglerAC.diagnosis()` prints the live numbers
(columns, bits per pulse, pulses fired, arrivals read, own sockets).

Other useful entry points (`window.__eaglerAC`): `state()`, `phase()`, `status()`,
`peers()`, `self()`, `logs()`, `stop()`, `start({socket:false})` to force the legacy
grid carrier instead of the socket channel.

## 5. Verification

### 5a. The payload read, as a unit test (`tmp/autoconnect/test-decode.mjs`)

`node tmp/autoconnect/test-decode.mjs` runs the engine's own encoder and decoder in
Node with `window` stubbed — no browser, no WebRTC — through the `_debug` seam.  All
checks pass:

```
PASS  clean frame decodes  (start 0, id 1a2b, port 61616)
PASS  a frame that starts inside a noisy stream decodes  (frame found at position 2)
PASS  a probe that missed one socket still decodes (107 pulses at 3 bit(s))  (start 0)
PASS  the peer's ports as the alphabet do not decode (the old defect)  (no decode, as the old logs showed)
PASS  one lost pulse inside the frame is rejected (documented limit)
```

The third check is the interesting one: a probe that missed a socket changes both
the numbering *and* the alphabet size (15 sockets → 3 bits per pulse, 107 pulses),
and the receiver recovers the payload from its own list and the CRC alone.  The
fourth is the defect: with the peer's ports as the alphabet the same stream decodes
to nothing, which is exactly what every logged run did.

### 5b. What the read can tolerate, measured

The frame carries **no redundancy**: 320 bits in 80 pulses, and one pulse that is
not read breaks the CRC for that whole attempt.  Simulated over 400 frames:

| lost pulses | 1 attempt | 3 attempts |
| --- | --- | --- |
| 0.1% | 92.3% | 100.0% |
| 0.5% | 63.7% | 95.0% |
| 1% | 41.3% | 82.5% |
| 5% | 1.8% | 6.3% |

So "100%" is a statement about the read rate, not about the retries: the carrier is
solid when a pulse's arrival is sampled reliably (≲0.1% misses) and is a coin toss
at 1%.  Two ways to close the rest, in order of value:

1. **Redundancy in the frame.** The CRC already rejects a bad read, so the cheapest
   real fix is a systematic erasure code over the 80 symbols with the decoder
   knowing which positions are missing (the stream tells it).  With 1.5× parity a
   frame would tolerate ~26 missing pulses instead of zero.
2. **Sampling margin.** Keep the pulse lifetime several times the receiver's full
   sweep (the adaptive lifetime does this) and keep the reader count low
   (`READER_TARGET = 16`: the sweep cost is K × pass).

### 5c. The engine in a browser

Verified on 2026-10-15 (macOS, Chrome 154 headless, two browser processes on one
machine, one static `python3 -m http.server`, **no discovery service**):

* The shipped artifact's inline engine contains no HTTP primitive (0 × `fetch`,
  `XMLHttpRequest`, `WebSocket`, `sendBeacon`; 0 × `/eagler-lan`), is present as
  version 4, and boots with a bare static origin.
* The origin tier parks with the actionable message quoted in section 1, while
  `LANInvites.isAvailable()` still reports a peer source (the serverless tier).
* Phases 0–1.5 of the serverless tier run with no server: 16/16 readers in 10–12
  batches (~3 s), `scan: peer found at 192.168.0.100:<port>` and
  `locate: … answered at 192.168.0.100`.
* The pre-fix defects are visible in the same logs and are now gone from the code:
  `census: reader sockets: 3 of 20 labelled` (one-shot census), the minute-long
  silence with 16 readers timing out at 4 s (queue starvation), and
  `no payload decoded after 1 attempts` (single attempt, broken alphabet).

Environment caveat, stated plainly: the machine used for these runs was heavily
loaded (load average 20–350 during the runs, a VM at 8 GiB, a local web page holding
~110% CPU, plus disk-indexing extensions), and its `getStats()` cost — the one number
the carrier is timed against — measured **0.3–4 s per pass** in the engine's own
logs (`reader stat poll timed out (4200 ms)`, once `62471 ms`), against the 20–60 ms
the engine was designed for.  With 4 s passes and 16 readers a receiver sweep costs
~64 s, so no frame can be read; the code fallback in that state is the designed
behaviour, and it is what the logs show.

**Not verified: a completed data channel.**  A run needs a machine where a stats
pass is tens of milliseconds — ideally two real devices on one LAN, which is also
the only way to exercise mDNS candidates.  Reproduce with section 4 and look for the
`payload decoded` / `connect: data channel open` lines.

**Not yet shipped.**  The module is inlined into the client at build time, so this
source fix is not in `dist/direct-connect.html` yet: the client needs a relink and a
repackage (`wasm-toolchain/build-standalone` + `build-single-html.js`) before a
player can use it.  Also still open from the previous pass: a guest who only wants
to join has no entry point, because `AutoConnect.start()` runs only from host-side
screens, and the sweep only covers `192.168.0.x`/`192.168.1.x`.

Two operator notes that matter for any repeat run:

* Two full clients on one machine, each building ~300 `RTCPeerConnection`s, is
  enough to oversubscribe a laptop; use two machines when you can.
* On a loaded machine the engine's own adaptive numbers get large (gap up to 700 ms,
  pulse lifetime up to 8 s, frame up to ~60 s), so allow ≥7 minutes per run.

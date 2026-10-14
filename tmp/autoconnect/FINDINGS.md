# AutoConnect (serverless LAN P2P) — what the browser actually allows

Measured in real Chromium (Playwright "Chrome for Testing" 148.0.7778.96,
headless, two separate browser processes on one machine) against a static page
served by `python3 -m http.server`.  Raw runs: `spike*.html` + `run-spike.py`,
`peer.html` + `run-peers.py`, logs `spike*-run*.txt` and `peers-run*.log`.

The engine under test is [autoconnect.js](../../target_teavm_wasm_gc/src/web/autoconnect.js)
(not wired into the game yet — `index.html` does not load it).

## 1. Mechanisms that work (verified, with numbers)

| Mechanism | Result |
|---|---|
| Munging `ice-ufrag`/`ice-pwd` in `setLocalDescription` | Accepted. Chrome keeps the rewritten credentials. |
| Forged remote answer (own SDP, shared creds, placeholder fingerprint, `a=setup:passive`, candidates stripped) | `setRemoteDescription` accepts it. Required before `addIceCandidate`. |
| ICE password length | Must be **22–256** chars. The plan's `AC24shared7x` (12) is rejected: "ICE pwd must be between 22 and 256 characters long". ufrag is `[A-Za-z0-9+/]{4,256}`. |
| Shared-credential check to a known address:port | Scanner reached `connected` in **52–106 ms**; the listening side saw a peer-reflexive pair with `requestsReceived ≥ 1`. |
| mDNS name as a check target (`<uuid>.local` from our own SDP) | Works. Pair succeeded (5 checks sent / 5 received) without knowing the peer's IP. |
| Reading our own network position | Local and peer-reflexive candidate **addresses are redacted** (`address: ""`, `ip: ""`); only the **port** is exposed. The only address we get back is one we supplied ourselves. Local SDP carries `<uuid>.local`, never a literal IP. |
| Pulse channel (one fresh connection per pulse, receiver counts new `prflx` candidates per slot) | Works. In a 16-bit test with 150 ms slots, 5 pulses landed 25–60 ms after each send, one new candidate each; decode is exact once slot alignment accounts for that lag (naive bucketing shifted a whole slot). |
| Port lottery | Real rates: a socket lands in a 64-port window with p≈0.39%, in a 1024-port window with p≈6.25% (two UDP sockets per connection). Reaching 4 sockets in the *narrow* window costs ~128 connections; in the wide window ~16 connections each. |

## 2. Mechanisms that do not work

* **Broadcast rendezvous.** Checks sent to `192.168.0.255` / `.1.255` / `10.0.0.255` were
  transmitted (`requestsSent` 13–14 each) but **nothing arrived** at a listener on
  the same machine, even at the exact port. So the plan's fast path (broadcast to
  1024 ports, 1–2 s) is unavailable.
* **ICE ufrag as a data carrier.** A per-connection ufrag is visible to the sender
  but the receiver's peer-reflexive candidate reports `usernameFragment: null` —
  no hidden payload channel (the plan does not use this, but it was worth testing
  as a way to avoid the 256-bit pulse exchange).
* **Peer address from `getStats`.** A peer-reflexive candidate has no address, so a
  discovered peer's IP is only knowable because *we* supplied the candidate that
  matched. The peer that is found cannot learn the finder's IP this way.

## 3. Hard limits that reshape the design

* **500 live `RTCPeerConnection`s per page, and closing one does not return
  budget.** Measured: the 501st constructor throws "Cannot create so many
  PeerConnections", and after closing 250 of 500, zero new ones could be created.
  This is a *lifetime* budget, so every pool (listeners, scanners, pulses, final)
  must fit inside ~500 — the plan's "~550 pooled PCs, 128 PCs per batch" cannot be
  created in one page at all.
* **Connectivity checks are paced at ~16/s per connection** (steady 16/s over 10 s
  in a single connection with 254 candidates). Aggregate throughput scales with the
  number of connections (~2 600 checks/s across 96 connections), so the sweep is
  scheduled by *connection count × window width × hosts*, not by wall-clock luck.
* **`addIceCandidate` cost depends on how it is issued.** Fired in one large
  `Promise.all`, 24 k candidates settled in ~10 s (~0.4 ms each). Awaited
  sequentially, or in chunks with yields, the same work costs ~3.5–4 ms per
  candidate (one /24 sweep took 105–134 s). The observed ceiling is the ICE
  thread's candidate processing rate (~300/s when chunked), not the pacer.
* **`getStats()` is expensive once a connection holds hundreds of pairs.** Polling
  all 96 scanners each cycle starved the sweep; polling a rotating slice of 8 is
  what let the sweep progress at all.

## 4. Consequence for the plan's numbers

The plan's "~10–18 s, zero clicks, ~90 % of home networks" does not follow from
the browser's real behaviour:

* A discovery window of *W* ports × 254 hosts × 2 subnets costs `512·W` candidates.
  At ~2 600 checks/s and ~0.4 ms/addition that is ≈ `0.2·W` s of pure work, so
  `W = 64` is already ≈ 13 s of sweep before any handshake — and it only finds a
  peer that owns a socket inside that window, which costs ~128 connections per
  socket (the reason the engine keeps a *narrow* discovery window and puts the
  pulse channels in a *wider* window that the peer enumerates on our address).
* The DTLS fingerprints cannot be skipped: they are 256 bits *per direction*, and
  the only covert channel between two peers that know each other's address but not
  their certificates is one fresh connection per bit (a connection's pulses are
  only visible to the peer as *new* peer-reflexive candidates). One channel at
  200 ms slots is ~50 s for a payload; four channels ~15 s. Every pulse also costs
  a connection from the lifetime budget.
* A fixed, well-known certificate would remove the exchange entirely, but WebRTC
  only accepts certificates from `generateCertificate()`; the private key cannot be
  imported, so the fingerprint cannot be shared in advance.

## 5. State of the engine

`autoconnect.js` implements: shared-credential pool, two-window port lottery
(narrow discovery socket + wide pulse channels), subnet sweep with rotating
`getStats` polling, peer-port enumeration, the preamble/bit-slot pulse exchange
with channel ids carried in the preamble and CRC-checked payload, and a real
final connection (reusable certificate, shared credentials, peer's real
fingerprint, roles by peer id).

Verified working in the harness: certificate fingerprint, listener lottery
(4 channels + a discoverable socket in ~1.4 s), pulse-pool construction, and the
engine's failure path (it reports "no peer found" instead of hanging).

**Not yet working end to end: discovery.** In the last runs the sweep did not
find the peer before its budget, because the candidate pump and the check pacer
interact badly at this volume (see §3 and the run logs: `peers-run5.log`,
`peers-run6.log`). The next step is to sweep in small rounds — 254 hosts × 8 ports
per round, 8 rounds — so per-connection pair counts stay low (~25/round) and each
addition keeps its ~0.4 ms cost, instead of letting 400+ pairs accumulate per
connection and paying ~4 ms per addition.

## 6. Reproduction

```bash
# mechanism probes (single page, self-contained)
python3 /Users/rohan/.agents/skills/webapp-testing/scripts/with_server.py \
  --server "python3 -m http.server 8125 --directory $PWD/tmp/autoconnect" --port 8125 \
  -- /tmp/webtest-venv/bin/python tmp/autoconnect/run-spike.py 8125 spike.html "<lan-ip>"

# two-peer harness (two browser processes, both peers)
python3 /Users/rohan/.agents/skills/webapp-testing/scripts/with_server.py \
  --server "python3 -m http.server 8124 --directory $PWD" --port 8124 \
  -- /tmp/webtest-venv/bin/python tmp/autoconnect/run-peers.py 8124 240000
```

## 7. Second pass (engine v3): reliable discovery, aiming still short

The sweep was rebuilt host-major and the discovery window widened, which fixed
discovery; the pulse channel now works but the aim still misses.

Measured this pass (two peers, one machine, `peers-run20..26.log`):

- **Discovery window = pulse window (50000-50511, 512 ports).**  Earlier the
  discovery window was a 48-port slice of the 512-port pulse window, so a
  listener was discoverable only ~9% of the time and most runs found nobody. With
  the windows equal, every listener is a discovery target and runs find the peer
  in round 1: **1119 ms and 1143 ms** (`peers-run26.log`).
- **Host-major sweep shape.**  Rounds are 2 host addresses x all 512 ports
  (~1024 checks) instead of 2 ports x 254 hosts.  Likely DHCP addresses
  (100-199) go first.  Round 1 decides the run, so the /24 cost never lands.
- **Candidates must be handed over in bursts.**  1024 `addIceCandidate` calls in
  one synchronous flood makes the first `getStats()` never return (run20 hung at
  0 heartbeats for 200 s); 128 per burst with a 40 ms gap is fine.
- **A DTLS fingerprint mismatch never exposes the peer's certificate**
  (`spike8.html`, `spike8-run3.txt`): after the transport fails, stats hold only
  the *local* certificate plus `iceState: connected`, `dtlsState: failed`.  The
  32-byte fingerprint therefore has to travel as data — 256 of the payload's 360
  bits, i.e. ~180 of the ~250 pulse sources a page can afford.  No redundancy
  budget: one channel's worth of extra copy does not fit.
- **A pulse's source port is readable, but it does not buy rate.**  The receiver
  reads the exact port the check came from (`spike7-run1.txt`: 6/6 arrivals
  matched the sender's own sockets).  It cannot increase throughput, because
  pulses are still capped by the number of connections: N sources = N pulses, so
  the port can only ever carry the symbol each source happens to own.

**Where it stops.**  A page cannot tell which of the peer's sockets the peer
actually polls for pulses.  Polled sockets and swept-over sockets answer a check
identically, and the peer's own triggered responses (echoes) are the same shape
as its announce.  Detection rules tried and measured: distinct source ports seen
on two or more of our sockets (fails when our enumeration only reaches one of the
peer's polled sockets: `peers-run22.log`); bursts of three or more distinct
sources on one socket inside 2.5 s (matches the peer's locate sweep, whose six
connections arrive together: `peers-run22.log`); a per-socket echo window now
removes our own echoes without hiding the peer's announce
(`peers-run26.log`), but the aim still found fewer than four targets, and four
are needed because the payload is split across four channels.

Last run in this pass (`peers-run26.log`): discovery 1.1 s, enumerate 18 s,
announce rounds at 20.5 s to 9 and 6 peer ports, then no burst was detected on
either side, so no payload was aimed and no channel opened.

## 8. Third pass (engine v4): the chain reaches the payload and stops there

This pass drove the engine itself - `target_teavm_wasm_gc/src/web/autoconnect.js`
through `peer.html` + `run-peers.py` - and nothing else.  The shipped client was
untouched: the earlier edits to `tools/lan-server.mjs`,
`target_teavm_wasm_gc/src/web/lan-discovery.js` and `dist/direct-connect.html`
were reverted to HEAD (`git checkout --`), so the discovery-server path and the
single-file client are exactly as shipped.  Raw runs: `runA.log`..`runE.log` and
`final-run1.log`/`final-run2.log`.

### Per-stage timeline (measured, two browser processes, one machine)

| Stage | Measured | Evidence |
|---|---|---|
| Pool: 4 discovery listeners + 4 beacons | 0.4-1.1 s | `listeners:` lines, every listener in the window |
| Sweep: 512 ports x 254 hosts, host-major | peer found at **1.2-4.0 s** | `scan: peer found at 192.168.0.100:<port>` |
| Enumerate the peer's ports | 6-8 ports answered, done at **7.5-10.3 s** | `ports: peer answered on ...` |
| Wake burst + pulse pool (160 sources) | pool ready **10.7-17.0 s** | `pulse pool ready: 160 fresh source ports` |
| Payload | 158 slots x 100 ms (v3) / x 200 ms (v4) | `sending 304 bits over 4 channels ...` |
| Align + bind channels | every attempt, 2-4 of 4 channels bound | `aligned ...` / `closest start:` |
| Payload checksum | **never passes** | `crc xx vs yy` in every attempt |
| DTLS + DataChannel | **never reached** | no `final:` / `connect:` line in any run |

Discovery and port enumeration are solid and repeatable.  The failure is inside
the payload exchange, and it is not "aim starves" any more: 300+ pulses are
fired, they arrive (the receiving sockets show 100+ each), a start is found and
channels bind.  The bits are wrong.

### Three defects found and fixed this pass

1. **The echo filter was dead code.**  `firedAt` was declared (l.197) and read in
   `pollOne` (l.768) but never written, so every check this page sent came back
   within 25-60 ms and was counted as peer traffic.  `/Users/rohan/.../runC.log`
   shows the effect: a *stable* decode of `a1 1f 6f 30 a4 64` where the true
   payload begins `a5 50 00 fe a7 91` - stable, because the page was reading its
   own transmission back.  Fixed: `fire()` now stamps `firedAt` for every port of
   the source connection, `ECHO_MS` 25 -> 80 ms to cover the measured 25-60 ms
   return lag.
2. **Alignment was a single guess, permanently blacklisted.**  `tryDecode` picked
   one start by a heuristic, and `onStream` marked it `badT0 = true` forever -
   including when the stream was read *while still being sent* (60 arrivals of a
   300-pulse transmission).  Measured: 5 attempts, `no sync burst found` or a
   start near the *end* of the stream (`decoded 9 pulses`).  Fixed: every
   plausible start is now tried in order of how many sockets fired together, each
   on three neighbouring grids, and the payload's own magic byte + CRC8 is what
   accepts one.  A wrong start costs a comparison.
3. **The grid was anchored to one polled arrival.**  An arrival's timestamp is the
   moment the *poll* noticed it, so anchoring the slot grid there put the origin up
   to half a slot off.  Fixed: `phaseOf` estimates the transmission's phase as the
   densest cluster of arrival offsets, and the grid is re-read at that phase and at
   +/-1 slot.  Measured after the fix: the phase estimate is stable to ~5-20 ms,
   i.e. the pulses *are* readable within a slot; the residual errors are per-pulse.

Also fixed: SLOT_MS 100 -> 200 ms (arrival quantisation is ~24 ms of poll delay
plus the check's own 25-60 ms lag, which at 100 ms slots put roughly half the
pulses on the wrong side of a boundary), two shots per 1-bit, and a tolerant
channel bind (a socket needs either end of the preamble, not both, and a doubled
staircase step counts as the earlier one).

### Where it still stops, with numbers

`runD.log` is the closest run: the receiver bound 3 of 4 channels and decoded
`ad b0 8f fd ff 78` where the truth is `a5 92 0f f7 b7 f0 ...`.

| | bits right vs truth |
|---|---|
| group carried by **2** bound channels | **23 / 24** |
| group carried by **1** bound channel | 15 / 24 |

So a channel whose pulses are read against a good grid is ~96% right, and the
payload is only wrong because the read is not 100%: the payload's integrity check
is a CRC8 over 37 bytes with a magic byte, and it accepts a bit string only when
*every* one of its 304 bits is right.  One misread channel - or one pulse read a
slot late - breaks it, and there are ~300 pulses.

### The single smallest change that would finish it

**Carry the payload's value in the pulse's source port, not in whether a pulse
arrived at all.**  A pulse's source port is read exactly by the receiver
(`spike7-run1.txt`: 6/6 arrivals matched the sender's own sockets; `arrivals[].source`
is already used for the peer-port votes), and the sender already chooses which
connection fires every pulse, so choosing one whose port is `symbol mod 16`
carries 4 bits per pulse instead of 1.  The slot grid then only has to give
*position*, which the measurements above say it can do (a good grid read 23/24
bits at 200 ms slots), while value errors - the ~4-40% that kill the CRC today -
disappear entirely: the port is a number the browser reports, not a timestamp.
It also shrinks the exchange: 4 bits per pulse over 4 channels is 16 bits per
slot, so the 304-bit payload is 19 slots - about 4 s instead of 32 s, leaving
room to send it twice for positional redundancy.

### The two consecutive runs of this revision

`two-runs.sh`, two runs back to back of the identical file
(sha256 `2fef63f99b6d5dc9f28be23b2035009de34ae9cf88fcc5d04b2989c1f829e3ca`,
re-hashed after run 1 and unchanged), each against the same 8124 harness:

| | run 1 | run 2 |
|---|---|---|
| peer found (alice / bob) | +1137 ms / +2769 ms | +1686 ms / +3255 ms |
| ports enumerated | 10 at +7481 ms / 8 at +9001 ms | 8 at +7892 ms / 5 at +9468 ms |
| payload attempts | 4 (`158 slots, 31600 ms`) | 4 (`158 slots, 31600 ms`) |
| channels bound at the closest start | 2 of 4 | 2-3 of 4 |
| payload checksum passed | **0** | **0** |
| `finishFinal` / DTLS / DataChannel | never called | never called |
| final peer state after 240 s | `linking`, 0 messages | `linking`, 0 messages |

Both runs stop at the same place, so this is not a flake: the payload is
transmitted, arrives, is aligned and partly bound, and never checksums out.  The
real connection never gets a matching candidate because `beginHandshake` (which
is what calls `finishFinal` with the peer's fingerprint) is only reached from a
decoded payload - so no `RTCPeerConnection` ever leaves `new`, and no
`dtlsState`/DataChannel result exists to report.

## 9. Fourth pass (engine v5): the symbol read, and what it left

Section 8 describes the bit-per-slot revision.  This pass replaced the payload's
*value* channel: a symbol now travels in the source port of the pulse that
carries it (`port % 16`, four bits), the preamble writes each channel's index the
same way (`port % 4`), the sync is an eight-shot burst per channel so a copy's
start is the densest event in the stream, and the four copies are read into one
set of cells and merged by vote.  Revision
`1539ccb4a9b46b11f04e148fd0f7a39016270313253962d9d513f772a071e6cf`.

### What the measurements say now

* **Channel binding is solved.**  Binding by port class identifies the peer's four
  channel sockets exactly, with 94-190 arrivals each, and the indices match the
  sender's target order in every run (`closest start: 4 channels (50033=c3
  50222=c0 50313=c1 50397=c2)` against the sender's logged port list).
* **The grid is anchored.**  The densest grid slots are the sync bursts, one copy
  stride apart: `densest 29:19 0:18 -1:17 ...` in `final-run2.log` (copy stride is
  29 slots, exactly `PREAMBLE_SLOTS + DATA_SLOTS`).
* **The cells still read empty.**  The merge across four copies leaves most of the
  76 symbol cells at zero, so the magic byte and CRC never pass.
* **Source exhaustion is the only measured correlate.**  `pulses: attempt 1 done:
  450 pulses fired, 46 had no free connection`, and on the retry `253 fired, 243
  had no free connection`: a class bucket holds ~16 sources per channel but the
  class of a random nibble is drawn ~5 times in a copy, and each source may fire
  at a given target only once, so the later copies run dry.

### The single smallest remaining change

**Stop over-committing the pulse pool: drop `PAYLOAD_REPEATS` from 4 to 3** (or
rebuild the class buckets at the start of every attempt instead of carving one set
per attempt).  Three copies put the per-class demand (~3.6) below the supply
(~16), so no symbol is ever skipped for want of a source, and the merge still
votes across three reads per cell.  Evidence: the fired/dropped counts above, and
empty cells appearing exactly where the sender reported no free connection.  That
inference is from the drop counts, not from a passing run.

### The two runs of this revision

| | run 1 | run 2 |
|---|---|---|
| peer found (alice / bob) | +1306 / +1896 ms | +1188 / +2019 ms |
| ports enumerated | 9 at +7623 ms / 10 at +8108 ms | 5 at +7485 ms / 9 at +8248 ms |
| payload | 3 attempts x 116 slots x 200 ms | 3 attempts x 116 slots x 200 ms |
| channels bound at the closest start | 4 (94-154 arrivals each) | 4 (94-120 arrivals each) |
| payload checksum passed | **0** | **0** |
| dtlsState / DataChannel | never reached | never reached |
| final peer state | `linking`, 0 messages | `linking`, 0 messages |

## 10. Fifth pass: the supply is fixed, the read is still short

Revision `ee3f70cf7b17244c5fc44ee251d655b77ac702db4f7259e7cc16358db2e98058`
(hashed before run 1, after run 1 and after run 2 in
[final-runs.txt](final-runs.txt); logs [final-run1.log](final-run1.log),
[final-run2.log](final-run2.log), analysis
[analyse-frozen.js](analyse-frozen.js)).

### What was changed, and why (supply only)

The previous revision's demand was `4 copies x (40 preamble + 19 data)` = 116
pulses per channel, against a supply that is *per port class*: a pulse can only be
sent by a connection whose port class (port % 16) **is** the symbol it carries, so
each class is a bucket of ~15 sources, and the preamble alone reserved ~10 of each
of the channel's own four classes.  Hence the measured 51/464, 172/464, 281/464
drops.  The fix is to size both consumers to the measured buckets:

* `PAYLOAD_REPEATS` 4 -> 2, `SYNC_SHOTS` 8 -> 4, `MARKER_SHOTS` 2 -> 1.

Demand per channel is now `2 x (5 + 19)` = 48 pulses and the preamble reserves
10 sources per channel (~2.5 per class), so per class the demand is
`2 x (how often the class appears)` against ~12 sources still in the bucket.  A
Monte Carlo over the measured class histogram (`tmp/autoconnect/final-run1.prev.log`,
246 sources, per-class supply 9-27) puts a dropped pulse at 9% of runs and a symbol
missing from *both* copies at 0.02-0.12%.  Transmission shortens to 11.6 s.

Diagnostics added so a failure can be scored rather than guessed at: the sender
logs the payload it actually transmitted (`payload truth <38 hex bytes> (crc N)`),
and the receiver's closest-start line now carries per-cell occupancy
(`cells 61/76 filled (14 of 19 14 of 19 18 of 19 15 of 19)`) and all 38 decoded
bytes instead of the first six.

### The measured result: no symbol is dropped any more

| attempt | alice fired / dropped | bob fired / dropped |
|---|---|---|
| run 1, attempt 1 | **192 / 0** | **192 / 0** |
| run 1, attempt 2 | 192 / 0 | 188 / 4 |
| run 1, attempt 3 | 180 / 12 | 161 / 31 |
| run 2, attempt 1 | **192 / 0** | **192 / 0** |
| run 2, attempt 2 | 192 / 0 | 191 / 1 |
| run 2, attempt 3 | 185 / 7 | 175 / 17 |

192 = 4 channels x (10 preamble + 38 data): **every symbol of every copy of
attempt 1 got a source, on both peers, in both runs.**  Attempts 2 and 3 drop
pulses because a source may only ever send one check to a given peer port, so the
second use of the same four targets finds the buckets already spent - retries are
structurally poorer than the first attempt, not better.

### The failure that remains, and it is not supply

Both runs ended with both peers `linking`, 0 payload checksums, no
`RTCPeerConnection` ever reaching `dtlsState: connected`, no DataChannel, 0
messages, 0 page errors.

Per-cell occupancy of the symbol grid (76 cells = 19 slots x 4 channels, merged
over the 2 copies), from the closest-start lines:

| run / peer | cells filled at the best candidate | per channel (of 19) | bound sockets |
|---|---|---|---|
| 1 / alice | 40/76 | 14, 0, 14, 12 | 50045=c3:49 50095=c0:49 50218=c2:51 50431=c1:2 |
| 1 / bob | 49/76 | 16, 14, 3, 16 | 50126=c3:35 50137=c0:36 50279=c1:33 50407=c2:38 |
| 2 / alice | **61/76** | 14, 14, 18, 15 | 50029=c3:35 50040=c0:32 50042=c1:28 50062=c2:35 |
| 2 / bob | 56/76 | 10, 3, 3, 19 | 50038=c3:52 50178=c0:43 50246=c1:51 50289=c2:55 |

Median occupancy across all logged candidates: 30/76 (run 1 alice), 39/76 (run 1
bob), 47/76 (run 2 alice), 28/76 (run 2 bob).  Two shapes of shortfall:

1. **A channel bound to a socket that is not carrying it.**  Run 1 alice bound
   channel 1 to socket 50431, which had **2 arrivals** in the window (0/19 cells);
   run 1 bob bound channel 2 to 50407 and saw 3/19 cells.  That quarter of the
   payload is simply absent.  The preamble's own `bindChannels` vote is what picks
   the socket, and with 4 sync shots x 2 copies a channel fires only 10 preamble
   pulses, so a stray class-1 echo can outvote a real channel socket that was
   polled too rarely to see them.
2. **Cells missing on a correctly bound socket.**  The three well-bound channels
   sit at 12-16 of 19 cells (63-84%), i.e. even a socket that is carrying its
   channel is read less than full: the slots a pulse falls in are read at the
   owner's poll cadence (252 own sockets, `COARSE_SLICE` 3 per pass), and only
   sockets that were woken stay fine-polled.

Bit errors, scored against the payload the *other* peer logged as transmitted:

| | closest any candidate got |
|---|---|
| run 1 | 60/76 nibbles and 117/304 bits wrong, 42/76 cells filled |
| run 2 | 49/76 nibbles and 117/304 bits wrong, 61/76 cells filled |

At the best candidate in each run, the part of that error that is *not* explained
by an empty cell is 60-(76-42)=26 and 49-(76-61)=34 nibbles respectively: cells
that were filled, but with the wrong symbol.  No permutation of the four channels
rescues any candidate (the best of the 24 permutations still leaves 65-71 of 76
nibbles wrong on the run 1 candidates), so this is not a channel-labelling mix-up
that a relabel would fix - the cells genuinely lack the pulses.

### Decision point

The supply question is settled: attempt 1 fires 192/192 on both peers in both
runs, so nothing here is a source shortage.  What is left is the carrier: at this
resolution (200 ms slots, 19 symbol slots per channel per copy, 2 copies, 16 port
classes as the symbol alphabet) the receiver populated at most 61 of 76 symbol
cells, and even that best candidate read 49 of 76 nibbles wrong, of which 34 were
wrong in cells that were filled.  A checksum over 38 bytes has no slack for that.

### Is the carrier viable at this resolution?

Derived from the measured occupancy (not measured directly): a channel read at
"f of 19 cells" over 2 copies implies a per-copy read rate `p = 1 - sqrt(1 - f/19)`.
For the four channels of the best candidates that is p = 0.49, 0.49, 0.60 and 0.77
(all four measured shapes: 14/19, 15/19, 16/19, 18/19), and the expected number of
empty cells out of 76 at k copies is `76 x (1-p)^k`:

| implied p | k=2 | k=3 | k=4 | k=6 |
|---|---|---|---|---|
| 0.49 (14/19) | 20.0 | 10.3 | 5.3 | 1.4 |
| 0.60 (16/19) | 12.0 | 4.8 | 1.9 | 0.3 |
| 0.77 (18/19) | 4.0 | 0.9 | 0.2 | 0.0 |

A 38-byte payload with a one-byte CRC needs all 76 nibbles, so even at the best
channel rate measured (0.77) 2 copies leave ~4 empty cells, and at the typical rate
(0.49) 6 copies are needed for an expected 0.5 empty cells.  The source supply
cannot pay for 6: with the same model as above, 4 copies drop a pulse in 98% of
runs (mean 13.5 pulses), and 6 copies in 100% (mean 49-74).  So no configuration
the supply affords reaches a clean grid at 200 ms slots / 16-class symbols / 76
symbols, and the residual "filled but wrong" cells (26-34 of 76 at the closest
candidate) are not something 2 copies can vote away.

**Verdict: not viable as it stands at this resolution, and the limit is the read,
not the supply** - attempt 1 now fires 192/192 with nothing dropped, so the
remaining loss is on the receiving side (which sockets get polled finely enough to
catch their channel's slots, and which socket the preamble's port-class vote ends
up binding).  Anything further here is a change of carrier or of parse rate, not
another copy/preamble knob.

## 11. Sixth pass: the receive side, instrumented (the loss is not the receiver)

The fifth pass left one question: every symbol of every copy had a free source,
the binding and the grid were right, and the payload still failed.  This pass
instrumented the thing the engine's own logs cannot show - what the receiver's
`getStats()` actually reports and when - with a probe that fires pulses from
known connections at known times and logs every stat poll on the other side.

Harness: [spike9.html](spike9.html) + [run-spike9.py](run-spike9.py), two Chrome
for Testing 148 processes on one machine, both stamping with `Date.now()` (one
clock), so each pulse's send time joins the poll that first saw its prflx pair.
Raw: [spike9-run1.txt](spike9-run1.txt), [spike9-run2.txt](spike9-run2.txt),
[spike9-run2.json](spike9-run2.json) (the first run's JSON was overwritten by the
second before the naming was fixed; its printed analysis is in
`spike9-run1.txt`).  The engine under test was not modified.

### What the receiver can see, and when (run 1: 169 pulses, 13 598 polls)

| pulse pattern | n | ms from `addIceCandidate` to the poll that reported the pair | never seen |
|---|---|---|---|
| one fresh source, isolated (400 ms apart) | 10 | min 6, median 30, p90 52, max 53 | 0 |
| 8 sources in a single tick | 8 | 22-23, all eight in one poll | 0 |
| 32 sources in a single tick | 32 | 44-53, all thirty-two in one poll group | 0 |
| 10 slots x 4 sources at 200 ms (clean) | 40 | min 3, median 36, max 52; 4/4 in every one of the 10 slots | 0 |
| the same source, same target, 4 shots, 400 ms apart | 4 | shot 0: 7 ms | shots 1-3: 0 (no new candidate) |
| a source with 24 queued checks, fired the instant after | 2 | 1540, 1540 | not in its slot |
| the same, fired 2 s later (queue partly drained) | 1 | 74 | 0 |
| 10 slots x 4 sources, each with 24 queued checks 1 s earlier | 40 | min 17, median 60, p90 551, max 551 | 0, but 12 of 40 read into a later cell |

Only 1509 distinct report timestamps came out of those 13 598 `getStats()`
calls; 12 089 consecutive polls returned the identical report, and the gap
between distinct report timestamps is min 50.0 / median 52.8 / p90 55.5 ms
(run 2: 1857 reports from 14 846 polls, median 53.4).  **Chrome's `getStats()`
serves a cached report for ~50-55 ms here**, so no poll loop can date an arrival
more precisely than that, and polling faster only re-reads the same snapshot.
Poll cost itself is not a problem: median 0 ms, p90 1 ms, max 55 ms, with the
listener holding up to 174 candidate pairs.  Nothing was pruned either: zero
candidate or pair disappearances in either run, and the 94/174 pairs were still
present at the end.  That matches the source: an unknown-address Binding Request
creates the `prflx` candidate synchronously in `P2PTransportChannel::OnUnknownAddress`
on the packet path, not on an `UpdateState` timer
([p2p_transport_channel.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/p2p/base/p2p_transport_channel.cc),
[port.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/p2p/base/port.cc)),
and pruning follows nomination/port lifecycle, not a sub-second timer
([connection.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/p2p/base/connection.cc)).

### The two measured causes of lost and wrong cells

**(a) A pulse does not leave from the port the sender labelled it with.**  Every
source connection in the probe has two UDP ports (184 of 184, 368 ports total),
and the check leaves from either one: 94 arrivals in run 1 split 55 first-port /
39 second-port, 174 in run 2 split 89 / 85 - a coin flip, stable per
(source, target) but not knowable in advance.  The engine labels each source
with `rows[0].port` (`registerSocket`), picks preamble sources by
`__acPort % CHANNELS` and data sources by `__acPort % SYMBOL_CLASSES`.  Half of
the pulses therefore carry a class nobody chose: the symbol nibble read at the
receiver is arbitrary, and the preamble votes that bind a socket to a channel
are corrupted, which matches the run pattern in §10: bound sockets with 3-4 hits
sitting next to sockets with 22-55, and one or two channels per attempt at 0-3
of 19 cells while the others read 10-18 of 19.  This is not a timing error and no
copy count can vote it away.

**(b) A source that still has queued checks sends its pulse late.**  Identical
pattern, identical target, identical 200 ms cadence, the only difference being
what the source was already carrying.  Clean sources fired all 40 pulses 3-52 ms
late (run 1) and 12-52 ms (run 2), and every one landed in its own slot.  Sources that had 24 checks queued at
closed ports one second earlier also delivered all 40, but 17-551 ms late
(median 60, p90 551): **12 of the 40 crossed the 100 ms rounding line into the
next cell**, the worst three cells away (run 2, `dslot` vs `cslot`).  The size of
the delay is how much of the queue is still in front: fired the instant after the
same 24 checks it was 1540 ms (two trials in run 1), and with 2 s of drain it was
74 ms.  An earlier read of run 2 attributed those arrivals to the load tick 1 s
before the slot pulse and so reported 1.5-2.7 s; the per-pulse delays above
supersede it.

That is libwebrtc's ICE pacing the new pair behind the queue.  The engine builds
its payload pools from `sourceList()` = scanners + enum + locate + pulse pool, and
the sweep runs tens of thousands of checks through those same scanners, so the
24-check case measured here is a floor rather than the engine's real debt - the
engine's actual tail was not measured.  `fire()` returning and `fired++` therefore
say nothing about when a pulse is actually on the wire.  The `fired 192/192`
of §10 is real (no bucket ran dry) but it is a count of accepted additions, not
of transmissions at a time.

Two things that were suspected and are not happening: the receiver is not too
slow (the 8-pulse burst and the 32-pulse flood were each reported whole in a
single poll, and a clean 200 ms grid reads 4/4 in every slot), and nothing is
pruned out from under the poll.  Also worth recording: a repeat pulse from the
same source to the same target produces no new candidate at all, so pulses can
never be counted by counting candidates; the pair's `requestsReceived` does climb
(0 -> 30 over 40 s in run 1) but it is fed by the peer ICE agent's own pings a
second or two apart and did not even count the first request that created the
pair.  `candidate-pair` carries no arrival timestamp in this build
(`lastPacketSentTimestamp` is our own send, and `lastPacketReceivedTimestamp`
is absent), so the receiver's only clock is the ~50-55 ms report cadence.

### Encodings this leaves open

* **Port-class symbols are fine and should stay** - the port the receiver reads
  is exact (`remotePort` matched the sender's real socket in 94/94 and 174/174
  arrivals).  What has to change is that the *sender* must know, and verify,
  which of its two sockets a given (source, target) pair uses before it assigns
  that source a class.  The mapping is readable locally: after a check, the
  source's own `candidate-pair` names `localCandidateId`, whose
  `local-candidate.port` is the socket that sent it.
* **Payload sources must have an empty send queue.**  The pulse pool exists
  (`buildPulsePool`, 160 fresh connections) but `assignPools` mixes it with the
  sweep scanners; a payload should use only connections that have never fired,
  and each pulse should be confirmed with `lastPacketSentTimestamp` rather than
  `fired++`.
* **The receiver should use the report timestamp as its clock.**  With a ~53 ms
  cache, an arrival is only known to lie in an interval; assigning it by
  `Math.round((at - t0) / SLOT_MS)` writes a wrong nibble into a neighbouring
  cell whenever the interval straddles a boundary.  Leaving the cell empty and
  letting the copies vote is strictly better than guessing.
* **A timing-free index channel exists but is not cheap**: the receiver's *local*
  port that received a pulse is exact (it is the socket it was aimed at), so cell
  identity could ride on which of the peer's ports is targeted - it needs as many
  known peer sockets as cells, and the peer's own two-socket ambiguity moves to
  the sender's target choice.

## 12. Seventh pass: implementation from the diagnosis, and how far it got

This pass acted on §11 instead of measuring further: the sender now labels every
pulse source with the socket that actually reaches the peer, payloads are fired
only from connections with an empty queue, aim targets are checked for liveness,
and the receiver's poll loop was freed of everything that was delaying it.
Engine: [autoconnect.js](../../target_teavm_wasm_gc/src/web/autoconnect.js)
(untracked, 2032 lines, still not loaded by `index.html`).

### What was built, and what each piece was verified to do

| change | evidence |
|---|---|
| **Census**: one check per payload source at `P_START - 7`, read back from the source's own `candidate-pair.localCandidateId` | One socket serves every target on the peer's address, and the readback matches what the receiver sees: 40 of 40, 40 of 40, 40 of 40 (spike10, [spike10-run1.txt](spike10-run1.txt)) |
| **Verified classes**: preamble and symbol buckets keyed on `__acVerified`, not `rows[0].port` | `census: 160 of 160 payload sources labelled` on attempt 1 in every run; the class buckets then hold 2-17 sources per class instead of a coin flip per pulse |
| **Cached labels** | `160 of 160 ... (160 reused from an earlier attempt)` on attempts 2 and 3; without the cache a re-probe labels 0 of 160, because the census pair (a port nothing answers on) has already been pruned by then |
| **Queue-free payload sources**: payloads and wake bursts fire from `pulsePool`/beacons only, never from the sweep connections | the wake burst now lands and the peer's target sockets reach the fine schedule; the earlier build reported `none answered the wake burst` and the payload that followed was read on the coarse schedule |
| **Live aim targets**: candidates ordered by how recently the peer was seen on them, 8 candidates, and liveness read from `peerSockets[p] > sentAt` instead of an 80 ms echo test the coarse poll can never meet | `aim: channels: 4 ports, groups ... (1 answered the wake burst)` (run 5) and `(7 answered)` on the next attempt, against `(none answered)` in every earlier run; the run that had aimed at four dead enumeration ports and saw zero arrivals (run 4) recovered to hundreds of arrivals |
| **Poll-loop hygiene**: reception stands down while this page is transmitting; the decoder is budgeted to one read per pass above 120 arrivals; the coarse slice is skipped only while transmitting | watch passes: `1-9 ms` with 8-12 sockets fine, against `113-333 ms` when a decode or the census ran inside the same turn; the sender's 200 ms slot loop no longer shares its thread with the receiver's polls |
| **CRC-16 in place of CRC-8** | not hypothetical: run 7 produced a decoded payload with peer id `0x0070`, final port `3072` and fingerprint `00:B0:A0:00:00:B0...` - a garbled read that passed magic + CRC-8 and sent a handshake at a port that never existed |
| **Alignment window scaled to the transmission** (60 slots now, 12 s + 3 s) | a window shorter than one transmission can only see the last copy's preamble, and anchoring there puts the earlier copy's pulses before the grid and drops them; that is what the `grid slots -2..28` reads were |

### Where it still stands

**No run has produced a valid payload decode, so no run has connected.** The
symbol grid reads back at 20-50 of 80 cells (the best read of any configuration
was 51 of 76 at 200 ms slots before the CRC-16 change), and a 37-byte payload
with a two-byte checksum needs every cell. The failures are one family: the
receiver's slot attribution. Arrivals do reach the right sockets - the best
bindings show four sockets with 46-61 pulses each, and 190 of 196 pulses fired -
but the pulses are not attributed to the slots they were sent in. The clearest
single observation of it is a read whose densest slot held 43 arrivals while the
grid spanned only `-2..44` of 58: that is a poll loop that stalled and then
reported a whole window at once, which is a *reading* failure, not a transmission
failure.

The next measurement is therefore inside the engine rather than in a probe: log,
for every arrival, the interval between the two polls that bracket it
(`lastPolledAt[socket]` -> `now`), the cell it was attributed to, and the truth
for that cell, so the attribution error is visible per pulse instead of inferred
from a census of filled cells. Everything else this pass changed is measured and
kept; the engine still runs with the 3-attempt structure, the four-channel
payload and the same preamble design.

## 13. The decoder was never on the air, and what it said once it was

Runs 10-12 fixed three defects that had nothing to do with the channel and
everything to do with the instrument:

- **The decode interval was never registered.** The decode step had been lifted
  out of the poll chain into `decodeTick`, but it was registered inside
  `startWatchAndAim()` - a function nothing calls (`start()` calls `watchLoop()`
  and `maybeAim()` directly). Run 10 therefore reports `last decode 0 ms` in
  every pass: the decoder never ran once. `startDecodeLoop()` is now called from
  `start()` and cleared in `stop()`.
- **The receiver stood down for the whole of its own transmission.** Both pages
  run their attempts at the same time - run 10: alice 15.2-27.0 s, bob
  16.5-28.3 s - so the comment's assumption ("the peer holds its own attempt
  back while we pulse") is false, and standing down meant each side was blind
  for exactly the window in which the other's payload arrived. The stand-down is
  gone; only the coarse sweep is skipped while transmitting.
- **The start candidate gate was unattainable.** `after >= 16` asked for 16
  arrivals in the four seconds behind a candidate start - which is what a
  *perfect* read of four channels over four data slots produces, so a stream
  read at 60% yielded no candidate at all and the run reported only "no start".
  The gate is 6 now, and an empty candidate list reports the busiest 4 s window
  and the most arrivals seen behind any start.

With those in place (log `census-run12.log`, revision in `census-run12.rev`) the
decoder runs at 0-1 ms per read and the picture is finally legible:

**The receiver's read is good.** Alice at +46.3 s: `113 pulses in the last 4 s on
4 of our sockets (top: 50440x41 50163x31 50494x28 50508x13)`. The pulses arrive,
on the sockets the payload is aimed at, at a rate well above the four-per-four-
seconds a channel consumes.

**The channel binding is the blocker, and it is a coin flip.** The same four
sockets are bound to different channels from one tick to the next:

```
closest start: 4 channels (50163=c1 50440=c0 50494=c3 50508=c2)
closest start: 4 channels (50163=c0 50440=c2 50494=c1 50508=c3)
closest start: 4 channels (50163=c2 50440=c1 50494=c0 50508=c3)
```

and in the reads where two of the four bound sockets are noise sockets the grid
says so directly: `cells 30/80 filled (0 of 20 15 of 20 15 of 20 0 of 20)` -
channels 0 and 3 contribute nothing, and each channel takes its 20 cells with
it. The binder identifies a channel from the port class (`port % 4`) of the
pulses it heard in the preamble slots, and the preamble is exactly the part of
the transmission with the least signal: 8 of every 30 slots carry nothing at
all, and the wake, census, enumeration and announce traffic lands on the same
sockets with the same port classes.

The design comment describes a staircase - "a slot per channel two slots apart"
- but the implementation skips slots 1..8 (`else if (local < PREAMBLE_SLOTS)
continue`), so the slot-position information the staircase would have carried
was lost. Together with the port-class scheme that replaced it, the binding has
no usable evidence and lands on a coin flip per tick, twice a run.

**Status: not connected, and the reason is now specific.** Nine runs before this
pass and three in it, all exit 0, zero PAGEERRORs, discovery + wake + targeting +
transmission all measured working, and no run decoded a payload: two of the four
channels are bound to the wrong sockets, so 40 of the 80 cells are read as zero
and a 39-byte payload with a CRC-16 never checksums out. The engine is still
untracked and still not loaded by `index.html`; nothing is committed.

## 14. The staircase: binding is fixed, but one channel is missing

The binding defect of section 13 is fixed as follows (revisions in
`census-run13.rev` and `census-run14.rev`):

- **The staircase is transmitted.** Slots 1..8 used to be skipped outright; now
  channel *c* fires in slot `2 + 2c` (`STAIR_FIRST`/`STAIR_SHOTS`), from its own
  preamble sources. Nothing else fires in those slots.
- **Channels are bound by which staircase slot a socket heard**, not by
  `port % 4`. Only even offsets count, so a pulse read a slot away names no
  channel, and a socket that heard no staircase pulse names none at all instead
  of voting at random.
- The port-class filter on preamble sources is gone (it made a quarter of the
  pool eligible per channel and reserved ten sources where twelve are needed),
  and the sync burst went from four shots to three to pay for a second staircase
  shot per channel per copy.

**Measured effect (run 13, before the second shot):** the binding stopped
flipping - `50064=c0 50162=c3 50254=c2` repeated unchanged over eight
consecutive ticks, against run 12 where the same four sockets bound differently
on consecutive ticks. What it no longer did was reach four channels: 3 of 4, for
every tick of the run, so `complete` was never true and no alignment was ever
tried.

**Measured effect (run 14, second staircase shot):** four channels do bind now -
`closest start: 4 channels (50017=c2:8 50054=c1:4 50138=c0:4 50477=c3:4)` - but
only in the windows before a payload, where the arrivals are the wake burst
(`grid slots -2..12 densest 6:5 0:3 2:3 4:2`, `cells 2/80 filled`: the
pre-amble, and nothing behind it). Across the payload windows of run 14 the
binding count is 76 ticks at 3 of 4 channels and 16 at 2 of 4, best cell read
**26 of 80**, no decode, zero PAGEERRORs.

**The remaining blocker is now one channel wide.** Three of the four channels
bind and carry their cells; the fourth never does, and each channel takes its
20 cells with it, so the best read (26) cannot reach the 76 a CRC-16 needs. The
most likely cause is structural rather than a read error: the sender picks its
four target ports from the peer's enumeration (`aimTargets()`), and one of those
ports can belong to a connection the *receiving* page has already closed - the
surplus lottery connections are closed as soon as four listeners are found, and
their ports stay in the peer's enumeration cache. A check aimed at a closed port
arrives nowhere, so that channel has no socket at all on the far side, and no
amount of extra shots on the staircase can bind it.

Fixing that means the sender must aim only at ports the peer can still be
reached on. The receiving side can measure exactly that set (the sockets that
received the wake burst), so the next change is for the wake burst to *answer*
with the list of ports that heard it, and for the payload to be aimed at those
rather than at the enumeration's cached list.

**Status: still not connected.** Fifteen harness runs (13 in `census-run*.log`,
plus the two frozen runs), all exit 0, zero PAGEERRORs. Discovery, wake,
targeting, transmission, channel binding and the decode instrumentation are all
measured working; no run has decoded a payload, because one of the four channels
is aimed at a port the peer no longer owns. The engine remains untracked and is
not loaded by `index.html`; nothing is committed.

## 15. Eighth pass: the exact carrier, and the machine the read runs on

This pass replaced the slot carrier with one built only on what the earlier
measurements proved exact, and then measured it with the same two-browser
harness as before.  Engine: [autoconnect.js](../../target_teavm_wasm_gc/src/web/autoconnect.js)
(untracked, `VERSION = 7`, 1650 lines).  Harness: [peer.html](peer.html) +
[run-peers.py](run-peers.py), two Chromium processes.  Runs: `grid-run1.log` ..
`grid-run7.log`.  Probes: [spike-pool.html](spike-pool.html),
[spike-pool2.html](spike-pool2.html) (spikes 11 and 12).

### The carrier, stated as the plan's phases with measured mechanisms

A cell is (class of the sender's sending socket, one of the receiver's sockets,
a round).  Both coordinates are *events*, not timings:

* the sending socket is exact because a connection's own
  `candidate-pair.localCandidateId` names the socket it will use for the peer's
  address after one check there (spike10: one socket served every target on the
  peer's address, 40/40, 40/40, 40/40); the census this engine runs before every
  payload labelled **175 of 175**, 162 of 173 and 146 of 175 sources in the
  loaded runs, and 6 of 6 readers in every run;
* the receiving socket is exact because it is the socket the check was aimed at,
  and a page can only be aimed at on a socket it owns - which is why every
  connection here that is not a reader is forced out of the discovery window
  (a connection whose ports land inside is closed; the port lottery pays for
  that by retrying, 24 connections per batch);
* the round is only "which burst", never a sub-slot clock: rounds are spaced
  4 s in the last two runs, 76x the ~53 ms stat cache.

Both peers transmit and read at the same time; outbound pulses never come back
to the sender's own readers.  Each copy starts with a list round (three pulses
per target socket) so the receiver can read *which* sockets this copy will use
instead of assuming its own list, and a payload is sent in two copies.

### What is measured working (two real browsers, one machine)

| step | evidence |
|---|---|
| Discovery, 512-port window x 254 hosts | peer found in **1.9-2.4 s** in every run; each side stops sweeping the moment the other's probe arrives (`grid-run3.log` +1.6 s, `grid-run5.log` +2.09 s) |
| Port lottery into the window | **6 readers with 6 in-window sockets** in 3-5 batches of 24 (~0.5 s); `grid-run1` had 1 of 120 before the wait for `local-candidate` rows was added - a just-made connection reports an empty port list for a moment, and the first version threw those connections away |
| Locate (the responder's address) | the caller's address from one of its source ports in **0.4-3 s** (every run after the readback was made to poll until a deadline instead of once) |
| Peer sockets, live by construction | the probe reads back the sockets the peer *answers from*: 4 and 3 (`grid-run5`), **6 and 5** (`grid-run7`) - never a cached list, which is the §14 dead port |
| Transmission | both peers fire a 320-bit payload as rounds x copies: `grid-run7` "2 rounds x 3 copies over 5 peer socket(s) x 32 classes", 147 and 127 pulses in copy 2 |
| No harness faults | exit 0, zero PAGEERRORs, in all seven runs |

### What fails, and why it is the machine before the carrier

No run checksummed a payload, and the reason is the read side, measured:

* a reader's `getStats()` with **2-33 remote candidates** took 250-1000 ms in
  early runs and **4-8+ s** in the last two while the page held ~230 live
  connections; `grid-run7` logs **81 poll timeouts** (the timeout is now 8 s, so
  a drop means a report that late);
* the best copy read the decoder reported was **2 of 384 cells** - and that
  diagnostic line was rate-limited by burst number, so it is a badly recorded
  maximum, not a proven ceiling; the read never progressed beyond it;
* probes 11 and 12 rule out the obvious causes: 176 *idle* connections report in
  **0-10 ms**, 176 connections holding 528 pending checks report in **0-2 ms**,
  24 connections holding 2040 checks in **0-8 ms**.  Pool size and pending checks
  alone cost nothing.  What is different in the engine is that its readers are
  live participants (they answer the peer's checks and hold succeeded pairs) and
  the page is running ICE for 230 connections per peer - on a machine measured at
  **load 9.9-14.5 on 12 CPUs** with `webfilterproxyd` at 163% CPU and Adobe
  processes at ~260%, i.e. the browser is not the only thing on the machine.
  The prior build's 1-9 ms passes (§12) were measured on a quiet one.

So the answer to "what can the receiver actually and reliably observe per slot"
is: the **socket identity of every pulse, exactly, in both directions** - that is
the signal, and it needs no clock at all.  What is *not* available: pulse counts
(`requestsReceived` does not count the first request and is fed by the peer's own
pings, §11), priorities (Chrome recomputes them, spike-prio), and any sub-slot
timing (the ~53 ms cache plus this machine's poll gaps).  Round position is a
real coordinate only if a poll completes inside a round, and under this load it
did not.

### The smallest change that would finish it, with the arithmetic

Close the pool before the exchange.  During phase 2 only the readers, the
sources that fire the payload, and the final connection are needed: 96 sources
give 96 x 6 = 576 fresh (source, socket) pairs against the 2 copies x ~160 set
cells = 320 the payload needs, so the other ~80 sources and all 40 scanners can
be closed once the probe has read back.  That leaves ~103 live connections
instead of ~230 - halving the page's ICE work - and is the one variable probe 11
did not vary (it varied *idle* pools, not pools whose connections are all being
pinged).  Keep rounds at 2 s or more, and one copy per attempt if the read is
still slow.

### Reproduction

```sh
cd project
/tmp/webtest-venv/bin/python -m http.server 8124 --bind 127.0.0.1 &
/tmp/webtest-venv/bin/python tmp/autoconnect/run-peers.py 8124 400000 | tee tmp/autoconnect/grid-runN.log
/tmp/webtest-venv/bin/python tmp/autoconnect/run-spike.py 8124 "tmp/autoconnect/spike-pool2.html?n=176&f=3" "" 55000
```

Still not loaded by `index.html`'s build output? It is: the tag is in
`target_teavm_wasm_gc/src/web/index.html` and the file is copied by
`wasm-toolchain/deploy_wasm_web.sh` and inlined by
`wasm-toolchain/build-single-html.js`, both unchanged this pass.  Nothing is
committed, no linker run, no push.

### 15a. The read is dense, not blind (run 8, `grid-run8.log`)

`grid-run7`'s "2 of 384 cells" was a badly recorded maximum (the line was
rate-limited by burst number).  Run 8 fixed the diagnostic to report the read
whenever it *improves*, and the picture is different and better:

```
read improved to  72 of 320 cells (burst 2, 5 column(s))
read improved to 146 of 320 cells (burst 3, 5 column(s))
read improved to 146 of 384 cells (burst 2, 6 column(s))
read improved to 192 of 384 cells (burst 3, 6 column(s))
```

So a copy read fills up to **half the grid** (192 of 384 cells, 6 columns,
3 rounds) - and a 320-bit payload has only ~160 set bits, so roughly **30 of
those 192 cells are noise**: pulses attributed to the wrong round, which is what
happens when the poll gap (4-8 s under this load) is not strictly smaller than
the round spacing (4 s).  That is the same failure family as §12/§13 - slot
attribution - now at multi-second rounds rather than 100 ms slots, and it is the
only reason no payload checksums.

The fix is therefore arithmetic, not structural: the poll gap must stay below
the round spacing.  Either close the pool so a reader's report returns in tens of
milliseconds again (96 sources x 6 sockets = 576 fresh pairs against 2 copies x
~160 cells = 320, so ~80 sources and all scanners can be closed after the probe
readback), or space rounds at 8 s (3 rounds x 8 s = 24 s per copy, 2 copies ~50 s
inside the 100 s exchange budget).  Doing both leaves a wide margin.

`grid-run8.log`: exit 124 (the harness was cut off by a 220 s shell timeout, not
by a page error), **zero PAGEERRORs**, both peers transmitting.

## 16. The pass that fixed the carrier's two real defects (runs 11-12)

The read was never the problem.  Two defects, both measured, both fixed:

**16a. The sender was firing four pulses per round, not a hundred and twenty.**
`takeSource()` keys its per-round counter by `s.__acId`, but the id was only ever
set on the *connection* (`pc.__acId`) - the source object never had one - so every
source in the pool shared the single key `undefined` and the fourth pulse of a
round exhausted the budget for all of them.  Reproduced offline in
[sim-schedule.mjs](sim-schedule.mjs) with `--sharedid`, which needs no browser:

    BUG: shared id (roundUsed[undefined]): [r0 attempted=117 fired=4 missed=113]

against the live `grid-run10.log` line `copy 1 round 1: 4 pulses, 112 unfilled`.
After the fix the same schedule fires `116` and `38` with `0` missed, and
`grid-run11.log` reports `grid: copy 1/2: 156 pulses` - full payload coverage
(~154 set bits) instead of 8 pulses per copy.

**16b. The receiver polled stats every 20 ms, forever.**
`watchLoop` re-polled each reader at `POLL_CYCLE_MS = 20`, so six readers issued
~300 `getStats()` passes a second.  That is what starved the very call the read
depends on: `grid-run10.log` logs a reader poll that returned after **43817 ms**
and three more at the 3012 ms timeout.  At 250 ms per cycle (~24 passes a
second) the same call returns in **467 ms** and run 11 logs **zero** timeouts
(run 7 had 81, run 10 had 3).

**What the fixed carrier measures (run 11, load 8.4-9.6 on 12 CPUs).**
Discovery 2-3 s; census 110/110 sources labelled; both peers transmitting two
copies of the 320-bit payload, 156 and 137 pulses per copy, five rounds per copy,
`2-14 unfilled` per round; reads dense and *distinct*: `read improved to 120 of
384 cells (burst 1, 6 column(s), 132 arrival(s) seen)` - 132 arrivals landing on
120 different cells, so attribution is now clean rather than padded with noise.

**Why one copy still cannot checksum, in arithmetic.**  The payload needs all
~154 set cells; a copy delivers ~132 arrivals, so ~20 cells are always missing
and the CRC can never pass on a single copy.  Hence run 12: three copies plus a
cross-copy union in the decoder, keyed by column list *and* burst phase, so a
cell dropped in copy 1 is filled by the same phase of copy 2 and a list round can
never be read as payload.  Remaining loss candidates if this still fails: pairs
pruned between 250 ms polls (next lever: 100 ms polls during the readback window
only) and the list-round column hypothesis (already retried with every
one-element removal of the receiver's own socket list).

## 17. The ceiling, measured twice, and the smallest change that finishes it

Run 12 added a third copy and a phase-keyed cross-copy union.  It did not
checksum, and it says why the *shape* of the fix was wrong rather than the idea:

    copy 1/3: 162 pulses   copy 2/3: 135 pulses   copy 3/3: 85 pulses
    grid: reader 50031 stat poll timed out (2386 ms)   ... 22 such timeouts
    (zero "read improved" lines: no burst set was ever complete enough to decode)

Three copies cannot work, for arithmetic reasons visible in one copy's own
numbers.  The pool has 96 sources x 6 peer sockets = 576 (source, socket) pairs,
and a pair only creates a candidate once, so copy 3 had only 85 pairs left -
each copy spends ~150.  Meanwhile the payload needs all ~154 of its set cells,
and a copy delivers ~132 arrivals: run 11's `read improved to 120 of 384 cells
(burst 1, 6 column(s), 132 arrival(s) seen)` is ~85% coverage against a CRC that
demands 100%.  (Run 8's `192 of 384` was not coverage - a copy only *has* ~154
set cells - it was misattribution, which the 250 ms poll cadence and the exact
column mapping have since removed.)

So the carrier's ceiling is **~130 readable cells per copy**, and the payload is
**40 bytes = 320 bits (~154 set)** because it carries the peer's 32-byte DTLS
fingerprint.  The smallest change that finishes it, therefore, is to stop putting
the fingerprint on the carrier at all:

  * payload becomes magic(2) + id(2) + rendezvous port(2) + crc16(2) = 48 bits,
    ~24 set cells - a 5x margin against the ~130 slots a copy can light, so one
    copy decodes with room to spare (and the 2-copy vote stays as insurance);
  * the fingerprint is not needed in the payload because the rendezvous
    connection is one of the *reader* connections, whose remote description was
    applied at offer/answer time and whose data channel is already negotiated;
    `finishFinal` can read the peer's fingerprint from that connection instead.

That keeps the Java bridge's contract exactly as it is (it still receives a port
and a fingerprint - the fingerprint just stops travelling over the carrier).

**Verified this pass (runs 11, 12), independent of the ceiling:**
discovery 2-3 s; census 110/110 sources labelled; `copy 1/2: 156 pulses` = full
payload coverage on the wire (was 8 before the `__acId` fix); reader stat polls
467 ms with **zero** timeouts (was 43817 ms with 81 timeouts); arrival
attribution exact - 132 arrivals landing on 120 distinct cells across 6 columns;
zero PAGEERRORs in every run.  Machine load during the runs: 8.4-11.9 on 12 CPUs
(`load-run11-before.txt`, `load-run12-before.txt`).

## 18. Tier two, measured, and what the single-file client still needs

Tier two (both peers enter the same six-character code, credentials derived from
it - `run-peers-code.py` + `?code=` on both URLs) was run twice.

The first run produced *nothing at all* and the reason was the harness, not the
product: the runner was passing `AC24LAN` - seven characters - and
`sanitizeCode()` requires exactly six, so `enterCode()` returned false before it
logged anything and both pages sat `idle`.  `probe-code-path.py` showed it
directly (`enteredCode: "AC24LAN"`, `state: "idle"`, no logs).

The second run with a valid code (`AC24LA`, log `grid-runcode2.log`) went the
whole way: both pages logged `code mode: both peers derive the same credentials
from the shared code`, both built their 110 sources, `copy 1: list round fired at
7 socket(s)`, three copies transmitted per peer (`140`, `128`, `93` pulses - full
payload coverage every copy), `grid: transmission done` on both.  It did not
connect: one peer ended `failed` with `error: no payload decoded after 1
attempts` (17 reader poll timeouts, one reader stuck at its 2001 ms timeout), the
other ended `linking` with no decode either.

So the code path removes the *alignment* question (the columns came out as 7
sockets and the list round was heard) exactly as planned, and it runs into the
same ceiling §17 measures: the payload needs ~154 set cells and a copy delivers
~130 of them on a machine at load ~10, with the CRC demanding all of them.  The
payload-size change in §17 is still the smallest thing that finishes it; tier two
does not route around it.

### The single-file client is stale and this is the exact command set to refresh it

Measured, not assumed:

    dist/direct-connect.html            89755321 bytes   Oct 13 07:25
    target_teavm_wasm_gc/build/generated/teavm/wasm-gc/classes.wasm   Oct 12 10:44
    grep -c enterCode dist/direct-connect.html   ->  0

The link output predates every Java file this pass touched (`AutoConnect.java`,
`AutoConnect` platform twin, `LANInvites.java`, `EaglerLANPeersScreen.java`,
`ClientMain.java`, `EaglerAutoConnectCodeScreen.java`), so `--reuse-client` is
**not** valid yet and the HTML does not contain the code path at all.  The
sequence, with the pinned toolchain from `../patcher-kit/toolchain.properties`:

    export PATH="/Users/rohan/eagle/patcher-kit/.toolchain/temurin25-25.0.4.1+1-e6229d9504f7/Contents/Home/bin:/Users/rohan/eagle/patcher-kit/.toolchain/node-v24.21.0-darwin-x64-1462cb3b3046/bin:$PATH"
    node wasm-toolchain/link-web-target-standalone.js client      # ~1 h, needs >= 10 GiB
    node wasm-toolchain/build-single-html.js --reuse-client --reuse-mesh --reuse-server \
      --fast-package --output dist/direct-connect.html
    node tools/lan-server.mjs --dir dist                          # serve; /eagler-lan/v1/ endpoints

Checks that pass on the current tree: `node --check
target_teavm_wasm_gc/src/web/autoconnect.js`; `./gradlew :game:compileJava
:platform-teavm:compileJava` with the pinned JDK 25 -> exit 0.

## 19. The fingerprint shortcut was checked and does not hold

§17 proposed a 48-bit payload with the fingerprint read from the rendezvous
reader connection.  That is wrong, and the code says so in three places:

  * every pooled connection is built by `makeSharedPc()`: local description
    munged to the shared constants, remote description applied as
    `forgeAnswer(pc.localDescription.sdp)` with `opts.fingerprint ||
    dummyFingerprint()` and no candidates - a *placeholder* certificate hash, not
    the peer's;
  * the file header states the reason (point 6): "A DTLS fingerprint mismatch
    ends the transport without exposing the peer's certificate, so the 256-bit
    fingerprint has to travel as data";
  * `finishFinal()` is the only consumer: it forges an answer for the final
    connection using `peerFingerprint` - the value decoded from the payload - so
    the payload is the only place the real fingerprint ever exists locally.

So the payload stays 40 bytes, and the three copies have to fit around it.

## 20. The copy arithmetic, measured offline (sim-copies.mjs)

The pass's fixes were sized offline before the browser ran.  `sim-copies.mjs`
mirrors `takeSource`/`fire` (a (source, column) pair is spent forever), the
per-copy bucket rotation added here, the list round and the round structure, and
reproduces run 12's collapse:

    OLD  K=6, 96 sources as run 12 built them, no rotation:
      copy 1: fired=170 missed=1
      copy 2: fired=129 missed=42
      copy 3: fired=74  missed=97      <- grid-run12.log: 162 / 135 / 85
      set cells by attempts: 1 attempt=41, 2 attempts=55, 3+=74
      union at 85% capture: ~8.6 set cells still missing -> CRC cannot pass

    NEW  K=10, 128 sources (8 per class), rotation:
      copy 1: fired=166 missed=0
      copy 2: fired=166 missed=0
      copy 3: fired=158 missed=8
      set cells by attempts: 2 attempts=8, 3+=158
      union at 95% capture: ~0.04 set cells still missing -> decodes

The two causes the user named are both in that table: the pool was too narrow per
class (a class with two sources cannot carry three copies of its own cells) and
K = 6 forced four rounds, so one (class, column) group held up to four cells and
needed twelve fresh pairs against the six a class owned.

## 21. Runs 13-14: what the fixes moved, and what they ran into

Run 13 (grid-run13.log) carried the pass's three changes: ten readers, 150 built
sources trimmed to 128 in 8-per-class balance, per-reader polling loops with
per-reader backoff, and per-copy bucket rotation.

  * The copy arithmetic is fixed on the wire: `transmitting 320 bits as 2 round(s)
    x 3 copies over 10 peer socket(s) x 16 classes`, copies firing 142 / 134 /
    121+ pulses - against run 12's 162 / 135 / 85 with its starved third copy.
  * The stuck reader is no longer page-wide: 5 timeouts in the whole run against
    22 in run 12, and each reader recovered on its own (`reader 50031 answered
    again after 3 timeout(s) (2268 ms, 38 remote candidate(s))`).
  * But with both peers sending three copies at once, *every* stats pass on both
    pages cost ~3 s (ten readers each logging "answered again after 6 timeout(s)"
    with 13-36 remote candidates), and no read line was ever printed.  Run 11,
    where reads worked, had 467 ms passes with one transmitter in effect and half
    the connections.
  * Both peers ended `no payload decoded after 1 attempts`.

Run 14 (grid-run14.log) added one-transmitter-at-a-time pacing: the sides elect a
first sender by comparing the lowest socket each owns with the lowest it probed
on the peer, and the other holds off 45 s.  It did not reach transmission at all:
the 150-candidate source pool built only 128 before its round cap, and the census
of those 128 sources never completed under the ~4 s stats latency (no `source
classes` / `ready:` line), so `maybeExchange` never fired.  One reader per page
sat in a permanent 4 s timeout loop.  The stagger is implemented but unproven.

What remains, in order: the source census and the payload read are the only
places this engine touches stats at scale, and both must run when the page is
otherwise quiet - the census must finish before the exchange starts (it is on the
critical path at 150 candidates), and only one side may transmit at a time.  The
next measurement is exactly that: a run where prepare() finishes and a single
transmitter's three full copies are read at ~0.5 s per pass.

## 22. The load sweep: neither idle connections nor firing explain the slow passes

Runs 13-14 implied that getStats() cost tracks page load, so the deciding window
was built to be quiet (probes closed before the census, one transmitter at a
time).  Before trusting that, the hypothesis was tested directly with
[spike-load.html](spike-load.html) / [run-spike-load.py](run-spike-load.py):
forged pooled connections created in batches, eight sequential getStats() passes
on one of them after each batch, then 120 addIceCandidate calls in flight while
measuring again.  Machine load during the sweep: 8.0-13.6 on 12 CPUs.

    stage                              connections   min  median  max
    readers only (10)                           10     0       0     0
    +32 sources (42)                            42     0       0     0
    +32 sources (74)                            74     0       0     0
    +64 sources (138)                          138     0       0     0
    +32 sources (170)                          170     0       0     2
    170 connections, 120 checks in flight      170     0       0    19

So on this machine an idle pool of 170 forged connections costs nothing
measurable, and firing does not change that.  The engine's 3-4 s passes in runs
13-14 are therefore not explained by connection count or by firing volume; they
appear when the engine's own loops run - ten concurrent reader loops plus the
census batches - and against a session whose pages also carry the peer's traffic
and the harness's console stream.  That is a measurement about this harness, not
about the carrier, and it is the reason the deciding run keeps the window empty
(releaseProbes() closes scanners and locate connections before the census, and
only one side transmits at a time).

## 23. Run 15: the quiet window works, the read still does not

Everything the directive asked for was implemented and reached, and the payload
still did not decode.  Measured in grid-run15.log:

  * `closed 32 probe connection(s) before the census: the read only needs readers
    and sources` - releaseProbes(), which is what run 14 could not do;
  * the census then completed on a quiet page: `source classes:
    14/4/5/9/11/9/11/10/9/6/8/5/14/17/9/9 (150 labelled)`, `ready: 10 own
    socket(s), 10 peer socket(s), 128 source(s) live`;
  * the single-transmitter election worked: `holding back 45s so the peer's
    transmission has the page to itself (own 50101 vs peer 50005)`;
  * all three copies fired nearly complete: `copy 1/3: 158 pulses`,
    `copy 2/3: 158 pulses`, `copy 3/3: 151 pulses` against ~154 set bits.

Result: nine poll timeouts in the run, one pass of **33777 ms**, zero read lines,
and `no payload decoded after 1 attempts`.  The sweep in section 22 says the
cause is not the size of the pool and not firing, so what remains is the only
difference between the sweep and the engine: the sweep issued its passes
**sequentially**, one in flight, while the engine runs up to ten reader passes
concurrently (plus the census's own batches).  That is the smallest change left
to try, and it is a scheduling change, not a new carrier: cap stats concurrency
page-wide (one or two passes in flight, round-robin) and poll each reader once
immediately after each burst.

State of the tree for the next pass: no commit, no push, no relink.  The
deliverable files are the ones listed in section 18, and dist/direct-connect.html
plus the linked classes.wasm are still older than the Java changes, so the relink
+ repackage + LFS push sequence documented there is the next pass's first step.

## 24. Run 16: the scheduler works, the balanced pool is the wrong size

Implemented exactly the named fix and nothing else: one page-wide stats queue
(`STATS_MAX_IN_FLIGHT = 1`), reader passes taking priority over census, pool and
probe reads, and every `pc.getStats()` in the engine routed through it; plus a
balanced trim whose quota is the weakest class's own count, identical on both
sides.

Measured in grid-run16.log:

    reader stat poll took 255 ms          (only ONE such line is in the log: the
                                           reader's own logging is throttled, so
                                           this is n=1, not a distribution.  Run 15
                                           recorded n=6 passes: min 785, median 1510,
                                           max 33777 ms.  The independent evidence
                                           that passes are cheap once serialized is
                                           the 32-pass spike sweep: 0 ms at 10/42/74/
                                           138/170 idle connections, max 19 ms with
                                           120 checks in flight.)
    timeouts: 14 "stat poll timed out" events (run 15: 18), and they are a TAIL
              phenomenon - all 14 fall at +136 s to +153 s, after the last copy,
              one reader 14 in a row as its own backoff climbed to 4000 ms.  They
              are not what truncated the union.
    pool trimmed for the exchange: 48 source(s) kept; quota 3 per class;
      classes 3/3/3/3/3/3/3/3/3/3/3/3/3/3/3/3
    ready: 7 own socket(s), 8 peer socket(s), 48 source(s) live
    holding back 45s so the peer's transmission has the page to itself
    copy 1/3: 160 pulses      <- full payload coverage
    copy 2/3: 85 pulses
    copy 3/3: 39 pulses
    grid read improved to 17 of 384 cells (burst 1, 8 column(s))   <- first read line of the thread

So the concurrency diagnosis is supported, not proven inside the engine: one
recorded pass of 255 ms after serialization, and the decoder going from nothing
read at all to 17 of 384 cells.  The decisive comparison stays indirect because
run 16's log is throttled (n=1) while run 15's six passes (median 1510 ms) ran
with up to ten passes concurrent; the 32-pass sweep is the only measurement with
both a quiet and a loaded state.  What failed here is the sizing of the pool the
trim produced, which the pulse counts settle on their own.  The arithmetic:

  * K = 8 own/peer sockets -> rounds = ceil(320 / (16 * 8)) = 3 rounds per copy;
  * a (class, column) group therefore holds up to 3 cells (x = col, col+8, col+16),
    and three copies need 3 x 3 = 9 fresh (source, column) pairs for that group;
  * the quota the lottery could actually supply was 3, so copies 2 and 3 starved
    exactly as the model says - 160, 85, 39.

The payload must fit the classes actually available, and there is one clean way
for it to: **K >= 20 columns makes rounds = 1** (16 x 20 = 320 cells exactly), so a
group holds a single cell and three copies need exactly 3 fresh pairs per class -
which is precisely the quota the lottery delivers.  That is the smallest change
left; per the pass directive the carrier stops here and this is the record.

## 25. Run 18 - the last carrier run: the payload fits the pool, the wall is capture

One change, as directed: the payload now fits the pool that actually exists.
`trimPool` computes the columns the peer really offers, `rounds = ceil(320/(16K))`,
and fires only the number of **full** copies the trimmed pool can carry,
`copies = floor(perClass / rounds)`, with the arithmetic in the log.  The reader
and source pools were re-sized inside Chrome's 500-connection lifetime cap
(~14 connections per kept socket: the lottery keeps ~7%).

Measured in grid-run18.log:

    reader connection(s), 20 socket(s) / 21 socket(s)      <- two pages
    pool trimmed for the exchange: 75 source(s) kept; quota 5 per class;
      classes 5/5/5/5/5/5/5/5/5/5/5/5/5/5/5/5;
      K 18 -> 2 round(s), pool carries 2 full copy/copies (three copies need 6 per class)
    transmitting 320 bits as 2 round(s) x 2 copies over 18 peer socket(s) x 16 classes
    copy 1/2: 156 pulses        <- full, no starved copy
    copy 2/2: 154 pulses        <- full
    read improved to 16 of 416 cells (burst 3, 13 column(s), 56 arrival(s) seen)
    error: no payload decoded after 1 attempts
    verdict: alice state "fallback", failure "exchange", terminal true; bob "linking"

So the pool arithmetic is fixed and the short copies are gone (run 16: 160/85/39
against quota 3 and three rounds; now 156/154 against quota 5 and two rounds).
What fails is **arrival capture**: 310 pulses fired, 56 arrivals seen by the
receiving page - ~18%.  At ten readers (runs 11-16) a copy's ~154 pulses arrived
as ~120-132 (~80%); at twenty-one readers a page polls each reader about once per
5.4 s instead of 2.5 s, and the arrivals are lost before they are read.

The exchange rate is the connection cap.  K >= 20 needs ~20 sockets, which costs
~285 of Chrome's 500 lifetime connections (no refund on close), leaving ~180 for
the source pool and scanners; the source pool must then still yield 3 per class
for three copies, and run 17 is what happens when the two are asked for at once:
`Failed to construct 'RTCPeerConnection': Cannot create so many PeerConnections`
at 173/200 sources, before any transmission.

Two requirements therefore pull opposite ways inside one lifetime cap:

  * `rounds = 1` needs many reader sockets (K >= 20) -> many connections -> slow
    reader poll cycle -> poor arrival capture;
  * good capture needs few readers (K <= 10) -> rounds = 2-3 -> the pool must
    deliver 3 x rounds per class, which the port lottery does not supply
    (measured weakest class: 2, 3, 5 over runs 12, 16, 18).

No DataChannel, no invite/answer.  Per the directive the carrier is finished;
this is its falsification, and the game-side path plus the LFS push are what
remain.

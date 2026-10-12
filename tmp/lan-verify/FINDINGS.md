# Two-peer LAN play — verification on the shipped artifact (2026-10-12)

Artifact under test: `dist/direct-connect.html`, sha256
`c0ae53cf952b3399c185c1e8232eb974e03827c301aa9bd835e96771a39032bc`, 89,755,321
bytes. It was repackaged from the same Wasm payloads as the previously shipped
file: all six inline payload blocks hash byte-identical, and the only differences
in the HTML are the `lan-discovery.js` edits listed below.

Setup: one machine, two isolated Chrome profiles, both loaded from the same LAN
address `http://192.168.0.100:8090/direct-connect.html` served by
`node tools/lan-server.mjs --dir dist` (discovery scope `192.168.0.0/24`).

## What was verified

| Step | Result |
| --- | --- |
| Presence + signalling | both peers registered in the same `/24` scope, `addr 192.168.0.100` |
| Automatic discovery | `peer-found` ~26 s after load (the client itself is ~26 s of Wasm boot), channel `open` ~5 s later |
| In-game status | Direct Connect Room and Players On This Network both show `Automatic LAN discovery: 1 connected, 1 found on this network (192.168.0.0/24)`; peer row `bob` at 1–18 ms |
| Invite | `[LANInvites]: Invite sent to bob`; guest renders the World Invite dialog — `alice invited you to their world` / `Accepting connects you over this network - no code to copy` |
| Accept | host: `bob accepted the invite, completing the direct connection`, `Direct connect guest direct0000000001 joined (no relay involved)`, `[CHAT] <guest> joined the game`; guest: in-world at 49 fps |
| Link stability | one link built, zero rebuild lines over ~15 minutes covering world creation, the invite and play. The previous build rebuilt the link every ~20 s (`peer stopped answering` / `channel error`) |

Screenshots in this directory: `36-a4-room.png` (room + discovery line),
`37-a4-invite.png` (players on this network), `38-b4-title.png` (invite dialog),
`39-b4-joining.png` (guest in the world).

## The 404 that prompted this pass

Serving the same artifact from a plain static server (`python3 -m http.server`,
a CDN, GitHub Pages) makes `GET /eagler-lan/v1/health` return 404, and the client
reported only `Automatic LAN discovery unavailable here (HTTP 404)`. That is not
a client bug — automatic discovery needs those endpoints on the page's own
origin and a static host has none — but the message now names the fix:
`... (HTTP 404) - run: node tools/lan-server.mjs`, and README/GUIDE point at the
discovery server instead of `python3 -m http.server` for same-network play.

## Changes in this pass

* `target_teavm_wasm_gc/src/web/lan-discovery.js`
  * 404/405/410 from the probe now adds the `run: node tools/lan-server.mjs` hint.
  * liveness counts **pongs**, not our own pings, and only rebuilds a link after
    `DEAD_TIMEOUT_MS` (25 s) of silence or of ICE `disconnected` — a hiccup no
    longer tears down a working link.
  * a duplicate/late answer, or an answer failure on an already-connected
    connection, is a no-op instead of `retryLink`.
  * every rebuild logs its reason (`rebuilding the link to <peer> (<reason>)`).
* `tools/lan-server.mjs` — the banner prints the exact URLs to open on both
  players instead of "this machine's LAN address".
* `README.md`, `GUIDE.md` — how to serve for automatic discovery, and what a
  static host can and cannot do.

## Still open

* The guest joins through `ServerAddress.parseString("eagler-direct:…")`, which
  logs `Failed to parse URL eagler-direct:…` and an
  `IllegalArgumentException: Unparseable port number`, and the join screen then
  shows `Connecting to server.invalid, 25565`. It does not stop the join (the
  transport is the direct data channel) but every call site that parses
  `ServerData.ip` should special-case the prefix. That is a Java change and
  needs a client relink.
* Two browser profiles on one machine share a `/24`, which is what the scope
  check uses, but mDNS candidate resolution between two physical machines on a
  real network was not exercised here.

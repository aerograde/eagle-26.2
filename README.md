# Eaglercraft 26.2 — patched source + direct-connect room

This repository is the patched Minecraft 26.2 (Eaglercraft) source tree for the
browser client, plus the work on **relay-free direct connect**: a host can now
open a room and pair with **more than one guest at the same time**.

The tree was produced by the `eaglercraft-26.2` patcher (`create-dev`): the
official client JAR is decompiled and patched, the project skeleton is laid on
top, and the result is this Gradle project. `receipt.json` records the input
hashes of that patch run.

## Direct-connect room (this pass)

Direct connect carries Minecraft's LAN traffic over ordinary WebRTC peer
connections: no relay server, no STUN/TURN, no signaling service. The two users
exchange short text codes by hand.

* The host publishes with the host URI `eagler-direct:` through the normal LAN
  world pipeline, so the integrated server, the `~!LAN` peer bridge and the
  in-world experience are unchanged.
* A WebRTC transport is 1:1, so **the room mints one offer code per guest** and
  consumes that guest's answer code. Guests already in the world keep playing
  while the next invite is handed out; the room screen shows
  `Room: N link(s), M connected`.
* The room, not a module-level singleton, owns peer state. `DirectRoom` holds
  the pending invite, the map of guest links, the per-guest inbound queues and a
  small event queue. Each guest link is a `DirectPeer` (peer connection + data
  channel). The LAN bridge drains the event queue into the worker's peer map
  (`open:<peerId>` / `close:<peerId>`), and every payload is routed by its own
  peer id, so each guest looks like one ordinary LAN peer to the integrated
  server.
* Codes are compact: `EG` + a version character + base64url (no padding),
  about 154 characters, carrying ICE ufrag/pwd, the SHA-256 certificate
  fingerprint, the DTLS setup role and the candidates verbatim.

| Piece | File |
| --- | --- |
| Room, guest links, WebRTC plumbing | `platform-teavm/src/main/java/net/lax1dude/eaglercraft/v1_8/internal/PlatformWebRTC.java` |
| Code packing/unpacking, SDP rebuild | `platform-teavm/src/main/java/net/lax1dude/eaglercraft/v1_8/internal/teavm/DirectConnectCodec.java` |
| Data channel as a LAN socket | `platform-teavm/src/main/java/net/lax1dude/eaglercraft/v1_8/internal/teavm/DirectLANWebSocketClient.java` |
| Host/join platform entry points | `platform-teavm/src/main/java/net/lax1dude/eaglercraft/v1_8/sp/internal/ClientPlatformSingleplayer.java` (stubs in `platform/` and `platform-lwjgl/`) |
| Game-side controller and screens | `game/src/main/java/net/lax1dude/eaglercraft/v1_8/sp/SingleplayerServerController26.java`, `.../sp/gui/EaglerDirectConnectHostScreen.java`, `.../sp/gui/EaglerDirectConnectJoinScreen.java` |

Because Chrome mDNS-obfuscates host ICE candidates per origin, both players must
load the client from the **same origin** for the direct connection to leave the
`new` ICE state; that is a browser behaviour, not something the client can work
around.

## What is tracked here

Tracked: all Java source (`game`, `platform`, `platform-teavm`,
`platform-lwjgl`, `teavm-compat`, the `target_*` targets), the link/package
toolchain in `wasm-toolchain/`, Gradle configuration, the guides and the patch
receipts, and the packaged client in `dist/` (Git LFS).

Not tracked, because the patcher regenerates them from your own official
Minecraft 26.2 JAR: `source/` (extracted jar inputs), the Mojang resource
payloads `game/src/main/resources/{assets,data}`, build outputs (`**/build/`,
`.gradle/`), generated link intermediates (`wasm-toolchain/generated/`) and the
optional music pack.

## Rebuilding

Use the patcher's CLI or GUI with an existing project folder and reuse it:

```sh
java -jar eaglercraft-26.2-java-cli.jar build-standalone \
  --output <this-folder> --reuse-project ...
```

Then link/package from the project folder with Java 25 and Node on `PATH`:

```sh
node wasm-toolchain/link-web-target-standalone.js client   # classes.wasm
node wasm-toolchain/build-single-html.js \
  --reuse-client --reuse-mesh --reuse-server --fast-package \
  --output dist/direct-connect.html
```

`node wasm-toolchain/build-single-html.js --help` lists the remaining options.
Linking the client is the slow step (roughly an hour on an 8-core laptop); the
mesh and server workers relink in a few minutes each.

## Running the packaged client

`dist/direct-connect.html` is a complete client, but it must be **served over
HTTP** (open it from a local web server, not `file://`), and both sides of a
direct connection must use the same origin, for example:

```sh
python3 -m http.server 8000 --directory dist
```

Then `http://localhost:8000/direct-connect.html` on both machines (or two tabs
on one machine for a local test). "Get game audio" is not required: the client
runs without the optional music pack.

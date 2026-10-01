# Local Direct TCP Isolated Web App

This is a separate local packaging lane. It does not replace the hosted web
client, LAN/WebRTC, relay, EaglerX, or desktop transports.

Build a signed `.swbn` locally from your own compiled 26.2 client with the
Java patcher's **Isolated Web App** option. Keep the generated signing key: a
new key gives the next build a different app identity. The game no longer
offers a hosted bundle download.

The older files named `rejected-wasmgc-csp-prototype.*` remain diagnostic
evidence only. The current builder generates static CSP-compatible TeaVM body
bridges for the exact client, mesh and server images; it does not re-enable
runtime `new Function`.

The normal Chrome web client exposes an **Isolated App** link in Multiplayer.
Chrome's privileged `chrome://web-app-internals/` address must still be opened
by the user because a normal website cannot navigate to it or enable Chrome
flags. Enable both **Enable Isolated Web Apps** and **Enable Isolated Web App
Developer Mode**, restart Chrome, open Web App Internals, then under Developer
Mode use **Install IWA from Signed Web Bundle** -> **Select file...**. Installed
apps can later be launched from `chrome://apps/`.

An earlier local u1 bundle passed a Chrome 151 install/boot and 32 KiB Direct
TCP loopback check. This does not validate a newly generated bundle, a remote
server login, or a physical-device run. Installing a new bundle path does not
update an app already installed in Chrome.

An ordinary website cannot enable Chrome flags, sign a bundle without exposing
the private identity key, or complete the privileged IWA installation for the
user. Embedding the signed bytes in the game HTML would add base64 size and
transient-memory overhead. HTTP compression can reduce transfer size, but it
does not reduce the installed bundle or make an incompatible runtime valid.

The packaged manifest enables the Direct Sockets policies required for remote,
LAN/private, and loopback TCP destinations in Chrome 151:

```json
"permissions_policy": {
  "direct-sockets": ["self"],
  "local-network": ["self"],
  "loopback-network": ["self"],
  "cross-origin-isolated": ["self"]
}
```

Do not enable the Chrome flag on someone else's profile or by editing managed
browser policy. The flag is a user-controlled development opt-in.

## Candidate build

The Java patcher builds the client first and signs the IWA from that same
build. For an existing source-current optimized WasmGC web build, the lower
level command is:

```sh
node iwa/build-iwa.mjs --source target_teavm_wasm_gc/build/web \
  --output ./eaglercraft-26.2-local.swbn --key ./eaglercraft-iwa-key.pem
node wasm-toolchain/validate-iwa-bundle.mjs \
  ./eaglercraft-26.2-local.swbn
```

The builder generates or reuses the specified local Ed25519 key, externalizes
inline scripts, and checks that the compressed client, mesh and server Wasm
decode to the raw images from the same build. Its old hosted bundle and old
validation receipt do not establish acceptance for a new patcher build.

When the installed IWA exposes `TCPSocket`, Multiplayer add/edit and Direct
Connect show **EaglerX** and **TCP**. New IWA direct entries default to TCP.
The ordinary hosted client still supports relay-backed WebSocket multiplayer.
Relay profiles retain their configured capabilities; TCP does not replace the
ordinary RELAY or LAN paths. Enter a Java server as `host:port` when using TCP.

Direct TCP carries the ordinary Minecraft byte protocol. It does not provide
EaglerX handshaking, a WebSocket upgrade, relay authentication, TLS tunneling,
or a way around a server's normal account/authentication requirements. Enter an
explicit port when a server relies on a Minecraft SRV record; the current IWA
adapter delegates host lookup to `TCPSocket` and does not perform SRV discovery.

## Loopback check

Start the fixture:

```sh
npm run echo
```

Open DevTools for the installed IWA and run:

```js
await runEaglerDirectSocketLoopback()
```

The result must be `{pass: true, bytes: 32768, port: 25567}`. This sends many
small writes and accepts arbitrarily split reads, proving that TCP chunk
boundaries do not change the byte stream. The Java framing parity reference is:

```sh
javac -d /tmp/eagler-iwa-parity ../wasm-toolchain/DirectSocketFramingParity.java
java -cp /tmp/eagler-iwa-parity DirectSocketFramingParity
```

Historical IWA test receipts are kept in the development workspace, not in
this kit. Run the bundle validator and test your own output in a disposable
Chrome profile before using it. An earlier candidate passed local Paper
login/terrain and an exact-4x performance run, but those results do not
transfer to a new build. There is no hosted u1 bundle download in the game.
Physical-device and remote-server checks remain separate.

Official references:

- https://developer.chrome.com/docs/iwa/direct-sockets
- https://developer.chrome.com/docs/iwa/introduction

Wispcraft support is optional and user-injected. No Wispcraft script is bundled
or automatically downloaded. Explicit WISP entries use the endpoint selected
in Wispcraft Settings; ordinary RELAY entries keep their configured relay.
The top Wisp Settings link opens Wispcraft's own configuration. LAN retains its
configured P2P relay.

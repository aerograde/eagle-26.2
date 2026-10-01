#!/usr/bin/env bash
# Deploy the wasm-gc web target: assemble target_teavm_wasm_gc/build/web from the DURABLE
# shell (src/web/index.html) + the generated classes.wasm + the generic wasm-gc runtime js
# + the JS build's EPKs + lang. Idempotent. Does NOT touch the JS build (target_teavm).
#
# Usage: bash wasm-toolchain/deploy_wasm_web.sh
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
SRC="$REPO/target_teavm_wasm_gc/src/web"
GEN="$REPO/target_teavm_wasm_gc/build/generated/teavm/wasm-gc"
MESH_GEN="$REPO/target_teavm_wasm_gc_mesh/build/generated/teavm/wasm-gc"
SERVER_GEN="$REPO/target_teavm_wasm_gc_server/build/generated/teavm/wasm-gc"
USE_DEDICATED_SERVER_WASM="${EAGLER_USE_DEDICATED_SERVER_WASM:-1}"
WEB="$REPO/target_teavm_wasm_gc/build/web"
RUNTIME_SRC="$REPO/target_teavm_wasm_gc/build/generated/teavm/wasm-gc/classes.wasm-runtime.js"
JSWEB="$REPO/target_teavm/build/web"
LANG_SRC="$REPO/game/src/main/resources/assets/minecraft/lang/en_us.json"

# BSD (macOS) and GNU userlands disagree on `sed -i` and `stat -c`. Rewrite via a
# temp file and use `wc -c`, so the deploy works on both without extra tools.
sed_inplace() {
	local expr="$1" file="$2" tmp
	tmp="$(mktemp "${TMPDIR:-/tmp}/eagler-deploy-sed-XXXXXX")"
	sed "$expr" "$file" > "$tmp"
	mv "$tmp" "$file"
}
file_size() {
	wc -c < "$1" | tr -d ' '
}

if [ ! -s "$RUNTIME_SRC" ]; then
	echo "[deploy_wasm_web] ERROR: missing authenticated TeaVM 0.13.1 client runtime: $RUNTIME_SRC" >&2
	exit 1
fi

mkdir -p "$WEB" "$WEB/lang"

# 1. durable shell (the boot splash lives here) + the wasm worker bootstrap
#    (WORKERS PORT: worker-bootstrap.js is BOTH the page-side compile-once
#    instantiate helper and the classic-worker script every wasm Web Worker
#    — server worker + mesh pool — is spawned from)
cp "$SRC/index.html" "$WEB/index.html"
cp "$SRC/worker-bootstrap.js" "$WEB/worker-bootstrap.js"
cp "$REPO/game/src/main/resources/pack.png" "$WEB/favicon.png"

# 2. wasm binary (symlink -> the freshly generated one; the client emits only classes.wasm)
ln -sf "$GEN/classes.wasm" "$WEB/classes.wasm"
ln -sf "$MESH_GEN/mesh-worker.wasm" "$WEB/mesh-worker.wasm"
if [ "$USE_DEDICATED_SERVER_WASM" = "1" ] && [ -f "$SERVER_GEN/server-worker.wasm" ]; then
	ln -sf "$SERVER_GEN/server-worker.wasm" "$WEB/server-worker.wasm"
	SERVER_WORKER_AVAILABLE=true
else
	rm -f "$WEB/server-worker.wasm" "$WEB/server-worker.wasm.br" "$WEB/server-worker.wasm.gz"
	SERVER_WORKER_AVAILABLE=false
	# Do not let the browser preload an artifact that is intentionally absent.
	sed_inplace '/<link rel="preload" href="server-worker\.wasm/d' "$WEB/index.html"
	echo "[deploy_wasm_web] ERROR: dedicated server image disabled/unavailable; the slim Wasm-GC client has no in-image server fallback" >&2
	exit 1
fi

sed_inplace "s/__EAGLER_SERVER_WORKER_AVAILABLE__/$SERVER_WORKER_AVAILABLE/g" "$WEB/index.html"

# Every browser-facing runtime URL carries the Wasm content hash. The filenames are
# stable across local rebuilds, so without this token an older immutable cache entry
# can survive an otherwise correct deploy and execute stale code.
# The token identifies the complete executable browser build, not only Wasm.
# Including the durable shell and worker bootstrap prevents a shell-only fix
# from being hidden behind an older immutable index/runtime cache entry.
BUILD_INPUTS=(
	"$GEN/classes.wasm"
	"$MESH_GEN/mesh-worker.wasm"
	"$SRC/index.html"
	"$SRC/worker-bootstrap.js"
)
if [ "$SERVER_WORKER_AVAILABLE" = true ]; then
	BUILD_INPUTS+=("$SERVER_GEN/server-worker.wasm")
fi
if [ -f "$RUNTIME_SRC" ]; then
	BUILD_INPUTS+=("$RUNTIME_SRC")
elif [ -f "$WEB/classes.wasm-runtime.js" ]; then
	BUILD_INPUTS+=("$WEB/classes.wasm-runtime.js")
fi
# Asset URLs use the same cache token as the Wasm. Include both EPKs so an
# asset-only rebuild cannot leave an older immutable browser-cache entry active.
for e in assets sounds; do
	if [ -f "$JSWEB/$e.epk" ]; then
		BUILD_INPUTS+=("$JSWEB/$e.epk")
	fi
done
BUILD_ID="$(sha256sum "${BUILD_INPUTS[@]}" | sha256sum | cut -c1-16)"
sed_inplace "s/__EAGLER_BUILD_ID__/$BUILD_ID/g" "$WEB/index.html"

ASSET_TOTAL_BYTES=0
for e in assets sounds; do
	if [ -f "$JSWEB/$e.epk" ]; then
		ASSET_TOTAL_BYTES=$((ASSET_TOTAL_BYTES + $(file_size "$JSWEB/$e.epk")))
	fi
done
ASSET_TOTAL_MB="$(awk -v bytes="$ASSET_TOTAL_BYTES" 'BEGIN { printf "%.1f", bytes / 1048576 }')"
sed_inplace "s/__EAGLER_ASSET_TOTAL_BYTES__/$ASSET_TOTAL_BYTES/g; s/__EAGLER_ASSET_TOTAL_MB__/$ASSET_TOTAL_MB/g" "$WEB/index.html"

# 3. generic wasm-gc runtime js installed from the authenticated TeaVM 0.13.1
#    tool classpath by the standalone client linker. Never preserve a stale web copy.
cp "$RUNTIME_SRC" "$WEB/classes.wasm-runtime.js"

# 4. Base EPKs (symlink -> the JS build's; same assets). Music is distributed
#    separately as an optional resource pack and must not delay normal startup.
rm -f "$WEB/music.epk" "$WEB/music.epk.br" "$WEB/music.epk.gz"
for e in assets sounds; do
	if [ -e "$JSWEB/$e.epk" ]; then ln -sf "$JSWEB/$e.epk" "$WEB/$e.epk"; fi
done

# 5. lang (localesURI is only stored at config time, not fetched during boot, but keep it present)
if [ -f "$LANG_SRC" ]; then cp "$LANG_SRC" "$WEB/lang/en_us.json"; fi

echo "[deploy_wasm_web] deployed -> $WEB"
echo "[deploy_wasm_web] build id: $BUILD_ID"
ls -la "$WEB"

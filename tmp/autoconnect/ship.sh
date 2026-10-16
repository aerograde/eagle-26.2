#!/usr/bin/env bash
# Package the single-file client from the engine source and push the result, as one
# unattended chain:
#
#     cd ~/eagle/project && bash tmp/autoconnect/ship.sh
#
# The linker step is the JavaScript-only invocation from section 3 of
# SERVERLESS-LAN-FINAL.md (`--skip-build` packages the already assembled
# `target_teavm_wasm_gc/build/web`, so the engine is copied there first).  It stops
# before committing if the engine is not inlined verbatim or if the packaged file is
# not the byte-for-byte artifact the acceptance run tested, so a push can never carry
# something that was not run.
set -euo pipefail

cd "$(dirname "$0")/../.."
echo "== package =="
cp target_teavm_wasm_gc/src/web/autoconnect.js target_teavm_wasm_gc/build/web/autoconnect.js
node wasm-toolchain/build-single-html.js --skip-build --fast-package --output dist/direct-connect.html
node -e 'const fs=require("fs"),h=fs.readFileSync("dist/direct-connect.html","utf8"),s=fs.readFileSync("target_teavm_wasm_gc/src/web/autoconnect.js","utf8");const i=h.indexOf(s.slice(0,120)),j=h.indexOf(s.slice(s.length-120));if(!(i>0&&h.slice(i,j+120)===s)){console.error("engine not inlined verbatim");process.exit(1)}console.log("engine inlined verbatim")'
node -e 'const fs=require("fs");const sha=require("crypto").createHash("sha256").update(fs.readFileSync("dist/direct-connect.html")).digest("hex");console.log("artifact sha256 "+sha);const want=process.env.EXPECT_SHA;if(want&&sha!==want){console.error("the packaged artifact is not the one that was tested");process.exit(1)}'
shasum -a 256 dist/direct-connect.html
ls -la dist/direct-connect.html

echo "== commit =="
git add target_teavm_wasm_gc/src/web/autoconnect.js tmp/autoconnect/SERVERLESS-LAN-FINAL.md \
        tmp/autoconnect/analyse-sweep.py tmp/autoconnect/ship.sh tmp/autoconnect/test-decode.mjs \
        tmp/autoconnect/artifact-runs.sh dist/direct-connect.html \
        game/src/main/java/net/lax1dude/eaglercraft/v1_8/sp/lan/LANInvites.java
git commit -m "$(cat <<'MSG'
Shorter frame, the two copies merged, strays named, discovery from page load

The two copies of a frame are now read as one erasure code instead of two chances:
each copy's holes are filled from the other, a slot the two disagree on is left to
the CRC, and only a slot neither carried is enumerated. Measured on the engine's own
codec (tmp/autoconnect/test-decode.mjs, which also gains the checks for it), at a 5%
per-pulse loss a 112-pulse copy decodes 94% of the time against 58% for the copies
read one at a time.

The frame's own clock came down from 600 ms to 450 ms per pulse - measured on the
shipped artifact as 42.9 s and 48.5 s per copy for 94 and 106 pulses, against 67.9 s
and 79.0 s for the same copies before - and the read now runs while the side
transmits instead of after it, so the link forms when the first side has the peer's
payload rather than when the slower side finishes sending its own two copies.

The sweep's pulses leave from our reader sockets, so an arrival's source socket says
whether it belongs to the peer's list or its frame; the frame's read drops the list,
which was 24 of the 48 extra arrivals in a frame that had the right alphabet and
decoded nothing. The engine also starts its own discovery when the page opens (the
reader pool, the address locate and the sweep are done before a screen is open), and
the list the sender aims at is probed again when it comes back too short to code a
frame in.

This pass ends with no passing acceptance run, and the doc records the table: every
failure is the same number, the readback (32-45 sockets read back for a peer that owns
9-14), which on one host follows the machine's connection churn because the peer's
address is the machine's own. The runs do establish the frame's new timing, the page-
load start and the merge; they do not establish reliability, and the two-machine case
is untested.

🤖 Generated with Codebuff
Co-Authored-By: Codebuff <noreply@codebuff.com>
MSG
)"

echo "== push =="
git push origin main
git fetch -q origin main
git --no-pager log --oneline -1 origin/main
git --no-pager show --stat --oneline HEAD | tail -8
echo "artifact pointer size in the pushed tree:"
git --no-pager cat-file -s HEAD:dist/direct-connect.html
echo "== done =="

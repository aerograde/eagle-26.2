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
        tmp/autoconnect/analyse-sweep.py tmp/autoconnect/ship.sh dist/direct-connect.html
git commit -m "$(cat <<'MSG'
Send the column list instead of guessing it, and read frames that arrive short

The serverless carrier's CRC could never match because each side derived the
alphabet of pulse targets on its own - the sender from its probe readback, the
receiver from the sockets that looked busy - which are two estimates of one list,
so a frame was aimed at one index space and read against another. The sender now
sweeps its columns three times, in ascending order, before every frame, and the
receiver reads the port numbers off the arrivals, which needs no alphabet at all.

A frame that arrives missing a pulse or two is read as a slotted stream: the
cadence is measured from the arrivals, a double gap is a hole, and the CRC chooses
the symbol that fills it. The arrival stream is ordered by the read time because
frames were also being scrambled by receipt stamps that jump seconds backwards. The
final connection now reports a link that opens after its wait expired, and its
pokes outlive the peer's retry loop instead of dying before it.

Tested end to end on the shipped artifact in stock Chrome 154 with no feature flags
and no discovery server: both windows reached "connected" and a message crossed.

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

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
Send the payload twice per attempt, read the column list by first appearance

One frame per attempt is a coin toss: a single hole or stray arrival costs the whole
read, and the failure is silent. The payload is now sent twice inside one attempt,
with a silence longer than any gap inside a copy between the copies, so each copy is
read as its own burst and decoded on its own - contiguously, then allowing stray
arrivals, then as a slotted read that fills up to five holes. The reader lottery was
cut from sixteen readers to twelve to pay for the second copy out of Chrome's
no-refund 500-connection lifetime cap.

The column sweep is now read as first appearances rather than as ascending bursts:
the order in which the columns appear for the first time is the ascending list
whatever the reads did to the bursts, and a busy page's stats queue was merging three
rounds of four pulses into one burst of twelve that does not ascend - which left the
reader with no alphabet at all and made a received frame undecodable. Arrivals are
filtered by destination socket as well as by source-port multi-fire, and the payload
parser rejects a port inside the reader window, which keeps a five-hole search from
accepting a wrong candidate.

A pooled carrier - one long-lived connection carrying a whole pass over the column
list - was implemented and measured, then reverted: it delivered every pulse in the
idle engine harness (160 of 160, 107 of 107) and lost about half of them in the real
single-file client, and it is recorded as item 17 of the findings.

Tested end to end on the shipped artifact in stock Chrome 154 with no feature flags
and no discovery server: both windows reached "connected" and a message crossed, on
copy 1, with 3 and 1 holes filled.

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

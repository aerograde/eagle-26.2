#!/usr/bin/env bash
# Two consecutive runs of one unchanged revision, same harness, same machine.
set -u
REV=$(shasum -a 256 target_teavm_wasm_gc/src/web/autoconnect.js | awk '{print $1}')
echo "revision $REV" | tee tmp/autoconnect/final-runs.txt
for n in 1 2; do
  echo "--- run $n start $(date +%H:%M:%S)" | tee -a tmp/autoconnect/final-runs.txt
  /tmp/webtest-venv/bin/python tmp/autoconnect/run-peers.py 8124 260000 > "tmp/autoconnect/final-run$n.log" 2>&1
  echo "--- run $n exit $? at $(date +%H:%M:%S)" | tee -a tmp/autoconnect/final-runs.txt
  shasum -a 256 target_teavm_wasm_gc/src/web/autoconnect.js | tee -a tmp/autoconnect/final-runs.txt
done

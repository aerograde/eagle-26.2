#!/usr/bin/env bash
# Two consecutive runs of one unchanged revision (the census build), same harness,
# same machine.  Separate output names so the frozen-run evidence stays intact.
set -u
REV=$(shasum -a 256 target_teavm_wasm_gc/src/web/autoconnect.js | awk '{print $1}')
echo "revision $REV" | tee tmp/autoconnect/census-runs.txt
for n in 1 2; do
  echo "--- run $n start $(date +%H:%M:%S)" | tee -a tmp/autoconnect/census-runs.txt
  /tmp/webtest-venv/bin/python tmp/autoconnect/run-peers.py 8124 260000 > "tmp/autoconnect/census-2run-$n.log" 2>&1
  echo "--- run $n exit $? at $(date +%H:%M:%S)" | tee -a tmp/autoconnect/census-runs.txt
  shasum -a 256 target_teavm_wasm_gc/src/web/autoconnect.js | tee -a tmp/autoconnect/census-runs.txt
done

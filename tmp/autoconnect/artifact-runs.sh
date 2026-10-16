#!/usr/bin/env bash
# Three consecutive unattended acceptance runs of the shipped artifact, the same
# acceptance the carrier has always been scored on: two stock Chrome windows, the
# single-file client served over plain static HTTP from the machine's LAN address,
# no feature flags, no discovery server, and a real message crossing the channel.
#
#     bash tmp/autoconnect/artifact-runs.sh [httpPort] [timeoutMs] [tag]
#
# Each run's log and the summary land in ~/eagle/out.  The script never aborts on a
# failed run (a failure is data), and it records the host load before and after so
# the table can say what the machine was doing.
set -uo pipefail

cd "$(dirname "$0")/../.."
PORT="${1:-8125}"
TIMEOUT="${2:-300000}"
TAG="${3:-runs6}"
OUT="$HOME/eagle/out"
mkdir -p "$OUT"
SUM="$OUT/artifact-$TAG-summary.txt"
: > "$SUM"

{
  echo "=== host at start: $(date) load $(uptime | sed 's/.*load averages://') ==="
  echo "artifact: http://$(ipconfig getifaddr en0):$PORT/dist/direct-connect.html"
} >> "$SUM"

for i in 1 2 3; do
  echo "=== run $i start $(date +%H:%M:%S) ===" >> "$SUM"
  LOG="$OUT/artifact$TAG-run-$i.log"
  python3 tmp/autoconnect/artifact-two-chrome.py "$PORT" "$TIMEOUT" > "$LOG" 2>&1
  status=$?
  echo "run $i exit $status at $(date +%H:%M:%S)" >> "$SUM"
  grep -E "VERDICT|MESSAGE (DELIVERED|NOT)|WAIT TIMEOUT" "$LOG" >> "$SUM"
  grep -E "autoconnect \+[0-9]+ms\] (boot|sym|connect|final|grid|ports|readers|census|locate|scan|code|error)" \
    "$LOG" | grep -vE "lottery|out-of-window" | tail -6 >> "$SUM"
  sleep 5
done

{
  echo "ALL RUNS DONE $(date +%H:%M:%S)"
  echo "=== host at end: load $(uptime | sed 's/.*load averages://') ==="
} >> "$SUM"

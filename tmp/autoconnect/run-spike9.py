"""Run spike9 (receiver instrumentation + known-pulse sender) and join the logs.

Usage: python run-spike9.py [httpPort] [addr] [recvDurationMs]

The two pages run in separate browser processes on one machine and both stamp
with Date.now(), so a pulse's send time from one page and the instant its
prflx candidate became visible in the other page's getStats() line up.  This
script does the join and prints the analysis.
"""

import json
import subprocess
import sys
from collections import Counter, defaultdict

from playwright.sync_api import sync_playwright

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8124
addr = sys.argv[2] if len(sys.argv) > 2 else None
recv_ms = int(sys.argv[3]) if len(sys.argv) > 3 else 80000
run = sys.argv[4] if len(sys.argv) > 4 else "1"

if not addr:
    out = subprocess.run(["ipconfig", "getifaddr", "en0"], capture_output=True, text=True)
    addr = out.stdout.strip() or "127.0.0.1"

base = "http://127.0.0.1:%d/tmp/autoconnect/spike9.html" % port
ARGS = ["--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessChecks", "--no-first-run"]


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    k = min(len(s) - 1, int(round((len(s) - 1) * p)))
    return s[k]


def stats(values):
    if not values:
        return "none"
    return "n=%d min=%s median=%s p90=%s max=%s" % (
        len(values), min(values), pct(values, 0.5), pct(values, 0.9), max(values))


with sync_playwright() as p:
    recv_browser = p.chromium.launch(headless=True, args=ARGS)
    recv = recv_browser.new_context().new_page()
    recv.on("console", lambda m: print("[recv] %s" % m.text, flush=True))
    recv.on("pageerror", lambda e: print("[recv] PAGEERROR %s" % str(e), flush=True))
    recv.goto("%s?mode=recv&duration=%d" % (base, recv_ms))
    recv.wait_for_function(
        "() => window.__spike && window.__spike.ports && window.__spike.ports.length > 0",
        timeout=40000)
    listener_port = recv.evaluate("window.__spike.ports[0]")
    print("listener port: %s addr: %s" % (listener_port, addr), flush=True)

    send_browser = p.chromium.launch(headless=True, args=ARGS)
    send = send_browser.new_context().new_page()
    send.on("console", lambda m: print("[send] %s" % m.text, flush=True))
    send.on("pageerror", lambda e: print("[send] PAGEERROR %s" % str(e), flush=True))
    send.goto("%s?mode=send&addr=%s&targetPort=%d" % (base, addr, listener_port))

    send.wait_for_function("() => window.__spike && (window.__spike.ok || window.__spike.error)",
                           timeout=180000)
    recv.wait_for_function("() => window.__spike && (window.__spike.ok || window.__spike.error)",
                           timeout=recv_ms + 60000)

    S = send.evaluate("window.__spike")
    V = recv.evaluate("window.__spike")
    send_browser.close()
    recv_browser.close()

with open("tmp/autoconnect/spike9-run%s.json" % run, "w") as fh:
    json.dump({"sender": S, "receiver": V, "addr": addr, "listener_port": listener_port}, fh)

print("\n=== SENDER ===", flush=True)
print("sources: %d, pulses fired: %d, error: %s" % (len(S["own"]), len(S["pulses"]), S.get("error")), flush=True)
print("\n=== RECEIVER ===", flush=True)
print("polls: %d  arrivals: %d  error: %s" % (V["pollCount"], len(V["arrivals"]), V.get("error")), flush=True)
print("raw remote-candidate fields:", json.dumps(V.get("rawRemote")), flush=True)
print("raw candidate-pair fields:", json.dumps(V.get("rawPair")), flush=True)

# ---------------------------------------------------------------- the join
arrivals = sorted(V["arrivals"], key=lambda a: a["at"])
used = [False] * len(arrivals)
joined = []
for pulse in sorted(S["pulses"], key=lambda x: x["at"]):
    hit = None
    for i, a in enumerate(arrivals):
        if used[i] or a["at"] < pulse["at"] - 5:
            continue
        if a["port"] in pulse["ports"]:
            hit = (i, a)
            break
    if hit:
        used[hit[0]] = True
        joined.append({"kind": pulse["kind"], "label": pulse.get("label"), "i": pulse["i"],
                       "delay": hit[1]["at"] - pulse["at"], "port": hit[1]["port"],
                       "pollIndex": hit[1].get("pollIndex")})
    else:
        joined.append({"kind": pulse["kind"], "label": pulse.get("label"), "i": pulse["i"],
                       "delay": None, "port": pulse["ports"][0] if pulse["ports"] else None,
                       "pollIndex": None})

print("\n=== PULSE -> VISIBILITY (ms from addIceCandidate to the poll that saw the pair) ===", flush=True)
by_kind = defaultdict(list)
for j in joined:
    by_kind[j["kind"] + (":" + j["label"] if j["label"] else "")].append(j)
for kind in sorted(by_kind):
    rows = by_kind[kind]
    delays = [r["delay"] for r in rows if r["delay"] is not None]
    missing = [r for r in rows if r["delay"] is None]
    print("%-18s %-60s  never-seen=%d" % (kind, stats(delays), len(missing)), flush=True)

base_delays = [j["delay"] for j in joined if j["kind"] in ("baseline", "burst") and j["delay"] is not None]
print("\nbaseline+burst delay: %s" % stats(base_delays), flush=True)

reps = [j for j in joined if j["kind"] == "repeat"]
print("\nrepeat phase (same source, same target, 4 shots): %s" % json.dumps(reps), flush=True)
rep_ports = [p["ports"] for p in S["pulses"] if p["kind"] == "repeat"]
rep_remote_ids = [a["id"] for a in arrivals if rep_ports and a["port"] in rep_ports[0]]
print("repeat: arrivals for that source port: %d (a repeat creates no new candidate if < 4)" % len(rep_remote_ids), flush=True)
for snap in V.get("counterSnapshots", []):
    for rec in snap:
        if rep_remote_ids and rec["id"] in rep_remote_ids:
            print("  counter change at %s: req=%s resp=%s state=%s crtt=%s"
                  % (rec["t"], rec["req"], rec["resp"], rec["state"], rec["crtt"]), flush=True)

# ------------------------------------------------------------ poll cost
polls = V["polls"]
durs = [p["dur"] for p in polls]
print("\n=== POLL COST ===", flush=True)
print("getStats() duration: %s" % stats(durs), flush=True)
buckets = defaultdict(list)
for p in polls:
    buckets[p["pairs"] // 25 * 25].append(p["dur"])
for key in sorted(buckets):
    print("  pairs %4d-%4d: %s" % (key, key + 24, stats(buckets[key])), flush=True)
gaps = []
prev = None
for p in polls:
    if prev is not None:
        gaps.append(p["t0"] - prev)
    prev = p["t1"]
print("gap between poll completion and next poll start: %s" % stats(gaps), flush=True)

# --------------------------------------------------------- report caching
ts = V["reportTs"]
distinct = [t for i, t in enumerate(ts) if i == 0 or t != ts[i - 1]]
deltas = [b - a for a, b in zip(distinct, distinct[1:])]
print("\n=== REPORT TIMESTAMP (identical across consecutive polls means a cached report) ===", flush=True)
print("polls=%d distinct report timestamps=%d adjacent-identical=%d" % (len(ts), len(distinct), V["cachedReports"]), flush=True)
print("delta between distinct report timestamps: %s" % stats(deltas), flush=True)

# ---------------------------------------------------------------- pruning
print("\n=== PRUNING ===", flush=True)
print("disappearances: %d" % len(V["disappearances"]), flush=True)
if V["disappearances"]:
    print("first few: %s" % json.dumps(V["disappearances"][:6]), flush=True)
snaps = V["snapshots"]
print("snapshots (t, pairs, prflx): %s" % json.dumps([[s["t"], s["pairs"], s["prflx"]] for s in snaps][:40]), flush=True)
print("max pairs seen: %s  max prflx: %s"
      % (max([s["pairs"] for s in snaps] or [0]), max([s["prflx"] for s in snaps] or [0])), flush=True)

# ------------------------------------------------------------- batching
print("\n=== ARRIVAL CLUSTERING (how many new pairs one poll reported) ===", flush=True)
per_poll = Counter(a.get("pollIndex") for a in arrivals)
print("new pairs per poll: %s" % dict(sorted(Counter(per_poll.values()).items())), flush=True)
print("largest single-poll groups: %s" % per_poll.most_common(6), flush=True)

# ---------------------------------------------------------- mini payload
print("\n=== MINI PAYLOAD: 10 slots x 4 sources, 200 ms apart ===", flush=True)
slots = defaultdict(list)
for j in joined:
    if j["kind"] == "slot" and j["label"]:
        slots[j["label"]].append(j)
for k in sorted(slots, key=lambda s: int(s.replace("slot", ""))):
    rows = slots[k]
    got = [r for r in rows if r["delay"] is not None]
    print("  %s: %d/4 seen, delays %s" % (k, len(got), [r["delay"] for r in got]), flush=True)

print("\n=== DIRTY VS CLEAN 10-SLOT PATTERN (identical cadence, only the sources' queue differs) ===", flush=True)
for prefix in ("dslot", "cslot"):
    slots = defaultdict(list)
    for j in joined:
        if j["kind"] == prefix and j["label"]:
            slots[j["label"]].append(j)
    total = seen_slots = 0
    delays = []
    for k in sorted(slots, key=lambda s: int(s.replace(prefix, ""))):
        rows = slots[k]
        got = [r for r in rows if r["delay"] is not None]
        total += 4
        seen_slots += len(got)
        delays += [r["delay"] for r in got]
        print("  %s: %d/4 seen delays=%s" % (k, len(got), [r["delay"] for r in got]), flush=True)
    print("%s TOTAL %d/%d seen, delay %s" % (prefix, seen_slots, total, stats(delays)), flush=True)
late = [j for j in joined if j["delay"] is not None and j["delay"] > 200]
print("pulses that took longer than one 200 ms slot to become visible: %d %s"
      % (len(late), json.dumps([(j["kind"], j["label"], j["delay"]) for j in late][:20])), flush=True)

print("\n=== VERDICT NUMBERS ===", flush=True)
fired = len(S["pulses"])
seen = len([j for j in joined if j["delay"] is not None])
print("fired=%d seen=%d never-seen=%d" % (fired, seen, fired - seen), flush=True)
print("baseline/burst median delay: %s (slot is 200 ms)"
      % pct(base_delays, 0.5), flush=True)

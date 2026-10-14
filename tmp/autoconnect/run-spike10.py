"""Run spike10 and score the census hypothesis.

Usage: python run-spike10.py [httpPort] [addr]

Per source connection, three targets on the same peer address: a closed port, and
the listener's two real UDP sockets.  For each, two facts: which of the source's
two ports the check actually left from (the receiver sees it for the two real
targets), and which local port the source's own stats say that target used (the
census readback).  If the choice is a property of the destination address, all
three agree and the readback is a usable census.
"""

import json
import subprocess
import sys
from collections import Counter

from playwright.sync_api import sync_playwright

port = int(sys.argv[1]) if len(sys.argv) > 1 else 8124
addr = sys.argv[2] if len(sys.argv) > 2 else None
if not addr:
    out = subprocess.run(["ipconfig", "getifaddr", "en0"], capture_output=True, text=True)
    addr = out.stdout.strip() or "127.0.0.1"

base = "http://127.0.0.1:%d/tmp/autoconnect/spike10.html" % port
ARGS = ["--disable-features=LocalNetworkAccessChecks,PrivateNetworkAccessChecks", "--no-first-run"]

with sync_playwright() as p:
    recv_browser = p.chromium.launch(headless=True, args=ARGS)
    recv = recv_browser.new_context().new_page()
    recv.on("console", lambda m: print("[recv] %s" % m.text, flush=True))
    recv.on("pageerror", lambda e: print("[recv] PAGEERROR %s" % str(e), flush=True))
    recv.goto("%s?mode=recv" % base)
    recv.wait_for_function("() => window.__spike && window.__spike.ports && window.__spike.ports.length > 1",
                           timeout=40000)
    ports = recv.evaluate("window.__spike.ports")
    A, B = ports[0], ports[1]
    print("listener sockets: %s %s (addr %s)" % (A, B, addr), flush=True)

    send_browser = p.chromium.launch(headless=True, args=ARGS)
    send = send_browser.new_context().new_page()
    send.on("console", lambda m: print("[send] %s" % m.text, flush=True))
    send.on("pageerror", lambda e: print("[send] PAGEERROR %s" % str(e), flush=True))
    send.goto("%s?mode=send&addr=%s&a=%d&b=%d" % (base, addr, A, B))

    send.wait_for_function("() => window.__spike && (window.__spike.ok || window.__spike.error)",
                           timeout=180000)
    recv.wait_for_function("() => window.__spike && (window.__spike.ok || window.__spike.error)",
                           timeout=60000)
    S = send.evaluate("window.__spike")
    V = recv.evaluate("window.__spike")
    send_browser.close()
    recv_browser.close()

with open("tmp/autoconnect/spike10-run1.json", "w") as fh:
    json.dump({"sender": S, "receiver": V, "addr": addr, "A": A, "B": B}, fh)

closed = S["params"]["closed"]
arrivals = V["arrivals"]
print("\narrivals: %d, fires: %d" % (len(arrivals), len(S["fires"])), flush=True)

rows = []
for i in range(len(S["fires"])):
    own = set(S["own"][i])
    a_hit = next((x for x in arrivals if x["localPort"] == A and x["source"] in own), None)
    b_hit = next((x for x in arrivals if x["localPort"] == B and x["source"] in own), None)
    rb = S["readback"].get(str(i), S["readback"].get(i, {}))
    rows.append({
        "i": i, "own": sorted(own),
        "closed_used": rb.get(str(closed)) or rb.get(closed),
        "a_used": rb.get(str(A)) or rb.get(A),
        "b_used": rb.get(str(B)) or rb.get(B),
        "a_seen": a_hit["source"] if a_hit else None,
        "b_seen": b_hit["source"] if b_hit else None,
    })

agree_addr = agree_read = agree_seen = 0
odd = []
for r in rows:
    used = {v for v in (r["closed_used"], r["a_used"], r["b_used"]) if v}
    seen = {v for v in (r["a_seen"], r["b_seen"]) if v}
    if len(used) == 1:
        agree_addr += 1
    else:
        odd.append(r)
    if seen and used and seen == used:
        agree_read += 1
    if len(seen) == 1:
        agree_seen += 1

print("\nper-source: does one socket serve all three targets?", flush=True)
print("  sources where the source's own stats say the same local port for the closed port and both listener sockets: %d/%d"
      % (agree_addr, len(rows)), flush=True)
print("  sources where the receiver saw the same source port on both listener sockets: %d/%d"
      % (agree_seen, len(rows)), flush=True)
print("  sources where the readback matches what the receiver saw: %d/%d" % (agree_read, len(rows)), flush=True)
print("\nfirst rows:", flush=True)
for r in rows[:8]:
    print("   ", json.dumps(r), flush=True)
if odd:
    print("\nsources where the socket differed by target:", flush=True)
    for r in odd[:8]:
        print("   ", json.dumps(r), flush=True)
print("\ndistribution of which of a source's two ports is used:", flush=True)
pick = Counter("first" if (r["closed_used"] and r["own"] and r["closed_used"] == r["own"][0])
               else ("second" if r["closed_used"] in r["own"] else "unknown") for r in rows)
print("  ", dict(pick), flush=True)
print("VERDICT: %s" % ("census at a throwaway port is valid" if agree_addr == len(rows) and agree_read == len(rows)
                       else "census hypothesis FAILS - see rows above"), flush=True)

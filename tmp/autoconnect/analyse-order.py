"""Does the receiver's reported arrival order match the sender's pulse order?

Run a two-peer run with ACDIAG=1 and hand it the log:

    ACDIAG=1 python3 tmp/autoconnect/run-peers.py 8124 300000 > out/ac-diag.log 2>&1
    python3 tmp/autoconnect/analyse-order.py out/ac-diag.log

The engine logs one `diag: arrival` line per arrival (the socket it landed on, the
source port it came from, the read's own wall-clock time and the sequence number
the reader pushed it in) and one `diag: pulse` line per sampled outgoing pulse
(its index, symbol, target and the two ports its connection owns).  An arrival's
`source` is one of the sending pulse's ports, so the two can be paired exactly -
no timing guesswork - and the question becomes measurable:

  * does sorting arrivals by the reader's timestamp reproduce the sender's order?
  * how many order inversions are there, and what is the read latency?

If the timestamp order scrambles the stream, a pulse train cannot be decoded by
arrivals alone and the frame needs an order-free carrier.
"""

import re
import sys
from collections import defaultdict

LINE = re.compile(r"^\[(alice|bob)\] \[autoconnect \+(\d+)ms\] diag: (.+)$")
PULSE = re.compile(r"^pulse i=(\d+) sym=(\d+) target=(\d+) src=([\d,]*) at=(\d+)$")
ARRIVAL = re.compile(r"^arrival seq=(\d+) port=(\d+) source=(\d+) at=(\d+)(?: obs=(\d+))?(?: precise=(\d+))? readerwin=([\d/]+)$")


def main(path):
    pulses = defaultdict(list)   # peer -> [{i, sym, target, srcs, at}]
    arrivals = defaultdict(list)  # peer -> [{seq, port, source, at}]
    with open(path) as fh:
        for line in fh:
            m = LINE.match(line.rstrip("\n"))
            if not m:
                continue
            peer, _ms, body = m.group(1), m.group(2), m.group(3)
            mp = PULSE.match(body)
            if mp:
                pulses[peer].append({
                    "i": int(mp.group(1)), "sym": int(mp.group(2)),
                    "target": int(mp.group(3)),
                    "srcs": [int(x) for x in mp.group(4).split(",") if x],
                    "at": int(mp.group(5)),
                })
                continue
            ma = ARRIVAL.match(body)
            if ma:
                arrivals[peer].append({
                    "seq": int(ma.group(1)), "port": int(ma.group(2)),
                    "source": int(ma.group(3)), "at": int(ma.group(4)),
                    "precise": int(ma.group(6) or 0),
                })

    for sender in ("alice", "bob"):
        receiver = "bob" if sender == "alice" else "alice"
        P, A = pulses[sender], arrivals[receiver]
        print("\n=== %s -> %s: %d sampled pulse(s), %d arrival(s) ===" % (sender, receiver, len(P), len(A)))
        if not P or not A:
            continue
        by_source = {}
        for a in A:
            by_source.setdefault(a["source"], []).append(a)

        rows = []       # (pulse.i, arrival) pairs, matched by source port
        for p in P:
            cands = []
            for s in p["srcs"]:
                for a in by_source.get(s, []):
                    cands.append((abs(a["at"] - p["at"]), a))
            if not cands:
                continue
            cands.sort(key=lambda t: t[0])
            rows.append((p, cands[0][1], cands[0][0]))

        if not rows:
            print("  no arrival matched a sampled pulse by source port "
                  "(pulse src sets: %s)" % [p["srcs"] for p in P[:3]])
            continue

        rows.sort(key=lambda t: t[0]["i"])
        print("  pulse->arrival pairs: %d" % len(rows))
        print("  read latency (arrival.at - pulse.at): min %d ms, median %d ms, max %d ms" % (
            min(r[2] for r in rows),
            sorted(r[2] for r in rows)[len(rows) // 2],
            max(r[2] for r in rows),
        ))

        # The order the decoder sees: arrivals sorted by (timestamp, push order).
        seen = {id(a): (p["i"], a) for p, a, _ in rows}
        ordered = [seen[id(a)][0] for a in sorted(A, key=lambda a: (a["at"], a["seq"])) if id(a) in seen]
        inversions = sum(1 for x in range(len(ordered)) for y in range(x + 1, len(ordered))
                         if ordered[x] > ordered[y])
        pairs = len(ordered) * (len(ordered) - 1) // 2
        print("  matched arrivals in timestamp order: %s" % ordered)
        print("  sender order was:                    %s" % sorted(ordered))
        print("  inversions: %d of %d pairs (%.1f%% out of order)" % (
            inversions, pairs, (100.0 * inversions / pairs) if pairs else 0.0))

        deltas = [A[i]["at"] - A[i - 1]["at"] for i in range(1, len(A))]
        big = [d for d in deltas if d > 0]
        print("  arrival timestamp deltas: %d positive, min %s ms, median %s ms" % (
            len(big), min(big) if big else "-", sorted(big)[len(big) // 2] if big else "-"))
        ports = defaultdict(int)
        for a in A:
            ports[a["port"]] += 1
        top = sorted(ports.items(), key=lambda kv: -kv[1])[:6]
        print("  arrivals per socket: %s" % top)
        prec = sum(a["precise"] for a in A)
        print("  arrivals carrying a receipt timestamp of their own: %d of %d" % (prec, len(A)))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out/ac-diag.log")

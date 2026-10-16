"""Was the frame decodable at all?  Reconstruct the symbols offline.

    ACDIAG=1 python3 tmp/autoconnect/run-peers.py 8124 300000 > out/ac-diag3.log 2>&1
    python3 tmp/autoconnect/analyse-symbols.py out/ac-diag3.log

The engine's `acdiag=1` lines name every arrival (the socket of ours it landed on,
the source port it came from, the read's wall clock and the order the read
reported it in) and every tenth outgoing pulse (its index, symbol, target and the
ports its connection owns).  An arrival's source is one of its pulse's ports, so
the two can be paired exactly.

With the pairs in hand this answers the question the browser cannot:

  * is every pulse the sender fired present in the receiver's arrival list?
  * does the port a pulse landed on map back to the symbol the sender chose
    (through the receiver's own socket list, and through the list read out of the
    stream)?
  * is the order the receiver reports the order the sender fired?

Any "no" is a fact about the carrier, not about the decoder, and it says where
the next fix goes.
"""

import re
import sys
from collections import Counter, defaultdict

LINE = re.compile(r"^\[(alice|bob)\] \[autoconnect \+(\d+)ms\] diag: (.+)$")
PULSE = re.compile(r"^pulse i=(\d+) sym=(\d+) target=(\d+) src=([\d,]*) at=(\d+)$")
ARRIVAL = re.compile(r"^arrival seq=(\d+) port=(\d+) source=(\d+) at=(\d+)(?: obs=(\d+))?(?: precise=(\d+))? readerwin=([\d/]+)$")


def parse(path):
    pulses = defaultdict(list)
    arrivals = defaultdict(list)
    frames = defaultdict(list)   # peer -> [(t, n, bits, cols)] from the sym log lines
    with open(path) as fh:
        for line in fh:
            line = line.rstrip("\n")
            m = LINE.match(line)
            if m:
                peer, body = m.group(1), m.group(3)
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
                    })
                continue
            mf = re.match(r"^\[(alice|bob)\] \[autoconnect \+(\d+)ms\] sym: transmitting (\d+) bits as "
                          r"(\d+) pulse\(s\) x (\d+) bit\(s\) over (\d+) peer socket", line)
            if mf:
                frames[mf.group(1)].append({
                    "at": int(mf.group(2)), "pulses": int(mf.group(4)),
                    "per": int(mf.group(5)), "cols": int(mf.group(6)),
                })
    return pulses, arrivals, frames


def main(path):
    pulses, arrivals, frames = parse(path)
    print("frames:")
    for peer, fs in sorted(frames.items()):
        for f in fs:
            print("  %-5s at +%6d ms: %3d pulses x %d bit(s) over %d socket(s)"
                  % (peer, f["at"], f["pulses"], f["per"], f["cols"]))

    for sender in ("alice", "bob"):
        receiver = "bob" if sender == "alice" else "alice"
        P, A = pulses[sender], arrivals[receiver]
        print("\n=== %s -> %s: %d sampled pulse(s), %d arrival(s) ===" % (sender, receiver, len(P), len(A)))
        if not P or not A:
            print("  (one side never transmitted, or nothing was read)")
            continue

        by_source = defaultdict(list)
        for a in A:
            by_source[a["source"]].append(a)

        paired = []          # (pulse, arrival) matched by source port
        for p in P:
            hits = []
            for s in p["srcs"]:
                for a in by_source.get(s, []):
                    hits.append((abs(a["at"] - p["at"]), a))
            if hits:
                hits.sort(key=lambda t: t[0])
                paired.append((p, hits[0][1]))
        print("  pulses matched to an arrival by source port: %d of %d sampled" % (len(paired), len(P)))
        if not paired:
            print("  pulse src sets: %s" % [p["srcs"] for p in P[:4]])
            continue

        landed_right = sum(1 for p, a in paired if a["port"] == p["target"])
        print("  of those, arrival port == the pulse's target: %d of %d" % (landed_right, len(paired)))

        # The receiver's two candidate alphabets, as the decoder builds them.
        own = sorted(set(a["port"] for a in A))
        used = own  # same list here: both are "the ports that arrived", sorted
        print("  observed socket set (%d): %s" % (len(own), own[:12]))

        exact = 0
        close = 0
        for p, a in paired:
            if a["port"] == p["target"]:
                if own.index(a["port"]) == p["sym"]:
                    exact += 1
                elif abs(own.index(a["port"]) - p["sym"]) <= 0:
                    close += 1
        print("  index of the arrival's port in the observed set == the sender's symbol: %d of %d"
              % (exact, len(paired)))

        # The sender's symbol is an index into *its* sorted socket list; the
        # receiver only sees the subset that arrived.  Rebuild the sender's list
        # from its own targets and check the mapping against that.
        targets = sorted(set(p["target"] for p in P))
        sym_of_target = {}
        for p in P:
            sym_of_target.setdefault(p["target"], p["sym"])
        mism = 0
        checked = 0
        for t in targets:
            if t in own and t in sym_of_target:
                checked += 1
                if own.index(t) != sym_of_target[t]:
                    mism += 1
        print("  against the *fired* target list (%d sockets): %d of %d symbols disagree"
              % (len(targets), mism, checked))

        # Order: the receiver sorts by (timestamp, push order).
        order_key = {id(a): (a["at"], a["seq"]) for a in A}
        seen = {id(a): p["i"] for p, a in paired}
        ordered = [seen[id(a)] for a in sorted(A, key=lambda a: order_key[id(a)]) if id(a) in seen]
        inv = sum(1 for x in range(len(ordered)) for y in range(x + 1, len(ordered)) if ordered[x] > ordered[y])
        pairs = len(ordered) * (len(ordered) - 1) // 2
        print("  matched pulses in the order the receiver sorts them: %s" % ordered)
        print("  inversions: %d of %d pairs (%.1f%% out of order)" % (
            inv, pairs, (100.0 * inv / pairs) if pairs else 0.0))
        lat = sorted(a["at"] - p["at"] for p, a in paired)
        print("  read latency: min %d ms, median %d ms, max %d ms" % (lat[0], lat[len(lat) // 2], lat[-1]))

        # Duplicate source ports among arrivals: a pulse is one check from a fresh
        # connection, so a source that appears twice is multi-fire traffic.
        per_source = Counter(a["source"] for a in A)
        multi = {s: n for s, n in per_source.items() if n > 1}
        print("  arrival(s) from multi-fire sources: %d of %d (from %d source port(s))"
              % (sum(multi.values()), len(A), len(multi)))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out/ac-diag3.log")

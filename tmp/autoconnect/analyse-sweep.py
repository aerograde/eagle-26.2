"""What the receiver's stream actually held, against the frame the peer fired.

    ACDIAG=1 python3 tmp/autoconnect/run-peers.py 8124 300000 > out/ac-diag.log 2>&1
    python3 tmp/autoconnect/analyse-sweep.py out/ac-diag.log

The column sweep is transmitted as pulses too, so the only way to tell a stray
arrival from a symbol is to line the receiver's arrival sequence up against the
sender's own pulse log.  The sender samples every tenth pulse (`acdiag=1`) with its
index, symbol and target port, so ten pulses should be ten arrivals apart in the
receiver's sequence: a gap of eleven means one arrival in between was never fired
as a symbol.  This prints that spacing, the symbol mapping of every sampled pulse,
and the count each side's decoder saw.
"""

import re
import sys
from collections import Counter

# [alice] [autoconnect +94798ms] diag: arrival seq=12 port=50019 source=61023 at=...
PAGE = re.compile(r"^\[(\w+)\] ")
ARRIVAL = re.compile(
    r"diag: arrival seq=(\d+) port=(\d+) source=(\d+) at=(-?\d+) obs=(-?\d+) precise=(\d)")
PULSE = re.compile(r"diag: pulse i=(\d+) sym=(\d+) target=(\d+) src=([\d,]+) at=(\d+)")
INTERESTING = re.compile(
    r"sym: (column list sent|peer column list|transmitting|payload decoded|"
    r"read \d+ arrival|stream:|transmission done|no payload|peer column list has)")


def load(path):
    pages = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = PAGE.match(line)
        if not m:
            continue
        name = m.group(1)
        page = pages.setdefault(name, {"arrivals": [], "pulses": [], "notes": []})
        a = ARRIVAL.search(line)
        if a:
            page["arrivals"].append(
                {"seq": int(a.group(1)), "port": int(a.group(2)), "source": int(a.group(3)),
                 "at": int(a.group(4)), "precise": a.group(6) == "1"})
            continue
        p = PULSE.search(line)
        if p:
            page["pulses"].append(
                {"i": int(p.group(1)), "sym": int(p.group(2)), "target": int(p.group(3)),
                 "at": int(p.group(5))})
            continue
        s = INTERESTING.search(line)
        if s:
            page["notes"].append(line.strip())
    return pages


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "out/ac-diag.log"
    pages = load(path)
    for name, page in sorted(pages.items()):
        arr, pulses = page["arrivals"], page["pulses"]
        print("=" * 78)
        print("%s: %d arrival(s) logged, %d sampled pulse(s)" % (name, len(arr), len(pulses)))
        for note in page["notes"]:
            print("   %s" % note)
        if not arr or not pulses:
            continue
        # The receiver's arrival order is the order the reads reported; the seq
        # field is the index of the arrival in the engine's own list, so the
        # position of an arrival in the frame is its seq minus the first seq.
        by_port = {}
        for i, a in enumerate(arr):
            by_port.setdefault(a["port"], []).append(i)
        print("  -- sampled pulses vs the arrival sequence --")
        prev = None
        for p in pulses:
            best, bestd = None, None
            for i in by_port.get(p["target"], []):
                d = arr[i]["at"] - p["at"]
                if d < -200 or d > 4000:
                    continue
                if bestd is None or abs(d) < abs(bestd):
                    best, bestd = i, d
            if best is None:
                print("   i=%-3d sym=%d target=%d -> NO ARRIVAL" % (p["i"], p["sym"], p["target"]))
                continue
            if prev is None:
                step = ""
            else:
                d = best - prev[1]
                step = " (step %+d: %s)" % (d, "as fired" if d == 10
                        else ("%d extra arrival(s) in between" % (d - 10) if d > 10
                              else "%d pulse(s) of this frame never arrived" % (10 - d)))
            print("   i=%-3d sym=%d target=%d -> arrival #%-4d seq=%-4d at+%dms%s"
                  % (p["i"], p["sym"], p["target"], best, arr[best]["seq"], bestd, step))
            prev = (p["i"], best)
        ports = Counter(a["port"] for a in arr)
        print("  -- arrivals per port (a symbol's socket is hit ~%d times) --"
              % max(1, len(arr) // 8))
        print("   %s" % ports.most_common())
        print("  -- receipt stamps precise? --")
        print("   precise=%d, fallback=%d"
              % (sum(1 for a in arr if a["precise"]), sum(1 for a in arr if not a["precise"])))
        gaps = [arr[i]["at"] - arr[i - 1]["at"] for i in range(1, len(arr))]
        if gaps:
            small = [g for g in gaps if 0 <= g < 400]
            print("  -- gaps between consecutive arrivals: min %d ms, median %d ms, "
                  "under 400 ms: %d of %d --"
                  % (min(gaps), sorted(gaps)[len(gaps) // 2], len(small), len(gaps)))


if __name__ == "__main__":
    main()

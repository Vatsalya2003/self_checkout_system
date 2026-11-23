#!/usr/bin/env bash
# Demo step 7: the measured numbers, from the saved runs (no load is generated).
# Averages the repeats in reports/rate-limiter/phase4/runs/runs.csv and
# reports/rate-limiter/phase5/runs/runs.csv (made by scripts/ratelimit-bench/summarize.py).
source "$(dirname "$0")/_common.sh"

header "STEP 7  The numbers (60-second runs, packaged jars, idle machine, averaged repeats)"
python3 - "$R/reports/rate-limiter" <<'EOF'
import csv, sys
from collections import defaultdict
from pathlib import Path
base = Path(sys.argv[1])

def groups(path):
    g = defaultdict(list)
    if path.exists():
        for r in csv.DictReader(open(path)):
            g[(r["label"].rsplit("-r", 1)[0])].append(r)
    return g

def avg(rows, col):
    v = [float(r[col]) for r in rows if r.get(col) not in (None, "")]
    return sum(v) / len(v) if v else float("nan")

p4 = groups(base / "phase4/runs/runs.csv")
print("\nThroughput and what admitted customers see (limit 650/s)")
print(f"  {'stations':>8} | {'mode':<28} | {'tx/s':>6} | {'admitted/s':>10} | {'pay p50':>8} | {'pay p99':>8} | {'5xx':>6} | {'no answer':>9} | {'other':>7}")
names = {"off": "no limiter", "on": "limiter, course client", "polite": "limiter, polite client"}
for st in (100, 500, 1000, 2000, 4000):
    for mode in ("off", "on", "polite"):
        rows = p4.get(f"{mode}-{st}")
        if not rows:
            continue
        adm = avg(rows, "rl_allowed_per_s")
        print(f"  {st:>8} | {names[mode]:<28} | {avg(rows,'tx_per_s'):>6.0f} | "
              f"{'-' if adm != adm else f'{adm:.0f}':>10} | {avg(rows,'complete_p50_ms'):>6.0f}ms | "
              f"{avg(rows,'complete_p99_ms'):>6.0f}ms | {avg(rows,'err_5xx'):>6.0f} | {avg(rows,'err_no_answer'):>9.0f} | {avg(rows,'err_other'):>7.0f}")
    print(f"  {'':-<8}-+-{'':-<28}-+-{'':-<6}-+-{'':-<10}-+-{'':-<8}-+-{'':-<8}-+-{'':-<6}-+-{'':-<9}-+-{'':-<7}")
print("  5xx / no answer / other = failed requests per 60 s run (429s not counted). The polite client retries")
print("  its failures; at 4000 stations its 'other' are nginx running out of local ports to checkout.")

def typical_check_p99(rows):
    # median over the 5-second log intervals of each run (the first interval
    # holds the start-up burst; the cumulative histogram would report that)
    import re, statistics
    vals = []
    for r in rows:
        f = base / "phase5/runs" / r["label"] / "limiter-log.txt"
        if f.exists():
            vals += [int(m) for m in re.findall(r"p99<=(\d+)us", f.read_text())]
    return statistics.median(vals) if vals else float("nan")

p5 = groups(base / "phase5/runs/runs.csv")
if p5:
    print("\nCost when NOT throttling (limit set to 100000/s, so every request is checked and none refused)")
    print(f"  {'stations':>8} | {'':<14} | {'tx/s':>6} | {'start p50':>9} | {'start p99':>9} | {'check p99*':>10} | {'checkout CPU':>12}")
    for st in (100, 500):
        for mode, name in (("off", "limiter off"), ("high", "limiter on")):
            rows = p5.get(f"{mode}-{st}")
            if not rows:
                continue
            chk = typical_check_p99(rows)
            print(f"  {st:>8} | {name:<14} | {avg(rows,'tx_per_s'):>6.0f} | {avg(rows,'start_p50_ms'):>7.2f}ms | "
                  f"{avg(rows,'start_p99_ms'):>7.2f}ms | {'-' if chk != chk else f'<={chk:.0f}us':>10} | {avg(rows,'cpu_checkout_pct'):>11.0f}%")
    print("  * check p99 = median over the runs' 5-second log intervals. The first interval of a 500-station")
    print("    run holds the start-up burst, where a few checks hit the 20 ms Redis timeout and the local")
    print("    fallback decided them (nothing was refused: the limit was 100000/s).")
EOF
echo
say "Redis right now: $(redis-cli INFO memory | grep -E '^used_memory_human' | tr -d '\r') | limiter keys: $(redis-cli --scan --pattern 'rl:*' | wc -l | tr -d ' ')"

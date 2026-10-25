#!/usr/bin/env python3
"""Results table across experiment dirs: table.py <experiments-dir> <variant>..."""
import glob, json, os, re, sys

E = sys.argv[1]


def runs(variant, kind):
    out = []
    for rep in sorted(glob.glob(f"{E}/{variant}/{kind}-run*-report.json")):
        r = json.load(open(rep))
        ops = {o["operation"]: o for o in r["operations"]}
        c = ops["COMPLETE_TRANSACTION"]
        errs = sum(o["errorCount"] for o in r["operations"])
        chk = open(rep.replace("-report.json", "-checks.txt")).read().strip().splitlines()[-1]
        summ = open(rep.replace("-report.json", "-summary.txt")).read()
        pg = re.search(r"cpu postgres\s+([\d.]+)", summ)
        out.append(dict(tps=r["transactionsPerSecond"], p50=c["p50Ms"], p95=c["p95Ms"], p99=c["p99Ms"],
                        errs=errs, inv=chk, pgcpu=float(pg.group(1)) if pg else 0))
    return out


base = {k: runs("current", k) for k in ("default", "stress")}
bmean = {k: sum(x["tps"] for x in v) / len(v) for k, v in base.items() if v}

print("| variant | load | tx/s (run 1 / run 2) | mean tx/s | vs current | complete p50 / p95 / p99 ms (mean) | errors | invariant | postgres CPU % |")
print("|---|---|---|---|---|---|---|---|---|")
for v in sys.argv[2:]:
    for k in ("default", "stress"):
        rs = runs(v, k)
        if not rs:
            continue
        m = sum(x["tps"] for x in rs) / len(rs)
        avg = lambda f: sum(x[f] for x in rs) / len(rs)
        tpsl = " / ".join("%.1f" % x["tps"] for x in rs)
        delta = "—" if v == "current" else f"{(m / bmean[k] - 1) * 100:+.1f} %"
        print(f"| {v} | {k} | {tpsl} | {m:.1f} | {delta} | "
              f"{avg('p50'):.1f} / {avg('p95'):.1f} / {avg('p99'):.1f} | {sum(x['errs'] for x in rs)} | "
              f"{', '.join(x['inv'] for x in rs)} | {avg('pgcpu'):.0f} |")

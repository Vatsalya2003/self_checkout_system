#!/usr/bin/env python3
"""One row per measured run, from the files run.sh saved.

    python3 scripts/ratelimit-bench/summarize.py reports/rate-limiter/phase4/runs

Writes runs.csv and runs.md next to the run folders and prints the table.
Every number comes from a file in the run's folder:
  client-report.json   throughput and latency percentiles (from the client)
  client-errors.txt    failed transactions by kind (429 / 5xx / no answer)
  limiter-stats.json   what the limiter allowed and refused (checkout's counters)
  samples.csv          per-second server samples: CPU, memory, threads, Redis
  invariant.txt        the cross-database check after the run
"""
import csv
import json
import re
import sys
from pathlib import Path

OPS = {"START_TRANSACTION": "start", "SCAN_ITEM": "scan", "COMPLETE_TRANSACTION": "complete"}


def read_samples(path):
    with open(path) as f:
        rows = [r for r in csv.DictReader(f) if r["t"]]
    return rows


def cpu_percent(rows, col):
    """Average CPU % (of one core) over the run, from the first and last sample."""
    good = [r for r in rows if r[col] not in ("", None)]
    if len(good) < 2:
        return None
    dt = float(good[-1]["t"]) - float(good[0]["t"])
    return round((float(good[-1][col]) - float(good[0][col])) / dt * 100, 1) if dt > 0 else None


def errors_by_kind(text):
    kinds = {"429": 0, "5xx": 0, "no_answer": 0, "other": 0}
    for line in text.splitlines():
        m = re.match(r"\s+(\d+)\s+(.+?)\s{2,}(\w+)$", line)
        if not m:
            continue
        n, kind = int(m.group(1)), m.group(2)
        if kind.startswith("429"):
            kinds["429"] += n
        elif re.match(r"5\d\d", kind):
            kinds["5xx"] += n
        elif kind.startswith("no answer"):
            kinds["no_answer"] += n
        else:
            kinds["other"] += n
    return kinds


def summarize(d):
    machine = (d / "machine.txt").read_text()
    meta = dict(re.findall(r"(\w+)=(\S+)", machine.splitlines()[0]))
    rep = json.loads((d / "client-report.json").read_text())
    row = {"label": d.name, "client": meta["client"], "stations": int(meta["stations"]),
           "limiter": meta["limiter"], "load_before": re.search(r"1-min load before: ([\d.]+)", machine).group(1)}
    if meta["client"] == "course":
        row["tx_per_s"] = round(rep["transactionsPerSecond"], 1)
        for op in rep["operations"]:
            k = OPS[op["operation"]]
            row[f"{k}_ok"] = op["successCount"]
            row[f"{k}_err"] = op["errorCount"]
            for p in ("p50", "p95", "p99"):
                row[f"{k}_{p}_ms"] = round(op[f"{p}Ms"], 2)
    else:
        row["tx_per_s"] = rep["transactionsPerSecond"]
        row["start_ok"], row["start_err"] = rep["admitted"], rep["throttled"]
        row["scan_ok"], row["scan_err"] = rep["scans"], 0
        row["complete_ok"], row["complete_err"] = rep["completed"], rep["errors"]
        for k, src in (("start", "startAdmittedMs"), ("scan", "scanMs"), ("complete", "completeMs")):
            for p in ("p50", "p95", "p99"):
                row[f"{k}_{p}_ms"] = round(rep[src][p], 2)
        row["start429_p50_ms"] = round(rep["start429Ms"]["p50"], 2)
        row["start429_p99_ms"] = round(rep["start429Ms"]["p99"], 2)
    errs = errors_by_kind((d / "client-errors.txt").read_text())
    if meta["client"] == "polite":
        errs["429"] = rep["throttled"]
        errs["other"] += rep["errors"]
    row.update({f"err_{k}": v for k, v in errs.items()})
    stats = json.loads((d / "limiter-stats.json").read_text())
    r = stats["rules"][0]
    dur = int(meta["duration"].rstrip("s"))
    row["rl_allowed_per_s"] = round(r["allowed"] / dur, 1) if meta["limiter"] == "on" else ""
    row["rl_rejected_per_s"] = round(r["rejected"] / dur, 1) if meta["limiter"] == "on" else ""
    row["rl_no_redis_share"] = (round(r["rejectedWithoutRedisCall"] / r["rejected"], 3)
                                if meta["limiter"] == "on" and r["rejected"] else "")
    row["rl_check_p99_us_at_most"] = stats["checkP99MicrosAtMost"] if meta["limiter"] == "on" else ""
    s = read_samples(d / "samples.csv")
    row["checkout_rss_mb_max"] = max(int(x["checkout_rss_mb"] or 0) for x in s)
    row["checkout_threads_max"] = max(int(x["checkout_threads"] or 0) for x in s)
    for col, name in (("checkout_cpu_s", "cpu_checkout"), ("nginx_cpu_s", "cpu_nginx"),
                      ("postgres_cpu_s", "cpu_postgres"), ("redis_cpu_s", "cpu_redis")):
        row[f"{name}_pct"] = cpu_percent(s, col)
    row["redis_used_mb_max"] = max(float(x["redis_used_mb"] or 0) for x in s)
    row["redis_rl_keys_max"] = max(int(x["redis_rl_keys"] or 0) for x in s)
    nginx = (d / "nginx-error.log").read_text()
    row["nginx_errors"] = len(nginx.splitlines())
    row["nginx_worker_connections"] = "worker_connections are not enough" in nginx
    inv = (d / "invariant.txt").read_text()
    row["invariant"] = "PASS" if re.search(r"^PASS", inv, re.M) and "FAIL" not in inv else "FAIL"
    return row


def main():
    root = Path(sys.argv[1])
    rows = [summarize(d) for d in sorted(root.iterdir()) if (d / "summary.txt").exists()]
    cols = []
    for r in rows:
        cols += [c for c in r if c not in cols]
    with open(root / "runs.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)
    show = ["label", "client", "stations", "limiter", "tx_per_s", "start_p99_ms", "scan_p99_ms", "complete_p50_ms",
            "complete_p99_ms", "err_429", "err_5xx", "err_no_answer", "err_other", "rl_allowed_per_s",
            "checkout_rss_mb_max", "checkout_threads_max", "cpu_checkout_pct", "nginx_errors", "invariant", "load_before"]
    lines = ["| " + " | ".join(show) + " |", "|" + "---|" * len(show)]
    for r in rows:
        lines.append("| " + " | ".join(str(r.get(c, "")) for c in show) + " |")
    (root / "runs.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()

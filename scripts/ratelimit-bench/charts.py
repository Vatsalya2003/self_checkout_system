#!/usr/bin/env python3
"""Before/after charts for the rate limiter, from summarize.py's runs.csv and
each run's samples.csv. Needs matplotlib.

    python3 scripts/ratelimit-bench/charts.py reports/rate-limiter/phase4/runs reports/rate-limiter/charts LIMIT

Run labels are <mode>-<stations>-r<repeat>, mode one of:
  off     course load client, limiter off
  on      course load client, limiter on (the client retries every 429 at once)
  polite  polite client, limiter on (waits at least as long as the server says, backs off)
Repeats of the same mode and station count are averaged.
"""
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

COLOR = {"off": "#e34948", "on": "#2a78d6", "polite": "#1baf7a"}
NAME = {"off": "no limiter", "on": "limiter on, course client (retry storm)", "polite": "limiter on, polite client (backs off)"}
plt.rcParams.update({"figure.facecolor": "#fcfcfb", "axes.facecolor": "#fcfcfb", "font.size": 9,
                     "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True,
                     "grid.color": "#e4e3df", "legend.frameon": False, "axes.titlelocation": "left",
                     "axes.titleweight": "bold"})


def load(runs_dir):
    with open(runs_dir / "runs.csv") as f:
        rows = list(csv.DictReader(f))
    groups = defaultdict(list)
    for r in rows:
        m = re.match(r"(off|on|polite)-(\d+)-r\d+$", r["label"])
        if m:
            groups[(m.group(1), int(m.group(2)))].append(r)
    return groups


def mean(rows, col):
    vals = [float(r[col]) for r in rows if r.get(col) not in ("", None)]
    return sum(vals) / len(vals) if vals else None


def series(groups, mode, col):
    xs = sorted(s for (m, s) in groups if m == mode)
    return xs, [mean(groups[(mode, s)], col) for s in xs]


def save(fig, out, name):
    out.mkdir(parents=True, exist_ok=True)
    fig.savefig(out / f"{name}.png", dpi=180, bbox_inches="tight")
    fig.savefig(out / f"{name}.svg", bbox_inches="tight")
    plt.close(fig)


def throughput_and_errors(groups, out, limit):
    fig, (a, b) = plt.subplots(1, 2, figsize=(10, 3.6))
    for mode in ("off", "on", "polite"):
        xs, ys = series(groups, mode, "tx_per_s")
        if xs:
            a.plot(xs, ys, marker="o", color=COLOR[mode], label=NAME[mode], linewidth=2)
        xs, _ = series(groups, mode, "tx_per_s")
        failed = [sum(mean(groups[(mode, s)], c) or 0 for c in ("err_5xx", "err_no_answer", "err_other")) for s in xs]
        if xs:
            b.plot(xs, failed, marker="o", color=COLOR[mode], label=NAME[mode], linewidth=2)
    a.axhline(limit, color="#52514e", linestyle=":", linewidth=1)
    a.text(a.get_xlim()[0], limit, f"  limit {limit}/s", va="bottom", fontsize=8, color="#52514e")
    a.set_xscale("log")
    a.set_xlabel("stations (log scale)")
    a.set_ylabel("completed transactions per second")
    a.set_title("Throughput")
    b.set_xscale("log")
    b.set_xlabel("stations (log scale)")
    b.set_ylabel("failed transactions per run (not counting 429)")
    b.set_title("Real failures: 5xx, timeouts, refused connections")
    a.legend(loc="lower left", fontsize=8)
    fig.tight_layout()
    save(fig, out, "throughput-and-failures")


def latency(groups, out, pct="p99"):
    fig, axes = plt.subplots(1, 3, figsize=(11, 3.4), sharey=False)
    for ax, (op, title) in zip(axes, [("start", "Start (admitted only)"), ("scan", "Scan"), ("complete", "Pay")]):
        for mode in ("off", "on", "polite"):
            xs, ys = series(groups, mode, f"{op}_{pct}_ms")
            if xs:
                ax.plot(xs, ys, marker="o", color=COLOR[mode], label=NAME[mode], linewidth=2)
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlabel("stations (log scale)")
        ax.set_title(f"{title}: {pct}")
    axes[0].set_ylabel("ms (log scale)")
    axes[0].legend(loc="upper left", fontsize=7)
    fig.tight_layout()
    save(fig, out, f"latency-{pct}")


def timeline(runs_dir, out, label, limit, name):
    path = runs_dir / label / "samples.csv"
    if not path.exists():
        return
    with open(path) as f:
        rows = [r for r in csv.DictReader(f) if r["rl_allowed"] not in ("", None)]
    t0 = float(rows[0]["t"])
    xs, adm, rej = [], [], []
    for p, c in zip(rows, rows[1:]):
        dt = float(c["t"]) - float(p["t"])
        xs.append(float(c["t"]) - t0)
        adm.append((int(c["rl_allowed"]) - int(p["rl_allowed"])) / dt)
        rej.append((int(c["rl_rejected"]) - int(p["rl_rejected"])) / dt)
    fig, (a, b) = plt.subplots(1, 2, figsize=(10, 3.2))
    a.plot(xs, adm, color=COLOR["on"], linewidth=2, label="admitted / s")
    a.axhline(limit, color="#52514e", linestyle=":", linewidth=1, label=f"configured limit ({limit}/s)")
    a.set_xlabel("seconds into the run")
    a.set_ylabel("new transactions per second")
    a.set_title("Admitted rate")
    a.legend(fontsize=8)
    b.plot(xs, rej, color=COLOR["off"], linewidth=2)
    b.set_xlabel("seconds into the run")
    b.set_ylabel("429s per second")
    b.set_title("Refused (429) rate")
    fig.suptitle(label, x=0.01, ha="left", fontsize=9, color="#52514e")
    fig.tight_layout()
    save(fig, out, name)


if __name__ == "__main__":
    runs_dir, out, limit = Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3])
    g = load(runs_dir)
    throughput_and_errors(g, out, limit)
    latency(g, out, "p50")
    latency(g, out, "p99")
    for label in sys.argv[4:]:
        timeline(runs_dir, out, label, limit, f"timeline-{label}")
    print("charts in", out)

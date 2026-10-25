# Week 5 re-run and explored settings (2026-10-24)

These runs came after the submitted Week 5 results (`reports/microservices/default/`,
`reports/microservices/stress/`, 2026-10-23). They had two purposes:
- re-run the submitted version to check that its result reproduces;
- try one database-side setting, group commit.

**None of this changed the submitted version.** Group commit was left off, because these
runs can't show whether it helps (see "Why group commit is inconclusive").

## How each run was made

Same procedure as the submitted runs: packaged jars, through the gateway on :8080, the
course load client, then `scripts/check-invariant-micro.sh`. The script is
[`harness/run-load.sh`](harness/run-load.sh), and [`harness/run-set.sh`](harness/run-set.sh)
runs four per setting, in this order: default, stress, default, stress.

For every run, the harness:
1. waits up to 6 minutes for the 1-minute load average to drop below 3.0;
2. checks that at least 3 GB of disk is free;
3. runs `stop-all.sh`, `db/reset.sh` and `start-all.sh`;
4. records load, per-process CPU, the services' metric lines and the client report.

Default is 10 stations × 60 s; stress is 100 stations × 120 s.

## Results

| Setting | Run | tx/s | COMPLETE p50 / p95 / p99 ms | Errors | Invariant | Load before (1 min) |
|---|---|---|---|---|---|---|
| **current (re-run)** | default 1 | **803.2** | 8.1 / 25.2 / 39.1 | 0 | PASS | 2.52 |
| **current (re-run)** | default 2 | **809.5** | 7.8 / 25.2 / 39.9 | 0 | PASS | 2.84 |
| **current (re-run)** | stress 1 | **817.0** | 97.4 / 246.8 / 364.6 | 0 | PASS | 3.03 |
| **current (re-run)** | stress 2 | **814.2** | 97.2 / 248.2 / 375.4 | 0 | PASS | 3.21 |
| commit_delay 10 µs | default 1 | 808.8 | 7.9 / 24.3 / 37.8 | 0 | PASS | 3.56 |
| commit_delay 10 µs | default 2 | 841.3 | 7.5 / 23.8 / 36.0 | 0 | PASS | 3.55 |
| commit_delay 10 µs | stress 1 | 732.6 | 105.8 / 285.3 / 468.8 | 0 | PASS | 3.49 |
| commit_delay 10 µs | stress 2 | 800.0 | 99.1 / 249.3 / 370.6 | 0 | PASS | 2.88 |
| commit_delay 50 µs | default 1 | 839.2 | 7.7 / 23.7 / 34.9 | 0 | PASS | 4.08 |
| commit_delay 50 µs | default 2 | 823.9 | 7.8 / 24.3 / 36.3 | 0 | PASS | 3.53 |
| commit_delay 50 µs | stress 1 | 761.8 | 101.4 / 271.7 / 451.1 | 0 | PASS | **29.05** |
| commit_delay 50 µs | stress 2 † | *637.0* | 120.9 / 332.0 / 531.0 | *501* | PASS | 3.20 |
| commit_delay 100 µs | default 1 | 841.3 | 7.6 / 23.4 / 35.5 | 0 | PASS | 3.46 |
| commit_delay 100 µs | stress 1 | 829.8 | 95.2 / 241.8 / 359.3 | 0 | PASS | 3.38 |

`commit_siblings` was 5 and `synchronous_commit` was on in every run. Each summary file's
`postgres:` line records the settings.

**Means:**

| | default | stress |
|---|---|---|
| submitted (2026-10-23, runs 1 and 2) | 852.4 / 826.9 → 839.7 | 806.0 / 764.8 → 785.4 |
| **current, re-run** | 806.3 | 815.6 |
| commit_delay 10 µs | 825.1 (+2.3 %) | 766.3 (−6.0 %) |
| commit_delay 50 µs | 831.5 (+3.1 %) | 761.8 (one valid run) |
| commit_delay 100 µs | 841.3 (one run) | 829.8 (one run) |

The percentages are against today's re-run.

† **Not a group-commit result.** This 120 s run took 1,105 s of real time: the laptop
stalled or slept partway through (swap was at 3 GB of an 8 GB machine). The errors are nginx
504s and client request timeouts from that stall. Even so, the cross-database invariant
passed after it.

The 100 µs setting has one run of each kind: the experiments were stopped before the rest.

## What the re-run shows

The submitted version reproduces:
- **Throughput:** 806 tx/s default and 816 tx/s stress, against 840 and 785 for the
  submitted pairs' means;
- **Reliability:** 0 errors in all four runs, and the cross-database invariant passed every
  time;
- **Saga:** no retries, rollbacks or ambiguous payments;
- **Scans:** none dropped.

## Group commit, in plain English

Every payment ends with a commit, and PostgreSQL makes a commit durable by flushing the
write-ahead log (WAL) to disk before it answers.

`commit_delay = N µs` tells a committing transaction to wait up to N microseconds before
flushing, so that other transactions committing at the same moment can share that one flush.
`commit_siblings = 5` means the wait only happens when at least 5 other transactions are
active at that moment, so a quiet server never waits for nothing.

Commits stay fully durable: no transaction is acknowledged before its WAL is on disk. The
setting only lets several of them share one flush. `synchronous_commit` was left on
throughout.

## Why group commit is inconclusive

- **The machine wasn't idle.** Every group-commit run started on a busier machine than the
  re-run. A browser tab was using a full core, and the load before each run was 2.9–4.1,
  against 2.5–3.2 for the re-run.
- **The differences go both ways.** −6 % to +4 %, both in favour and against, which is
  within what that noise can do.
- **Too few runs.** The 100 µs setting has one run of each kind.

Whether group commit helps would need all settings re-run back to back on an idle machine.

## Not measured

Two more changes were prototyped but never measured, so they aren't part of this version:
- a **separate PostgreSQL instance for `inventory_db`** (port 5433, its own data directory
  and WAL);
- a **circuit breaker** on checkout's stock-commit call to inventory.

## Files

- `current/`, `group-commit-10/`, `group-commit-50/`, `group-commit-100/`: per run,
  `-start.txt`, `-client.txt`, `-report.json`, `-summary.txt` (load, CPU per process, saga,
  scan and analytics metric lines, Postgres settings) and `-checks.txt` (the invariant).
- `harness/`: the scripts that made them. `table.py` prints the comparison table.

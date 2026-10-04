# Performance results

Every number here comes from a real run on this laptop (8 cores, PostgreSQL 16
via Homebrew, server and load client on the same machine). Raw reports are kept
under `reports/perf/<phase>/`. Nothing is hand-written or rounded up, and no run
is discarded — reverted phases keep their row.

**Throughput is a closed loop:** the load client runs a fixed number of stations,
each looping start → ~10.5 scans → complete. So
`tx/sec ≈ stations ÷ time per full cycle`. Making any step of the cycle shorter
raises throughput; nothing else does.

---

## Measurement note: how the server is launched matters a lot

The Phase 0 baseline was first taken with `./mvnw spring-boot:run`, which gave
**353.1 / 351.2 tx/s** — about 17 % below the 422 / 417 recorded for this same
commit in `analysis/COMPARISON.md`. The code was not the difference: the Spring
Boot Maven plugin starts the JVM with **`-XX:TieredStopAtLevel=1`**, a
fast-startup default that caps the JIT at C1 and never lets the optimising C2
compiler run.

Same commit, same machine, back to back:

| Launcher | tx/s | SCAN p50 | COMPLETE p50 |
|---|---|---|---|
| `./mvnw spring-boot:run` (`-XX:TieredStopAtLevel=1`) | 353.1 / 351.2 | 1.06 ms | 16.98 ms |
| `java -Xmx1g -jar target/…​.jar` (full JIT) | **424.5** | 0.83 ms | 13.22 ms |

That is **+20 % for free**, and it reconciles with the comparison document.
**Every measurement from here on uses the packaged jar**, which is also how the
comparison measured both servers. Measuring under a crippled JIT would have
understated the baseline and mis-ranked which costs matter.

---

## Results

| Phase | Change | tx/s run 1 | tx/s run 2 | SCAN p50/p99 | COMPLETE p50/p99 | Errors | Invariant | CPU-bound | Kept |
|---|---|---|---|---|---|---|---|---|---|
| Baseline | `pipeline-v1`, unchanged | 404.6 | 407.2 | 0.85 / 2.86 | 13.82 / 50.43 | 0 | PASS | no (~505 % of 800 %) | — |
| Baseline stress | 100 stations, 120 s | 420.3 | 431.9 | 12.52 / 34.61 | 90.84 / 328.56 | 0 | PASS | no (~500 % of 800 %) | — |
| 0.5 | Fix scan/complete race: `scan()` locks the transaction row | 422.4 | 413.3 | 0.85 / 2.65 | 13.22 / 45.19 | 0 | PASS | no (~506 % of 800 %) | **kept** |
| 2+3 | Catalog cached in memory; open baskets in memory (start + scan do no DB) | 669.0 | 670.2 | 0.09 / 0.38 | 15.59 / 50.02 | 0 | PASS | no (~294 % of 800 %) | **kept** |
| 5 (first attempt) | Bulk lock with plain `FOR UPDATE` | 2.9 | 5.0 | 0.64 / 5.41 | 1148 / 9146 | **9,239** | PASS | no (~20 %) | **reverted** |
| 5 | Lock late + batch: 1 bulk `FOR NO KEY UPDATE` + 3 bulk writes | 742.1 | 677.1 (3rd: 744.9) | 0.09 / 0.45 | 6.11 / 56.61 | 0 | PASS | no (~344 % of 800 %) | **kept** |
| 5 stress | 100 stations, 120 s | 849.3 | 650.7 | 0.09 / 0.34 | 93.45 / 354.11 | 0 | PASS | no (~320 %) | see warning below |
| 4 | Raw scans optional (off), non-blocking tee, batched sink on its own pool | 997.5 | 989.5 | 0.09 / 0.36 | 4.94 / 39.38 | 0 | PASS | — | **kept** |
| 4 stress | 100 stations, 120 s | 993.3 | 999.8 | 0.10 / 0.33 | 80.56 / 286.58 | 0 | PASS | — | **kept** |

### Reading the phases

**Baseline.** 404.6 / 407.2 tx/s at 10 stations, 420.3 / 431.9 at 100. Throughput
barely moves between 10 and 100 stations, which is the signature of a shared
ceiling rather than a lack of load. CPU sits around 505 % of the 800 % available,
so **the machine is not CPU-saturated** — the limit is waiting, not computing.

**Phase 0.5 — race fix.** `scan()` now takes the same transaction row lock that
`complete()` takes. Before the fix, `scripts/test-scan-complete-race.py` failed
hard: **199 of 200 paid baskets reverted to `OPEN`**, and stock removed (404)
did not match units applied (4), because those baskets no longer counted as
COMPLETED. Hibernate writes every column on an update, so a scan that lost the
race wrote the stale `OPEN` status back over a basket that had already been paid
for and had its stock taken — and it could then be paid for a second time.

After the fix all four assertions pass. The cost is one extra uncontended row
lock per scan: the load client drives each basket from a single station
sequentially, so two requests never contend for the same transaction row.
Measured effect is **within noise** (422.4 / 413.3 against a 404.6 / 407.2
baseline — if anything slightly faster, which is just run-to-run variation).
This is a correctness fix that happens to be free.

---

## Phase 1 — measured statement profile

Counted with Postgres' own statement log, enabled **only for this server's
sessions** by passing `options=-c log_statement=all` in the JDBC URL on the
command line. No config file was edited and the global setting is still
`log_statement = none`.

Basket shape, measured over a real 25 s run (8,791 baskets), not estimated:
**L = 9.32 distinct SKUs**, 10.47 units, and **66.4 % of baskets contain
`SKU-000001`**.

| Operation | Statements | Breakdown |
|---|---|---|
| `POST /transactions` (start) | **4** | BEGIN · SELECT transactions · INSERT transactions · COMMIT |
| `POST /{id}/items` (scan, new line) | **8** request-path + 3 background | BEGIN · SELECT tx `FOR UPDATE` · SELECT item · SELECT line · SELECT line *again* · INSERT line · UPDATE tx (all 7 columns) · COMMIT |
| `POST /{id}/items` (scan, SKU already in basket) | **7** request-path + 3 background | same, minus one line SELECT, UPDATE line instead of INSERT |
| `POST /{id}/complete` (L distinct SKUs) | **3L + 5** → **≈ 33** at L = 9.32 | BEGIN · SELECT tx `FOR UPDATE` · SELECT lines · L × SELECT item `FOR UPDATE` · UPDATE tx · L × UPDATE line · L × UPDATE item · COMMIT |
| `GET /transactions/{id}` | 4 | |
| `GET /items` | 1 | |
| `GET /analytics/popular-items` | 3 | |
| `GET /inventory/low-stock` | 3 | |

**Statements held inside the item locks — the number that sets the stress
ceiling.** For L = 3 the first item lock is taken at statement 4 and COMMIT is
statement 14, so **11 statements run while the first-locked row is held**, 8 of
them after every lock is already taken. In general that is **3L + 2 ≈ 30
statements** for an average basket. Because locks are taken in ascending SKU
order and `SKU-000001` sorts first, the hottest row in the system — in two
thirds of all baskets — is the one held across that entire window.

**Cycle arithmetic (checks out against the measured baseline).** 10 stations at
404.6 tx/s is a 24.7 ms cycle. The measured means add up to it exactly:

| Step | Mean | Share of cycle |
|---|---|---|
| start | 0.61 ms | 2.5 % |
| 10.47 scans × 0.96 ms | 10.05 ms | 40.7 % |
| complete | 14.02 ms | 56.8 % |
| **total** | **24.68 ms** | — |

About **120 request-path statements per checkout cycle**.

**Not CPU-bound.** Across every baseline run CPU sat at ~505 % of the 800 %
available (server ~200 %, Postgres ~230 %, load client ~75 %). The server is
waiting on round trips, not computing — which is exactly why cutting the
*number* of statements is the right lever.

### Two things the profile shows that the plan did not predict

1. **`start` wastes a SELECT.** Spring Data's `save()` sees a non-null assigned
   id (our UUID), concludes the entity is not new, and issues a `merge` —
   SELECT then INSERT. The same thing causes the *two* `transaction_items`
   SELECTs on a new-line scan. Phase 3 removes both paths entirely.
2. **Hibernate rewrites whole rows.** `update items set name=$1,price=$2,stock=$3`
   rewrites the name and price of an item on every single stock change, and the
   transaction row is rewritten across all 7 columns on every scan. That is
   also the mechanism behind the Phase 0.5 race bug.

**Phases 2 + 3 — catalog cache and in-memory baskets (measured together).**
The catalog cache alone is a prerequisite, not a win: it removes one SELECT
from a scan that was doing eight. Holding open baskets in memory removes the
other seven, and all four statements of `start`. Together: **404.6 → 669.0 tx/s,
+65 %**. Scan p50 fell 0.85 → 0.09 ms and start p50 0.50 → 0.10 ms, because
neither endpoint touches Postgres any more. Total CPU fell from ~505 % to
~294 % of 800 % — the work did not move, it stopped existing. Complete got
slightly *slower* (13.82 → 15.59 ms p50), which is expected: it now writes the
header and all the lines that scans used to write incrementally. After this,
complete is **91 % of the cycle**.

**Phase 5, first attempt — reverted, and the most instructive failure here.**
The bulk lock was written as `SELECT ... WHERE sku IN (...) ORDER BY sku FOR
UPDATE`. Throughput collapsed to **2.9 tx/s with 9,239 errors** and the logs
filled with `ERROR: deadlock detected`.

The cause is a lock-mode detail that is easy to miss. `scan_log`,
`transaction_items` and `low_stock_alerts` all have a foreign key to `items`,
and Postgres takes a **`FOR KEY SHARE`** lock on the parent row whenever it
validates one. `FOR KEY SHARE` is compatible with `FOR NO KEY UPDATE` but
**conflicts with `FOR UPDATE`**. Hibernate's `PESSIMISTIC_WRITE` had been
emitting `for no key update` all along, so checkouts and the analytics
scan-log writer never contended. Writing the "obvious" `FOR UPDATE` made every
scan-log batch fight the checkout holding those rows, and they deadlocked.

Changing one clause to `FOR NO KEY UPDATE` — the exact mode Hibernate used —
restored it. `FOR NO KEY UPDATE` still excludes other checkouts from the same
row, which is all the stock invariant requires.

**Phase 5 — lock late, commit early.** Complete went from `3L + 5` statements
to a fixed **6**, and from `3L + 2 ≈ 30` statements inside the item locks to
**4**. Throughput **669 → 742.1 / 677.1 / 744.9** (a third run was added
because the first two spanned 9.6 %); taking the median, roughly **+8 %** at 10
stations. The far bigger effect is at 100 stations: **420 → 849.3 tx/s**, and
complete p50 fell 90.84 → 93.45 ms while serving twice the transactions.

---

## Phase 4 — the analytics pipeline could not keep up with the faster checkout

Phases 2–5 roughly doubled checkout, which pushed the scan rate to ~10,400
items/s. The pipeline had been sized for Week 2's ~3,600/s, and two different
stages buckled. They were diagnosed separately because they are separate bugs.

### The sink was the real bottleneck, and it was waiting, not working

The earlier stop-report blamed the scan-log writer. That was wrong for stress
run 1, where `scanLogPipe` peaked at 22,335 / 65,536 — not full — while
`rankPipe` and `sinkPipe` were both full with 1,299 sink full-waits. The sink
was writing ~18 windows/s at ~55 ms each and backing up the whole
window → rank branch behind it.

So the sink was instrumented to split its cost three ways instead of guessing.
Measured on a 100-station run with the writers still sharing the main pool:

| Where the sink's time went | per window |
|---|---|
| waiting for a pooled connection | **66,480 µs** |
| executing the INSERT | 230 µs |
| commit | 156 µs |

**99.4 % of it was queueing.** The write itself takes under half a millisecond.
At 100 stations, checkout keeps the 32-connection pool saturated while it waits
on item row locks, and the sink was simply at the back of that queue.

Two fixes, measured in that order:

1. **Batch the sink** (`BatchingLoop`, now shared with the scan-log writer):
   drain everything waiting on `sinkPipe` and write it in one multi-row INSERT
   and one commit. This is what removed the backpressure — `sinkPipe` max depth
   went from **256 / 256 (full, 1,299 full-waits)** to **1 / 4096**.
2. **Give the pipeline writers their own 2-connection pool**
   (`AnalyticsWriterPool`). This is what removed the wait:

| | shared pool | dedicated pool |
|---|---|---|
| pool wait | 66,480 µs | **11 µs** |
| execute | 230 µs | 204 µs |
| commit | 156 µs | 216 µs |
| **total per window** | **66,866 µs** | **431 µs** |

That is **155× faster per window**. It did **not** raise checkout throughput
(1001.5 tx/s before vs 979.4 immediately after, both inside the run-to-run
spread seen below) — the sink only took a connection ~20 times a second, so
checkout was never really waiting on it. It is kept because it makes the
analytics branch independent of checkout pressure, which is the whole point of
the pipeline, and because it matters enormously with raw scans switched on,
where the writer does ~1.2 M inserts instead of 2,500.

### The run-to-run spread closed on its own

Phase 5's stress runs were 849.3 and 650.7 — a 23 % gap, which made it
impossible to say whether a change had helped. After Phase 4 the two stress
runs were **993.3 and 999.8, a 0.65 % gap**. The earlier spread was the
pipeline backing up to different depths on different runs and dragging the
checkout path with it; with the pipeline no longer saturating, the measurement
is stable. The 979.4 reading above is best read as the low end of that old
spread, not as a regression.

### Raw scan persistence is now optional, and off

`selfcheckout.analytics.persist-raw-scans=false` by default. The assignment
requires the **top-N windows** in the database; those are computed in memory
from the event stream, so the raw `scan_log` rows are an audit trail and not an
input to anything. At ~10,400 scans/s that was ~1.25 M inserts per stress run.
With the flag off the stage does not exist — no pipe, no thread, no writer.
`scripts/test-popular-window.py` turns it on and still passes, including its
cross-check of the stored window against a raw SQL aggregate.

### The tee no longer lets the audit branch starve the window branch

`IngestTee` deliberately treats its two branches differently: the window branch
uses a blocking `put` (losing a window loses an answer the API serves), the
scan-log branch a non-blocking `offer` with a counted, logged drop. Previously
both blocked, so a saturated raw-scan writer stalled the shared ingest thread
and the window branch went idle behind an audit trail nobody reads live.

### Acceptance check on both stress runs

| | stress run 1 | stress run 2 | limit |
|---|---|---|---|
| `ingestPipe` max depth | 31 / 65,536 (0.05 %) | 25 / 65,536 (0.04 %) | < 10 % |
| `windowPipe` max depth | 31 / 65,536 (0.05 %) | 26 / 65,536 (0.04 %) | < 10 % |
| `rankPipe` max depth | 1 / 4,096 (0.02 %) | 1 / 4,096 (0.02 %) | < 10 % |
| `sinkPipe` max depth | 1 / 4,096 (0.02 %) | 1 / 4,096 (0.02 %) | < 10 % |
| drops | 0 | 0 | 0 |
| stage errors | 0 | 0 | 0 |
| ingest in == out | 1,249,436 == 1,249,436 | 1,258,349 == 1,258,349 | equal |
| rank in == out | 2,498 == 2,498 | 2,516 == 2,516 | equal |
| sink rows in == out | 2,498 == 2,498 | 2,516 == 2,516 | equal |

`window` is the one stage where in ≠ out by design: it aggregates, emitting one
window per 500 scans (1,258,349 / 500 = 2,516). All 2,516 windows reached
`popular_snapshots`, and `scan_log` was empty, as the flag intends.

### A regression the golden diff caught

Not a performance change, but found by this phase's verification. Receipt
`lines[]` came back in a different order than in every previous version of the
server. `OpenBasket` kept its lines in a `LinkedHashMap` — correctly, so the
receipt lists them in the order first scanned — but handed out snapshots via
`Map.copyOf`, **whose iteration order is unspecified**. The same five-SKU basket
reorders:

```
LinkedHashMap : [SKU-000009, SKU-000004, SKU-000007, SKU-000001, SKU-000002]
Map.copyOf    : [SKU-000004, SKU-000007, SKU-000009, SKU-000001, SKU-000002]
```

Fixed, and locked down by `OpenBasketTest.linesKeepFirstScanOrder`, which was
confirmed to fail against the old code. The golden diff is now byte-for-byte
identical across all 28 captured responses.

### Where throughput now stands

Default (10 stations) and stress (100 stations) now return **the same ~995
tx/s**. That is the signature of a server already at its ceiling with only 10
stations: the extra 90 stations add queueing, not work. It shows up entirely in
`COMPLETE` latency — p50 4.94 ms at 10 stations against 80.56 ms at 100, for
the same throughput — while `SCAN` and `START` stay flat at ~0.1 ms because
they touch no database at all.

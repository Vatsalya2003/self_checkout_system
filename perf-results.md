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
| 6 | Write the transaction header before taking the item lock | 1051.7 | 1007.9 | 0.09 / 0.38 | 4.35 / 41.54 | 0 | PASS | — | **kept** |
| 6 stress | 100 stations, 120 s | 1012.2 | 998.1 | 0.10 / 0.33 | 77.49 / 290.08 | 0 | PASS | — | **kept** |
| 7 | Connection pool 16 vs 32 vs 64, interleaved | see Phase 7 below | | | | 0 | PASS | — | **kept 32** (unchanged) |
| **FINAL** | 10 stations, 60 s | **1031.8** | **1028.5** | 0.09 / 0.40 | 4.44 / 43.51 | 0 | PASS | no (~310 % of 800 %) | — |
| **FINAL stress** | 100 stations, 120 s | **1007.8** | **1040.2** | 0.10 / 0.32 | 76.51 / 305.22 | 0 | PASS | no (~350 % of 800 %) | — |

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

---

## Phase 6 — what actually sets the ceiling, and shortening it

### The statement profile, before and after

Re-measured with the same per-session `log_statement=all` method as Phase 1.
The global setting is still `none`.

| Operation | Phase 1 (Week 3 code) | Now | Change |
|---|---|---|---|
| `POST /transactions` (start) | 4 | **0** | no database work at all |
| `POST /{id}/items` (scan, new line) | 8 + 3 background | **0** | no database work at all |
| `POST /{id}/items` (scan, repeat SKU) | 7 + 3 background | **0** | no database work at all |
| `POST /{id}/complete`, L distinct SKUs | 3L + 5 → ≈ 33 at L = 9.32 | **6** (7 with a low-stock crossing) | constant in L |
| `GET /items` | 1 | **0** | served from the catalog cache |
| `GET /transactions/{id}` | 4 | 4 | unchanged (JPA, low volume) |
| `GET /inventory/low-stock` | 3 | 4 | unchanged (JPA, low volume) |
| `GET /analytics/popular-items` | 3 | 4 | unchanged (JPA, low volume) |

A full checkout cycle went from about **120 request-path statements to 6**.
The merge-on-save SELECTs and the full-row Hibernate UPDATEs are gone from
start, scan and complete; what remains on JPA is three read-only endpoints the
load client barely touches, where plain JDBC would be churn for no measurable
gain.

The complete path is now:

```
BEGIN
INSERT INTO transactions ...                          <- before the lock
SELECT sku, stock FROM items WHERE sku IN (...) ORDER BY sku FOR NO KEY UPDATE
UPDATE items SET stock = v.stock FROM (VALUES ...)
INSERT INTO transaction_items ... VALUES (...),(...)
COMMIT
```

### Statements held under the item lock

| | Phase 1 | After Phase 5 | After Phase 6 |
|---|---|---|---|
| statements held | 3L + 2 ≈ **30** | **4** | **3** |

### The measurement that makes this a lever and not a guess

`CheckoutTimings` splits a checkout's database time in two: how long the bulk
lock statement took (the **wait**), and how long the rows were then held, lock
granted to commit (the **critical section**).

| | 10 stations | 100 stations |
|---|---|---|
| lock wait | 7,626 µs | 101,224 µs |
| critical section | 1,127 µs | 1,119 µs |

The wait grows 13× with load; the critical section does not move. That is the
whole point: the wait is a queue, the critical section is the server's own
work, and only the second one is something the code can change. Because every
basket containing the hottest SKU has to pass through that window one at a
time, it is also what sets the ceiling:

```
ceiling ≈ 1 / (critical section × fraction of baskets holding that SKU)
        ≈ 1 / (0.001127 s × 0.66) ≈ 1,344 tx/s
```

Measured throughput was ~995 tx/s, about 74 % of that, which is the right
order — other SKUs serialize too, and 66 % is a measured approximation.

### The change, and its honest effect

Moving the header INSERT before the lock takes one statement out of the
critical section. Same database transaction, so a failure still rolls the
header back; only the lock-hold duration changes.

| | before | after |
|---|---|---|
| critical section | 1,127 µs | **1,016 µs** (−9.8 %) |
| tx/s, 10 stations | 997.5 / 989.5 | **1051.7 / 1007.9** (+3.7 % on the mean) |
| tx/s, 100 stations | 993.3 / 999.8 | **1012.2 / 998.1** (+0.9 %, inside noise) |
| COMPLETE p50, 10 stations | 4.94 / 4.84 ms | **4.35 / 4.41 ms** (−10 %) |

The critical-section shrink is direct and repeatable. The throughput gain is
real at 10 stations and indistinguishable from noise at 100, which says the
ceiling is not set by that one SKU alone. Kept because it is strictly less
work inside a lock and costs nothing.

### Considered and rejected: folding the UPDATE and the line INSERT into one CTE

```sql
WITH upd AS (UPDATE items SET ... FROM (VALUES ...) ...)
INSERT INTO transaction_items ...
```

This would remove one more round trip from the critical section. It is
rejected because it fuses two cleanly separated persistence operations
(`StockUpdater` and `CompletedTransactionWriter`) into one SQL string for an
expected few percent. Not worth the loss of explainability here.

Also rejected: `synchronous_commit = off`. It would cut the commit cost, which
is a large part of the remaining 1,016 µs, but a crash would lose paid
transactions. Correctness beats speed.

---

## Measurement validity: the machine throttles under sustained load

After roughly 45 minutes of continuous stress runs, throughput on an
**unchanged** binary and configuration fell from ~1,036 tx/s to 805, and in the
worst period to 304–367. The cause was not the code:

- the critical section — the server's own work — grew from 1,013 µs to
  1,318 µs for identical statements
- `SCAN` p50 rose from 0.102 ms to 0.129 ms and `START` from 0.118 to 0.152 ms,
  i.e. **everything** slowed by about the same fraction, including paths that
  touch no database
- during the worst period a browser renderer was measured at 117 % CPU and
  `ANECompilerService` at 85 %, with a load average of 12.7 on 8 cores

`pmset -g therm` reports nothing on this machine, so thermal state is inferred
from the uniform slowdown rather than read directly. Postgres was ruled out:
the database is 58 MB, there is no `pg_catalog` bloat, and checkpoint counts
are normal.

**Consequence for method.** Back-to-back A/B runs are only comparable inside a
short window. The pool-size sweep below is therefore run **interleaved**
(16, 32, 64, 16, 32, 64, …) rather than grouped, so that any drift is shared
by all three arms instead of being charged to whichever ran last, with a
cooldown first. Every run taken during the degraded period is kept below and
labelled, not deleted.

### Runs taken during the degraded period — kept, not used

| Config | tx/s | note |
|---|---|---|
| pool=16, 100 st | 595.1 | contaminated |
| pool=32, 100 st | 697.6 | contaminated |
| pool=16, 100 st | 367.3 | contaminated, background CPU peaked at 118 % |
| pool=32, 100 st | 332.7 | contaminated |
| pool=64, 100 st | 303.5 | contaminated, background CPU peaked at 232 % |
| pool=16, 100 st | 347.2 | contaminated |
| pool=32, 100 st (A/A re-test) | 805.4 | partial recovery; critical section 1,318 µs vs 1,013 µs |

---

## Phase 7 — connection pool size: 16 vs 32 vs 64

Run **interleaved** (16, 32, 64 × 3) after a 12-minute cooldown, 100 stations,
60 s each. Rep 3 matches rep 1 to within 0.4 %, so there is no drift left in
these numbers.

| pool | tx/s (rep 1 / 2 / 3) | mean tx/s | COMPLETE p50 | p95 | p99 | critical section |
|---|---|---|---|---|---|---|
| 16 | 945.5 / 1008.1 / 949.2 | 967.6 | 90.8 ms | **167.6 ms** | **228.9 ms** | 1,124 µs |
| **32** | 992.9 / 981.4 / 995.6 | **990.0** | 78.2 ms | 205.4 ms | 307.1 ms | **1,065 µs** |
| 64 | 892.3 / 883.8 / 879.3 | 885.1 | **66.6 ms** | 324.6 ms | 511.3 ms | 1,159 µs |

Zero errors at every size.

**Decision: keep 32.** This phase confirms the existing setting with data
rather than changing it.

**Reading the numbers.** There is a clean trade running across the three rows,
and it is not "bigger pool, more throughput":

- **p50 falls as the pool grows** (90.8 → 78.2 → 66.6 ms). A bigger pool admits
  more requests sooner, so the median request starts earlier.
- **p95 and p99 rise sharply** (229 → 307 → 511 ms at p99). Admitting more
  requests does not make the hot row any less serial — it just moves the queue
  from the connection pool into Postgres, where it is longer and less fair.
- **Throughput peaks in the middle.** 64 is 10.6 % *worse* than 32, with no
  overlap between the two sets of runs.

The mechanism shows up in the critical section, which is the server's own work
and should not depend on the pool at all: at 64 it grows from 1,065 µs to
1,159 µs. With 8 cores, 64 Postgres backends contending means each one's work
takes measurably longer — so a bigger pool makes the serialized section
*slower*, which is exactly the wrong direction.

16 vs 32 is a genuine choice rather than a clear win: 32 is ~2.3 % faster on
the mean but their ranges overlap (16 peaked at 1008.1, above 32's best run),
while 16 gives a visibly tighter tail (p99 229 ms vs 307 ms). 32 is kept
because throughput is the goal this week and it also has the lowest critical
section. **If tail latency were the priority, 16 would be the better choice** —
that is a real, measured option, not a hypothetical.

---

## Final result

Four runs, fresh restart before each, packaged jar, machine cooled first.
Raw reports in `reports/perf/final/`.

| | baseline (`pipeline-v1`) | final | change |
|---|---|---|---|
| tx/s, 10 stations | 404.6 / 407.2 | **1031.8 / 1028.5** | **×2.54** |
| tx/s, 100 stations | 420.3 / 431.9 | **1007.8 / 1040.2** | **×2.40** |
| scans/s, 100 stations | ~4,400 | **10,591 / 10,950** | ×2.45 |
| `START` p50 | 0.61 ms | **0.10 ms** | −84 % |
| `SCAN` p50 / p99 | 0.85 / 2.86 ms | **0.09 / 0.40 ms** | −89 % / −86 % |
| `COMPLETE` p50 / p99 (10 st) | 13.82 / 50.43 ms | **4.44 / 43.51 ms** | −68 % / −14 % |
| `COMPLETE` p50 / p99 (100 st) | 90.84 / 328.56 ms | **76.51 / 305.22 ms** | −16 % / −7 % |
| errors | 0 | **0** of 4,624,769 requests across the four runs | — |
| invariant | PASS | **PASS** | — |

### Statements per operation, start to finish

| Operation | `pipeline-v1` | final |
|---|---|---|
| `POST /transactions` | 4 | **0** |
| `POST /{id}/items` (scan) | 7–8 + 3 background | **0** |
| `POST /{id}/complete` (L SKUs) | 3L + 5 ≈ 33 | **6** |
| `GET /items` | 1 | **0** |
| **statements held under the item lock** | **3L + 2 ≈ 30** | **3** |
| whole checkout cycle | ~120 | **6** |

### Phase 8 — is it CPU-bound? No, and that is the point

CPU sampled every 3 s during each final run, as a percentage of the 800 %
available on 8 cores:

| Run | server | Postgres | load client | total |
|---|---|---|---|---|
| default 1 | 100 % | 96 % | 114 % | 310 % |
| default 2 | 103 % | 94 % | 111 % | 308 % |
| stress 1 | 104 % | 132 % | 122 % | 358 % |
| stress 2 | 87 % | 134 % | 126 % | 347 % |

**The server uses about one core.** It is not short of CPU, memory or
connections — at 100 stations it is blocked, and the measurement in Phase 6
says exactly where: `lockWait` 93–96 ms against a `criticalSection` of
1.01–1.04 ms. Roughly 99 % of a completion's database time is spent queueing
for the hottest SKU's row, and about 1 % doing the work.

Note also that the baseline burned **~505 %** of CPU to do 405 tx/s, while the
final build does 1030 tx/s on ~310 %. Most of the removed work was never
useful: it was round trips, Hibernate dirty-checking and merge-before-insert
SELECTs.

### What would have to change to go faster

Not a to-do list, just the honest reading of the numbers. Everything below is
a **hypothesis**, not a measurement.

1. **Shard the hot row.** The ceiling is one row's serialization. Splitting
   `SKU-000001`'s stock across N counter rows and decrementing a random one
   would cut contention by roughly N, at the cost of making "what is the
   stock?" a `SUM` and the invariant check more complicated.
2. **Shorten the critical section further.** The remaining 1.0 ms is one
   `UPDATE`, one `INSERT` and a commit. Folding the first two into a single
   CTE statement would remove one round trip; see the rejection above for why
   it was not done.
3. **Don't take the lock at all.** `UPDATE items SET stock = stock - LEAST(stock, ?)`
   is atomic without a prior `SELECT`. It was rejected because the row-locking
   order inside a multi-row `UPDATE` is not guaranteed, which reopens the
   deadlock the sorted lock exists to prevent.

The two levers that are *not* worth pulling: more CPU (there is spare), and a
bigger connection pool (Phase 7 measured it as actively harmful).

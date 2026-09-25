# Architecture — Self-Checkout Monolith

A single Spring Boot application over a single PostgreSQL database, implementing the
shared course contract in `CS6510-2026/spec/self-checkout-openapi.yaml`.

Layering is `controller → service → repository`, with records as DTOs and constructor
injection throughout. There is one process and one database; every endpoint is
synchronous request/response.

All numbers in this document come from the two JSON reports in [`reports/`](reports/),
produced by the unmodified course load client against the code as committed. Anything
not measured is labelled as a prediction or a hypothesis.

---

## 1. Required architectural characteristics

Each characteristic below has at least one concrete, testable requirement, and a note on
how it is actually verified.

| # | Characteristic | Concrete, testable requirement | How it is verified |
|---|---|---|---|
| 1 | **Correctness under concurrency** | For every SKU: `starting_stock − final_stock == SUM(applied_quantity)` over COMPLETED transactions. Zero mismatching rows. | `scripts/check-invariant.sql` CHECK 2, run after every load test. **Measured: 0 violations in both runs.** |
| 2 | **Correctness under concurrency** | No SKU ever has `stock < 0`, even when scanned units exceed available stock. | `scripts/check-invariant.sql` CHECK 1. **Measured: 0 rows in both runs.** |
| 3 | **Correctness under concurrency** | A transaction can be completed at most once; concurrent completes of the same transaction yield exactly one 200 and the rest 409, and stock moves once. | `scripts/test-oversell-and-double-complete.py`. **Measured: 20 simultaneous completes → 1×200, 19×409, stock −4 not −80.** |
| 4 | **Performance / latency (default load)** | At 10 stations for 60 s: `SCAN_ITEM` p95 < 10 ms and `COMPLETE_TRANSACTION` p95 < 100 ms. | Default report. **Measured: scan p95 1.99 ms, complete p95 30.98 ms.** Thresholds grounded in the measured default run, with headroom. |
| 5 | **Performance / latency (stress)** | At 100 stations for 120 s: `COMPLETE_TRANSACTION` p99 < 1000 ms and no request exceeds the client's 10 s timeout. | Stress report. **Measured: complete p99 434.42 ms, max 1052.19 ms.** |
| 6 | **Throughput** | Sustain ≥ 1000 scans/sec at 100 stations. | Stress report. **Measured: 3105.8 scans/sec, 297.1 transactions/sec.** |
| 7 | **Scalability** | Going from 10 → 100 stations must not break any correctness requirement above; degradation must be in latency only. | Both runs pass CHECK 1 and CHECK 2 identically. **Measured: correctness unchanged; complete p95 grew 30.98 → 275.79 ms.** |
| 8 | **Availability / robustness** | Zero errors on every operation for the full duration of both runs; no deadlocks, no connection-acquisition timeouts, no unhandled exceptions. | Client error counts + server log scan. **Measured: 0 errors of 702,394 total requests across both runs (257,562 default + 444,832 stress); 0 deadlocks; 0 WARN/ERROR lines.** |
| 9 | **Analytics consistency** | One popular-items window is written per `slideInterval` scans, each covering exactly `windowSize` scans, with 1-based dense ranks. | `scripts/test-popular-window.py` plus snapshot counts. **Measured: 432 windows for 216,324 scans (= ⌊216324/500⌋) in the default run; 746 for 373,394 in the stress run.** |
| 10 | **Analytics correctness** | Stored window counts must equal a plain SQL aggregate over the same `seq` range. | `scripts/test-popular-window.py` cross-checks against raw SQL. **Measured: exact match.** |
| 11 | **Alerting** | A low-stock alert is recorded exactly once per SKU, at the moment stock first goes strictly below the threshold, and remains queryable afterwards with its real trigger time. | `scripts/test-low-stock.py`. **Measured: landing exactly on 50 does not alert; 49 does; further drops neither duplicate the row nor move `triggeredAt`.** |
| 12 | **Observability** | Every correctness property must be checkable after a run from the database alone, without instrumenting the server. | `scripts/check-invariant.sql` reports negative stock, invariant violations, sold-out shortfalls, and a summary line. |
| 13 | **Simplicity / reinitializability** | A full reset to a known-good state must be a single action with no manual SQL. | `schema.sql` + `data.sql` run on every boot: restarting the server drops, recreates and reseeds 2000 SKUs × 10,000 units. **Verified before every run in this document.** |
| 14 | **Contract conformance** | All seven endpoints match the OpenAPI spec in path, field names, status codes and error shape. | The unmodified course load client parses every response; it fails fast on an unexpected status or missing field, and reported 0 errors. |

---

## 2. Top 3 prioritized characteristics, with explicit trade-offs

### Priority 1 — Correctness under concurrency

The spec singles this out as "the correctness property most naive implementations
violate." It is prioritized above everything else: a fast checkout that loses stock is
worthless, and unlike latency the damage is silent — no error is raised, and it is only
visible later by auditing the database.

**What we gave up for it.**

- **Completions serialize on hot rows.** `SELECT ... FOR UPDATE` turns contention into a
  queue. Under the course's Zipf workload, `SKU-000001` alone takes 12.2 % of all scans
  and appears in roughly two thirds of baskets, so most completions queue on one row.
  The cost is visible and measured: at 100 stations `COMPLETE_TRANSACTION` p95 is
  275.79 ms against 30.98 ms at 10 stations — **+245 ms in absolute terms**, an 8.9×
  increase. `SCAN_ITEM`, which takes no item locks at all, grew 1.99 → 34.00 ms: a
  *larger* relative jump (17×) but a far smaller absolute one (**+32 ms**). Completion
  is where the lock cost actually lands; the scan increase has a different cause, traced
  in §3.1.
- **Locks are held until commit.** Strict two-phase locking means a completion holds
  every row it has touched until the transaction ends. There is no way to release the
  hot row early without giving up the guarantee.
- **Throughput no longer scales with stations.** 10 → 100 stations is 10× the offered
  load for 0.86× the transaction throughput (343.5 → 297.1 tx/sec). The system is at its
  ceiling at 10 stations already; extra stations buy queueing, not work.

### Priority 2 — Performance and latency

Second because the workload is interactive: a customer is standing at the station.
Correctness is never traded away for it, but within that constraint the design actively
protects the hot path.

**What we gave up for it.**

- **Scans are not validated against stock.** A scan never reads or locks `items`, which
  is why scan latency stays low (p95 1.99 ms default, 34.00 ms stress). The price is that
  the basket can contain more units than the shelf holds, which forces the
  `applied_quantity` design in §4.4.
- **Analytics are eventually consistent.** The popular-items aggregate runs on a
  background thread after the scan commits, so no request ever pays for it. A window is
  therefore visible slightly after the scan that triggered it.
- **Tuning is workload-specific.** The connection pool (32) was chosen for this machine
  and this workload, not derived from first principles.

### Priority 3 — Simplicity and modifiability

This is the "monolith" week of a semester-long comparison, so the value of this
implementation is partly as a clean baseline for later architectures.

**What we gave up for it.**

- **Single point of failure.** One process and one database. If either dies, the whole
  store stops. There is no redundancy and no failover.
- **Vertical scaling only.** The ceiling is one Postgres instance. Sharding by SKU or
  splitting checkout from analytics would raise it, at the cost of the simplicity that
  makes this version easy to reason about.
- **Simplicity was chosen over measured micro-optimizations.** §3.2 records two variants
  that were built and measured and then *not* adopted, because neither beat the baseline
  by more than run-to-run noise.

---

## 3. Measured results

### 3.1 The two required runs

Both runs: server restarted immediately beforehand (so the catalog is back to
2000 SKUs × 10,000 units), machine otherwise idle, SQL logging off.

| Metric | Default (10 stations, 60 s) | Stress (100 stations, 120 s) | Change |
|---|---|---|---|
| Transactions | 20,619 | 35,719 | 1.73× |
| Transactions / sec | 343.5 | 297.1 | **0.86×** |
| Items scanned | 216,324 | 373,394 | 1.73× |
| Scans / sec | 3,604.1 | 3,105.8 | 0.86× |
| **Total errors** | **0** | **0** | — |
| Invariant violations | **0** | **0** | — |

Per-operation latency, milliseconds:

| Operation | Run | p50 | p95 | p99 | max | errors |
|---|---|---|---|---|---|---|
| START_TRANSACTION | default | 0.63 | 1.12 | 1.93 | 56.74 | 0 |
| START_TRANSACTION | stress | 17.06 | 34.19 | 69.47 | 333.46 | 0 |
| SCAN_ITEM | default | 1.19 | 1.99 | 3.13 | 80.41 | 0 |
| SCAN_ITEM | stress | 17.38 | 34.00 | 60.91 | 403.68 | 0 |
| COMPLETE_TRANSACTION | default | 14.99 | 30.98 | 43.92 | 265.58 | 0 |
| COMPLETE_TRANSACTION | stress | 118.71 | 275.79 | 434.42 | 1052.19 | 0 |

**Why start and scan slowed down too.** Neither `START_TRANSACTION` nor `SCAN_ITEM`
takes an item lock, yet both went from about 1 ms to about 17 ms at p50. Stated both
ways, because the two framings disagree: in **relative** terms they degraded *more* than
completion (scan p95 17×, start p95 30×, against 8.9× for complete); in **absolute**
terms they degraded far *less* (+32 ms and +33 ms, against +245 ms for complete).

**Measured cause — connection-pool queueing.** Thread dumps taken during a 100-station
run show 103 Tomcat request threads, of which **65–68 were parked in
`HikariPool.getConnection`** at every sample, with exactly 31–32 inside JDBC — the pool
(maximum 32) saturated continuously. So roughly two thirds of in-flight requests at any
instant were waiting for a connection rather than doing work. A completion holds its
connection for as long as it holds its row locks, so a lock-free scan queues behind
lock-holding completions *at the pool*. That is the mechanism by which contention on
`items` rows reaches operations that never touch those rows. The connection pool, not
the row lock, is what start and scan are actually waiting on.

**Secondary hypothesis, not isolated.** The load client's 100 threads and the server
share one 8-core machine, so part of the increase is ordinary CPU contention rather than
queueing. Separating the two would need the client on a different host, which was not
done; this remains a hypothesis.

**Consistency check (Little's Law).** Each station runs one start, ~10.5 scans and one
complete strictly in sequence, so one cycle costs roughly
`20 + 10.5 × 19.7 + 111 ≈ 337 ms` using the measured means. With 100 stations that
predicts `100 / 0.337 ≈ 297` transactions/sec, against **297.1 measured**. The stations
are simply serialized by their own round-trip latency — the throughput ceiling is fully
explained by per-request latency, with nothing unaccounted for.

Raw reports: [`reports/report-default-10stations-60s.json`](reports/report-default-10stations-60s.json),
[`reports/report-stress-100stations-120s.json`](reports/report-stress-100stations-120s.json).

### 3.2 Lock-order and batching A/B

Three implementations were built and measured at 100 stations / 120 s, all with 0 errors
and 0 invariant violations, all on an otherwise-idle machine. The committed stress run
from §3.1 is included as a second ascending row, because it runs the *same* ascending
code as the first row and so measures variance rather than a design difference:

| Variant | tx/sec | complete p50 | complete p95 | complete p99 | complete max |
|---|---|---|---|---|---|
| **Ascending per-row (adopted)** | 347.8 | 114.31 | 192.87 | 320.80 | 865.32 |
| **Ascending per-row — the committed stress run (§3.1)** | 297.1 | 118.71 | 275.79 | 434.42 | 1052.19 |
| Descending per-row, run 1 | 324.8 | 63.26 | 279.08 | 447.06 | 956.20 |
| Descending per-row, run 2 | 347.8 | **59.86** | 256.28 | 402.38 | 965.44 |
| Batched `WHERE sku IN (...) ORDER BY sku FOR UPDATE` | 340.7 | 101.70 | **179.75** | 407.96 | 1621.46 |

**Reading these numbers honestly.** The two ascending rows run identical code, and they
differ by more than the variants differ from each other: 347.8 vs 297.1 tx/sec (**~15 %**)
and a complete p99 of 320.80 vs 434.42 ms (**~35 %**). The committed run falls outside the
324.8–347.8 tx/sec band formed by the other three, and its p99 is worse than *both*
descending runs.

So run-to-run variance for unchanged code is at least ~15 % on throughput and larger on
the tail — bigger than any gap between the three designs. **No variant showed a reliable
difference, and this A/B cannot justify choosing one over another on performance.**
Ascending was kept because it is the simplest of the three to reason about: one total
order, one row at a time, exactly as described in §4.3.

One difference did reproduce across both descending runs: a markedly better median
(≈60 ms against ≈102–118 ms). That is consistent with the mechanism — locking the hottest
SKU *last* shortens how long the most contended row is held, so the typical completion
finishes sooner. Its effect on the tail is not separable from the noise, and no claim is
made about it.

### 3.3 The sold-out case, measured

The Zipf sampler weights rank *i* by 1/*i* over 2000 items, and rank is the order
`GET /items` returns, which is SKU ascending — so `SKU-000001` is the hottest item.
Arithmetic over that distribution predicts it should take ≈12.2 % of all scans and
exhaust its 10,000 units after ≈82,000 total scans.

That prediction held. In a 60 s run measured during development, **18,687 of 153,291
scans** went to `SKU-000001` = **12.19 %**, against the predicted 12.23 %.

In the committed **default** run two SKUs sold out (`SKU-000001`, `SKU-000002`); in the
committed **stress** run four did. The shortfalls recorded for the stress run were:

| SKU | units scanned | units applied | units short |
|---|---|---|---|
| SKU-000002 | 22,749 | 10,000 | 12,749 |
| SKU-000003 | 15,260 | 10,000 | 5,260 |
| SKU-000004 | 11,366 | 10,000 | 1,366 |

(`SKU-000001` also sold out in this run; its exact shortfall was not captured.)

CHECK 3 of the invariant script reports this deliberately, so clamping stays visible
rather than hiding inside a passing CHECK 2.

### 3.4 A note on measurement validity

The load client and the server run on the same 8-core machine, so the measurement is
sensitive to anything else using the CPU. This was not theoretical: one stress run
produced 22 client-side timeouts purely because an iOS Simulator started up mid-run
(155 processes, load average 21.5), and the server's own log showed
`Thread starvation or clock leap detected (housekeeper delta=59s622ms)` with zero
database errors. **That run was discarded and re-run on an idle machine, not reported as
a result.** Every number in §3.1 and §3.2 comes from a run where load average was below 4
at start, and every run is reported — none were dropped for being unflattering.

Both official runs are single runs. §3.2 measures run-to-run variance for *unchanged*
code at roughly 15 % on throughput and 35 % on complete p99, so differences smaller than
that — between these two runs or against any other implementation — should not be
over-read.

---

## 4. Design decisions

### 4.1 Stock is decremented at completion, never at scan

The spec requires it, and the physical metaphor explains it: the customer already holds
the item when it is scanned, so the backend's job is to keep an accurate count, not to
refuse the scan. `TransactionService.scan()` therefore never reads or locks `items.stock`.

This is also what keeps the hot path fast. From 10 to 100 stations, scan p95 grew
1.99 → 34.00 ms (+32 ms absolute, 17× relative) while completion p95 grew
30.98 → 275.79 ms (+245 ms absolute, 8.9× relative). In relative terms scans degraded
more; in absolute terms they degraded by an eighth as much, and for a different reason
(§3.1 — connection-pool queueing, not item locks).

### 4.2 Pessimistic row locking, not optimistic

Two stations completing baskets that share a SKU are two threads, on two JDBC
connections, in two independent Postgres sessions with independent MVCC snapshots. If
both read `stock`, decide in Java, and write back an absolute value, one decrement is
silently lost. `SELECT ... FOR UPDATE` makes read-check-write atomic: the second caller
blocks until the first commits, then re-reads the committed value.

Optimistic locking (`@Version` + retry) was rejected on the shape of this workload, not
on taste. It wins when conflicts are rare, because the happy path takes no lock. Here
`SKU-000001` is in roughly two thirds of all baskets, so at 100 stations most completions
would collide on that row, and each collision costs a full retry whose retry collides
again. Pessimistic locking converts the same contention into an orderly FIFO queue: one
wait and one write per completion, bounded work, and no starvation.

The measured consequence is the serialization documented in §2 — and 0 lost updates
across 56,338 completed transactions in the two official runs.

### 4.3 Basket lines are locked in ascending SKU order

Locks are held until commit, so a transaction holding `SKU-A` while waiting for `SKU-B`
can deadlock against one holding `SKU-B` and waiting for `SKU-A`. Postgres would detect
that after `deadlock_timeout` (1 s on this machine) and kill one transaction with
SQLSTATE `40P01` — a 500 to the customer and a non-zero error count.

Sorting every basket by SKU before locking gives all transactions one global acquisition
order, which removes the circular-wait condition entirely: a cycle would require some
transaction to hold a higher SKU while waiting for a lower one, which sorted acquisition
never does. It is prevention, not detection, and it costs one `ORDER BY`.

`scripts/test-concurrent-completions.py` exercises this directly by building half its
baskets in reverse SKU order — the deadlock bait — and completing them simultaneously.

Any total order works for deadlock-freedom, which is why descending was a legitimate
variant to test (§3.2). A generalization we did **not** implement, and flag as an
untested hypothesis: ordering by *popularity ascending, then SKU* would put the hottest
row last for any catalog, not just one where popularity happens to correlate with SKU
order. That is still a total order, so it would remain deadlock-free.

### 4.4 `applied_quantity`, and the sold-out edge case

Because scans are never gated by stock, a hot SKU can be scanned more times than it has
units — and §3.3 shows this happens routinely within a single run. If the decrement were
simply clamped, `10000 − stock` would no longer equal the units the system believed it
had sold, and the invariant could not distinguish an honest sell-out from a lost update.

So `transaction_items` carries both numbers:

- `quantity` — units scanned. This is what the **receipt** reports, because it is what
  the customer physically has.
- `applied_quantity` — units actually subtracted. This is what the **invariant** is
  checked against.

CHECK 3 of the invariant script reports the difference per SKU, so clamping is surfaced
rather than swallowed.

### 4.5 Two-pass lock-then-mutate

`complete()` acquires every item lock in one loop, then does all arithmetic and mutation
in a second loop. This is semantically identical to a single interleaved loop, but it
avoids leaving an `Item` dirty while further lock queries are still being issued:
Hibernate auto-flushes before a query that touches a dirty table, so a single loop would
scatter UPDATE statements through the lock-acquisition phase. Two passes keep the
sequence "take all the locks, then mutate," which is also how the code reads.

### 4.6 Considered alternative: the single-statement CTE decrement

The clamp and the decrement can be done in one statement on PostgreSQL 16:

```sql
WITH locked AS (SELECT sku, stock FROM items WHERE sku = :sku FOR UPDATE)
UPDATE items i SET stock = i.stock - LEAST(l.stock, :qty)
FROM locked l WHERE i.sku = l.sku
RETURNING LEAST(l.stock, :qty) AS applied;
```

This is correct and cheaper — one round trip instead of two, and a shorter lock hold. The
CTE captures the pre-update stock, so `applied_quantity` comes back from `RETURNING`
without a separate read. (Referencing `OLD.*` directly in `RETURNING` would require
PostgreSQL 18; the CTE form does not, and works on the 16.15 used here.)

**It was not adopted, for readability, not because it is impossible.** The JPA locking
read keeps the clamp arithmetic in Java where it is easy to follow next to the
`applied_quantity` bookkeeping, and §3.2 found no throughput headroom that would justify
dropping to hand-written SQL on the critical path.

### 4.7 Hopping window over `scan_log.seq`, and BIGSERIAL gaps

`scan_log.seq` is a `BIGSERIAL`, used as the global scan sequence. Every
`slideInterval` (500) scans, the top 10 SKUs over the last `windowSize` (1000) scans are
computed and written to `popular_snapshots`, as the assignment requires.

The boundary is **not** detected with `seq % 500 == 0`. Postgres sequences are not
gapless — a rolled-back insert consumes its number permanently — so an exact multiple can
simply never appear, and that window would be skipped forever. Instead each scan computes
which multiple of the slide interval its own `seq` has passed and tries to claim it with
an `AtomicLong.compareAndSet`. Exactly one thread wins per boundary; everyone else
returns immediately. The hot-path cost is one integer division and one atomic read.

The aggregate itself runs on a single dedicated background thread, handed off via
`afterCommit`, so (a) no request thread ever pays for it, (b) a window can never include
a scan that later rolls back, and (c) analytics can never consume more than one extra
database connection regardless of checkout load. The consequence, accepted deliberately,
is that a window becomes visible slightly after the scan that triggered it.

Windows use the half-open range `(start, end]` so consecutive windows neither
double-count nor skip a scan at the boundary. Ties break on SKU ascending, so rankings
are deterministic.

The top 10 is what is persisted, per the assignment. `?limit=` therefore truncates the
stored ten; a request for more than 10 returns the 10 that exist.

### 4.8 Low-stock semantics: strict `<`

The spec says alerts fire when stock "drops **below**" the threshold, so the comparison is
strict `<`: landing exactly on 50 is not low, 49 is. The reference mock server uses `<`
too (`MockServer.java`, `current < lowStockThreshold`), so the two agree.

Where this implementation deliberately differs from the mock server: the mock recomputes
from current stock on every request and stamps `triggeredAt` with `Instant.now()`, so its
"trigger time" is really just the query time. The spec asks for alerts that are
"queryable after the fact," which needs a real trigger time, so here a row is written to
`low_stock_alerts` at the moment of the crossing, from inside the completing transaction
while that item's row lock is held. Holding the lock means exactly one completer can
observe any given crossing. Stock is never replenished in this simulation, so a SKU
crosses at most once and the recorded time is the true crossing time.

**Ad-hoc `?threshold`** applies to that query only and is never persisted. A SKU that
qualifies under an ad-hoc threshold but never recorded a crossing has no real trigger
time; it reports `generatedAt`, matching what the mock server does for every alert. A SKU
that *did* record a crossing keeps its recorded time. Both behaviours are asserted in
`scripts/test-low-stock.py`.

### 4.9 Assumption: one station drives one transaction sequentially

The basket upsert in `scan()` reads the existing line and then either increments it or
inserts a new one. That read-then-write is race-free **only because a transaction is
driven by a single station thread, one scan at a time** — confirmed in the load client's
`StationWorker`, which issues each scan and waits for the response before the next.

This is a real assumption the design depends on, not an accident. Two concurrent scans of
the same transaction and SKU could both miss the read and both attempt an insert, and one
would fail on the primary key. A single `INSERT ... ON CONFLICT (transaction_id, sku) DO
UPDATE SET quantity = quantity + 1` would remove the assumption and save a query; it was
left out to keep the scan path readable, and is the first thing to change if the client
ever parallelizes scans within one basket.

### 4.10 Configuration choices

- **`spring.jpa.open-in-view=false`.** Every controller returns a record fully built
  inside the service transaction and no entity has a lazy association, so nothing needs
  the persistence session during response rendering. Leaving it on would hold a pooled
  connection for the whole response and shrink effective pool capacity for no benefit.
- **Hikari `maximum-pool-size=32`.** Postgres `max_connections` is 100 and psql/pgAdmin
  need room, so the app must stay well under it. The machine has 8 cores, and a pool far
  larger than the core count adds no throughput when the bottleneck is a row lock rather
  than CPU. But it must still exceed the number of completions that can be queued on a
  hot SKU at once, or fast scans get stuck behind slow completions waiting for a
  *connection* — head-of-line blocking in the pool rather than in the database. 32
  balances those three. It is a reasoned starting point, re-tunable from measurements;
  it was not varied experimentally, so its optimality is **not** a claim this document
  makes. The thread dumps in §3.1 now show it is the binding constraint at 100 stations:
  the pool sits saturated with roughly two thirds of request threads queued on it. That
  makes pool size the first thing to vary in any follow-up, though raising it only moves
  the queue from the pool to the contended row unless completions also get shorter.
- **SQL logging off.** Per-statement logging would dominate the measurement.

---

## 5. Known limitations

1. **Single point of failure, vertical scaling only** (§2, priority 3).
2. **Throughput does not scale with stations.** Already at the ceiling at 10 stations;
   100 stations produced 0.86× the transaction rate.
3. **The pool size is reasoned, not optimized** (§4.10), and is measurably the binding
   constraint at 100 stations (§3.1).
4. **Single measurement runs.** One default and one stress run. §3.2 measures run-to-run
   variance for unchanged code at ~15 % on throughput and ~35 % on complete p99, so
   differences below that are not meaningful. Any firm performance comparison would need
   repeated runs and a reported spread.
5. **Client and server share one machine**, so results are sensitive to host load (§3.4).
6. **Analytics are eventually consistent** by design (§4.7).
7. **The basket upsert assumes sequential scans per transaction** (§4.9).

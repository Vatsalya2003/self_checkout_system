# Architecture — Self-Checkout: Layered, with a Pipelined Analytics Stage

Weeks 2 to 4 of the semester project. Same API contract, same database, same
externally visible checkout behaviour as Week 1's monolith throughout — what
changed is how the code is structured (Week 2), how the windowed analytics are
executed (Week 3, part 1), and how much work a request actually does (Week 3, part 2 — the performance work, §7).

Week 1 organized classes by technical type (`controller/`, `service/`,
`repository/`). That reads like layers but nothing enforced it: HTTP types had
already leaked into the business code, and the services built API-shaped DTOs.
This week the layers are real packages with **dependency rules enforced by a
test that fails the build**.

All numbers come from JSON reports produced by the unmodified course load
client against the code as committed: the two graded runs in
[`reports/`](reports/), and for the Week 3 performance work every run in
`reports/perf/` — including the ones that were reverted or taken while the
machine was throttling, which are kept and labelled rather than dropped.
Anything not measured is labelled a prediction.

---

## 1. Required architectural characteristics

| # | Characteristic | Concrete, testable requirement | How it is verified |
|---|---|---|---|
| 1 | **Correctness under concurrency** | For every SKU: `starting_stock − final_stock == SUM(applied_quantity)` over COMPLETED transactions. Zero mismatching rows. | `scripts/check-invariant.sql` CHECK 2. **Measured: 0 violations in both runs.** |
| 2 | **Correctness under concurrency** | No SKU ever has `stock < 0`, even when scanned units exceed available stock. | `scripts/check-invariant.sql` CHECK 1. **Measured: 0 rows in both runs.** |
| 3 | **Correctness under concurrency** | A transaction completes at most once; concurrent completes give exactly one 200 and the rest 409, and stock moves once. | `scripts/test-oversell-and-double-complete.py`. **Measured: 20 simultaneous completes → 1×200, 19×409, stock −4 not −80.** |
| 4 | **Modifiability — enforced layer boundaries** | No class outside `api` may reference `org.springframework.http..` or `org.springframework.web..`; no class in `api` may reference `..persistence..`; `persistence` and `analytics` may not reference anything above them. | `LayeredArchitectureTest`, 6 rules, run by `./mvnw test`. **Measured: 5 of 6 failed before the refactor, 6 of 6 pass after.** |
| 5 | **Modifiability — layer isolation** | Each layer is replaceable without touching its neighbours' internals: business code must compile with no knowledge of HTTP, and the API must compile with no knowledge of JPA. | Same test, rules 2 and 3. **Measured: 47 HTTP-leak violations before, 0 after.** |
| 6 | **Behavioural equivalence with Week 1** | Every endpoint returns byte-identical bodies, status codes and error codes to the monolith, for the happy path and all 400/404/409 cases. | 27-call golden script captured from the `monolith` tag, re-run against the layered build and diffed with IDs/timestamps normalized. **Measured: identical.** |
| 7 | **Performance / latency (default load)** | At 10 stations for 60 s: `SCAN_ITEM` p95 < 10 ms, `COMPLETE_TRANSACTION` p95 < 100 ms. | Default report. **Measured (Week 3 final, pipeline-v2): scan p95 0.20 ms, complete p95 28.45 / 28.53 ms.** (Week 2: 2.19 / 32.68 ms.) |
| 8 | **Performance / latency (stress)** | At 100 stations for 120 s: `COMPLETE_TRANSACTION` p99 < 1000 ms, no request exceeds the client's 10 s timeout. | Stress report. **Measured (Week 3 final, pipeline-v2): complete p99 305.22 / 285.03 ms, max 1302 / 748 ms.** The 1,302 ms outlier is one request of 121,005; the requirement is on p99, which holds. |
| 9 | **Throughput** | Sustain ≥ 1000 scans/sec at 100 stations. | Stress report. **Measured (Week 3 final, pipeline-v2): 10,591 / 10,950 scans/sec, 1007.8 / 1040.2 transactions/sec** — 10× the requirement. (Week 2: 3,651.7 scans/sec.) |
| 10 | **Scalability** | 10 → 100 stations must not break any correctness requirement; degradation must be latency only. | Both runs pass CHECK 1 and CHECK 2 identically. **Measured: correctness unchanged.** |
| 11 | **Availability / robustness** | Zero errors on every operation for the full duration of both runs; no deadlocks, no connection-acquisition timeouts, no unhandled exceptions. | Client error counts + server log scan. **Measured (Week 3 final, pipeline-v2): 0 errors of 4,624,769 requests across four runs; 0 deadlocks; 0 WARN/ERROR lines.** |
| 12 | **Analytics consistency** | One window written per `slideInterval` scans, each covering `windowSize` scans, ranks 1-based and dense. | `scripts/test-popular-window.py` + snapshot counts. **Measured (Week 3 final, pipeline-v2): 2,629 windows for 1,314,701 scans = exactly ⌊n/500⌋; 0 dropped events; in == out at every filter.** |
| 13 | **Alerting** | An alert is recorded exactly once per SKU, at the moment stock first goes strictly below the threshold, and stays queryable with its real trigger time. | `scripts/test-low-stock.py`. **Measured: 50 does not alert, 49 does, further drops neither duplicate nor move `triggeredAt`.** |
| 14 | **Observability** | Every correctness property checkable after a run from the database alone. | `scripts/check-invariant.sql`. |
| 15 | **Simplicity / reinitializability** | A full reset to a known state is one action, no manual SQL. | Restarting the server re-runs `schema.sql` + `data.sql`. **Verified before every run here.** |
| 16 | **Contract conformance** | All seven endpoints match the OpenAPI spec in path, field names, status codes and error shape. | The unmodified load client fails fast on any unexpected status or missing field, and reported 0 errors. |

---

## 2. Top 3 prioritized characteristics, with explicit trade-offs

### Priority 1 — Correctness under concurrency

Unchanged from Week 1, and still first. A fast checkout that loses stock is
worthless, and the damage is silent: no error is raised, and it is only visible
by auditing the database afterwards. **The refactor was not allowed to touch
any of it** — same locks, same lock order, same clamping, same window logic.

**What we give up.** Completions serialize on hot rows. Under the Zipf
workload `SKU-000001` takes ~12% of all scans and appears in roughly two thirds
of baskets, so most completions queue on one row. At 100 stations
`COMPLETE_TRANSACTION` p95 is 185.50 ms against 32.68 ms at 10 stations. Locks
are held until commit, and throughput does not scale with stations.

### Priority 2 — Modifiability (this week's actual subject)

The assignment is about structure, so this is the characteristic the week is
spent buying. Concretely it means: a change confined to one concern touches one
package, and the compiler plus a test stop you from creating a shortcut.

**What we gained, measured.**

- **47 HTTP-leak violations → 0.** `TransactionService` no longer names a
  single HTTP status. The business rules could be served over gRPC, a message
  queue or a CLI without editing a line of them.
- **23 business-to-API dependencies → 0.** Services return domain records; the
  API maps them to spec shapes. The wire format can change without the business
  layer noticing.
- **The rules are executable.** `LayeredArchitectureTest` fails the build on a
  violation, so the structure cannot quietly rot back.

**What we gave up, honestly.**

- **Seven near-duplicate result records.** `CatalogItem`/`ItemDto`,
  `TransactionView`/`TransactionResponse`, `ScanOutcome`/`ScanResult`,
  `ReceiptView`/`Receipt`, `ReceiptLineView`/`ReceiptLine`,
  `LowStockView`/`LowStockResponse`, `LowStockEntry`/`LowStockAlertDto`,
  plus `PopularWindowView`/`PopularItemsResponse` and
  `PopularEntry`/`PopularItem`. Today they are field-for-field identical and
  the mapping code is pure overhead. It pays off only the first time the API
  shape and the domain shape need to differ. **Until then this is a real,
  visible cost, not a hidden one.**
- **More classes.** 42 production classes became 58.
- **Sinkholes.** Some paths now pass through a layer that adds nothing —
  see §4.3.
- **A small latency cost at low load**, see §3.2.

### Priority 3 — Simplicity and operability

Still one process and one database.

**What we gave up.** Single point of failure; vertical scaling only. And this
is worth stating plainly because layering invites the opposite assumption:

> **Layering changed nothing about deployment, scaling or fault isolation.**
> It is a *source-code* structure. There is still one JAR, one JVM, one
> Postgres, one thread pool and one connection pool. If the process dies the
> whole store stops, exactly as in Week 1. Layers bought modifiability and
> testability; they did not buy availability, elasticity or independent
> deployment. Those need the later weeks' architectures.

---

## 3. Measured results

### 3.1 The two required runs

Both runs: server restarted immediately beforehand, machine idle (1-minute load
average below 4 at start), SQL logging off.

| Metric | Default (10 stations, 60 s) | Stress (100 stations, 120 s) |
|---|---|---|
| Transactions | 19,663 | 41,866 |
| Transactions / sec | 327.4 | 348.1 |
| Items scanned | 205,130 | 439,230 |
| Scans / sec | 3,415.9 | 3,651.7 |
| **Total errors** | **0** | **0** |
| Invariant violations | **0** | **0** |
| Popular windows written | 410 | 878 |

Per-operation latency, milliseconds:

| Operation | Run | p50 | p95 | p99 | max | errors |
|---|---|---|---|---|---|---|
| START_TRANSACTION | default | 0.65 | 1.23 | 2.20 | 63.69 | 0 |
| START_TRANSACTION | stress | 15.69 | 25.45 | 35.26 | 256.42 | 0 |
| SCAN_ITEM | default | 1.24 | 2.19 | 3.55 | 240.70 | 0 |
| SCAN_ITEM | stress | 16.04 | 25.44 | 34.33 | 265.87 | 0 |
| COMPLETE_TRANSACTION | default | 15.16 | 32.68 | 47.44 | 339.50 | 0 |
| COMPLETE_TRANSACTION | stress | 114.67 | 185.50 | 326.29 | 894.20 | 0 |

### 3.2 Week 1 vs Week 2

| Metric | Week 1 (monolith) | Week 2 (layered) | Δ |
|---|---|---|---|
| **Default — transactions/sec** | 343.5 | 327.4 | −4.7% |
| Default — COMPLETE p50 | 14.99 | 15.16 | +1.1% |
| Default — COMPLETE p95 | 30.98 | 32.68 | +5.5% |
| Default — COMPLETE p99 | 43.92 | 47.44 | +8.0% |
| Default — SCAN p50 | 1.19 | 1.24 | +4.2% |
| Default — SCAN p99 | 3.13 | 3.55 | +13.5% |
| **Stress — transactions/sec** | 297.1 | 348.1 | +17.2% |
| Stress — COMPLETE p50 | 118.71 | 114.67 | −3.4% |
| Stress — COMPLETE p95 | 275.79 | 185.50 | −32.7% |
| Stress — COMPLETE p99 | 434.42 | 326.29 | −24.9% |
| Stress — SCAN p99 | 60.91 | 34.33 | −43.6% |
| **Errors** | 0 | 0 | — |

**Reading these numbers honestly — the stress column is not a layering win.**

Layering adds in-process method calls and object allocation. It cannot make
PostgreSQL row locks faster, and it plainly cannot buy 17% throughput. What
actually happened is that Week 1's *committed* stress run was a slow one:
Week 1's own lock-order A/B (ARCHITECTURE.md §3.2 at the `monolith` tag)
measured four clean runs of unchanged code spanning **324.8–347.8 tx/sec**, and
the committed 297.1 sat below that band.

Week 2's 348.1 lands at the top of that same band. Against the Week 1 *best*
clean run of 347.8 the difference is **+0.1%** — i.e. nothing. The right
conclusion is:

> **At stress, the cost of layering is indistinguishable from zero**, and the
> apparent improvement is run-to-run variance in the Week 1 baseline.

The **default** column is the more informative one, because at 10 stations the
database is not the bottleneck, so per-request CPU work shows up. There, every
number moved the *wrong* way by a small amount: throughput −4.7%, scan p99
+13.5%. That is the expected direction and rough size for the extra mapping —
each request now allocates a domain record and copies it into a DTO. Still a
single run each, so a few percent should not be over-read; the sign being
consistently negative across six metrics is the part worth noting.

### 3.3 The architecture test, before and after

`LayeredArchitectureTest` was written **before** the refactor and run against
Week 1's code:

| Rule | Before | After |
|---|---|---|
| `layerDependenciesAreRespected` | ❌ 4× "Layer *X* is empty" | ✅ |
| `apiDoesNotDependOnPersistence` | ❌ no classes matched | ✅ |
| `analyticsDependsOnNothingAbove` | ❌ no classes matched | ✅ |
| `persistenceDependsOnNothingAbove` | ❌ no classes matched | ✅ |
| `httpTypesDoNotLeakOutOfApi` | ❌ **47 violations** | ✅ |
| `configDependsOnNoLayer` | ✅ | ✅ |

Four of the five "before" failures are the same fact stated four ways: the
layers did not exist. To get a concrete picture, a temporary test mapped Week
1's package names onto the layers they were morally meant to be. It found:

- **23 violations** — the business layer constructing API DTOs
  (`CatalogService:28`, `InventoryService:45,47`, `PopularItemsService:151,175,180`,
  `TransactionService:141,230,245,262`).
- **HTTP types outside the API layer** — of which the ones that genuinely had
  to change were `TransactionService` (9 references to `HttpStatus`) and
  `ApiException` (3). The rest were controllers and the exception handler,
  which became legal simply by moving into `api`.

### 3.4 Measurement validity

The load client and server share one 8-core machine, so results are sensitive
to other CPU load. Every run reported here started with a 1-minute load average
below 4, and the server logged no `Thread starvation` warning. Both runs are
single runs; per §3.2 the Week 1 baseline's own spread was about 15%, so
differences smaller than that carry no signal.

---

## 4. The layered design

### 4.1 The layers

```mermaid
graph TD
    API["<b>api</b> — HTTP only · CLOSED<br/>4 controllers · dto/ (12 records) · error/"]
    TX["<b>transactions</b> — CLOSED<br/>CatalogService · TransactionService · InventoryService<br/>result/ (7 records) · exception/ (6 types)"]
    AN["<b>analytics</b> — OPEN<br/>AnalyticsService · ScanRecorder<br/>result/ (2 records) · pipeline/ (4-5 filters)"]
    PER["<b>persistence</b> — JPA only · CLOSED<br/>entity/ (8) · repository/ (6 + 2 projections)"]
    CFG["config — shared kernel<br/>SelfCheckoutProperties"]
    DB[("PostgreSQL")]

    API -->|"start · scan · complete<br/>catalog · low-stock"| TX
    API -.->|"popular-items<br/>skips transactions ⇒ OPEN"| AN
    TX -->|"ScanRecorder.recordScan(sku)"| AN
    TX --> PER
    AN --> PER
    PER --> DB
    API -.-> CFG
    TX -.-> CFG
    AN -.-> CFG

    classDef openLayer stroke-dasharray:5 5,stroke-width:2px
    class AN openLayer
```

Every arrow is one clause in `LayeredArchitectureTest`. If the diagram and the
test ever disagree, the test is right.

### 4.2 Closed and open layers

A **closed** layer must be passed *through*: you cannot reach what is beneath
it without going via it. That is what buys isolation — change persistence and
only the business layers notice. An **open** layer may be *bypassed*.

| Layer | Closed/open | Why |
|---|---|---|
| `api` | closed | Nothing sits above it. |
| `transactions` | closed | It never lets the API reach persistence. |
| `analytics` | **open** | Reached from `api` for the read-only popular-items query, and from `transactions` via `ScanRecorder` when a scan happens. |
| `persistence` | closed | Only business layers touch JPA. |

**Why analytics is open.** `GET /analytics/popular-items` is a read-only query
that the transactions layer has no opinion about. Forcing it through
transactions would mean adding a method whose entire body is
`return analytics.latest(limit);` — a **sinkhole**: a layer that adds a hop and
nothing else. Openness is the honest choice, and it is declared in the test
(`.whereLayer("Analytics").mayOnlyBeAccessedByLayers("API", "Transactions")`)
rather than left as an undocumented shortcut.

The cost is real: the API can now reach analytics without transactions
mediating, so a future rule about who may read analytics has two call sites to
police instead of one.

### 4.3 Where our layers are sinkholes — honestly

A sinkhole is a layer that receives a call and passes it straight on. Ours has
two clear cases:

- **`GET /items`.** `CatalogService.listItems()` fetches rows, maps entity →
  `CatalogItem`; the controller maps `CatalogItem` → `ItemDto`. Two mappings,
  zero business rules. The transactions layer adds nothing here today.
- **`GET /transactions/{id}`.** A read-through with one not-found check.

`POST /transactions/{id}/complete` is the opposite — the transactions layer is
where all the locking, ordering, clamping and alerting lives.

So the layering earns its keep on the write paths and costs a little on the
read paths. The usual escape hatch is to let simple reads bypass the business
layer, which is exactly the "open layer" idea already applied to analytics. We
did not apply it to the catalog, because `api → persistence` would let entities
reach the controllers, and that is the one leak most likely to cause real harm
later.

### 4.4 Dependency rules

| Layer | May be accessed by | May depend on |
|---|---|---|
| `api` | *nothing* | transactions, analytics, config |
| `transactions` | api | analytics *(via `ScanRecorder` only)*, persistence, config |
| `analytics` | api, transactions | persistence, config |
| `persistence` | transactions, analytics | JPA / Spring Data only |
| `config` | all | nothing |

`config` sits outside the four layers as a **shared kernel**: it holds settings
and no logic. The test pins it in place — `configDependsOnNoLayer` fails if it
ever grows a dependency on a layer that reads it.

### 4.5 Why `@Transactional` stays in the business layers

Transaction boundaries are a **business** decision, not a data-access one. "Lock
the basket, subtract every line's stock, record any threshold crossings, mark it
paid — all or nothing" is a statement about what a *sale* means. The persistence
layer cannot know where a unit of work starts and ends; it only knows how to
read and write rows. Push `@Transactional` down to repositories and every
repository call becomes its own transaction, which would destroy the atomicity
the entire correctness argument rests on.

### 4.6 Why the transactions → analytics call is one narrow interface

`TransactionService` needs exactly one fact recorded: a unit of a SKU was
scanned. It has no business knowing that a `scan_log` table exists, that scans
are numbered, or that windows are recomputed every 500 of them. `ScanRecorder`
is a single-method interface, so all of that lives behind it.

The `scan_log` INSERT itself moved into the analytics layer during the refactor,
from `TransactionService.scan()` into `AnalyticsService.recordScan()`, at the
same point in the sequence. `recordScan` is deliberately **not** annotated with
`@Transactional`, so it joins the scan's existing transaction — if the scan
rolls back, the log row rolls back with it, exactly as in Week 1.

### 4.7 Why low-stock stays in the transactions layer

The low-stock alert row is written inside `complete()`, in the same database
transaction and under the same item row lock as the stock decrement. Moving it
to its own layer would split one atomic unit of work across a layer boundary.
The alert is a consequence of the sale, not a separate concern.

---

## 5. Design decisions carried over from Week 1

These are unchanged in behaviour; only the file locations moved.

### 5.1 Stock is decremented at completion, never at scan
Required by the spec, and it is what keeps the hot path fast:
`transactions/TransactionService.scan()` never reads or locks `items.stock`.
From 10 to 100 stations, scan p95 grew 2.19 → 25.44 ms (+23 ms absolute,
11.6× relative) while completion p95 grew 32.68 → 185.50 ms (+153 ms absolute,
5.7× relative).

### 5.2 Pessimistic row locking, not optimistic
Two stations paying at once are two threads on two JDBC connections in two
independent Postgres sessions. Read-in-Java → decide → write-an-absolute-value
silently loses one decrement.
`persistence/repository/ItemRepository.findBySkuForUpdate` makes it atomic.
Optimistic locking was rejected on this workload's shape: with `SKU-000001` in
two thirds of baskets, most completions would collide and retry, and the
retries would collide again. **Measured: 0 lost updates across 61,529 completed
transactions in the two runs.**

### 5.3 Basket lines are locked in ascending SKU order
Locks are held until commit, so opposite acquisition orders deadlock.
`findByTransactionIdOrderBySkuAsc` gives every transaction one global order,
which removes the circular-wait condition entirely. Prevention, not detection.
`scripts/test-concurrent-completions.py` builds half its baskets in reverse
order as bait.

### 5.4 `applied_quantity` and the sold-out case
Scans are never gated by stock, so a hot SKU can be scanned more times than it
has units. `quantity` (scanned) goes on the receipt; `applied_quantity`
(actually subtracted) is what the invariant is checked against. CHECK 3 of the
invariant script surfaces the difference instead of hiding it.

### 5.5 Two-pass lock-then-mutate
`complete()` takes every item lock in one loop, then mutates in a second.
Identical semantics to one interleaved loop, but it avoids leaving an `Item`
dirty while further lock queries are still being issued, which would make
Hibernate auto-flush UPDATEs into the middle of the locking phase.

### 5.6 Considered alternative: the single-statement CTE decrement
```sql
WITH locked AS (SELECT sku, stock FROM items WHERE sku = :sku FOR UPDATE)
UPDATE items i SET stock = i.stock - LEAST(l.stock, :qty)
FROM locked l WHERE i.sku = l.sku
RETURNING LEAST(l.stock, :qty) AS applied;
```
Correct and cheaper — one round trip, shorter lock hold — and it works on
PostgreSQL 16 because the CTE captures the pre-update stock (`OLD.*` in
`RETURNING` would need PG 18). **Not adopted for readability, not because it is
impossible.**

### 5.7 Hopping window and BIGSERIAL gaps
Windows are `(start, end]`, width 1000, step 500. Boundaries are detected by
asking which multiple of the step a scan's `seq` has passed and claiming it with
`AtomicLong.compareAndSet` — **not** `seq % 500 == 0`, because a `BIGSERIAL`
burns numbers on rollback and an exact multiple may never appear. The aggregate
runs on one dedicated background thread, handed off via `afterCommit`.

### 5.8 Low-stock semantics: strict `<`
The spec says "drops below", so 50 is not low and 49 is; the reference mock
server agrees. Unlike the mock server, alerts are recorded at the moment of the
crossing so `triggeredAt` is a real trigger time. Ad-hoc `?threshold` applies to
one query and persists nothing; a SKU with no recorded crossing reports
`generatedAt`.

### 5.9 Assumption: one station drives one transaction sequentially
The basket upsert in `scan()` is a read-then-write, race-free only because a
station issues one scan at a time and waits for the response. An
`INSERT ... ON CONFLICT DO UPDATE` would remove the assumption.

### 5.10 Configuration
`spring.jpa.open-in-view=false`; Hikari `maximum-pool-size=32`; SQL logging off.
Unchanged from Week 1 so the two weeks are comparable. Week 1's thread dumps
showed this pool saturated at 100 stations with roughly two thirds of request
threads queued on it, which remains the dominant latency factor.

---

## 6. Week 3 — the analytics pipeline

### 6.1 Why only analytics

Checkout is not a pipeline and should not be forced into one. `complete()` is a
single atomic unit of work: lock the basket, lock each item row in SKU order,
clamp and decrement, record any threshold crossing, mark it paid — all or
nothing. Splitting that across stages connected by queues would mean splitting
one database transaction across threads, which either breaks atomicity or
reinvents a distributed commit for no gain.

The windowed analytics are the opposite: a stream of independent events, each
one cheap, where the answer is a periodic aggregate and a few hundred
milliseconds of lag costs nothing. That is exactly the shape a pipeline fits,
so that is the part that was refactored.

### 6.2 The design

Five filters, one thread each, connected by five bounded
`ArrayBlockingQueue`s behind a `Pipe<T>` interface. Full description and
diagram in **[PIPELINE.md](PIPELINE.md)**.

```
request thread --offer--> [ingest] --+--> [window] --> [rank] --> [sink] --> popular_snapshots
                                     |
                                     +--> [scan-log writer] --------------> scan_log
```

The pipeline lives entirely inside the `analytics` layer. The transactions
layer still calls exactly one method, `ScanRecorder.recordScan(sku)`, and knows
nothing about sequences, windows, queues or threads. `LayeredArchitectureTest`
still passes unchanged.

### 6.3 Correctness rules

1. **Only committed scans enter** — the handover is registered on
   `afterCommit`, so a rolled-back scan never reaches the pipeline. Same
   guarantee as Week 2.
2. **Thread confinement** — each filter's state is touched by one thread only,
   so there are no locks inside filters; every message between them is an
   immutable record.
3. **Identical window semantics** — size 1000, slide 500, half-open
   `(start, end]`, top 10, ties by SKU ascending.
4. **Asymmetric backpressure, deliberately** — see §6.4.
5. **Graceful shutdown drains** — the front of the pipeline is poisoned, each
   stage finishes its backlog before poisoning the next, and the writer flushes
   its final batch.
6. **A bad message never kills a stage** — caught per message, logged with the
   event, counted, loop continues.
7. **Duplicate windows fail loudly** — `UNIQUE (window_end)` on
   `popular_snapshots`, and the sink does **not** use `ON CONFLICT DO NOTHING`.
   One thread owns boundaries, so a duplicate could only be a bug; swallowing
   it would hide exactly what the constraint exists to catch.

**Why the Week 2 `AtomicLong` boundary logic is gone.** It existed because
`scan_log.seq` was a `BIGSERIAL`, and a rolled-back insert burns its number, so
an exact multiple of 500 might never appear and a window could be skipped
forever. The ingest filter numbers only committed scans, one at a time, on one
thread, so the sequence is gapless and `seq % 500 == 0` is now exactly right.

**One consequence to be careful about:** `scan_log.seq` is now assigned by the
application and the counter restarts at 1 on every boot. That is safe only
because `schema.sql` drops and recreates the table on every start. If
`spring.sql.init.mode` were ever turned off, the second start would collide on
the primary key. The warning is in `schema.sql` too.

### 6.4 Two kinds of backpressure

| Boundary | Behaviour | Why |
|---|---|---|
| **Between filters** | blocking `put` | A slow stage should make the stage above it wait. Nothing is discarded and the backlog stays inside the pipeline. |
| **Request thread → ingest** | non-blocking `offer`, 10 ms ceiling | A request thread must never wait on analytics. In Week 2 an analytics failure could not touch checkout, and that property is preserved: a refused event is counted, logged at WARN, and the scan completes normally. |

Without that split, a wedged analytics stage would fill 65,536 slots and then
block **every scan** — turning an analytics outage into a checkout outage. The
asymmetry is the price of keeping "analytics can never take checkout down"
true, and the drop counter makes any loss impossible to miss rather than
silent.

### 6.5 Observability — the evidence

Per-filter in/out/error counters, per-pipe depth, high-water depth and
full-wait counts, plus a drop counter, logged as one line every 10 seconds.

**Stress run (100 stations, 120 s), final line:**

```
ingest  in=456040 out=456040   window in=456040 out=912   rank in=912 out=912
sink    in=912    out=912      scanlog in=7298  out=456040
ingestPipe  max=5/65536     windowPipe max=5/65536     scanLogPipe max=238/65536
rankPipe    max=1/256       sinkPipe   max=1/256
droppedAtIngress=0
```

Reading it: **in == out at every stage**, so nothing was lost or stuck. The
deepest any pipe ever got was **238 of 65,536** (0.4 %), so backpressure never
engaged — **0 full-pipe waits, 0 drops, 0 stage errors, 0 WARN/ERROR lines**.
`scanlog in=7298` counts *batches* and `out=456040` counts *rows*, i.e. an
average of **62.5 rows per INSERT** — the adaptive batching working. At 10
stations the same average is 1.46, because the writer drains faster than events
arrive and there is simply nothing to batch.

Independently confirmed in the database: `scan_log` held exactly 456,040 rows
with `max(seq) = 456040` and 456,040 distinct values — gapless, no duplicates,
no loss.

**Shutdown test:** 1,200 scans sent, `SIGTERM` immediately after, process
exited cleanly; `scan_log` then held exactly 1,200 gapless rows.

### 6.6 Measured results

Both runs: server restarted beforehand, machine idle (1-minute load average
below 4), invariant check run before any restart.

| Metric | Default (10 st, 60 s) | Stress (100 st, 120 s) |
|---|---|---|
| Transactions | 20,545 | 43,436 |
| Transactions / sec | 342.3 | 361.2 |
| Items scanned | 216,507 | 456,040 |
| Scans / sec | 3,606.7 | 3,792.6 |
| **Total errors** | **0** | **0** |
| Invariant violations | **0** | **0** |
| Events dropped | **0** | **0** |
| Windows written | 433 | 912 |

| Operation | Run | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| START_TRANSACTION | default | 0.63 | 1.05 | 1.83 | 58.79 |
| START_TRANSACTION | stress | 15.23 | 24.40 | 33.64 | 235.59 |
| SCAN_ITEM | default | 1.08 | 1.67 | 2.52 | 30.21 |
| SCAN_ITEM | stress | 15.41 | 24.51 | 32.82 | 171.67 |
| COMPLETE_TRANSACTION | default | 17.25 | 34.78 | 45.46 | 92.65 |
| COMPLETE_TRANSACTION | stress | 113.21 | 173.70 | 305.87 | 731.03 |

0 errors across **800,509 total requests**.

### 6.7 Week 2 vs Week 3

| Metric | Week 2 | Week 3 | Δ |
|---|---|---|---|
| **Default — transactions/sec** | 327.4 | 342.3 | +4.6 % |
| Default — SCAN p50 | 1.24 | 1.08 | **−12.9 %** |
| Default — SCAN p99 | 3.55 | 2.52 | **−29.0 %** |
| Default — COMPLETE p50 | 15.16 | 17.25 | +13.8 % |
| Default — COMPLETE p99 | 47.44 | 45.46 | −4.2 % |
| **Stress — transactions/sec** | 348.1 | 361.2 | +3.8 % |
| Stress — SCAN p50 | 16.04 | 15.41 | −3.9 % |
| Stress — SCAN p99 | 34.33 | 32.82 | −4.4 % |
| Stress — COMPLETE p50 | 114.67 | 113.21 | −1.3 % |
| Stress — COMPLETE p99 | 326.29 | 305.87 | −6.3 % |
| **Errors** | 0 | 0 | — |

**Reading these honestly.** Week 2 measured roughly 15 % run-to-run variance on
throughput for unchanged code, so the **+4.6 % and +3.8 % throughput figures sit
inside the noise band and should not be claimed as a win**. Each configuration
was therefore run twice:

| | run 1 | run 2 | Week 2 |
|---|---|---|---|
| default tx/sec | 342.3 | 347.8 | 327.4 |
| default SCAN p99 | 2.52 | 2.40 | 3.55 |
| stress tx/sec | 361.2 | 355.0 | 348.1 |
| stress SCAN p99 | 32.82 | 34.05 | 34.33 |

The signal that *is* consistent and outside the noise is **scan latency at low
load**: SCAN p99 fell from 3.55 ms to 2.52 and 2.40 ms across both repeats, a
~30 % reduction, with p50 down ~13 %. That is the one thing the pipeline
actually targets — the per-scan `INSERT` left the request's transaction
entirely — and it is exactly where the design predicted the gain would show.

At 100 stations the picture is flatter, also as predicted: the binding
constraint there is the hot-row lock on `SKU-000001` during `complete` plus the
saturated connection pool, and the pipeline does not touch that path. The small
COMPLETE improvements are a plausible second-order effect of the scan path no
longer competing for pool connections, but they are within noise and are **not**
claimed as a result.

### 6.8 The async `scan_log` trade-off

`scan_log` rows are now written on a background thread in batches. On a hard
kill (`SIGKILL`, power loss) up to one unflushed batch plus whatever sat in the
pipes would be lost. This is analytics data only: checkout remains fully
transactional, and stock, receipts and the invariant are untouched.

A graceful stop loses nothing — verified. Every test run in this course starts
from a freshly recreated database, so there is no recovery-on-restart to build.
A production version would replay from a durable append-only log or a broker
with publisher confirms and manual acknowledgements.

### 6.9 Why not RabbitMQ or Kafka this week

The pipeline is in-process, so an in-memory queue hands a message to the next
filter in nanoseconds. A network broker would add a serialization step, a
socket round trip and a second process to operate — real latency and real
operational cost — in exchange for durability this workload does not need,
given the database is wiped before every run anyway.

The `Pipe<T>` interface exists precisely so that decision stays reversible: a
`RabbitPipe<T>` would be one more implementation and no change to any filter.
That is a worthwhile A/B experiment, but on a separate branch, not in the
submission.

### 6.10 What Week 3 did not change

Checkout behaviour is untouched: same schema for `items`/`transactions`/
`transaction_items`, same pessimistic row locks, same ascending SKU lock order,
same two-pass lock-then-mutate, same clamping and `applied_quantity`, same
low-stock semantics, same Hikari pool size of 32. Verified by a 27-call golden
response diff (byte-identical, including every 400/404/409 case) and by all
three checkout behaviour scripts passing unchanged.

---

## 7. Performance — making it do less, not making it work harder

The performance work (the second half of Week 3) kept the architecture and the contract exactly as they were and went
after throughput. Every claim here is backed by a run recorded in
[`perf-results.md`](perf-results.md), with the raw JSON in `reports/perf/`.

### 7.1 Result

| | baseline (`pipeline-v1`) | final | change |
|---|---|---|---|
| tx/s, 10 stations | 404.6 / 407.2 | **1031.8 / 1028.5** | **×2.54** |
| tx/s, 100 stations | 420.3 / 431.9 | **1007.8 / 1040.2** | **×2.40** |
| `SCAN` p50 / p99 | 0.85 / 2.86 ms | **0.09 / 0.40 ms** | −89 % / −86 % |
| `COMPLETE` p50, 100 st | 90.84 ms | **76.51 ms** | −16 % |
| errors | 0 | **0** of 4,624,769 requests | — |
| stock invariant | PASS | **PASS** | — |

### 7.2 A note on how these were launched

The Week 1–3 reports in [`reports/`](reports/) were produced with
`./mvnw spring-boot:run`. That plugin starts the JVM with
`-XX:TieredStopAtLevel=1`, which caps the JIT at C1 — worth about 20 % of
throughput. Every performance-work number, **including the baseline it is compared
against**, was taken from the packaged jar instead, so the comparison is
like-for-like. The earlier submitted reports have not been re-run or altered.

### 7.3 The one idea behind all of it

Nothing here is a faster algorithm. Every change removes database work from a
path that did not need it:

| | statements, `pipeline-v1` | statements, final |
|---|---|---|
| `POST /transactions` | 4 | **0** |
| `POST /{id}/items` (scan) | 7–8 + 3 background | **0** |
| `POST /{id}/complete` (L SKUs) | 3L + 5 ≈ 33 | **6** |
| `GET /items` | 1 | **0** |
| held under the item lock | 3L + 2 ≈ 30 | **3** |

Three structural changes got there:

1. **An unpaid basket is not a database row.** It lives in memory until the
   customer pays (`OpenBasket`, `OpenTransactionStore`). The database only
   ever sees baskets that were actually paid for.
2. **The catalog is immutable, so it is cached.** 2,000 rows, read once.
3. **One checkout, one round of bulk statements.** One `SELECT ... IN (...)
   ORDER BY sku FOR NO KEY UPDATE` for the whole basket instead of one per
   SKU, then bulk `UPDATE`/`INSERT`s, instead of Hibernate's per-row
   merge-then-write.

### 7.4 What actually limits it now, measured

`CheckoutTimings` splits a completion's database time in two:

| | 10 stations | 100 stations |
|---|---|---|
| lock **wait** | 7.2 ms | 93–96 ms |
| **critical section** (lock granted → commit) | 1.02 ms | 1.01–1.04 ms |

The wait grows 13× with load; the critical section does not move. At 100
stations roughly **99 % of a completion's database time is queueing** for the
hottest SKU's row and about 1 % is work.

CPU confirms it is not a resource problem — sampled during the final runs, out
of 800 % available: server ~100 %, Postgres ~95–134 %, load client ~111–126 %,
**total ~310–360 %**. The server uses about one core. (The baseline burned
~505 % to do 405 tx/s; most of the removed work was round trips, not
computation.)

Because every basket containing the hottest SKU passes through that critical
section one at a time, it is what sets the ceiling:

```
ceiling ≈ 1 / (critical section × fraction of baskets holding that SKU)
```

which is why the only change that raised it was taking a statement *out* of
the critical section (writing the transaction header before the lock:
−9.8 % critical section, +3.7 % throughput at 10 stations).

### 7.5 Trade-offs accepted

These are costs, stated as costs:

| Change | What it buys | What it costs |
|---|---|---|
| Baskets in memory | ~87 statements per basket gone (4 to start + 7–8 per scan) | **An unpaid basket does not survive a restart**, and the design is now firmly single-process: two servers would not see each other's baskets. A *paid* basket is as durable as before. |
| Catalog cached | every price lookup is a map read | **Price and name changes need a restart.** Safe only because this catalog is seeded once and never updated. |
| Raw scan persistence off by default | ~1.25 M inserts per stress run gone | **No row-level audit trail** unless switched back on. The graded requirement — top-10 windows in the database — is unaffected, because windows are computed from the event stream, not from those rows. |
| Separate 2-connection pool for the pipeline | sink write 66.9 ms → 0.43 ms | Two more Postgres connections, and a second pool to reason about. |
| Pool size 32, not 16 | ~2.3 % more throughput | **A worse tail**: p99 307 ms vs 229 ms at 16. If tail latency mattered more than throughput, 16 is the better setting (§7.6). |

### 7.6 Choices measured and *not* taken

- **Connection pool 64.** 10.6 % *worse* than 32, with p99 doubling to 511 ms.
  More connections do not make a serialized row less serial; they just move
  the queue into Postgres, and with 8 cores they make the critical section
  itself slower (1,065 → 1,159 µs).
- **Plain `FOR UPDATE` on the bulk lock.** Collapsed the server to 2.9 tx/s
  with 9,239 errors and deadlocks. `scan_log`, `transaction_items` and
  `low_stock_alerts` all have a foreign key to `items`, so Postgres takes a
  `FOR KEY SHARE` lock on the parent row when it checks one — compatible with
  `FOR NO KEY UPDATE`, in conflict with `FOR UPDATE`. Reverted.
- **Folding the stock `UPDATE` and the line `INSERT` into one CTE.** Would
  remove a round trip from the critical section; rejected because it fuses two
  cleanly separated persistence classes into one SQL string for an expected
  few percent.
- **`synchronous_commit = off`.** Would cut the commit cost, which is a large
  part of the remaining 1.0 ms. Rejected: a crash could lose paid
  transactions.

### 7.7 Measurement honesty

Two things are worth stating rather than hiding.

**The machine throttles.** After ~45 minutes of continuous stress runs,
throughput on an *unchanged* binary fell from ~1,036 to 805 tx/s and in the
worst period to ~330. The critical section — the server's own work — grew from
1,013 to 1,318 µs, and operations that touch no database slowed by the same
fraction, so this is the machine, not the code. The pool-size sweep was
therefore re-run **interleaved** (16, 32, 64, 16, 32, 64, …) after a cooldown,
so drift is shared across arms rather than charged to whichever ran last.
Every contaminated run is kept in `reports/perf/contaminated-thermal/` and
labelled, not deleted.

**One change at a time.** Each phase was measured on its own, and one was
reverted outright. The reverted run is in the results table with its real
numbers.

### 7.8 A regression this work introduced, and how it was caught

Moving baskets into memory silently changed the order of `lines[]` on a
receipt. `OpenBasket` stored lines in a `LinkedHashMap` — correctly, so they
appear in the order first scanned — but published snapshots with
`Map.copyOf`, whose iteration order is unspecified. No test covered line
order; the 28-response golden diff against the pipeline-v1 build did. Fixed,
and now locked down by `OpenBasketTest.linesKeepFirstScanOrder`, which was
confirmed to fail against the old code.

---

## 8. Known limitations

1. **Layering changed nothing operational.** One process, one database, one
   point of failure, vertical scaling only (§2, priority 3).
2. **Seven near-duplicate result records** and their mapping code are pure
   overhead today (§2, priority 2).
3. **Two read paths are sinkholes** (§4.3).
4. **`analytics` is open**, so "who may read analytics" has two call sites.
5. **Single measurement runs**, against a Week 1 baseline whose own spread was
   about 15% (§3.2, §3.4).
6. **Client and server share one machine.**
7. **~~The pool size is reasoned, not optimized~~** — measured in the Week 3 performance work
   (§7.6). 32 is a deliberate throughput-over-tail-latency choice; 16 would
   give a better p99.
8. **Analytics are eventually consistent** by design (§5.7).
9. **The basket upsert assumes sequential scans per transaction** (§5.9).
10. **`scan_log` is written asynchronously** — a hard kill can lose up to one
    unflushed batch of analytics rows (§6.8).
11. **The scan sequence restarts at 1 on every boot**, which is safe only
    because the schema is recreated on every start (§6.3).
12. **The throughput gain is within run-to-run noise** (§6.7); only the
    low-load scan latency improvement is outside it. *(Week 3 statement. Week
    4's gain is ×2.4–2.5 and far outside noise — see §7.)*
13. **An unpaid basket is lost on restart**, and the in-memory basket store
    makes the single-process assumption explicit rather than incidental
    (§7.5).
14. **The catalog is cached at first use**, so price or name changes require a
    restart (§7.5).
15. **The ceiling is one row's serialization.** At 100 stations ~99 % of a
    completion's database time is spent queueing for the hottest SKU. Going
    materially faster means sharding that row, not tuning the server (§7.4).
16. **Measurements are sensitive to machine thermal state** (§7.7).

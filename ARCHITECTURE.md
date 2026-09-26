# Architecture — Self-Checkout, Layered

Week 2 of the semester project. Same API contract, same database, same
concurrency behaviour as Week 1's monolith — the **only** variable is how the
code is structured.

Week 1 organized classes by technical type (`controller/`, `service/`,
`repository/`). That reads like layers but nothing enforced it: HTTP types had
already leaked into the business code, and the services built API-shaped DTOs.
This week the layers are real packages with **dependency rules enforced by a
test that fails the build**.

All numbers come from the two JSON reports in [`reports/`](reports/), produced
by the unmodified course load client against the code as committed. Anything
not measured is labelled a prediction.

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
| 7 | **Performance / latency (default load)** | At 10 stations for 60 s: `SCAN_ITEM` p95 < 10 ms, `COMPLETE_TRANSACTION` p95 < 100 ms. | Default report. **Measured: scan p95 2.19 ms, complete p95 32.68 ms.** |
| 8 | **Performance / latency (stress)** | At 100 stations for 120 s: `COMPLETE_TRANSACTION` p99 < 1000 ms, no request exceeds the client's 10 s timeout. | Stress report. **Measured: complete p99 326.29 ms, max 894.20 ms.** |
| 9 | **Throughput** | Sustain ≥ 1000 scans/sec at 100 stations. | Stress report. **Measured: 3651.7 scans/sec, 348.1 transactions/sec.** |
| 10 | **Scalability** | 10 → 100 stations must not break any correctness requirement; degradation must be latency only. | Both runs pass CHECK 1 and CHECK 2 identically. **Measured: correctness unchanged.** |
| 11 | **Availability / robustness** | Zero errors on every operation for the full duration of both runs; no deadlocks, no connection-acquisition timeouts, no unhandled exceptions. | Client error counts + server log scan. **Measured: 0 errors of 767,418 total requests (244,456 default + 522,962 stress); 0 deadlocks; 0 WARN/ERROR lines.** |
| 12 | **Analytics consistency** | One window written per `slideInterval` scans, each covering `windowSize` scans, ranks 1-based and dense. | `scripts/test-popular-window.py` + snapshot counts. **Measured: 410 windows for 205,130 scans; 878 for 439,230.** |
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
    AN["<b>analytics</b> — OPEN<br/>AnalyticsService · ScanRecorder<br/>result/ (2 records) · RecomputeExecutorConfig"]
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

## 6. Known limitations

1. **Layering changed nothing operational.** One process, one database, one
   point of failure, vertical scaling only (§2, priority 3).
2. **Seven near-duplicate result records** and their mapping code are pure
   overhead today (§2, priority 2).
3. **Two read paths are sinkholes** (§4.3).
4. **`analytics` is open**, so "who may read analytics" has two call sites.
5. **Single measurement runs**, against a Week 1 baseline whose own spread was
   about 15% (§3.2, §3.4).
6. **Client and server share one machine.**
7. **The pool size is reasoned, not optimized**, and is the binding constraint
   at 100 stations.
8. **Analytics are eventually consistent** by design (§5.7).
9. **The basket upsert assumes sequential scans per transaction** (§5.9).

# Self-Checkout System — Layered Architecture with a Pipelined Analytics Stage

> ## Week 3 submission
>
> **The two load-client JSON reports:**
> - [`reports/report-default-10stations-60s.json`](reports/report-default-10stations-60s.json) — default, 10 stations, 60 s: **1031.8 tx/s, 0 errors**
> - [`reports/report-stress-100stations-120s.json`](reports/report-stress-100stations-120s.json) — stress, `--stations=100 --duration=120`: **1007.8 tx/s, 0 errors**
>
> **The filter description: [PIPELINE.md](PIPELINE.md).**
>
> The system has a **4-stage pipeline**: **ingest** (numbers each scan with a gapless
> global sequence) → **window** (last 1000 scans in a ring buffer, emits a counts snapshot
> every 500) → **rank** (top 10, ties by SKU ascending, names attached) → **sink** (batches
> windows and writes them to `popular_snapshots`). **Ingest** optionally also tees to a
> fifth filter, the **scan-log writer** (batched multi-row INSERT into `scan_log`), which is
> off by default. The pipes are implemented with bounded Java `ArrayBlockingQueue`s, one
> thread per filter.
>
> The throughput is well above the `pipeline-v1` tag because a separate round of checkout
> performance work followed the pipeline — see
> [PIPELINE.md § Beyond the pipeline](PIPELINE.md#beyond-the-pipeline) and
> [perf-results.md](perf-results.md).

A monolithic backend for a supermarket self-checkout system: customers start a
transaction at a station, scan items one unit at a time, then pay. Stock is decremented
at payment, low-stock alerts are recorded as they happen, and item popularity is tracked
over a hopping window.

Spring Boot 4.1.1 (Java 21) over PostgreSQL 16, implementing the shared course contract
in `CS6510-2026/spec/self-checkout-openapi.yaml`.

The code is split into four layers — **api**, **transactions**, **analytics**,
**persistence** — and the dependency rules between them are enforced by an ArchUnit test,
not by convention.

The windowed analytics run as a **pipeline of filters connected by pipes** (4 stages, 5 with raw scan persistence on):
**ingest** (numbers each committed scan and tees it) → **window** (last 1000 scans, emits
every 500) → **rank** (top 10, ties by SKU) → **sink** (writes `popular_snapshots`), with
ingest also teeing to an optional **scan-log writer** (batched multi-row INSERT into
`scan_log`, off by default — see [Configuration](#configuration)).
The pipes are bounded Java `ArrayBlockingQueue`s, one thread per filter.
**See [PIPELINE.md](PIPELINE.md)** for the full description and diagram.

**New to this codebase? Start with [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md)** — a full
walkthrough with diagrams: how a request flows, what every table and column is for,
an API reference, a file-by-file tour, and plain-English deep dives on the concurrency
work (the lost-update race, lock ordering, the sold-out case, the hopping window).

See [ARCHITECTURE.md](ARCHITECTURE.md) for the architectural characteristics, the
prioritized trade-offs, measured results, and the design decisions behind the
concurrency handling.

See [perf-results.md](perf-results.md) for the performance work: every change, what it
measured before and after, and the ones that were tried and reverted. Note that
**performance runs must use the packaged jar**, not `./mvnw spring-boot:run` — the Maven
plugin starts the JVM with `-XX:TieredStopAtLevel=1`, which caps the JIT and costs about
20 % throughput:

```bash
./mvnw -DskipTests package
java -Xmx1g -jar target/self-checkout-0.0.1-SNAPSHOT.jar
```

---

## Prerequisites

| Requirement | Version used |
|---|---|
| JDK (full JDK, `javac` needed for the load client) | 21 |
| PostgreSQL | 16 (Homebrew) |
| Maven | bundled wrapper `./mvnw` — nothing to install |

---

## Create the database

The application connects as the OS user with no password (Homebrew trust auth). Adjust
`src/main/resources/application.properties` if your setup differs.

```bash
brew services start postgresql@16
createdb self_checkout
```

That is the only manual database step. Tables and seed data are created by the
application itself — see "Reset" below.

---

## Run

```bash
./mvnw spring-boot:run
```

The server listens on `http://localhost:8080`.

On every boot it runs `src/main/resources/schema.sql` then `data.sql`, which drop and
recreate all tables and seed the catalog with **2000 items** (`SKU-000001` …
`SKU-002000`), each with **10,000 units** in stock.

### Quick check

```bash
curl -s localhost:8080/items | head -c 200

TX=$(curl -s -X POST localhost:8080/transactions \
      -H 'Content-Type: application/json' -d '{"stationId":"station-01"}' \
     | sed 's/.*"transactionId":"\([^"]*\)".*/\1/')

curl -s -X POST "localhost:8080/transactions/$TX/items" \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-000001"}'

curl -s -X POST "localhost:8080/transactions/$TX/complete"
```

---

## Reset

**Restarting the server is the reset.** Stop it and start it again; `schema.sql` drops
every table and `data.sql` reseeds the catalog, so you are back to a clean 2000 × 10,000
state with no transactions, no scans, no alerts and no snapshots.

There is no separate reset command, and no manual SQL is needed.

> Because a restart wipes everything, **run the invariant check before restarting** if
> you want to inspect the results of a load test.

---

## API

| Endpoint | Purpose |
|---|---|
| `GET /items` | Full catalog (sku, name, price) |
| `POST /transactions` | Start a transaction — body `{"stationId":"..."}` → 201 |
| `POST /transactions/{id}/items` | Scan one unit — body `{"sku":"..."}` |
| `POST /transactions/{id}/complete` | Pay, decrement stock, return a receipt |
| `GET /transactions/{id}` | Transaction status (debugging) |
| `GET /inventory/low-stock?threshold=` | Current low-stock alerts |
| `GET /analytics/popular-items?limit=` | Most-scanned items in the latest window |

Errors are `{"error":"CODE","message":"..."}` with codes `INVALID_REQUEST`,
`TRANSACTION_NOT_FOUND`, `TRANSACTION_NOT_OPEN`, `TRANSACTION_EMPTY`, `ITEM_NOT_FOUND`.

### Configuration

In `src/main/resources/application.properties`:

| Property | Default | Meaning |
|---|---|---|
| `selfcheckout.low-stock-threshold` | 50 | A SKU is low when stock is **strictly below** this |
| `selfcheckout.popular-window-size` | 1000 | Scans covered by the popularity window |
| `selfcheckout.popular-slide-interval` | 500 | How often (in scans) the window is recomputed |
| `selfcheckout.analytics.persist-raw-scans` | `false` | Write every individual scan to `scan_log`. Off because the windows are computed in memory and the raw rows are only an audit trail. `scripts/test-popular-window.py` turns it on. |
| `selfcheckout.analytics.writer-pool-size` | 2 | Connections for the pipeline's background writers, in their own pool so they never queue behind checkout |
| `spring.datasource.hikari.maximum-pool-size` | 32 | See ARCHITECTURE.md §4.10 for why |

To run with the audit trail on:

```bash
java -Xmx1g -jar target/self-checkout-0.0.1-SNAPSHOT.jar \
  --selfcheckout.analytics.persist-raw-scans=true
```

---

## Run the load tests

Build the course load client once:

```bash
cd ../CS6510-2026/load-client
bash build.sh
```

**Restart the server before every run**, so each run starts from a full catalog.

```bash
# Default run: 10 stations, 60 seconds
bash run.sh --baseUrl=http://localhost:8080 --stations=10 --duration=60

# Stress run: 100 stations, 120 seconds
bash run.sh --baseUrl=http://localhost:8080 --stations=100 --duration=120
```

Each run prints a console report and writes a JSON report to `--reportDir`
(default `./reports`). The two committed runs are in [`reports/`](reports/).

Results are sensitive to other load on the machine, since the client and server share
one host — check that the load average is low before starting a run.

---

## Run the correctness checks

### Invariant check — after a load test, before restarting

```bash
psql self_checkout -f scripts/check-invariant.sql
```

Reports three things:

1. Any SKU with negative stock — must be **0 rows**.
2. Any SKU where `10000 − stock ≠ SUM(applied_quantity)` over completed transactions —
   must be **0 rows**. This is the property the spec says naive implementations break.
3. Sold-out SKUs and how many scanned units the shelf could not cover — informational,
   so clamping stays visible.

Plus a one-line summary of violations, completed transactions, total scans, windows
written and alerts recorded.

### Behavioural tests — against a freshly restarted server

Each script restarts nothing itself. **Restart the server before each one** — they
assume a clean database (stock back at 10,000, no alerts, empty `scan_log`). Running
them back to back without a restart fails on leftover state rather than on a real bug.

```bash
python3 scripts/test-concurrent-completions.py        # no lost updates, no deadlocks
python3 scripts/test-oversell-and-double-complete.py  # clamping + one-winner completion
python3 scripts/test-low-stock.py                     # threshold semantics
python3 scripts/test-scan-complete-race.py            # a scan racing a complete cannot
                                                      # corrupt a paid transaction
# this one needs the audit trail, so start the server with persist-raw-scans=true
python3 scripts/test-popular-window.py                # hopping window cadence + contents
```

Every script prints `PASS`/`FAIL` per assertion and exits non-zero on failure. The header
of each file explains what it proves and why it matters.

---

## Project layout

```
src/main/java/com/selfcheckout/self_checkout/
  api/            HTTP only — controllers, dto/ (wire shapes), error/ (exception → status)
  transactions/   checkout business rules — services, result/, exception/
                  OpenBasket + OpenTransactionStore: unpaid baskets live in memory
  analytics/      popularity windows — AnalyticsService, ScanRecorder, result/
    pipeline/     the filters, the Pipe abstraction, wiring and metrics
  persistence/    database access only — entity/, repository/
                  AnalyticsWriterPool: a separate 2-connection pool for the pipeline
  config/         shared kernel — SelfCheckoutProperties
src/test/java/com/selfcheckout/self_checkout/
  architecture/   LayeredArchitectureTest — the dependency rules, as a test
src/main/resources/
  schema.sql            dropped and recreated on every boot
  data.sql              seeds 2000 items x 10,000 stock
  application.properties
scripts/        invariant check + behavioural tests
reports/        the committed load-test reports
  perf/         every raw run from the performance work, including reverted ones
```

Dependencies only ever point downwards: `api → transactions → persistence`, with
`analytics` sitting below transactions and reachable directly from `api` for its one
read-only endpoint. See [ARCHITECTURE.md](ARCHITECTURE.md) §4 for why, and
[DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) §4.5 for a request crossing all four.

## Run the architecture test

```bash
./mvnw test -Dtest=LayeredArchitectureTest     # six rules, ~1s, no database
./mvnw test                                     # all 29 tests
```

The suite also covers the analytics pipeline with no Spring and no database: the
window, rank, scan-log writer and pipe classes individually, plus an end-to-end test
that pushes 2,600 events through the wired filters on real threads and compares every
emitted window against a brute-force recount.

> **`./mvnw test` boots the application, which re-runs `schema.sql` and wipes the
> database.** Never run it between a load test and its invariant check.

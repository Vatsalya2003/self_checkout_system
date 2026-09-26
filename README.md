# Self-Checkout System — Layered Architecture

A monolithic backend for a supermarket self-checkout system: customers start a
transaction at a station, scan items one unit at a time, then pay. Stock is decremented
at payment, low-stock alerts are recorded as they happen, and item popularity is tracked
over a hopping window.

Spring Boot 4.1.1 (Java 21) over PostgreSQL 16, implementing the shared course contract
in `CS6510-2026/spec/self-checkout-openapi.yaml`.

The code is split into four layers — **api**, **transactions**, **analytics**,
**persistence** — and the dependency rules between them are enforced by an ArchUnit test,
not by convention.

**New to this codebase? Start with [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md)** — a full
walkthrough with diagrams: how a request flows, what every table and column is for,
an API reference, a file-by-file tour, and plain-English deep dives on the concurrency
work (the lost-update race, lock ordering, the sold-out case, the hopping window).

See [ARCHITECTURE.md](ARCHITECTURE.md) for the architectural characteristics, the
prioritized trade-offs, measured results, and the design decisions behind the
concurrency handling.

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
| `spring.datasource.hikari.maximum-pool-size` | 32 | See ARCHITECTURE.md §4.10 for why |

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

Each script restarts nothing itself; restart the server first so stock is back at 10,000.

```bash
python3 scripts/test-concurrent-completions.py        # no lost updates, no deadlocks
python3 scripts/test-oversell-and-double-complete.py  # clamping + one-winner completion
python3 scripts/test-low-stock.py                     # threshold semantics
python3 scripts/test-popular-window.py                # hopping window cadence + contents
```

Every script prints `PASS`/`FAIL` per assertion and exits non-zero on failure. The header
of each file explains what it proves and why it matters.

---

## Project layout

```
src/main/java/com/selfcheckout/self_checkout/
  api/            HTTP only — controllers, dto/ (wire shapes), error/ (exception → status)
  transactions/   checkout business rules — services, result/ (domain records), exception/
  analytics/      popularity windows — AnalyticsService, ScanRecorder, result/
  persistence/    database access only — entity/, repository/
  config/         shared kernel — SelfCheckoutProperties
src/test/java/com/selfcheckout/self_checkout/
  architecture/   LayeredArchitectureTest — the dependency rules, as a test
src/main/resources/
  schema.sql            dropped and recreated on every boot
  data.sql              seeds 2000 items x 10,000 stock
  application.properties
scripts/        invariant check + behavioural tests
reports/        the two committed load-test reports
```

Dependencies only ever point downwards: `api → transactions → persistence`, with
`analytics` sitting below transactions and reachable directly from `api` for its one
read-only endpoint. See [ARCHITECTURE.md](ARCHITECTURE.md) §4 for why, and
[DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) §4.5 for a request crossing all four.

## Run the architecture test

```bash
./mvnw test -Dtest=LayeredArchitectureTest     # six rules, ~1s, no database
./mvnw test                                     # also runs contextLoads
```

> **`./mvnw test` boots the application, which re-runs `schema.sql` and wipes the
> database.** Never run it between a load test and its invariant check.

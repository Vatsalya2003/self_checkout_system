# Self-Checkout System — Service-Based Architecture

> ## Week 4 submission
>
> **The service list: [SERVICES.md](SERVICES.md).**
>
> **The two load-client JSON reports (through the gateway on :8080):**
> - [`reports/report-default-10stations-60s.json`](reports/report-default-10stations-60s.json) — default, 10 stations, 60 s: **1015.8 tx/s, 0 errors** (Week 3: 1031.8)
> - [`reports/report-stress-100stations-120s.json`](reports/report-stress-100stations-120s.json) — stress, `--stations=100 --duration=120`: **960.1 tx/s, 0 errors** (Week 3: 1007.8)
>
> Every run: 0 dropped scan events, a gapless `scan_log`, every popularity window equal
> to a brute-force recount, and the stock invariant PASS. Analytics stays within about
> 700 scans (~0.1 s) of checkout. The raw runs are in [`reports/service-based/`](reports/service-based/).
>
> The single application is now **four services**: **catalog** (:8081), **checkout**
> (:8082), **inventory** (:8083) and **analytics** (:8084). Each is its own jar and
> process, and all four share **one PostgreSQL database**. An **nginx gateway** on :8080
> routes by path, so the load client and the OpenAPI contract are unchanged. The
> services never call each other. Checkout hands every scan to analytics through the
> `scan_log` table, which is now **always written** (the Week 3 `persist-raw-scans`
> flag is gone). Design, ownership matrix and trade-offs:
> [ARCHITECTURE.md § Week 4](ARCHITECTURE.md#week-4-service-based-architecture).

> ### Previous: Week 3 submission (tag `pipeline-v2`)
>
> **The two load-client JSON reports:**
> - [`reports/report-default-10stations-60s.json` at `pipeline-v2`](https://github.com/Vatsalya2003/self_checkout_system/blob/pipeline-v2/reports/report-default-10stations-60s.json) — default, 10 stations, 60 s: **1031.8 tx/s, 0 errors**
> - [`reports/report-stress-100stations-120s.json` at `pipeline-v2`](https://github.com/Vatsalya2003/self_checkout_system/blob/pipeline-v2/reports/report-stress-100stations-120s.json) — stress, `--stations=100 --duration=120`: **1007.8 tx/s, 0 errors**
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

---

A backend for a supermarket self-checkout system: customers start a transaction at a
station, scan items one unit at a time, then pay. Stock is decremented at payment,
low-stock alerts are recorded as they happen, and item popularity is tracked over a
hopping window.

Spring Boot 4.1.1 (Java 21) over PostgreSQL 16, implementing the shared course contract
in `CS6510-2026/spec/self-checkout-openapi.yaml`.

- **[SERVICES.md](SERVICES.md)**: the four services, what each owns, how they stay apart.
- **[DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md)**: a walkthrough with diagrams, starting with
  how a scan crosses gateway → checkout → `scan_log` → analytics.
- **[ARCHITECTURE.md](ARCHITECTURE.md)**: characteristics, trade-offs and measured results,
  week by week.
- **[PIPELINE.md](PIPELINE.md)**: the analytics filters (Week 3; they now run inside
  analytics-service).
- **[perf-results.md](perf-results.md)**: the checkout performance work.

**Performance runs must use the packaged jars** (`java -jar`, which is what
`scripts/start-all.sh` does), not `./mvnw spring-boot:run`. The Maven plugin caps the JIT
and costs about 20 % throughput.

---

## Prerequisites

| Requirement | Version used |
|---|---|
| JDK (full JDK, `javac` needed for the load client) | 21 |
| PostgreSQL | 16 (Homebrew) |
| nginx (the gateway) | 1.31 (Homebrew: `brew install nginx`) |
| Maven | bundled wrapper `./mvnw`, nothing to install |

---

## Create the database

Each service connects as **its own PostgreSQL role** (`catalog_svc`, `checkout_svc`,
`inventory_svc`, `analytics_svc`), with no password (Homebrew trust auth). `db/reset.sh`
creates the roles the first time it runs, and re-grants each one only the tables its
service uses (`db/roles.sql`). So run it as a user that may create roles: the Homebrew
default user is a superuser. Adjust the `spring.datasource.*` lines in each
`*-service/src/main/resources/application.properties` if your setup differs.

```bash
brew services start postgresql@16
createdb self_checkout
db/reset.sh                       # creates the tables and seeds the catalog
```

---

## Run

```bash
scripts/start-all.sh --build      # build the 4 jars, start them, then the gateway
scripts/stop-all.sh               # stop the gateway and all four services
```

`start-all.sh` (without `--build` it only builds if a jar is missing) starts each
service as `java -Xmx… -jar <service>/target/<service>-0.0.1-SNAPSHOT.jar`, waits until
each answers, and then starts nginx on :8080. Logs go to `logs/<service>.log`.

| Port | Process |
|---|---|
| **8080** | **gateway (nginx): the one base URL clients use** |
| 8081 | catalog-service |
| 8082 | checkout-service |
| 8083 | inventory-service |
| 8084 | analytics-service |

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

```bash
scripts/stop-all.sh && db/reset.sh && scripts/start-all.sh
```

`db/reset.sh` runs `db/schema.sql` and `db/data.sql`. They drop and recreate every table
and seed **2000 items** (`SKU-000001` … `SKU-002000`) with **10,000 units** each.
**Restarting the services no longer resets anything**: they run with
`spring.sql.init.mode=never`, because four processes must not each recreate a shared
schema. Restart them anyway after a reset. Checkout keeps open baskets and the next
`scan_log` seq in memory, and analytics keeps its window in memory.

> Run the invariant and window checks **before** `db/reset.sh` if you want to inspect a
> load test's results.

---

## API

Unchanged from the contract, all on `http://localhost:8080`:

| Endpoint | Purpose | Served by |
|---|---|---|
| `GET /items` | Full catalog (sku, name, price) | catalog |
| `POST /transactions` | Start a transaction — body `{"stationId":"..."}` → 201 | checkout |
| `POST /transactions/{id}/items` | Scan one unit — body `{"sku":"..."}` | checkout |
| `POST /transactions/{id}/complete` | Pay, decrement stock, return a receipt | checkout |
| `GET /transactions/{id}` | Transaction status (debugging) | checkout |
| `GET /inventory/low-stock?threshold=` | Current low-stock alerts | inventory |
| `GET /analytics/popular-items?limit=` | Most-scanned items in the latest window | analytics |

Errors are `{"error":"CODE","message":"..."}` with codes `INVALID_REQUEST`,
`TRANSACTION_NOT_FOUND`, `TRANSACTION_NOT_OPEN`, `TRANSACTION_EMPTY`, `ITEM_NOT_FOUND`.

### Configuration

Each service has its own `src/main/resources/application.properties`:

| Property | Service | Default | Meaning |
|---|---|---|---|
| `selfcheckout.low-stock-threshold` | checkout, inventory | 50 | A SKU is low when stock is **strictly below** this |
| `selfcheckout.popular-window-size` | analytics | 1000 | Scans covered by the popularity window |
| `selfcheckout.popular-slide-interval` | analytics | 500 | How often (in scans) the window is recomputed |
| `selfcheckout.scan-log.max-linger-ms` | checkout | 50 | How long the scan-log writer collects scans (up to 1,000) before one INSERT |
| `selfcheckout.scan-log.writer-pool-size` | checkout | 2 | Connections for the scan-log writer, in their own pool so it never queues behind a payment |
| `spring.datasource.hikari.maximum-pool-size` | each | 2 / 32 / 3 / 4 | catalog / checkout / inventory / analytics: 43 connections in total with the writer pool, well under PostgreSQL's 100 |

`scan_log` is always written; there is no switch for it any more.

---

## Run the load tests

Build the course load client once:

```bash
cd ../CS6510-2026/load-client
bash build.sh
```

**Reset and restart before every run**, so each run starts from a full catalog:

```bash
scripts/stop-all.sh && db/reset.sh && scripts/start-all.sh

# Default run: 10 stations, 60 seconds
bash run.sh --baseUrl=http://localhost:8080 --stations=10 --duration=60

# Stress run: 100 stations, 120 seconds
bash run.sh --baseUrl=http://localhost:8080 --stations=100 --duration=120
```

The submitted pair is in [`reports/`](reports/); every Week 4 run, including the raw
ones, is in [`reports/service-based/`](reports/service-based/). Results are sensitive to
other load on the machine, because the client, the gateway, four JVMs and PostgreSQL share
one host. Check that the load average is low before starting a run.

---

## Run the correctness checks

### After a load test, before resetting

```bash
psql self_checkout -f scripts/check-invariant.sql
psql self_checkout -f scripts/check-windows.sql
```

`check-invariant.sql`: no SKU with negative stock, and for every SKU
`10000 − stock = SUM(applied_quantity)` over completed transactions. It also lists sold-out
SKUs and prints a one-line summary.

`check-windows.sql`: wait for analytics to catch up first (its log shows `behind scan_log
now=0`). It checks that `scan_log` holds exactly seqs `1..max` with no gap, and that
**every** stored window equals a brute-force SQL recount of `scan_log` over the same range.

### Behavioural tests, each against a fresh reset

```bash
python3 scripts/test-concurrent-completions.py        # no lost updates, no deadlocks
python3 scripts/test-oversell-and-double-complete.py  # clamping + one-winner completion
python3 scripts/test-low-stock.py                     # threshold semantics
python3 scripts/test-scan-complete-race.py            # a scan racing a complete cannot
                                                      # corrupt a paid transaction
python3 scripts/test-popular-window.py                # hopping window cadence + contents
scripts/test-service-restart.sh                       # stop each service once: the others
                                                      # keep serving, the stopped one recovers
```

Each Python script prints `PASS`/`FAIL` per assertion and exits non-zero on failure. They
assume a clean database, so reset (and restart) before each one.

### Golden diff: same responses as the single application

```bash
python3 scripts/golden.py diff reports/golden/single-app.txt http://localhost:8080
```

This replays a fixed 29-request scenario, including error cases and an unknown route.
It normalises UUIDs and timestamps and compares every status and body with the
responses the pre-split single application gave.

---

## Project layout

```
pom.xml                 parent pom (packaging pom): the six modules and shared versions
shared-data/            library: read-only catalog cache + shared config properties
shared-web/             library: error JSON, exception handler, domain exceptions
catalog-service/        :8081   api/ → catalog/ → (cache)
checkout-service/       :8082   api/ → transactions/ → scanlog/ → persistence/
inventory-service/      :8083   api/ → inventory/ → persistence/
analytics-service/      :8084   api/ → analytics/ (+ pipeline/) → persistence/
gateway/nginx.conf      the gateway; runtime files go to gateway/run/ (ignored)
db/                     schema.sql, data.sql, roles.sql (per-service grants), reset.sh
scripts/                start/stop, golden diff, invariant + window checks, behavioural tests
reports/                the submitted reports; service-based/ has every Week 4 run
```

## Run the tests

```bash
./mvnw test          # every module: unit tests, pipeline end-to-end, ArchUnit rules
```

Each service has an ArchUnit test. Checkout checks its four internal layers. Catalog
and inventory check that they contain **no database write calls at all**, and analytics
checks that only its snapshot writer writes. The suite does not touch the schema. The
context-load tests only need the database and the service roles to exist, so run
`db/reset.sh` once first.

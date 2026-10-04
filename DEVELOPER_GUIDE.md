# Developer Guide — Self-Checkout, Layered

A walkthrough of the whole project: what it does, how it is built, why each
decision was made, and how to run and explain it.

The code is organized into **four layers** — `api`, `transactions`,
`analytics`, `persistence` — with the dependency rules between them enforced by
an automated test. §3 and §4.5 cover how they talk to each other.

Inside the analytics layer, the windowed analytics run as a **pipeline of five
filters connected by bounded queues** — see §4.6 and
[PIPELINE.md](PIPELINE.md).

For **measured results and architectural trade-offs**, see
[ARCHITECTURE.md](ARCHITECTURE.md). This guide deliberately does not repeat the
numbers — it explains the *machinery* that produced them.

---

## Table of contents

1. [What the system does](#1-what-the-system-does)
2. [Tech stack, and why each piece](#2-tech-stack-and-why-each-piece)
3. [Architecture](#3-architecture)
4. [How a request flows](#4-how-a-request-flows) · [how the layers talk](#45-how-the-layers-talk-to-each-other) · [the analytics pipeline](#46-one-scan-event-through-the-analytics-pipeline)
5. [The database](#5-the-database)
6. [API reference](#6-api-reference)
7. [File-by-file walkthrough](#7-file-by-file-walkthrough)
8. [Deep dives](#8-deep-dives) — including [why a basket is not a row](#88-why-an-unpaid-basket-is-not-a-database-row), [`FOR NO KEY UPDATE`](#89-for-update-vs-for-no-key-update--a-250-mistake), [the critical section](#810-the-critical-section-is-the-ceiling)
9. [How to run everything](#9-how-to-run-everything)
10. [Troubleshooting](#10-troubleshooting)
11. [Glossary](#11-glossary)
12. [Where the results live](#12-where-the-results-live)

---

## 1. What the system does

A supermarket self-checkout system, as a backend service.

A customer walks up to one of the checkout stations and:

1. **Starts a transaction** — the station tells the server "a customer is here".
   The server creates an empty basket and hands back an id.
2. **Scans items**, one physical beep at a time. Each scan looks up the price
   and returns the running total, so the screen can update.
3. **Pays.** The server finalizes the basket, subtracts what was bought from
   the shelf count, and returns a receipt.

Around that, the store needs two pieces of reporting:

- **Low-stock alerts** — "we're nearly out of milk", recorded the moment it
  happens so someone can go and restock.
- **Popular items** — what's selling right now, over a rolling view of the
  most recent scans.

Two rules drive almost every design decision in the codebase:

> **Stock is subtracted when the customer pays, not when they scan.**
> The customer is already holding the item — the backend's job is to keep an
> accurate count, not to refuse the scan.

> **Stock must never go wrong, no matter how many stations run at once.**
> For every product: `what we started with − what's left == what we sold`.
> Ten stations run by default, one hundred in the stress test. This is the
> property that naive implementations quietly break, and most of §8 is about
> making sure ours doesn't.

---

## 2. Tech stack, and why each piece

| Piece | Version | Why it's here |
|---|---|---|
| **Java** | 21 | Long-term-support release. Gives us `record`, which makes the DTOs one-liners instead of 40-line classes. |
| **Spring Boot** | 4.1.1 | Bundles the web server, the database wiring and the configuration system, and auto-configures them. Without it there'd be a lot of setup code that has nothing to do with checkouts. |
| **Spring Web MVC** | (bundled) | The HTTP layer. It maps URLs to methods and converts objects to JSON, so no request parsing is written by hand. Uses an embedded Tomcat with a **thread pool** — which is exactly why concurrency is a real concern here. |
| **Spring Data JPA + Hibernate** | 7.x | Maps Java objects to table rows. Saves writing SQL for ordinary reads and writes, and — importantly — gives a clean way to ask for a **row lock** (`@Lock(PESSIMISTIC_WRITE)`) without hand-writing `SELECT ... FOR UPDATE`. |
| **PostgreSQL** | 16 | A real relational database with real transactions and real row-level locking. The correctness guarantee in this project is *enforced by the database*, not by Java code, so the database choice matters. Also easy to reinitialize from a `.sql` file. |
| **HikariCP** | (bundled) | The connection pool. Opening a database connection is slow, so the app keeps a set of them open and lends them out. Worth knowing about: under heavy load this pool becomes the bottleneck — see §8.7. |
| **Maven** | wrapper (`./mvnw`) | Builds the project and downloads dependencies. The *wrapper* means nobody has to install Maven — `./mvnw` fetches the right version itself. |
| **ArchUnit** | 1.3.0 (test) | Reads the compiled classes and fails the build if a layer depends on something it shouldn't. Turns "controllers must not call repositories" from a convention nobody checks into a test. |
| **Jackson** | 3.x | Turns objects into JSON and back. Note: Jackson 3 moved to the `tools.jackson` package — the old `com.fasterxml` imports do not work on Spring Boot 4. |

**Why layers?** This is week two of a semester that rebuilds the same API in a
different architecture each week. Week 1 grouped classes by technical type,
which *looked* layered but enforced nothing — HTTP types had already leaked into
the business code. This week the layers are real packages with rules a test
checks. It is still one process and one database: layering is a source-code
structure, not a deployment one.

---

## 3. Architecture

Four layers. Each may only depend downwards, and a test enforces it.

```mermaid
graph TD
    subgraph api["api — HTTP only (CLOSED)"]
        A1["ItemController · TransactionController<br/>InventoryController · AnalyticsController"]
        A2["dto/ — 12 records, the spec's wire shapes"]
        A3["error/ — ApiError · GlobalExceptionHandler<br/>domain exception → status + error code"]
    end

    subgraph tx["transactions — business rules (CLOSED)"]
        T1["CatalogService · TransactionService · InventoryService"]
        T2["result/ — 7 domain records"]
        T3["exception/ — DomainException + 5 subtypes"]
    end

    subgraph an["analytics — business rules (OPEN)"]
        N1["AnalyticsService implements ScanRecorder"]
        N2["ScanRecorder — the one-method door in"]
        N3["result/ — 2 domain records"]
        N4["pipeline/ — 4-5 filters over bounded queues"]
    end

    subgraph per["persistence — JPA only (CLOSED)"]
        P1["entity/ — 8 entities"]
        P2["repository/ — 6 repositories + 2 projections"]
    end

    CFG["config — shared kernel<br/>SelfCheckoutProperties"]
    DB[("PostgreSQL")]

    api -->|"start · scan · complete · catalog · low-stock"| tx
    api -.->|"popular-items only"| an
    tx -->|"recordScan(sku)"| an
    tx --> per
    an --> per
    per --> DB
    api -.-> CFG
    tx -.-> CFG
    an -.-> CFG

    classDef openLayer stroke-dasharray:5 5
    class an openLayer
```

**Closed vs open.** A *closed* layer must be passed through — you cannot reach
what is under it without going via it. An *open* layer may be bypassed.
`api`, `transactions` and `persistence` are closed. **`analytics` is open**:
the API calls it directly for `GET /analytics/popular-items`, because routing a
read-only query through a transactions method that only forwards the call would
be a **sinkhole** — a layer that adds a hop and nothing else.

**The rules, as enforced by `LayeredArchitectureTest`:**

| Layer | May be accessed by | May depend on |
|---|---|---|
| `api` | *nothing* | transactions, analytics, config |
| `transactions` | api | analytics *(via `ScanRecorder` only)*, persistence, config |
| `analytics` | api, transactions | persistence, config |
| `persistence` | transactions, analytics | JPA / Spring Data only |
| `config` | all | nothing |

Plus: **no class outside `api` may touch `org.springframework.http..` or
`org.springframework.web..`**, and **no class in `api` may touch
`..persistence..`**.

**What this buys, in one example.** `TransactionService` does not contain the
word `HttpStatus`. It throws `TransactionNotOpenException`; the API layer
decides that means 409 with code `TRANSACTION_NOT_OPEN`. Serve the same rules
over gRPC tomorrow and the business layer does not change.

**What it costs.** Seven of the domain result records are field-for-field
identical to the DTOs they map to, and that mapping code is pure overhead until
the day the API shape and the domain shape need to differ. See ARCHITECTURE.md
§2 for the honest accounting.

## 4. How a request flows

### 4.1 Start a transaction

Nothing is locked, nothing can conflict — and since the performance work,
nothing is written either. An unpaid basket is not a database row.

```mermaid
sequenceDiagram
    participant C as Station
    participant TC as TransactionController
    participant TS as TransactionService
    participant OTS as OpenTransactionStore

    C->>TC: POST /transactions {"stationId":"station-07"}
    TC->>TS: start("station-07")
    TS->>TS: reject blank stationId → 400
    TS->>TS: new OpenBasket(UUID, stationId, now)<br/>state=OPEN, itemCount=0, total=0
    TS->>OTS: put(basket)
    Note over OTS: a ConcurrentHashMap entry.<br/>No transaction, no SQL, no connection.
    TS-->>TC: TransactionView
    TC-->>C: 201 Created + JSON
```

This used to be `BEGIN · SELECT · INSERT · COMMIT`. The `SELECT` was pure
waste: Spring Data saw our assigned UUID, could not tell a new row from an
existing one, and checked before inserting. **Measured: 4 statements → 0.**

### 4.2 Scan an item

The hot path — it runs about 10× more often than the others. It is now pure
memory too.

```mermaid
sequenceDiagram
    participant C as Station
    participant TC as TransactionController
    participant TS as TransactionService
    participant OTS as OpenTransactionStore
    participant CC as CatalogCache
    participant SR as ScanRecorder

    C->>TC: POST /transactions/{id}/items {"sku":"SKU-000001"}
    TC->>TS: scan(id, sku)

    TS->>OTS: find(id)
    alt not in memory
        Note over TS: ask the database: is it COMPLETED (409)<br/>or unknown (404)? Both answers must stay apart.
        TS-->>C: 404 TRANSACTION_NOT_FOUND / 409 TRANSACTION_NOT_OPEN
    end
    TS->>CC: find(sku)
    alt unknown or blank SKU
        TS-->>C: 404 ITEM_NOT_FOUND
    end

    Note over TS,CC: items.stock is never read or locked here
    TS->>TS: basket.addUnit(sku, price)
    Note over TS: synchronized on THIS basket only.<br/>Uncontended in practice (one station per basket),<br/>but it is what makes a scan racing a complete safe.
    alt a complete already claimed the basket
        TS-->>C: 409 TRANSACTION_NOT_OPEN
    end

    TS->>SR: recordScan(sku)
    Note over SR: non-blocking offer into the analytics<br/>pipeline. Never waits, never fails the scan.
    TS-->>C: 200 ScanResult
```

The catalog is immutable (2,000 rows, loaded once), so the price lookup is a
map read rather than a query. **Measured: 7–8 statements → 0.**

### 4.3 Complete the transaction — the important one

This is where every database write for the whole basket happens at once, and
where the locks are.

```mermaid
sequenceDiagram
    participant C as Station
    participant TS as TransactionService
    participant OB as OpenBasket
    participant CW as CompletedTransactionWriter
    participant SU as StockUpdater
    participant DB as PostgreSQL

    C->>TS: POST /transactions/{id}/complete

    TS->>OB: claimForCompletion()
    Note over OB: OPEN → COMPLETING, in memory, under the<br/>basket's own monitor. A second complete and a<br/>late scan both get 409 from here on.
    alt not OPEN → 409 TRANSACTION_NOT_OPEN
    else basket empty → 409 TRANSACTION_EMPTY (stays OPEN)
    end

    Note over TS,DB: one database transaction starts here
    TS->>TS: sort SKUs ascending
    Note over TS: the same global lock order for every basket —<br/>this is the deadlock prevention.

    TS->>CW: insertHeader(...)
    CW->>DB: INSERT INTO transactions
    Note over DB: deliberately BEFORE the lock: the header<br/>depends on nothing the lock protects.

    TS->>SU: lockAndReadStock(sortedSkus)
    SU->>DB: SELECT sku, stock FROM items WHERE sku IN (...)<br/>ORDER BY sku FOR NO KEY UPDATE
    Note over DB: 🔒 every item row in the basket, in ONE statement.<br/>FOR NO KEY UPDATE, not FOR UPDATE — see §8.9.

    TS->>TS: in Java, holding no new locks:<br/>applied = min(stockBefore, quantity)<br/>after = before − applied<br/>collect threshold crossings

    TS->>SU: applyStock(newStock)
    SU->>DB: UPDATE items SET stock = v.stock<br/>FROM (VALUES ...) AS v
    TS->>CW: insertLines(...)
    CW->>DB: INSERT INTO transaction_items ... VALUES (...),(...)
    TS->>DB: INSERT low_stock_alerts (only if something crossed)

    Note over TS,DB: COMMIT — releases ALL item locks 🔓
    TS->>OB: remove from memory (or releaseClaim on failure)
    TS-->>C: 200 Receipt (quantity = units SCANNED)
```

**Six statements, whatever the basket size.** It used to be `3L + 5` — about
33 for a typical 9-SKU basket — because Hibernate issued a `SELECT` before
each `INSERT` and an `UPDATE` per row.

Only **three** of those six are held under the item lock (`UPDATE items`,
`INSERT transaction_items`, `COMMIT`), down from about 30. That number matters
more than the total: see §8.10.

### 4.4 Popular-items window recompute

The only asynchronous thing in the system.

```mermaid
sequenceDiagram
    participant T as Tomcat request thread
    participant TS as TransactionService
    participant PS as PopularItemsService
    participant SYN as Spring transaction sync
    participant BG as popular-recompute thread
    participant DB as PostgreSQL

    T->>TS: scan(...)
    TS->>DB: INSERT scan_log → seq = 1500
    TS->>PS: onScanRecorded(1500)
    PS->>PS: boundary = (1500 / 500) * 500 = 1500
    alt boundary already claimed
        PS-->>TS: return immediately
    else this thread wins compareAndSet
        PS->>SYN: registerSynchronization(afterCommit)
    end
    TS->>DB: COMMIT
    Note over T: the request thread is DONE here —<br/>it never waits for the aggregate
    SYN->>BG: afterCommit fires → submit(1500)
    T-->>T: 200 response already sent

    Note over BG: separate thread, its own transaction<br/>REQUIRES_NEW
    BG->>DB: SELECT sku, count(*) FROM scan_log<br/>WHERE seq > 500 AND seq <= 1500<br/>GROUP BY sku ORDER BY count DESC LIMIT 10
    BG->>DB: INSERT INTO popular_snapshots (items_json)
```

**Why `afterCommit` and not just "run it now":**

- If the scan rolls back, `afterCommit` never fires, so a rolled-back scan can
  never end up counted in a window.
- The request thread hands the work off and returns. Checkout never pays for
  analytics.
- One dedicated thread means analytics can never use more than one extra
  database connection, however heavy the checkout load gets.

---

### 4.5 How the layers talk to each other

One scan, followed all the way down and back. Watch where each layer stops
knowing things: the controller knows HTTP but not SQL; the service knows stock
rules but not HTTP; analytics knows windows but not baskets; persistence knows
rows but nothing above it.

```mermaid
sequenceDiagram
    participant C as Station
    box api — HTTP only
    participant CTL as TransactionController
    participant EH as GlobalExceptionHandler
    end
    box transactions — business rules
    participant TS as TransactionService
    end
    box analytics — business rules
    participant AS as AnalyticsService<br/>(as ScanRecorder)
    end
    box persistence — JPA only
    participant REPO as Repositories
    end
    participant DB as PostgreSQL

    C->>CTL: POST /transactions/{id}/items {"sku":"SKU-000001"}
    Note over CTL: parses JSON into ScanItemRequest.<br/>Knows nothing about stock.
    CTL->>TS: scan(id, sku)
    Note over TS: @Transactional starts here —<br/>a business decision, not a DB one

    TS->>REPO: findById(transaction) / findById(item)
    REPO->>DB: SELECT
    alt transaction missing
        TS-->>CTL: throw TransactionNotFoundException
        Note over TS: a DOMAIN exception —<br/>no HttpStatus anywhere in this layer
        CTL-->>EH: propagates
        EH-->>C: 404 {"error":"TRANSACTION_NOT_FOUND"}
    end

    TS->>REPO: upsert basket line, tx.addScan(price)
    Note over TS,DB: items.stock is never read or locked on a scan

    TS->>AS: recordScan("SKU-000001")
    Note over TS: one method on ScanRecorder.<br/>Transactions does not know scan_log exists.
    AS->>REPO: INSERT scan_log → seq
    REPO->>DB: INSERT
    Note over AS: no @Transactional — joins the scan's<br/>transaction, so a rollback takes the log row too
    AS->>AS: boundary = (seq / 500) * 500<br/>compareAndSet — did we cross?
    opt boundary crossed
        AS->>AS: register afterCommit hook
    end

    TS-->>CTL: ScanOutcome (a domain record)
    Note over CTL: maps ScanOutcome → ScanResult.<br/>Domain shape and wire shape are separate.
    CTL-->>C: 200 {"transactionId":...,"runningTotal":...}

    Note over AS,DB: after COMMIT, on the background thread:<br/>aggregate the window, INSERT popular_snapshots
```

**The three boundary crossings worth noticing:**

1. **api → transactions** passes a `String`, gets back a domain record. No DTO
   goes down; no entity comes up.
2. **transactions → analytics** is one method, `recordScan(sku)`. Everything
   about sequence numbers, boundaries and background threads is hidden behind
   it.
3. **The exception path** never mentions HTTP until it reaches
   `GlobalExceptionHandler`, which is the only class allowed to turn
   "the basket wasn't open" into "409".

### 4.6 One scan event through the analytics pipeline

§4.5 followed a scan down through the layers. This follows what happens to it
*afterwards*, on the analytics side, once the scan has committed.

The thing to notice: the request thread's involvement ends at a single
in-memory handover. Everything else happens on background threads, so a scan
request does no analytics database work at all.

```mermaid
sequenceDiagram
    participant REQ as Tomcat request thread
    participant AS as AnalyticsService
    participant ING as ingest thread
    participant WIN as window thread
    participant RNK as rank thread
    participant SNK as sink thread
    participant LOG as scan-log thread
    participant DB as PostgreSQL

    REQ->>AS: recordScan("SKU-000001")
    Note over AS: no I/O — just registers<br/>an afterCommit callback
    REQ->>DB: COMMIT (the scan's own transaction)
    Note over REQ: only now does analytics hear about it;<br/>a rolled-back scan never fires
    REQ->>ING: ingestPipe.offer(ScanEvent, 10 ms)
    Note over REQ: non-blocking. If the pipe were full the<br/>event is counted + logged and the scan<br/>still succeeds — analytics can never<br/>stall checkout.
    REQ-->>REQ: request finishes

    ING->>ING: seq = ++counter (gapless)
    par tee, arm 1
        ING->>WIN: windowPipe.put(SequencedScanEvent)
    and tee, arm 2
        ING->>LOG: scanLogPipe.put(SequencedScanEvent)
    end

    WIN->>WIN: ring buffer: add new SKU, evict the<br/>one falling out of the last 1000
    alt seq % 500 != 0
        WIN-->>WIN: nothing to emit
    else boundary reached
        WIN->>RNK: rankPipe.put(WindowCounts) — immutable copy
        RNK->>RNK: sort by count desc, then SKU asc; take 10;<br/>attach names from the lazy catalog cache
        RNK->>SNK: sinkPipe.put(RankedWindow)
        SNK->>DB: INSERT popular_snapshots
    end

    LOG->>LOG: take one row, then sweep up everything<br/>else already queued (max 1000)
    LOG->>DB: one multi-row INSERT into scan_log
```

**Why the tee.** The same event has two unrelated destinations: the live window
needs it immediately in memory, and `scan_log` needs it durably on disk. They
run at completely different rates — the window emits once per 500 events, the
writer flushes a batch whenever it can — so decoupling them means neither waits
for the other.

**Why the batching is adaptive.** The writer takes one row, then sweeps up
whatever else is already waiting. Under light load that sweep finds nothing and
the batch is one row. Under stress it averaged **62.5 rows per INSERT**. If the
writer is ever held up — waiting for a pooled connection, say — the backlog
simply makes the *next* write bigger, so falling behind is self-correcting.

## 5. The database

```mermaid
erDiagram
    ITEMS ||--o{ TRANSACTION_ITEMS : "appears in"
    ITEMS ||--o{ SCAN_LOG : "is scanned as"
    ITEMS ||--o| LOW_STOCK_ALERTS : "may trigger"
    TRANSACTIONS ||--o{ TRANSACTION_ITEMS : "contains"

    ITEMS {
        varchar sku PK
        varchar name
        numeric price
        integer stock "CHECK >= 0"
    }
    TRANSACTIONS {
        varchar id PK
        varchar station_id
        varchar status
        integer item_count
        numeric running_total
        timestamptz started_at
        timestamptz completed_at "null until paid"
    }
    TRANSACTION_ITEMS {
        varchar transaction_id PK_FK
        varchar sku PK_FK
        integer quantity "scanned"
        integer applied_quantity "actually subtracted"
    }
    SCAN_LOG {
        bigserial seq PK
        varchar sku FK
        timestamptz scanned_at
    }
    LOW_STOCK_ALERTS {
        varchar sku PK_FK
        timestamptz triggered_at
    }
    POPULAR_SNAPSHOTS {
        bigserial id PK
        bigint window_start
        bigint window_end
        timestamptz computed_at
        text items_json
    }
```

`POPULAR_SNAPSHOTS` has no foreign keys on purpose — it's a computed report,
not part of the transactional model.

### Every column, explained

**`items`** — the catalog. The only table holding stock.

| Column | Type | What it is |
|---|---|---|
| `sku` | `VARCHAR(32)` PK | Product code, e.g. `SKU-000001`. The string itself is the key. |
| `name` | `VARCHAR(255)` | Display name on screen and receipt. |
| `price` | `NUMERIC(10,2)` | Unit price. `NUMERIC`, not `FLOAT` — money must be exact. |
| `stock` | `INTEGER` | Units on the shelf. `CHECK (stock >= 0)` is a last line of defence: even a bug cannot write a negative value, the statement just fails. |

**`transactions`** — one customer's basket.

| Column | Type | What it is |
|---|---|---|
| `id` | `VARCHAR(64)` PK | A UUID generated by the app. |
| `station_id` | `VARCHAR(64)` | Which checkout the customer is at. |
| `status` | `VARCHAR(16)` | `OPEN`, `COMPLETED` or `CANCELLED`. Stored as text so rows are readable in `psql`. |
| `item_count` | `INTEGER` | Units scanned — **not** distinct SKUs. Three tins of beans = 3. |
| `running_total` | `NUMERIC(12,2)` | Kept up to date on every scan, so the receipt total never has to be recomputed. |
| `started_at` | `TIMESTAMPTZ` | When the basket opened. |
| `completed_at` | `TIMESTAMPTZ` | `NULL` until paid. |

**`transaction_items`** — the basket lines. **The two quantity columns are the
subtle part of the whole project.**

| Column | Type | What it is |
|---|---|---|
| `transaction_id` | `VARCHAR(64)` PK, FK | Which basket. |
| `sku` | `VARCHAR(32)` PK, FK | Which product. Composite key with the above, so scanning the same item twice bumps `quantity` instead of adding a row. |
| `quantity` | `INTEGER` | Units **scanned**. This is what the receipt prints — what the customer is physically holding. |
| `applied_quantity` | `INTEGER` | Units **actually subtracted** from stock. Usually the same as `quantity`, but smaller if the product sold out. **The stock invariant is checked against this column.** See §8.4. |

**`scan_log`** — one row per beep, across all stations.

| Column | Type | What it is |
|---|---|---|
| `seq` | `BIGINT` PK | The global scan counter, **assigned by the pipeline's ingest filter** (not a `BIGSERIAL` since Week 3). Only committed scans are numbered, one at a time by one thread, so it is gapless. The counter restarts at 1 each boot, which is safe only because the schema is recreated on every start. |
| `sku` | `VARCHAR(32)` FK | What was scanned. |
| `scanned_at` | `TIMESTAMPTZ` | When. |

**`low_stock_alerts`** — recorded threshold crossings.

| Column | Type | What it is |
|---|---|---|
| `sku` | `VARCHAR(32)` PK, FK | The product. Being the primary key means at most one alert per product, ever. |
| `triggered_at` | `TIMESTAMPTZ` | The real moment stock crossed below the threshold — not the time somebody asked. |

**`popular_snapshots`** — computed windows, append-only.

| Column | Type | What it is |
|---|---|---|
| `id` | `BIGSERIAL` PK | Append-only, so the largest id is the current window. |
| `window_start` | `BIGINT` | A scan **sequence number**, not a timestamp. |
| `window_end` | `BIGINT` | Ditto. The window is `seq` in `(window_start, window_end]`. |
| `computed_at` | `TIMESTAMPTZ` | When the aggregate ran. |
| `items_json` | `TEXT` | The ranked top 10 as a JSON array. Written once, read whole, never queried by field — so JSON is simpler than ten child rows. |

---

## 6. API reference

Base URL `http://localhost:8080`. All bodies are JSON.

Errors always look like this:

```json
{ "error": "TRANSACTION_NOT_OPEN", "message": "Transaction abc is COMPLETED" }
```

| Code | Status | Means |
|---|---|---|
| `INVALID_REQUEST` | 400 | Missing `stationId`, or a missing/malformed request body. |
| `TRANSACTION_NOT_FOUND` | 404 | No transaction with that id. |
| `ITEM_NOT_FOUND` | 404 | No such SKU — including when `sku` was blank or absent. |
| `TRANSACTION_NOT_OPEN` | 409 | Already completed or cancelled. |
| `TRANSACTION_EMPTY` | 409 | Tried to pay for an empty basket. |

### `GET /items`

The full catalog. The load client calls this once at startup.

```bash
curl -s localhost:8080/items
```
```json
{ "items": [ { "sku": "SKU-000001", "name": "Item 1", "price": 1.37 } ] }
```
**200** always. Note there is no `stock` field — the catalog is not an
inventory endpoint.

### `POST /transactions`

```bash
curl -s -X POST localhost:8080/transactions \
  -H 'Content-Type: application/json' -d '{"stationId":"station-07"}'
```
```json
{ "transactionId": "f518f161-...", "stationId": "station-07", "status": "OPEN",
  "itemCount": 0, "runningTotal": 0, "startedAt": "2026-09-25T21:23:17.912797Z" }
```
**201** created · **400** `INVALID_REQUEST` if `stationId` is missing or blank.

### `POST /transactions/{id}/items`

One call = one physical unit. To scan two of the same thing, call it twice.

```bash
curl -s -X POST localhost:8080/transactions/$TX/items \
  -H 'Content-Type: application/json' -d '{"sku":"SKU-000001"}'
```
```json
{ "transactionId": "f518f161-...", "sku": "SKU-000001", "name": "Item 1",
  "unitPrice": 1.37, "itemCount": 1, "runningTotal": 1.37 }
```
**200** · **404** unknown transaction or SKU · **409** basket not open.
`itemCount` and `runningTotal` already include this unit.

### `POST /transactions/{id}/complete`

No request body. Payment always succeeds in this simulation.

```bash
curl -s -X POST localhost:8080/transactions/$TX/complete
```
```json
{ "transactionId": "f518f161-...", "stationId": "station-07", "itemCount": 2,
  "totalAmount": 3.11, "startedAt": "...", "completedAt": "...",
  "lines": [ { "sku": "SKU-000001", "name": "Item 1", "unitPrice": 1.37, "quantity": 1 } ] }
```
**200** · **404** unknown · **409** not open, or basket empty.
`quantity` is what was **scanned**, even if the shelf ran short.

### `GET /transactions/{id}`

Debugging only; the load client never calls it. Returns the same shape as
`POST /transactions`. **200** or **404**.

### `GET /inventory/low-stock`

```bash
curl -s "localhost:8080/inventory/low-stock"
curl -s "localhost:8080/inventory/low-stock?threshold=100"
```
```json
{ "threshold": 50, "generatedAt": "2026-09-25T21:30:00Z",
  "alerts": [ { "sku": "SKU-000001", "name": "Item 1", "currentStock": 0,
                "threshold": 50, "triggeredAt": "2026-09-25T21:24:11Z" } ] }
```
**200** always. `?threshold=` applies to this query only and is never saved.
`triggeredAt` is the real crossing time when one was recorded, otherwise it
falls back to `generatedAt`. See §8.6.

### `GET /analytics/popular-items`

```bash
curl -s "localhost:8080/analytics/popular-items?limit=10"
```
```json
{ "windowSize": 1000, "slideInterval": 500, "windowStart": 500, "windowEnd": 1500,
  "computedAt": "2026-09-25T21:25:00Z",
  "items": [ { "sku": "SKU-000001", "name": "Item 1", "scanCount": 375, "rank": 1 } ] }
```
**200** always — before any window has been computed you get a well-formed
response with an empty `items` list, never a 404.
`windowStart`/`windowEnd` are **scan sequence numbers**, not timestamps.

---

## 7. File-by-file walkthrough

Organized by layer, top to bottom.

### Entry point and shared config

| File | What it does |
|---|---|
| `SelfCheckoutApplication.java` | `main()`. Sits above every layer so component scanning reaches them all. Starting it also rebuilds the database. |
| `config/SelfCheckoutProperties.java` | A record bound to the `selfcheckout.*` keys. The **shared kernel**: any layer may read it, it may depend on none of them. |

### `api/` — HTTP only

| File | Endpoints / role |
|---|---|
| `ItemController` | `GET /items`. Maps `CatalogItem` → `ItemDto`. Honestly a pass-through today. |
| `TransactionController` | `POST /transactions`, `POST /{id}/items`, `POST /{id}/complete`, `GET /{id}`. Maps domain results → spec DTOs. No `try/catch`. |
| `InventoryController` | `GET /inventory/low-stock`. `Integer threshold` so "absent" ≠ "zero". |
| `AnalyticsController` | `GET /analytics/popular-items`. Calls **analytics directly** — this is what makes analytics an open layer. |
| `api/dto/*` (12 records) | The spec's wire shapes. Renaming a field here changes the API; renaming a domain record does not. |
| `api/error/ApiError` | The `{error, message}` body. |
| `api/error/GlobalExceptionHandler` | **The only class that maps a business failure to an HTTP status.** One `@ExceptionHandler` per domain exception, plus one for malformed JSON. |

### `transactions/` — checkout business rules

| File | Key methods | Notes |
|---|---|---|
| `TransactionService` | `start`, `scan`, `complete`, `get` | **Read this first.** `start` and `scan` touch no database at all; `complete` does every write for the basket at once and holds all the locks. Contains no HTTP type. |
| `OpenBasket` | `addUnit`, `claimForCompletion`, `snapshot` | One unpaid basket, in memory, guarded by its own monitor. `claimForCompletion` is a one-way door — it is what makes a double complete 409 and a late scan 409. |
| `OpenTransactionStore` | `put`, `find`, `remove` | The `ConcurrentHashMap` of open baskets. Nothing else knows baskets are not rows. |
| `CheckoutTimings` | `record`, `logSummary` | Splits a completion's database time into lock **wait** and **critical section**. The second one is what sets the ceiling — see §8.10. |
| `CatalogService` | `listItems()` | Entity → `CatalogItem`. Its sort order makes `SKU-000001` the hottest item under the load client's Zipf sampler. |
| `InventoryService` | `lowStock(Integer)` | Only *reads* alerts; they are *written* by `complete()`. |
| `transactions/result/*` (7 records) | — | `CatalogItem`, `TransactionView`, `ScanOutcome`, `ReceiptView`, `ReceiptLineView`, `LowStockView`, `LowStockEntry`. `TransactionView.status` is a `String`, not the persistence enum. |
| `transactions/exception/*` (6 types) | — | `DomainException` + `TransactionNotFound`, `ItemNotFound`, `TransactionNotOpen`, `EmptyTransaction`, `InvalidRequest`. All unchecked, so `@Transactional` rolls back. |

### `analytics/` — popularity windows

| File | Role |
|---|---|
| `ScanRecorder` | The one-method interface the transactions layer calls. Keeps the dependency narrow and one-directional. |
| `AnalyticsService` | Implements `ScanRecorder`. Now just the two ends: `recordScan` registers the `afterCommit` handover, `latest` serves the endpoint. All the work moved into `pipeline/`. |
| `analytics/result/*` | `PopularWindowView`, `PopularEntry`. `PopularEntry` is also the on-disk JSON format inside `popular_snapshots`. |
| `pipeline/Pipe` · `BlockingQueuePipe` | The pipe abstraction and its bounded-queue implementation. The seam that would let a broker replace queues. |
| `pipeline/IngestFilter` | Assigns the gapless global scan sequence. |
| `pipeline/IngestTee` | The fan-out, and the place the two branches stop being symmetric: blocking `put` to the window branch, non-blocking `offer` + counted drop to the optional scan-log branch. |
| `pipeline/WindowFilter` | Ring buffer of the last 1000 scans plus running counts; emits a window every 500. Owns the boundary logic. |
| `pipeline/RankFilter` | Counts → top 10, ties by SKU ascending, names attached. |
| `pipeline/SinkFilter` | Writes a batch of windows as one multi-row `INSERT`. Does **not** swallow duplicates. |
| `pipeline/ScanLogWriterFilter` | Maps events to rows and writes a batch. Only wired when raw scan persistence is on. |
| `pipeline/FilterLoop` · `BatchingLoop` | The thread halves. `BatchingLoop` is shared by the sink and the scan-log writer: wait for one message, sweep up everything else queued, write the lot in one statement — so falling behind makes the next write *cheaper* per row. Filters stay plain classes so they test without Spring. |
| `pipeline/AnalyticsPipeline` | Builds the pipes, starts the threads (four, or five with raw scans on), poison-pill shutdown, and the one entry point `submit(...)`. |
| `pipeline/PipelineMetrics` | In/out/error per stage, pipe depths, drops (two kinds, counted separately). Logged every 10 s — the evidence in ARCHITECTURE.md §6.5. |
| `pipeline/ScanEvent` · `SequencedScanEvent` · `WindowCounts` · `RankedWindow` | The immutable messages carried between filters. |

### `persistence/` — all the SQL

| File | Notable method | Why it matters |
|---|---|---|
| `repository/JdbcStockUpdater` | `lockAndReadStock`, `applyStock` | **The lock that prevents lost updates**, now one statement for the whole basket: `... WHERE sku IN (...) ORDER BY sku FOR NO KEY UPDATE`. Both the `ORDER BY` and the `NO KEY` are load-bearing — see §8.2 and §8.9. |
| `repository/JdbcCompletedTransactionWriter` | `insertHeader`, `insertLines` | Split so the header can be written *before* the lock is taken (§8.10). Plain JDBC because Spring Data's `save()` on an assigned id issues a `SELECT` before every `INSERT`. |
| `repository/JdbcLowStockAlertWriter` | `insertCrossings` | One multi-row `INSERT ... ON CONFLICT (sku) DO NOTHING`. Skipped entirely when nothing crossed. |
| `repository/CatalogCache` · `LazyCatalogCache` · `CatalogEntry` | `find(sku)`, `all()` | The 2,000-row catalog is immutable, so it is read once and served from memory. This is why `GET /items` and every scan's price lookup issue no SQL. Loaded **lazily** — `data.sql` runs during startup, so an eager load could find an empty table. |
| `persistence/AnalyticsWriterPool` | `dataSource()` | A second, 2-connection Hikari pool used only by the pipeline's background writers, so they never queue behind checkout. Measured reason in §8.11. |
| `repository/ItemRepository` | — | Still used by the read-only endpoints. |
| `repository/TransactionRepository` | `findByIdForUpdate` | Serializes two completes of the same basket. |
| `repository/TransactionItemRepository` | `findByTransactionIdOrderBySkuAsc` | Used by `GET /transactions/{id}` for a basket that has already been paid for. |
| `repository/ScanLogRepository` | `topInWindow(start, end, limit)` | The windowed aggregate, half-open `(start, end]`. No longer used in production — it is the brute-force oracle the window tests check against. |
| `repository/ScanLogBatchWriter` | `insertBatch(rows)` | Multi-row `INSERT` via `JdbcTemplate`. Plain JDBC because `scan_log` rows are write-only facts that nothing ever loads as entities. |
| `repository/JdbcSnapshotWriter` | `insertBatch(rows)` | A batch of `popular_snapshots` rows in one statement and one commit. Takes a raw `Connection` rather than `JdbcTemplate` so it can time pool-wait, execute and commit *separately* — which is how the sink's real problem was found (§8.11). |
| `repository/LowStockAlertRepository` | `findBelowThreshold` | `LEFT JOIN` + `coalesce` for the ad-hoc threshold fallback. |
| `repository/PopularSnapshotRepository` | `findFirstByOrderByIdDesc` | Newest window; the table is append-only. |
| `repository/ScanCount`, `LowStockRow` | — | Query projections. |
| `entity/*` (8) | — | `Item`, `Transaction`, `TransactionItem` (+`TransactionItemId`), `ScanLog`, `LowStockAlert`, `PopularSnapshot`, `TransactionStatus`. Foreign keys are plain `String`s, not `@ManyToOne`, to avoid N+1 fetches and unlocked copies. |

### Tests, scripts, reports

| File | What it does |
|---|---|
| `src/test/.../architecture/LayeredArchitectureTest.java` | Six ArchUnit rules encoding the dependency table. Does not start Spring. |
| `src/test/.../SelfCheckoutApplicationTests.java` | `contextLoads` smoke test. **Boots the app, so it re-runs `schema.sql` and wipes the database.** |
| `scripts/check-invariant.sql` | The three correctness checks. Run after a load test, before restarting. |
| `src/test/.../transactions/OpenBasketTest.java` | The guarantees the database used to give the basket and now have to come from the class: first-scan line order, and claim-once. |
| `src/test/.../analytics/pipeline/IngestTeeTest.java` | That a full audit branch is dropped from rather than waited on, and that the window branch is unaffected. |
| `scripts/_client.py`, `scripts/test-*.py` | Shared helper + five behavioural tests. Each one wants a **freshly restarted server**; chaining them without a restart fails on leftover state, not on a bug. |
| `reports/*.json` | The committed load-test reports. `reports/perf/` holds every raw run from the performance work, including the ones that were reverted or contaminated. |

## 8. Deep dives

### 8.1 The lost-update race — what goes wrong without a lock

This is the bug the whole design exists to prevent.

**The setup.** Tomcat serves each HTTP request on its own thread. Each thread
borrows its own database connection. So two stations paying at the same moment
are two *independent* database sessions that cannot see each other's
uncommitted work.

Two customers both buy `SKU-000001`. Stock is 10,000. Alice buys 3, Bob buys 2.

| Time | Alice's thread | Bob's thread | Row value |
|---|---|---|---|
| t1 | `SELECT stock` → **10000** | | 10000 |
| t2 | | `SELECT stock` → **10000** | 10000 |
| t3 | computes 10000 − 3 = 9997 | | 10000 |
| t4 | | computes 10000 − 2 = 9998 | 10000 |
| t5 | `UPDATE stock = 9997`, commit | | 9997 |
| t6 | | `UPDATE stock = 9998`, commit | **9998** |

Five units left the shop. Only two were subtracted. **No error was raised** —
both customers got a valid receipt, both requests logged 200. The only way to
find it is to audit the database afterwards, which is exactly what
`check-invariant.sql` does.

**The fix.** `SELECT ... FOR UPDATE` takes an exclusive lock on the row. Now at
t2 Bob *blocks*. When Alice commits at t5, Bob wakes up and — this is the key
part — **re-reads the current committed value**, 9997, not the 10000 from
before. He computes 9995. Correct.

> Worth understanding: the bug lives in the *read into Java → decide in Java →
> write back an absolute number* pattern. A bare `UPDATE items SET stock = stock - 3`
> would already be safe, because PostgreSQL re-reads the row after waiting.

### 8.2 Why sorting the basket prevents deadlock

A lock is held until the transaction commits. So two baskets can trap each
other:

| Time | Transaction 1, basket {A, B} | Transaction 2, basket {B, A} |
|---|---|---|
| t1 | locks **A** ✅ | |
| t2 | | locks **B** ✅ |
| t3 | wants **B** → blocks | |
| t4 | | wants **A** → blocks |

Neither can move. PostgreSQL notices after `deadlock_timeout` (1 second here),
kills one transaction, and that customer gets a 500.

**Sorting makes the cycle impossible.** If *every* transaction takes its locks
in ascending SKU order, a deadlock would require someone to hold a higher SKU
while waiting for a lower one — which sorted acquisition never does. This
breaks the "circular wait" condition, one of the four things a deadlock needs.

It costs one `ORDER BY`. It's prevention, not detection.

`scripts/test-concurrent-completions.py` tests this on purpose by building half
its baskets in reverse order and completing them all simultaneously.

> Any *total order* works — descending would be just as deadlock-free. That's
> why descending was worth A/B testing (see ARCHITECTURE.md §3.2).

### 8.3 Pessimistic vs optimistic locking

| | Pessimistic (what we use) | Optimistic |
|---|---|---|
| How | Take the lock up front: `SELECT ... FOR UPDATE` | No lock. Add a `@Version` column; on write, check nobody changed it. If they did, throw and retry. |
| Good when | Conflicts are **common** | Conflicts are **rare** |
| Cost of a conflict | One wait, in an orderly queue | Throw away all the work and redo it |

The load client's sampler is **Zipf-weighted**: item *i* gets weight 1/*i*. With
2000 items that means `SKU-000001` takes ~12% of all scans and appears in
roughly **two thirds of all baskets**.

So conflicts aren't rare — they're the normal case. Under optimistic locking at
100 stations, most completions would lose the race on that one row, retry, and
collide again on the retry. Throughput collapses while CPU usage goes *up*, and
an unlucky transaction can be starved indefinitely because a retry race has no
fairness.

Pessimistic locking turns the same contention into a **FIFO queue**: one wait
and one write per completion, bounded work, nobody starves. We pay for it in
tail latency on completions, which is measured in ARCHITECTURE.md §2.

### 8.4 Selling out, and why `applied_quantity` exists

Scans are never gated by stock. So a popular item can be *scanned* more times
than it has units — and in a real run this happens routinely; four SKUs sold
out in the stress test.

If we simply clamped the subtraction at zero, the books would stop balancing:
`10000 − stock` would no longer equal what the system thinks it sold, and the
invariant check couldn't tell an honest sell-out from a genuine lost-update bug.

So each basket line records **two** numbers:

- `quantity` — units **scanned**. Printed on the receipt, because that's what
  the customer is holding.
- `applied_quantity` — units **actually subtracted**. What the invariant is
  checked against.

```java
int before  = item.getStock();
int applied = Math.min(before, line.getQuantity());  // clamp: never below zero
item.setStock(before - applied);
line.setAppliedQuantity(applied);                    // remember what really happened
```

CHECK 3 of `check-invariant.sql` lists the difference per SKU, so a sell-out is
*visible* rather than hidden inside a passing CHECK 2.

### 8.5 The hopping window, and why `BIGSERIAL` has gaps

**Hopping window** = a window of fixed *width* that jumps forward in fixed
*steps*. Width 1000 scans, step 500 scans, so windows overlap:

```
scans:    0 ────────── 500 ────────── 1000 ────────── 1500 ────────── 2000
window 1: (   0 , 500 ]
window 2: (   0 , 1000 ]
window 3:           ( 500 , 1500 ]
window 4:                     ( 1000 , 2000 ]
```

Half-open `(start, end]` so consecutive windows neither double-count nor skip
the scan sitting exactly on the boundary.

**The trap.** The obvious way to detect a boundary is `if (seq % 500 == 0)`.
That is **broken**, because a `BIGSERIAL` is not gapless — a rolled-back INSERT
still consumes its number permanently. If scan 1000 happens to be the one that
rolls back, the number 1000 never appears in the table and that window is
skipped *forever*.

**That was the Week 2 fix** — ask which boundary this scan has *passed*, then
claim it atomically with `compareAndSet`, so exactly one of 100 threads wins.

**Week 3 removed the problem instead of working around it.** The sequence no
longer comes from a `BIGSERIAL`: the pipeline's ingest filter numbers events
itself, one at a time, on one thread, and only ever sees scans that already
committed. No rollbacks, no gaps, one writer — so `seq % 500 == 0` is now
simply correct, and the whole `AtomicLong` dance is gone. Fixing the cause beat
defending against the symptom.

### 8.6 Low-stock semantics

The spec says alerts fire when stock "drops **below**" the threshold, so the
comparison is strict `<`. Landing exactly on 50 is **not** low; 49 is. The
reference mock server uses `<` too.

Where we deliberately differ from the mock server: it recomputes from current
stock on every request and stamps `triggeredAt` with "now" — so its trigger
time is really just the query time. The spec asks for alerts that are
"queryable after the fact", which needs a *real* trigger time. So we write a
row at the moment of the crossing, from inside the completing transaction while
that item's row lock is held — which guarantees exactly one completer can
observe any given crossing.

An ad-hoc `?threshold=` applies to that one query and is never persisted. If a
SKU qualifies under it but never actually crossed, it has no genuine trigger
time, so it reports `generatedAt`.

### 8.7 Connection-pool saturation — the measured surprise

At 100 stations, `START_TRANSACTION` and `SCAN_ITEM` went from ~1 ms to ~17 ms
at p50 — even though **neither takes an item lock**. Why?

Thread dumps during a live 100-station run answered it:

```
http-nio threads   waiting in HikariPool.getConnection   inside JDBC
      103                      65                            31
      103                      68                            31
      103                      67                            32
```

About **two thirds of all request threads were parked waiting for a database
connection**, with exactly 31–32 inside JDBC — the pool's maximum of 32,
saturated continuously.

The mechanism: a completion holds its connection for as long as it holds its
row locks. So a lock-free scan ends up queuing behind lock-holding completions
**at the pool**. That's how contention on `items` rows reaches operations that
never touch `items` at all.

A sanity check with Little's Law confirms nothing else is going on. Each
station does one start, ~10.5 scans and one complete, strictly in sequence:

```
cycle ≈ 20 + 10.5 × 19.7 + 111 ≈ 337 ms
100 stations / 0.337 s ≈ 297 transactions/sec      (measured: 297.1)
```

The stations are simply serialized by their own round-trip latency.

> **Read this alongside §8.10.** That measurement was taken before the
> performance work. Scans no longer open a transaction or take a connection at
> all, so they can no longer queue behind completions at the pool: `SCAN` p50
> at 100 stations is now ~0.10 ms rather than ~17 ms. The pool is still the
> place where completions queue, but the operations that take no locks have
> been taken out of that queue entirely.

### 8.8 Why an unpaid basket is not a database row

A basket being scanned has exactly one writer (its own station), is read by
nobody else, and is thrown away if the customer walks off. Storing it in
Postgres bought nothing and cost four statements per start and seven or eight
per scan.

It now lives in an `OpenBasket` guarded by its own monitor, in a
`ConcurrentHashMap`. The database only ever sees a basket that was actually
paid for.

What that costs, stated plainly:

- **An unpaid basket does not survive a restart.** That is acceptable here
  (the customer is standing at the till and can rescan) and it is the only
  thing lost — a *paid* basket is as durable as it ever was, because the
  commit still happens before the receipt is returned.
- **One process only.** Two servers would each have their own map and would
  not recognise each other's baskets. Scaling out would mean a shared store
  (Redis, or back to the database) — the same trade the single-process design
  already makes elsewhere.

The guarantees the database used to provide had to be re-created in the class,
which is why `OpenBasketTest` exists: line ordering, and "a basket can only be
claimed for completion once".

### 8.9 `FOR UPDATE` vs `FOR NO KEY UPDATE` — a 250× mistake

The bulk lock says `FOR NO KEY UPDATE`. Changing it to the more obvious
`FOR UPDATE` collapsed the server: **2.9 tx/s and 9,239 errors**, with
deadlocks, against 742 tx/s and zero errors.

The reason is a Postgres detail that is easy to miss. `scan_log`,
`transaction_items` and `low_stock_alerts` all carry a foreign key to `items`.
Whenever Postgres validates one of those foreign keys it takes a
**`FOR KEY SHARE`** lock on the parent `items` row. And:

| | `FOR KEY SHARE` |
|---|---|
| `FOR NO KEY UPDATE` | compatible ✅ |
| `FOR UPDATE` | **conflicts** ❌ |

So with `FOR UPDATE`, every basket's own line inserts fought the lock that
basket was already holding, against every other basket doing the same.
`FOR NO KEY UPDATE` is also the correct intent: we change `stock`, never the
primary key.

This is why the lock statement has a comment on it telling you not to
"simplify" it.

### 8.10 The critical section is the ceiling

`CheckoutTimings` splits a completion's database time into two numbers that
behave completely differently:

| | 10 stations | 100 stations |
|---|---|---|
| **lock wait** — how long the lock statement took | 7,626 µs | 101,224 µs |
| **critical section** — lock granted → commit | 1,127 µs | 1,119 µs |

The wait grows 13× with load. The critical section does not move at all.

That is the whole diagnosis in two rows. The wait is a *queue* — the server
cannot shorten it directly. The critical section is the server's own work, and
because every basket containing the hottest SKU has to pass through it one at
a time, it is what sets the ceiling:

```
ceiling ≈ 1 / (critical section × fraction of baskets holding that SKU)
        ≈ 1 / (0.001127 s × 0.66) ≈ 1,344 tx/s
```

Measured throughput was ~995 tx/s, about 74 % of that — the right order, since
other SKUs serialize too.

**So the only useful question is "what can be moved out of the critical
section?"** The transaction header could: it depends on nothing the lock
protects. Moving its `INSERT` above the lock statement cut the critical
section to 1,016 µs (−9.8 %) and raised throughput ~3.7 % at 10 stations. It
is the same database transaction either way, so a failure still rolls the
header back.

The lines cannot move — their `applied_quantity` is only known after the stock
is read under the lock.

### 8.11 "The sink is slow" is not a diagnosis

Once checkout got about twice as fast, the analytics pipeline stopped keeping
up: `sinkPipe` sat full at 256/256 with 1,299 full-waits, and the backlog
pushed all the way up to the window stage.

The obvious reading — "writing a snapshot takes ~55 ms, make it faster" — is
wrong, and the fix for it would have been wrong too. Three completely
different problems hide behind one slow write, so `JdbcSnapshotWriter` was
changed to take a raw `Connection` and time them apart:

| Per snapshot write | shared pool | dedicated pool |
|---|---|---|
| waiting for a pooled connection | **66,480 µs** | 11 µs |
| executing the `INSERT` | 230 µs | 204 µs |
| commit | 156 µs | 216 µs |
| **total** | **66,866 µs** | **431 µs** |

**99.4 % of it was queueing, not working.** At 100 stations, checkout keeps
the 32-connection pool saturated while it waits on item row locks, and the
sink was simply last in that queue. No amount of tuning the `INSERT` would
have touched it.

Two separate fixes came out of this, and they fix two separate things:

1. **Batching** (`BatchingLoop`) removed the *backpressure*. Draining
   everything queued into one multi-row `INSERT` means a backlog makes the
   next write cheaper per row. `sinkPipe` max depth went from 256/256 to
   1/4096.
2. **A dedicated 2-connection pool** (`AnalyticsWriterPool`) removed the
   *wait*, 155× per window.

Honest footnote: the dedicated pool did **not** raise checkout throughput —
the sink only took a connection about 20 times a second, so checkout was never
really waiting on it. It is kept because it makes the analytics branch
independent of checkout pressure, which is the entire point of separating
them, and because with raw scan persistence switched on the writer does
~1.2 M inserts instead of 2,500.

---

## 9. How to run everything

### Prerequisites

- A full **JDK 21** (not just a JRE — the load client needs `javac`)
- **PostgreSQL 16**
- No Maven install needed; use the bundled `./mvnw`

### Create the database — once

```bash
brew services start postgresql@16
createdb self_checkout
```

That's the only manual database step ever.

### Start the server

```bash
./mvnw spring-boot:run
```

Listens on `http://localhost:8080`.

### Reset — just restart

**Restarting the server IS the reset.** On every boot `schema.sql` drops and
recreates all six tables and `data.sql` reseeds 2000 items × 10,000 units.

> Because a restart wipes everything, **run the invariant check before
> restarting** if you care about a load test's results.

### Run the load client

```bash
cd ../CS6510-2026/load-client
bash build.sh                      # once

# Default: 10 stations, 60 seconds
bash run.sh --baseUrl=http://localhost:8080 --stations=10 --duration=60

# Stress: 100 stations, 120 seconds
bash run.sh --baseUrl=http://localhost:8080 --stations=100 --duration=120
```

Restart the server before **every** run so each starts from a full catalog.
The client and server share one machine, so check the load average is low
first — a busy machine changes the numbers a lot.

### Check correctness after a load test

```bash
psql self_checkout -f scripts/check-invariant.sql
```

| Check | What it proves | Expected |
|---|---|---|
| 1 | No SKU has negative stock | **0 rows** |
| 2 | `10000 − stock == SUM(applied_quantity)` for every SKU | **0 rows** |
| 3 | Sold-out SKUs and how many scanned units the shelf couldn't cover | informational |

Plus a summary line: violations, completed transactions, total scans, windows
written, alerts recorded.

### Run the architecture test

```bash
./mvnw test -Dtest=LayeredArchitectureTest
```

Six rules, no Spring, no database — about a second. It fails the build if any
layer starts depending on something it shouldn't.

> `./mvnw test` on its own also runs `contextLoads`, which boots the app and
> therefore **re-runs `schema.sql` and wipes the database**. Never run it
> between a load test and its invariant check.

### What the test suite covers

29 tests, none of which need a running server:

| Group | What it checks |
|---|---|
| `LayeredArchitectureTest` | The 6 layer dependency rules. |
| `WindowFilterTest` | Eviction at 1000, emission exactly every 500, half-open ranges, counts across boundaries, snapshots immutable. |
| `RankFilterTest` | Top 10, tie-break by SKU, fewer than 10 distinct SKUs, empty windows. |
| `ScanLogWriterFilterTest` | Batching, the `maxBatch` cap, flush-on-idle, and the shutdown flush. |
| `BlockingQueuePipeTest` | `offer` refuses instead of blocking; poison never jumps the queue; depth/wait accounting. |
| `PipelineEndToEndTest` | 2,600 events through the wired filters on real threads, every window compared with a brute-force recount; plus "one bad message does not kill a stage". |

### Run the behavioural tests

Restart the server first (they assume full stock), then:

| Command | What it proves |
|---|---|
| `python3 scripts/test-concurrent-completions.py` | 60 baskets completing simultaneously on shared SKUs decrement stock **exactly**, with no lost updates and no deadlocks. Half the baskets are built in reverse SKU order as deadlock bait. |
| `python3 scripts/test-oversell-and-double-complete.py` | Stock clamps at zero and never goes negative when a SKU sells out, `applied_quantity` records what really happened, the receipt still shows the scanned quantity — and 20 threads completing one basket produce exactly one 200 and 19 409s. |
| `python3 scripts/test-low-stock.py` | Strict `<` (50 doesn't alert, 49 does), alerts don't duplicate or move as stock keeps falling, and an ad-hoc `?threshold` persists nothing. |
| `python3 scripts/test-popular-window.py` | One window per 500 scans, each spanning exactly 1000, ranks 1-based and dense, counts matching a raw SQL aggregate — and the window genuinely **slides** (the winner changes when the scan mix changes halfway through). |

Each prints `PASS`/`FAIL` per assertion and exits non-zero on failure.

---

## 10. Troubleshooting

Real problems hit while building this, and what they actually meant.

### `FATAL: database "Self_Checkout_Local" does not exist`

The name in a GUI like pgAdmin is just a **connection label**, not the database
name. The app needs the real database that `createdb` made.

```bash
psql -l                       # list the real database names
createdb self_checkout        # if it's genuinely missing
```
Then make sure `spring.datasource.url` ends in the real name:
`jdbc:postgresql://localhost:5432/self_checkout`.

### `Failed to configure a DataSource: 'url' attribute is not specified`

Spring can't see your datasource settings. Almost always one of:

1. **The properties file wasn't saved.** The most common cause by far — the
   editor shows the setting, the file on disk doesn't have it. Save and rerun.
2. The file is in the wrong place. It must be
   `src/main/resources/application.properties`.
3. A typo in the key, e.g. `spring.datasource.urls`.

### `cannot drop table items because other objects depend on it`

Usually an **orphaned table from a rename**. If a table was renamed, the old
one still exists but `schema.sql` no longer mentions it, so the script never
drops it — and its foreign key still points at `items`.

```bash
psql self_checkout -c "\dt"      # find the table that shouldn't be there
psql self_checkout -c "DROP TABLE the_orphan;"
```

Drop it by name, **once**, by hand. Don't reach for `DROP ... CASCADE` — it
will happily remove things you didn't intend.

### `cannot find symbol: class Xyz`

A file is missing or was renamed and something still refers to the old name.

```bash
find src/main/java -name "*.java" | sort    # is the file actually there?
grep -rn "Xyz" src/main/java                # who still references it?
```

Also check the `package` line matches the folder path, and that the class is
under `com.selfcheckout.self_checkout` — component scanning starts there, so a
class outside it compiles but is never wired up.

### `curl: (23) Failed writing body`

Not a server error at all. It happens when you pipe `curl` into something that
stops reading early:

```bash
curl -s localhost:8080/items | head -c 200     # ← error 23
```

`head` closes the pipe once it has enough, and `curl` complains it can't finish
writing. The request succeeded. Either ignore it, or avoid the early close:

```bash
curl -s localhost:8080/items -o /tmp/items.json && head -c 200 /tmp/items.json
```

### Latency looks terrible / load test reports errors

Check what else is using the machine — the load client and server share one
CPU. A background app spinning up mid-run has produced timeouts that looked
like server bugs but weren't.

```bash
uptime                        # load average; want it low before a run
ps aux | sort -nrk3 | head    # top CPU consumers
```

The server log will say `Thread starvation or clock leap detected` when the JVM
itself was starved — that's a host problem, not a code problem.

---

## 11. Glossary

| Term | Plain meaning |
|---|---|
| **Entity** | A Java class mapped to a database table; one object = one row. `Item`, `Transaction`. |
| **DTO** | Data Transfer Object. A simple record shaped for the *API*, not the database. Keeps the JSON contract independent of the tables. |
| **Layer** | A group of classes with one responsibility, allowed to depend only on the layers below it. |
| **Closed layer** | Must be passed through; you cannot reach past it. Buys isolation. |
| **Open layer** | May be bypassed. Ours: `analytics`. Avoids a pointless hop. |
| **Sinkhole** | A layer that receives a call and just forwards it, adding nothing. |
| **Domain exception** | An error thrown by the business layer that describes *what* went wrong, with no HTTP in it. |
| **Shared kernel** | Something every layer may use (here: `config`) that depends on none of them. |
| **ArchUnit** | A library that reads compiled classes and fails a test when a dependency rule is broken. |
| **Repository** | The interface that holds the queries. Spring generates the implementation; we just declare the methods. |
| **Derived query** | A repository method whose **name** defines the SQL — `findByTransactionIdOrderBySkuAsc` becomes a `WHERE` + `ORDER BY`. Rename it carelessly and you change the query. |
| **Projection** | A small record used to receive query results, instead of loading whole entities you don't need. |
| **`@Transactional`** | "Everything in this method is one database transaction." It all commits together, or none of it does. Also the boundary at which row locks are released. |
| **Dirty checking** | Hibernate remembers what a loaded object looked like. At commit it compares, and writes `UPDATE`s for whatever changed — which is why `complete()` has no `save()` calls. |
| **Managed entity** | An object Hibernate is currently tracking. Changing its fields is enough; you don't have to save it. |
| **Row lock** | An exclusive claim on one table row, taken by `SELECT ... FOR UPDATE`, held until commit. Others wanting that row wait. |
| **Pessimistic locking** | Assume a conflict; take the lock up front. Best when conflicts are common. |
| **Optimistic locking** | Assume no conflict; detect it at write time and retry. Best when conflicts are rare. |
| **Deadlock** | Two transactions each holding what the other wants, so neither can move. PostgreSQL detects it and kills one. |
| **Lost update** | Two writers both read the same value and both write back, so one update vanishes silently. §8.1. |
| **Connection pool** | A set of pre-opened database connections lent out to threads, because opening one is slow. Ours is HikariCP, max 32. |
| **Zipf distribution** | A "few things are hugely popular" pattern: rank *i* gets weight 1/*i*. Real retail looks like this, and the load client copies it. |
| **p50 / p95 / p99** | Percentiles. p95 = 95% of requests were faster than this. The **tail** (p99) is where contention shows up; an average hides it. |
| **Little's Law** | `items in system = arrival rate × time in system`. Used here to check that throughput is fully explained by per-request latency. |
| **Hopping window** | A fixed-width window that jumps forward in fixed steps, so consecutive windows overlap. Width 1000 scans, step 500. |
| **BIGSERIAL** | PostgreSQL auto-incrementing 8-byte integer. Always increases, but **can skip** numbers. §8.5. |
| **Invariant** | Something that must always be true. Here: `starting stock − current stock == units actually sold`. |

---

## 12. Where the results live

This guide covers *how it works*. For **what it measured** — the two load runs,
the latency percentiles, the lock-order A/B, the prioritized architectural
characteristics and their trade-offs — see:

### → [ARCHITECTURE.md](ARCHITECTURE.md)

Raw data: [`reports/report-default-10stations-60s.json`](reports/report-default-10stations-60s.json)
and [`reports/report-stress-100stations-120s.json`](reports/report-stress-100stations-120s.json).

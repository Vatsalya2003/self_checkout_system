# Microservices (Week 5)

The self-checkout system is four **independently deployable microservices**:
- **Own database:** each service has its own database, which only its own PostgreSQL role can open.
- **Own process:** each service is its own Spring Boot jar, JVM process and port.
- **One URL:** an nginx gateway on `:8080` gives clients one base URL.

The OpenAPI contract and the load client are unchanged. The 29-response golden diff through the gateway is identical to the single application.

## The services and their data stores

| Service | Business function | Public endpoints (via :8080) | Port | Own data store | Tables |
|---|---|---|---|---|---|
| **catalog-service** | What the store sells | `GET /items` | 8081 | `catalog_db` | `items` |
| **checkout-service** | Running a sale; orchestrates payment | `POST /transactions`<br>`POST /transactions/{id}/items`<br>`POST /transactions/{id}/complete`<br>`GET /transactions/{id}` | 8082 | `checkout_db` | `transactions`, `transaction_items` (open baskets stay in memory) |
| **inventory-service** | Stock and low-stock alerts | `GET /inventory/low-stock` | 8083 | `inventory_db` | `stock`, `low_stock_alerts`, `stock_commits`, `stock_commit_lines` |
| **analytics-service** | Popular items (top 10 of the last 1,000 scans, every 500) | `GET /analytics/popular-items` | 8084 | `analytics_db` | `scan_log`, `popular_snapshots` |
| *gateway (nginx)* | Routing only | everything above | 8080 | — | — |

The four databases are on one PostgreSQL server. `db/reset.sh` creates them, revokes `CONNECT` from `PUBLIC`, and grants each one to a single role (`catalog_svc`, `checkout_svc`, `inventory_svc`, `analytics_svc`). A service cannot even open another service's database, so no service can read another's tables.

## How the services communicate

A service gets data it doesn't own by asking the owner over HTTP, directly on the owner's port:

| From → to | Call | When | Why |
|---|---|---|---|
| checkout, inventory, analytics → catalog | `GET /items` | once at startup, then cached | prices and names (the catalog never changes during a run) |
| checkout → inventory | `POST /internal/stock/commit` | every payment | the stock half of the payment (the saga's pivot) |
| checkout → analytics | `POST /internal/scans` | every ≤ 1,000 scans or 50 ms, in the background | the scan hand-off for popularity |
| checkout → analytics | `GET /internal/scans/max-seq` | at startup, until analytics answers | where scan numbering continues |

The `/internal/**` endpoints are service-to-service only. nginx answers 404 for them before any service sees the request.

## How the multi-database update is coordinated: the payment saga

Paying changes two databases owned by two services: stock in `inventory_db` and the sale in `checkout_db`. That can't be one database transaction any more, so it's a **saga orchestrated by checkout-service**:

| Step | Where | What |
|---|---|---|
| 1 | checkout memory | Claim the basket: OPEN → COMPLETING. A second payment or a late scan now gets 409. |
| 2 | `checkout_db` (tx A) | Save the sale as **PENDING**, with its lines. This is the saga log, written before anything irreversible. |
| 3 | `inventory_db` | `POST /internal/stock/commit` is **the pivot**. In one local transaction, inventory: <ol><li>claims the transaction id (the idempotency key);</li><li>locks the basket's rows in ascending SKU order;</li><li>clamps: takes `min(asked, in stock)`;</li><li>decrements the stock and records the lines and any low-stock alert;</li><li>commits.</li></ol> |
| 4 | `checkout_db` (tx B) | Store the quantities inventory actually took, and mark the sale COMPLETED. |
| 5 | checkout memory | Drop the basket and return the receipt. |

**Why this keeps every unit counted exactly once:**

- **Idempotency.** `stock_commits.transaction_id` is a primary key. Re-sending the same commit, after a timeout or during recovery, returns the stored result and takes nothing more.
- **Forward recovery.** After the pivot the saga only goes forward. If checkout dies between steps 3 and 4, it finds the PENDING row on restart, re-sends the same commit (inventory answers with what it already did) and finishes step 4. Stock is never put back, because undoing a stock commit would be a second distributed step that could itself fail halfway.
- **Inventory down.** If the call **certainly** didn't reach inventory (the connection was refused, or inventory answered with an error after rolling back), checkout deletes the PENDING row, reopens the basket and returns **503 `SERVICE_UNAVAILABLE`** with `Retry-After: 1`. Stock is untouched, and the client can simply pay again.
- **The ambiguous case (no answer).** If the request was sent but no answer came back, inventory may already have taken the stock. Checkout then:
  1. retries the same commit for a few seconds (5.1 s in the failure test);
  2. if there's still no answer, returns **503** to the client, but leaves the basket closed: reopening it could sell the same units twice;
  3. keeps retrying in the background through a **resolver**, which completes the sale shortly after inventory answers.

  Meanwhile a retry of `complete` gets **409**. `GET /transactions/{id}` shows the real outcome, **COMPLETED**, once resolved.
- **Deadlock freedom and the clamp** are unchanged from Week 4. They now run inside inventory.

**Why orchestration rather than choreography.** With choreography, checkout would emit PaymentRequested, inventory would react and emit StockCommitted, and checkout would react to that. The `complete` request has to return the receipt, with applied quantities, in the same HTTP response. With orchestration, one owner calls, waits and decides, and the saga's state lives in one place.

## Scans to analytics: batched HTTP push

- Checkout's scan path offers each scan to a bounded in-memory queue (65,536, at most a 10 ms wait; if full, the scan is dropped and counted). **A customer never waits on analytics.**
- One writer thread numbers the scans with a **gapless** `seq` and pushes them to analytics in batches of up to 1,000 or 50 ms.
- Analytics stores each batch in its own `scan_log` (`ON CONFLICT (seq) DO NOTHING`) and only then replies. Batch N+1 is sent only after batch N is acknowledged, and a failed batch is retried with the same seqs. **No gaps, no duplicates.**
- If analytics is down, checkout keeps serving; scans wait in the queue (drops counted once it fills). A checkout that starts while analytics is down still serves customers and syncs its seq once analytics answers.

## Results

See [README.md](README.md#results) and [ARCHITECTURE.md § Week 5](ARCHITECTURE.md#week-5-microservices).

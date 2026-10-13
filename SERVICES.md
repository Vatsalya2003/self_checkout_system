# Services (Week 4: service-based architecture)

The single application is now **four domain services**. Each is its own Maven module,
its own Spring Boot jar, its own JVM process and its own port. All four share **one
PostgreSQL database** (`self_checkout`). A plain **nginx gateway** on `:8080` gives the
load client its single base URL. The API contract
(`CS6510-2026/spec/self-checkout-openapi.yaml`) is unchanged.

## The services

| Service | Business function | Endpoints | Port | Writes | Reads |
|---|---|---|---|---|---|
| **catalog-service** | What the store sells: SKUs, names, prices | `GET /items` | 8081 | — | `items` |
| **checkout-service** | Running a sale: open basket, scan, pay; decrements stock, records alerts, publishes every scan | `POST /transactions`<br>`POST /transactions/{id}/items`<br>`POST /transactions/{id}/complete`<br>`GET /transactions/{id}` | 8082 | `items.stock`, `transactions`, `transaction_items`, `low_stock_alerts`, `scan_log` | `items`, `transactions`, `scan_log` (MAX(seq) at startup) |
| **inventory-service** | Stock watch: which SKUs have fallen below the threshold | `GET /inventory/low-stock` | 8083 | — | `low_stock_alerts`, `items` |
| **analytics-service** | Popularity: the top-10 over the last 1,000 scans, every 500 | `GET /analytics/popular-items` | 8084 | `popular_snapshots` | `scan_log`, `popular_snapshots`, `items` (names) |
| *gateway (nginx)* | Routes by path; no business logic | everything, on one URL | 8080 | — | — |

Every table has **exactly one writing service**. `items` is read by every service but
written only by checkout, and only its `stock` column (the decrement at payment).

## Deployment

```bash
db/reset.sh                    # schema + seed data + per-service grants (PostgreSQL running)
scripts/start-all.sh --build   # build the 4 jars, start them, then nginx on :8080
scripts/stop-all.sh            # stop the gateway and all four services
```

Each service is started as `java -Xmx… -jar <service>/target/<service>-0.0.1-SNAPSHOT.jar`
and logs to `logs/<service>.log`. The gateway (`gateway/nginx.conf`) starts last, once
every service answers its health check. It routes `/items` to catalog, `/transactions/**`
to checkout, `/inventory/**` to inventory and `/analytics/**` to analytics. Unmatched paths
go to catalog, so an unknown route gets the same Spring 404 body as before. It keeps
HTTP/1.1 keep-alive connections open to each service.

## How the services avoid talking to each other

There are **no service-to-service calls**: no HTTP client, no message broker, no shared
in-memory state. The only coupling is the database.

- **Scans → analytics.** This was the one live flow between the old components. Checkout
  appends every successful scan to `scan_log` with a gapless sequence number. It uses a
  bounded in-memory queue and one writer thread, which does one multi-row INSERT per
  ≤ 1,000 rows or 50 ms. Analytics polls `SELECT … WHERE seq > :last ORDER BY seq` and
  feeds its Week 3 window → rank → sink pipeline. **`scan_log` is therefore always
  written.** The old `selfcheckout.analytics.persist-raw-scans` flag is gone. A scan
  never waits on analytics: if the queue is ever full, the event is dropped and counted,
  and the scan still succeeds.
- **Payments → inventory.** Checkout writes `low_stock_alerts` in the same transaction as
  the stock decrement. Inventory only reads that table.
- **Catalog data.** Each service that needs names or prices reads `items` itself, through
  the shared read-only catalog cache.

Each service keeps working if another is stopped. A stopped analytics-service catches up
from `scan_log` when it restarts. A restarted checkout-service continues numbering from
`MAX(seq)`.

## How database-access logic is shared

Through two small **library modules**. They are plain jars that every service depends on,
not running services:

- **`shared-data`**: the read-only catalog cache (`CatalogCache`, loaded once with a plain
  `SELECT sku, name, price FROM items`) and the shared configuration properties (stock
  threshold, window size and slide).
- **`shared-web`**: the error JSON shape (`ApiError`), the global exception handler and the
  domain exceptions. This keeps every service's error responses byte-for-byte what the
  single application returned.

Everything that *writes* lives inside the one service that owns the table. That is
enforced twice:
- **In the code, by ArchUnit** (`*/src/test/java/…/architecture/`): catalog and inventory
  contain no database write calls at all, and in analytics only the snapshot writer may
  write.
- **In the database, by roles.** Each service connects as its own PostgreSQL role, and
  `db/roles.sql` grants it only its row of the table above. Checkout's UPDATE on `items`
  covers the `stock` column only.

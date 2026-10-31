# Events (Week 6)

The four microservices no longer call each other over HTTP. **Every piece of
cross-service communication is a message through RabbitMQ.** The load client still talks
HTTP to the nginx gateway on `:8080`; that's client traffic, not service-to-service traffic.
Each service still owns its own database.

## The broker

- **RabbitMQ 4.4.0**, one local node (Homebrew, `brew services start rabbitmq`), AMQP on `:5672`.
- **Spring AMQP 4.1.1** (`spring-boot-starter-amqp`, version from the Spring Boot 4.1.1 BOM).
- **Shared code:** the event records, the queue names and the broker settings live in one
  shared module, [`messaging/`](messaging/src/main/java/com/selfcheckout/self_checkout/messaging/).
- **Why RabbitMQ, not Kafka:**
  - The payment needs a **request with a reply**, because the HTTP `complete` must return the
    receipt. RabbitMQ has per-message acks and publisher confirms for exactly that.
  - It needs about 150 MB of memory, against roughly 1 GB for a Kafka JVM, on an 8 GB laptop.
  - It deletes messages once they are acked, so it doesn't keep a log on a nearly full disk.
  - Kafka's replayable log would add nothing here: analytics already stores every scan in its
    own `scan_log`.

## The events

| Event | Publisher → consumer | Queue / exchange | Replaces (Week 5 HTTP) |
|---|---|---|---|
| `CatalogRequested {requestedBy}` | checkout, inventory, analytics → catalog | queue `catalog.requests` | `GET /items` at startup |
| `CatalogPublished {items[sku, name, price]}` | catalog → checkout, inventory, analytics | fanout exchange `catalog.snapshots`, one temporary queue per consumer | the response to it |
| `StockCommitRequested {transactionId, lines[sku, quantity]}` | checkout → inventory | queue `inventory.stock-commit` | `POST /internal/stock/commit` |
| `StockCommitted {transactionId, replayed, lines[sku, quantity, applied]}` | inventory → checkout | queue `checkout.stock-replies` | its 200 response |
| `StockCommitRejected {transactionId, reason}` | inventory → checkout | queue `checkout.stock-replies` | its error response |
| `ScansRecorded {batchId, scans[sku, scannedAt]}` | checkout → analytics | queue `analytics.scans` | `POST /internal/scans` |

`GET /internal/scans/max-seq` is gone: checkout no longer numbers scans (see below). All
`/internal/**` endpoints have been removed, and nginx still answers 404 for that path.

**Delivery settings:**
- **Queues and messages:** all queues are **durable** classic queues, and messages are
  **persistent**, so they survive a broker restart.
- **Publisher confirms and returns:** a message counts as sent only once the broker has
  acknowledged it.
- **Consumer acks:** a consumer acknowledges a message only after its database commit.
- **Concurrency:**

  | Consumer | Consumers | Prefetch |
  |---|---|---|
  | inventory | 32 competing | 1 each |
  | analytics | **1** (keeps order) | 10 |
  | checkout's replies | 4 | — |
  | catalog | 1 | — |

- **Dead letters:** every queue has a dead-letter queue (`<queue>.dlq`). A message only goes
  there if it can never be read (not valid JSON); transient failures are retried instead.
  Every service logs its DLQ counts every 10 s (`dead letters | …`, WARN if not zero). The
  load-run summary and the failure tests both check that every DLQ is empty.

## The payment saga over events

This is still **orchestration**, with checkout as the orchestrator: it sends a command and
waits for the answer. The `complete` HTTP request must return the receipt synchronously, so
one owner that waits and decides is simpler than a chain of reactions (choreography).

1. Claim the basket in memory (OPEN → COMPLETING). **Tx A** saves the sale as PENDING in
   `checkout_db`. This is unchanged from Week 5.
2. Register a waiting future under the `transactionId`, the correlation id. Then publish
   `StockCommitRequested` and wait for the broker's confirm.
3. Inventory consumes it and runs the **same idempotent local transaction as Week 5**:
   1. claim the id;
   2. lock the basket's rows in SKU order (`FOR NO KEY UPDATE`);
   3. clamp, then decrement;
   4. record the lines and any low-stock alert;
   5. commit.

   Then it publishes `StockCommitted` and waits for that confirm. **Only then does it ack the
   request.**
4. Checkout's reply consumer hands the answer to the waiting future. **Tx B** marks the sale
   COMPLETED, and the request returns the receipt.

**Why every crash is safe:**
- **Inventory crashes after its commit, before acking:** the broker redelivers the request.
  Inventory's commit is idempotent on `transactionId`, so it answers from what it stored and
  takes nothing more. That is why no transactional outbox is needed: the reply can always be
  rebuilt from `inventory_db`.
- **Checkout crashes after publishing:** on restart its resolver re-sends the request for
  every PENDING sale, inventory replays its answer, and tx B runs.
- **A duplicated request or reply is harmless:**
  - inventory replays the stored result;
  - a reply nobody is waiting for is counted (`stray`) and dropped;
  - tx B only changes a sale that is still PENDING.

## Failure handling

`ConfirmedPublisher` sorts every publish into three outcomes, and the saga maps them onto
Week 5's two cases:

| What happened | Meaning | Checkout does | Client sees |
|---|---|---|---|
| no connection to the broker, an explicit nack, or unroutable | **certainly not delivered** | deletes the PENDING sale, reopens the basket | **503** + `Retry-After: 1`; can pay again |
| no confirm in time (or the send itself hangs, e.g. on a broker disk alarm) | **possibly delivered** | keeps the basket closed; the resolver finishes the payment | **503**, then **409** on a retry; `GET` shows COMPLETED once resolved |
| confirmed, but no reply within 2.5 s (e.g. inventory is down) | **certainly queued**: inventory will process it when it returns | same as above | same as above |
| `StockCommitted` | committed | tx B, receipt | **200** |
| `StockCommitRejected` | inventory took nothing | as "not delivered" | **503** |

**Inventory down** is therefore no longer the "reopen the basket" case it was in Week 5. Once
the request is in a durable queue, inventory will act on it later, so reopening the basket
could sell the same units twice. The sale completes in the background as soon as inventory is
back. Waiting time is bounded by a 1 s confirm and a 2.5 s reply per attempt within a 3 s
budget, so the client gets its 503 in 3–5 s, well inside its 10 s timeout.

## Scans over events

- **A scan never waits:** it only offers its SKU to a bounded in-memory queue (65,536 entries,
  at most a 10 ms wait; if the queue is full, the scan is dropped and counted).
- **Batching:** one writer thread collects up to 1,000 scans or 50 ms into a `ScansRecorded`
  batch with a fixed **`batchId`**. It publishes the batch with a confirm, and re-sends the
  *same* batch until it is confirmed. Only then does it build the next one.
- **Analytics numbers the scans,** with a single consumer, in one transaction per batch:
  1. take a numbering lock (`pg_advisory_xact_lock`);
  2. `next = MAX(seq) + 1`;
  3. insert the `batchId` into `scan_batches`; if it is already there, this is a redelivery,
     so store nothing;
  4. insert the scans as `next …`;
  5. commit, then ack.
- **Guarantees:**
  - **gapless:** only that transaction assigns numbers;
  - **no duplicates:** the `batchId`;
  - **in order:** one consumer, batches published one at a time.

  The window filter's `seq % 500` boundaries stay exact.
- **When something is down:** if analytics is down, batches wait in `analytics.scans`; if the
  broker is down, they wait in checkout's queue. Checkout keeps serving either way.
- **Database errors:** a scan batch is never dead-lettered for a database error. Analytics
  retries it, with backoff, until it succeeds.

**Why analytics numbers the scans, instead of checkout saving its own next `seq`:** checkout
would have to save the counter either before or after publishing, and a crash between the
two breaks the sequence.
- **Saved after publishing:** the same numbers get reused, and analytics drops those scans as
  duplicates.
- **Saved before publishing:** a gap opens, and the window filter stops at it forever.

Numbering inside the transaction that stores the scans has neither problem.

## Evidence

- [`reports/event-driven/failure-tests.txt`](reports/event-driven/failure-tests.txt), all
  PASS. It tests:
  - the broker stopped under load;
  - inventory down;
  - checkout crashing between inventory's commit and tx B;
  - analytics down under load;
  - duplicated messages;
  - a publisher confirm timeout;
  - `/internal/**` gone;
  - no service-to-service connections ([`scripts/check-connections.sh`](scripts/check-connections.sh)).
- [`reports/event-driven/verification/`](reports/event-driven/verification/): the golden diff
  (identical, 29/29), the five behavioural scripts and the restart walkthrough.

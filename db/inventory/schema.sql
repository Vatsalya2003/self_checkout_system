-- ============================================================================
-- inventory_db -- owned by inventory-service.
--
-- Week 5: stock lives here now, and so does every decision about it. A payment
-- reaches this database through ONE call, POST /internal/stock/commit, which
-- locks, clamps, decrements and records the result in one local transaction.
-- That commit is the pivot of the payment saga (see MICROSERVICES.md).
-- ============================================================================
DROP TABLE IF EXISTS stock_commit_lines;
DROP TABLE IF EXISTS stock_commits;
DROP TABLE IF EXISTS low_stock_alerts;
DROP TABLE IF EXISTS stock;

-- One row per SKU. CHECK (qty >= 0) is the last line of defence: even a bug
-- cannot write negative stock, the UPDATE just fails.
CREATE TABLE stock (
    sku VARCHAR(32) PRIMARY KEY,
    qty INTEGER NOT NULL CHECK (qty >= 0)
);

-- Written the moment a SKU's stock first drops below the threshold, in the
-- same transaction as the decrement. Stock is never replenished, so a SKU
-- crosses at most once.
CREATE TABLE low_stock_alerts (
    sku          VARCHAR(32) PRIMARY KEY REFERENCES stock(sku),
    triggered_at TIMESTAMPTZ NOT NULL
);

-- One row per payment whose stock has been committed. The primary key on
-- transaction_id IS the idempotency check: a retried or duplicated commit for
-- the same transaction finds its row already here and gets the stored answer
-- back, instead of taking the stock a second time.
CREATE TABLE stock_commits (
    transaction_id VARCHAR(64) PRIMARY KEY,
    committed_at   TIMESTAMPTZ NOT NULL
);

-- What each commit asked for and what it actually took. applied < quantity
-- only when the SKU sold out (the clamp). The stock invariant is
--   10000 - stock.qty = SUM(applied) per SKU.
-- No foreign key to stock, on purpose: the same lesson as Week 4's scan_log
-- (an FK check share-locks the hot item rows). The SKUs here were just locked
-- and decremented in the same transaction, so they are known to exist.
CREATE TABLE stock_commit_lines (
    transaction_id VARCHAR(64) NOT NULL REFERENCES stock_commits(transaction_id),
    sku            VARCHAR(32) NOT NULL,
    quantity       INTEGER NOT NULL CHECK (quantity > 0),
    applied        INTEGER NOT NULL CHECK (applied >= 0 AND applied <= quantity),
    PRIMARY KEY (transaction_id, sku)
);

-- ============================================================================
-- analytics_db -- owned by analytics-service.
--
-- Week 5: checkout-service can no longer write into analytics' tables, so it
-- pushed batches of scans to analytics, which stores them here itself.
-- Week 6: the batches arrive as ScansRecorded events from RabbitMQ, and
-- analytics now NUMBERS the scans itself as it stores them (see scan_batches).
-- From scan_log on, everything is exactly Week 4: the poller reads
-- "seq > last ORDER BY seq" and feeds window -> rank -> sink.
-- ============================================================================
DROP TABLE IF EXISTS popular_snapshots;
DROP TABLE IF EXISTS scan_batches;
DROP TABLE IF EXISTS scan_log;

-- Analytics' own copy of every scan. Week 6: seq is assigned HERE, by the one
-- consumer of analytics.scans, inside the same transaction that inserts the
-- rows: next seq = MAX(seq) + 1, under a transaction-level advisory lock. So
-- the numbers are gapless by construction (a rolled-back batch uses none).
CREATE TABLE scan_log (
    seq        BIGINT PRIMARY KEY,
    sku        VARCHAR(32) NOT NULL,
    scanned_at TIMESTAMPTZ NOT NULL
);

-- Week 6: one row per stored batch. batch_id comes from checkout, which keeps
-- it when it re-sends a batch, so a redelivered batch hits this primary key
-- and is skipped: a scan can never be stored (or numbered) twice.
CREATE TABLE scan_batches (
    batch_id    VARCHAR(64) PRIMARY KEY,
    first_seq   BIGINT NOT NULL,
    last_seq    BIGINT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per computed popular-items window, unchanged from Week 4.
CREATE TABLE popular_snapshots (
    id           BIGSERIAL PRIMARY KEY,
    window_start BIGINT NOT NULL,     -- scan seq numbers, not timestamps:
    window_end   BIGINT NOT NULL,     -- the window is seq in (window_start, window_end]
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    items_json   TEXT NOT NULL,       -- the ranked top 10, as a JSON array
    CONSTRAINT popular_snapshots_window_end_key UNIQUE (window_end)
);

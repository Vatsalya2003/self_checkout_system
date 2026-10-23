-- ============================================================================
-- analytics_db -- owned by analytics-service.
--
-- Week 5: checkout-service can no longer write into analytics' tables, so it
-- PUSHES batches of numbered scans to POST /internal/scans, and analytics
-- stores them here itself. From this table on, everything is exactly Week 4:
-- the poller reads "seq > last ORDER BY seq" and feeds window -> rank -> sink.
-- ============================================================================
DROP TABLE IF EXISTS popular_snapshots;
DROP TABLE IF EXISTS scan_log;

-- Analytics' own copy of every scan. seq is assigned by checkout's single
-- writer thread and arrives gapless and in order (a batch is only sent after
-- the previous one was acknowledged). A retried batch is absorbed by
-- ON CONFLICT (seq) DO NOTHING, so a scan can never be counted twice.
CREATE TABLE scan_log (
    seq        BIGINT PRIMARY KEY,
    sku        VARCHAR(32) NOT NULL,
    scanned_at TIMESTAMPTZ NOT NULL
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

-- Cross-check for the scan hand-off and the analytics pipeline (Week 4).
--
-- Run AFTER a load run, once analytics-service has caught up (its log line
-- "analytics lag | ... behind scan_log now=0"), and BEFORE db/reset.sh:
--
--     psql analytics_db -f scripts/check-windows.sql
--
-- CHECK 1 and CHECK 2 must both report 0. Read-only.

\timing off
\pset pager off

\echo ''
\echo '=============================================================='
\echo ' CHECK 1  scan_log is gapless: seqs are exactly 1..max'
\echo '          expected: rows = max_seq, min_seq = 1, gaps = 0'
\echo '=============================================================='
SELECT count(*)                         AS rows,
       COALESCE(min(seq), 0)            AS min_seq,
       COALESCE(max(seq), 0)            AS max_seq,
       COALESCE(max(seq), 0) - count(*) AS gaps
FROM scan_log;

\echo ''
\echo '=============================================================='
\echo ' CHECK 2  every stored window equals a brute-force recount of'
\echo '          scan_log over the same (window_start, window_end]'
\echo '          expected: mismatched_entries = 0'
\echo '=============================================================='
WITH w AS (
    SELECT window_start, window_end, items_json::jsonb AS j FROM popular_snapshots
),
stored AS (
    SELECT w.window_end, (e->>'rank')::int AS rnk, e->>'sku' AS sku, (e->>'scanCount')::bigint AS cnt
    FROM w, jsonb_array_elements(w.j) AS e
),
counted AS (
    SELECT w.window_end, s.sku, count(*) AS cnt
    FROM w JOIN scan_log s ON s.seq > w.window_start AND s.seq <= w.window_end
    GROUP BY w.window_end, s.sku
),
brute AS (
    -- the same ordering the rank filter uses: count desc, then sku asc
    SELECT window_end, sku, cnt,
           row_number() OVER (PARTITION BY window_end ORDER BY cnt DESC, sku ASC) AS rnk
    FROM counted
)
SELECT (SELECT count(*) FROM popular_snapshots) AS windows_checked,
       count(*) FILTER (WHERE st.sku IS DISTINCT FROM b.sku OR st.cnt IS DISTINCT FROM b.cnt)
                                                AS mismatched_entries
FROM stored st
FULL OUTER JOIN (SELECT * FROM brute WHERE rnk <= 10) b
       ON b.window_end = st.window_end AND b.rnk = st.rnk;

\echo ''
\echo '=============================================================='
\echo ' CHECK 3  one window per slide interval (informational)'
\echo '          expected: windows = floor(max_seq / 500)'
\echo '=============================================================='
SELECT (SELECT count(*) FROM popular_snapshots)            AS windows,
       (SELECT COALESCE(max(seq), 0) / 500 FROM scan_log)  AS expected_windows,
       (SELECT max(window_end) FROM popular_snapshots)     AS last_window_end;

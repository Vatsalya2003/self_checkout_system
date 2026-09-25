-- Correctness check for the self-checkout server.
--
-- Run this AFTER a load test and BEFORE restarting the server -- restarting
-- recreates the schema and reseeds the catalog, which wipes the evidence:
--
--     psql self_checkout -f scripts/check-invariant.sql
--
-- Checks 1 and 2 must both report zero rows. Check 3 is informational: it
-- makes the sold-out clamping visible rather than letting it hide.

\timing off
\pset pager off

-- Every SKU starts each run with this much stock (see data.sql).
\set starting_stock 10000

\echo ''
\echo '=============================================================='
\echo ' CHECK 1  no SKU may ever have negative stock'
\echo '          expected: 0 rows'
\echo '=============================================================='
SELECT sku, stock
FROM items
WHERE stock < 0
ORDER BY sku;

\echo ''
\echo '=============================================================='
\echo ' CHECK 2  for every SKU:'
\echo '            starting_stock - current_stock'
\echo '              = SUM(applied_quantity) over COMPLETED transactions'
\echo '          expected: 0 rows'
\echo ''
\echo '          A row here means units left the shelf without being'
\echo '          accounted for (a lost update) or were subtracted twice.'
\echo '          This is the property concurrent implementations break.'
\echo '=============================================================='
SELECT i.sku,
       i.stock                                             AS current_stock,
       :starting_stock - i.stock                           AS removed_from_shelf,
       COALESCE(a.applied, 0)                              AS sum_applied_quantity,
       (:starting_stock - i.stock) - COALESCE(a.applied, 0) AS discrepancy
FROM items i
LEFT JOIN (
    SELECT ti.sku, SUM(ti.applied_quantity) AS applied
    FROM transaction_items ti
    JOIN transactions t ON t.id = ti.transaction_id
    WHERE t.status = 'COMPLETED'
    GROUP BY ti.sku
) a ON a.sku = i.sku
WHERE (:starting_stock - i.stock) <> COALESCE(a.applied, 0)
ORDER BY ABS((:starting_stock - i.stock) - COALESCE(a.applied, 0)) DESC, i.sku;

\echo ''
\echo '=============================================================='
\echo ' CHECK 3  sold-out SKUs: scanned units the shelf could not cover'
\echo '          informational, not a failure'
\echo ''
\echo '          Scans are never gated by stock (a customer already holds'
\echo '          the item), so a hot SKU can be scanned more times than it'
\echo '          has units. The decrement is clamped at zero and the'
\echo '          shortfall shows up here instead of corrupting CHECK 2.'
\echo '=============================================================='
SELECT ti.sku,
       i.stock                                            AS final_stock,
       SUM(ti.quantity)                                   AS units_scanned,
       SUM(ti.applied_quantity)                           AS units_applied,
       SUM(ti.quantity) - SUM(ti.applied_quantity)        AS units_short
FROM transaction_items ti
JOIN transactions t ON t.id = ti.transaction_id
JOIN items i        ON i.sku = ti.sku
WHERE t.status = 'COMPLETED'
GROUP BY ti.sku, i.stock
HAVING SUM(ti.quantity) <> SUM(ti.applied_quantity)
ORDER BY units_short DESC, ti.sku;

\echo ''
\echo '=============================================================='
\echo ' SUMMARY'
\echo '=============================================================='
SELECT
  (SELECT count(*) FROM items WHERE stock < 0)                            AS negative_stock_skus,
  (SELECT count(*) FROM items i
     LEFT JOIN (SELECT ti.sku, SUM(ti.applied_quantity) applied
                FROM transaction_items ti JOIN transactions t ON t.id = ti.transaction_id
                WHERE t.status = 'COMPLETED' GROUP BY ti.sku) a ON a.sku = i.sku
     WHERE (:starting_stock - i.stock) <> COALESCE(a.applied, 0))         AS invariant_violations,
  (SELECT count(*) FROM transactions WHERE status = 'COMPLETED')          AS completed_transactions,
  (SELECT count(*) FROM transactions WHERE status = 'OPEN')               AS abandoned_open_transactions,
  (SELECT count(*) FROM scan_log)                                         AS total_scans,
  (SELECT count(*) FROM popular_snapshots)                                AS popular_windows_written,
  (SELECT count(*) FROM low_stock_alerts)                                 AS low_stock_alerts_recorded;

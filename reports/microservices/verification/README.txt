The five behavioural scripts, run through the gateway (:8080) against the
microservices build, each on a fresh db/reset.sh + restart. All PASS.

Their database checks now say which service's database they read
(scripts/_client.py: INVENTORY_DB, CHECKOUT_DB, ANALYTICS_DB); the checks
themselves are unchanged from Week 4.

Cross-database invariant (scripts/check-invariant-micro.sh) after each one:
PASS for test-concurrent-completions, test-popular-window and
test-scan-complete-race. After test-oversell-and-double-complete and
test-low-stock it reports exactly one SKU where 10000 - stock <> SUM(applied):
the SKU those two tests put into place by setting its stock directly with SQL
(UPDATE stock SET qty = ...) as test setup, which by design never goes through
a stock commit. Every other check (no negative stock, checkout and inventory
lines identical, no PENDING, gapless scan_log, every window recounted) passes
after them too. The Week 4 single-database invariant flagged the same SKU
after the same two tests, for the same reason.

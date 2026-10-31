Week 6 (event-driven) correctness evidence, each check on a fresh db/reset.sh
(databases AND broker queues) + scripts/start-all.sh, all through the gateway.
Summary in RESULT.txt.

- mvnw test: 52 unit and ArchUnit tests.
- golden diff: identical to the single application (29/29 responses).
- The five behavioural scripts: all PASS.
- Cross-database invariant (scripts/check-invariant-micro.sh) after each:
  PASS for test-concurrent-completions, test-popular-window and
  test-scan-complete-race. After test-oversell-and-double-complete and
  test-low-stock it reports exactly one SKU where 10000 - stock <> SUM(applied):
  the SKU those two tests set up by writing its stock directly with SQL, which
  by design never goes through a stock commit. Every other check passes after
  them too. Same as Week 5 (reports/microservices/verification/README.txt).
- test-service-restart.sh: each service stopped and restarted; while
  analytics was down, scan batches waited in the broker's analytics.scans
  queue and were stored gapless when it returned; final invariant PASS.

The failure tests (A-H) are in ../failure-tests.txt.

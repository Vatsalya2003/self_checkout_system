Unofficial A/B of the scan_log -> items foreign key, 10 stations x 30 s, interleaved
fk-1, nofk-1, fk-2, nofk-2. "nofk" = db/reset.sh, then
ALTER TABLE scan_log DROP CONSTRAINT scan_log_sku_fkey (measurement only; the
schema keeps the FK). ALL FOUR RUNS STARTED ABOVE LOAD 4 (macOS background work:
ANECompilerService, mobileassetd), so treat them as indicative, not as results.
fk-1 810.8  nofk-1 858.0  fk-2 495.0  nofk-2 913.3 tx/s; 0 errors, 0 dropped
scans, scan_log gapless and every window matching in all four.

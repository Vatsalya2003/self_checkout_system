Week 4 load runs, every one through the gateway on :8080 with the packaged jars,
db/reset.sh + restart of all services before each run, then check-invariant.sql
and check-windows.sql after it. Each <label>-summary.txt has the load average
before the run, per-process CPU, the scan-log publisher line and the analytics
lag lines. Runs were started only below load 3 (1-min); load rose to ~4-5 between
runs because of macOS background work (ANECompilerService, mobileassetd).

baseline-single-app/  pre-split single app (pipeline-v2 code), 10 st x 60 s, x2
                      (run 2 started at load 5.97 -- Spotlight -- marked)
default/              Week 4, 10 stations x 60 s, x2
stress/               Week 4, 100 stations x 120 s, x2
fk-measurement/       same, with scan_log_sku_fkey dropped after the reset
                      (measurement only). default-nofk-run1 (586.1) is an
                      outlier: every latency is worse, including scan p99.
week3-same-day/       pipeline-v2 jar, 100 st x 120 s, x2, run between the
                      Week 4 stress runs to rule out machine drift
unofficial-fk-ab/     30 s smoke A/B at load > 4 -- indicative only
restart-test/         scripts/test-service-restart.sh output
verification/         the five behavioural scripts through the gateway
gateway-hop.txt       scripts/measure-gateway-hop.py (started at load 7.13 -- marked)

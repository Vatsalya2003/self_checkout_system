# Performance results

Every number here comes from a real run on this laptop (8 cores, PostgreSQL 16
via Homebrew, server and load client on the same machine). Raw reports are kept
under `reports/perf/<phase>/`. Nothing is hand-written or rounded up, and no run
is discarded — reverted phases keep their row.

**Throughput is a closed loop:** the load client runs a fixed number of stations,
each looping start → ~10.5 scans → complete. So
`tx/sec ≈ stations ÷ time per full cycle`. Making any step of the cycle shorter
raises throughput; nothing else does.

---

## Measurement note: how the server is launched matters a lot

The Phase 0 baseline was first taken with `./mvnw spring-boot:run`, which gave
**353.1 / 351.2 tx/s** — about 17 % below the 422 / 417 recorded for this same
commit in `analysis/COMPARISON.md`. The code was not the difference: the Spring
Boot Maven plugin starts the JVM with **`-XX:TieredStopAtLevel=1`**, a
fast-startup default that caps the JIT at C1 and never lets the optimising C2
compiler run.

Same commit, same machine, back to back:

| Launcher | tx/s | SCAN p50 | COMPLETE p50 |
|---|---|---|---|
| `./mvnw spring-boot:run` (`-XX:TieredStopAtLevel=1`) | 353.1 / 351.2 | 1.06 ms | 16.98 ms |
| `java -Xmx1g -jar target/…​.jar` (full JIT) | **424.5** | 0.83 ms | 13.22 ms |

That is **+20 % for free**, and it reconciles with the comparison document.
**Every measurement from here on uses the packaged jar**, which is also how the
comparison measured both servers. Measuring under a crippled JIT would have
understated the baseline and mis-ranked which costs matter.

---

## Results

| Phase | Change | tx/s run 1 | tx/s run 2 | SCAN p50/p99 | COMPLETE p50/p99 | Errors | Invariant | CPU-bound | Kept |
|---|---|---|---|---|---|---|---|---|---|
| Baseline | `pipeline-v1`, unchanged | 404.6 | 407.2 | 0.85 / 2.86 | 13.82 / 50.43 | 0 | PASS | no (~505 % of 800 %) | — |
| Baseline stress | 100 stations, 120 s | 420.3 | 431.9 | 12.52 / 34.61 | 90.84 / 328.56 | 0 | PASS | no (~500 % of 800 %) | — |
| 0.5 | Fix scan/complete race: `scan()` locks the transaction row | 422.4 | 413.3 | 0.85 / 2.65 | 13.22 / 45.19 | 0 | PASS | no (~506 % of 800 %) | **kept** |

### Reading the phases

**Baseline.** 404.6 / 407.2 tx/s at 10 stations, 420.3 / 431.9 at 100. Throughput
barely moves between 10 and 100 stations, which is the signature of a shared
ceiling rather than a lack of load. CPU sits around 505 % of the 800 % available,
so **the machine is not CPU-saturated** — the limit is waiting, not computing.

**Phase 0.5 — race fix.** `scan()` now takes the same transaction row lock that
`complete()` takes. Before the fix, `scripts/test-scan-complete-race.py` failed
hard: **199 of 200 paid baskets reverted to `OPEN`**, and stock removed (404)
did not match units applied (4), because those baskets no longer counted as
COMPLETED. Hibernate writes every column on an update, so a scan that lost the
race wrote the stale `OPEN` status back over a basket that had already been paid
for and had its stock taken — and it could then be paid for a second time.

After the fix all four assertions pass. The cost is one extra uncontended row
lock per scan: the load client drives each basket from a single station
sequentially, so two requests never contend for the same transaction row.
Measured effect is **within noise** (422.4 / 413.3 against a 404.6 / 407.2
baseline — if anything slightly faster, which is just run-to-run variation).
This is a correctness fix that happens to be free.

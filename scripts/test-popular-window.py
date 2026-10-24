#!/usr/bin/env python3
"""
PROVES the popular-items hopping window: cadence, contents, and that it slides.

The window covers the most recent popular-window-size scans and is recomputed
every popular-slide-interval scans, with each result written to
popular_snapshots (the assignment requires the top 10 to reach the database).

To show the window genuinely moves rather than accumulating forever, the scan
mix CHANGES halfway through the run: the first half is dominated by one SKU and
the second half by another. A window that really slides must report the second
SKU as rank 1 at the end, with the first SKU's count reduced to only the part
that still falls inside the window.

Since Week 3 the window is computed by a pipeline of filters on background
threads, and scan_log is written in batches rather than one row per scan. The
window counts therefore reach memory before the matching rows reach the
database, so this script WAITS for the writer to catch up before comparing a
stored snapshot against a raw SQL aggregate. Without that wait the comparison
would be racy and fail for the wrong reason.

Usage:  python3 scripts/test-popular-window.py
Requires a freshly restarted server (empty scan_log).
"""
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from _client import call, check, new_conn, req, sql, INVENTORY_DB, CHECKOUT_DB, ANALYTICS_DB  # noqa: E402

TOTAL = 1500
FIRST_HALF_SKU = "SKU-000001"
SECOND_HALF_SKU = "SKU-000002"


def sku_for(n):
    # First half of the run is dominated by one SKU, second half by another.
    # A window that genuinely slides must change its winner; a window that
    # just accumulates everything from the start would not.
    if n < TOTAL // 2:
        return FIRST_HALF_SKU if n % 2 == 0 else f"SKU-{(n % 97) + 100:06d}"
    return SECOND_HALF_SKU if n % 2 == 0 else f"SKU-{(n % 89) + 300:06d}"


def main():
    cfg = req("GET", "/analytics/popular-items")[1]
    window_size, slide = cfg["windowSize"], cfg["slideInterval"]
    print(f"windowSize={window_size} slideInterval={slide}")
    ok = check("empty but well-formed before any scan", cfg["items"], [])

    conn = new_conn()
    tid = None
    started = time.time()
    for n in range(TOTAL):
        if n % 20 == 0:
            if tid:
                call(conn, "POST", f"/transactions/{tid}/complete")
            tid = call(conn, "POST", "/transactions", {"stationId": "popular"})[1]["transactionId"]
        call(conn, "POST", f"/transactions/{tid}/items", {"sku": sku_for(n)})
    call(conn, "POST", f"/transactions/{tid}/complete")
    conn.close()
    print(f"{TOTAL} scans in {time.time() - started:.1f}s")

    # Everything downstream of the scan is asynchronous now, so rather than
    # sleeping a fixed amount we wait for two concrete conditions:
    #   1. the scan-log writer has flushed every event we sent
    #   2. the expected number of snapshots has been written
    # Both are bounded so a genuine failure still fails rather than hanging.
    expected_windows = TOTAL // slide
    deadline = time.time() + 30
    while time.time() < deadline:
        flushed = int(sql("SELECT count(*) FROM scan_log", db=ANALYTICS_DB) or 0)
        windows = int(sql("SELECT count(*) FROM popular_snapshots", db=ANALYTICS_DB) or 0)
        if flushed >= TOTAL and windows >= expected_windows:
            break
        time.sleep(0.2)
    print(f"pipeline drained: scan_log={flushed} snapshots={windows}")

    rows = sql("SELECT window_start || ',' || window_end FROM popular_snapshots ORDER BY id;", db=ANALYTICS_DB).splitlines()
    print(f"snapshots written: {rows}")
    ok &= check("one snapshot per slide interval", len(rows), expected_windows)
    ok &= check("scan_log received every scan exactly once", 
                sql("SELECT count(*) FROM scan_log", db=ANALYTICS_DB), str(TOTAL))
    ok &= check("the scan sequence has no gaps", 
                sql("SELECT max(seq) FROM scan_log", db=ANALYTICS_DB), str(TOTAL))

    body = req("GET", "/analytics/popular-items")[1]
    ws, we = body["windowStart"], body["windowEnd"]
    print(f"latest window = ({ws},{we}]")
    ok &= check("latest window ends at the last boundary", we, TOTAL)
    ok &= check("window spans exactly windowSize", we - ws, window_size)

    ranks = [i["rank"] for i in body["items"]]
    ok &= check("ranks are 1-based and dense", ranks, list(range(1, len(ranks) + 1)))
    ok &= check("the window slid: the second-half SKU now leads",
                body["items"][0]["sku"], SECOND_HALF_SKU)

    # The stored counts must match a plain SQL aggregate over the same window.
    expected = sql(f"SELECT sku || '=' || count(*) FROM scan_log "
                   f"WHERE seq > {ws} AND seq <= {we} "
                   f"GROUP BY sku ORDER BY count(*) DESC, sku ASC LIMIT 3;", db=ANALYTICS_DB).splitlines()
    actual = [f"{i['sku']}={i['scanCount']}" for i in body["items"][:3]]
    ok &= check("stored counts match a raw SQL aggregate over the same window", actual, expected)

    for limit in (3, 1):
        got = req("GET", f"/analytics/popular-items?limit={limit}")[1]["items"]
        ok &= check(f"limit={limit} returns {limit} items", len(got), limit)

    print("\nRESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

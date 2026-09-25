#!/usr/bin/env python3
"""
PROVES: concurrent completions never lose a stock decrement, and never deadlock.

This is the correctness property the OpenAPI spec calls out as the one naive
implementations break. Many baskets that all contain the same hot SKUs are
completed at the same instant (released together by a barrier, so their
read-check-write windows genuinely overlap). If the server read stock, decided
in Java, and wrote back an absolute value without a row lock, decrements would
be silently lost and the final stock would be too high.

Half the baskets are built in reverse SKU order. That is the deadlock bait: two
transactions holding locks in opposite orders would wait on each other forever.
The server sorts each basket by SKU before locking, so the cycle cannot form.

Usage:  python3 scripts/test-concurrent-completions.py [N]
Requires a freshly restarted server (stock back at 10000).
"""
import sys
import threading
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from _client import call, check, new_conn, req, sql  # noqa: E402

BASKET = {"SKU-000001": 3, "SKU-000002": 2, "SKU-000003": 1}


def build(i):
    conn = new_conn()
    try:
        tid = call(conn, "POST", "/transactions", {"stationId": f"station-{i}"})[1]["transactionId"]
        skus = list(BASKET)
        if i % 2:
            # Half the baskets are built in the opposite order. This is the
            # deadlock bait: if the server locked rows in the order it received
            # them, these would deadlock against the other half. It sorts, so
            # they can't.
            skus = list(reversed(skus))          # opposite lock order
        for sku in skus:
            for _ in range(BASKET[sku]):
                call(conn, "POST", f"/transactions/{tid}/items", {"sku": sku})
        return tid
    finally:
        conn.close()


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    before = {sku: int(sql(f"SELECT stock FROM items WHERE sku='{sku}'")) for sku in BASKET}
    print(f"stock before: {before}")

    with ThreadPoolExecutor(max_workers=20) as pool:
        ids = list(pool.map(build, range(n)))
    print(f"built {len(ids)} transactions of {sum(BASKET.values())} units each")

    # A Barrier makes every thread wait until all n have arrived, then lets
    # them all go at once. Without it the threads would trickle in and their
    # read-check-write windows might never actually overlap -- the test would
    # pass even against a broken server.
    barrier = threading.Barrier(n)

    def complete(tid):
        barrier.wait()                            # all released together
        return req("POST", f"/transactions/{tid}/complete")[0]

    with ThreadPoolExecutor(max_workers=n) as pool:
        codes = list(pool.map(complete, ids))

    counts = {}
    for c in codes:
        counts[c] = counts.get(c, 0) + 1
    print(f"completion statuses: {counts}")

    ok = check("all completions returned 200", counts, {200: n})
    after = {sku: int(sql(f"SELECT stock FROM items WHERE sku='{sku}'")) for sku in BASKET}
    print(f"stock after: {after}")
    for sku, per_basket in BASKET.items():
        ok &= check(f"{sku} decremented by exactly {per_basket * n}",
                    before[sku] - after[sku], per_basket * n)

    print("\nRESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

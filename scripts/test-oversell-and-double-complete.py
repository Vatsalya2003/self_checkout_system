#!/usr/bin/env python3
"""
PROVES two things the stock invariant depends on.

1. SOLD-OUT CLAMP. Scans are deliberately never gated by stock -- a customer
   already has the item in hand -- so a hot SKU can be scanned more times than
   it has units. The completion clamps the decrement so stock stops at zero and
   never goes negative, and records the units actually taken in
   transaction_items.applied_quantity. The receipt still shows the SCANNED
   quantity, because that is what the customer is holding. Without the separate
   applied_quantity column the invariant check could not tell an honest sell-out
   apart from a lost update.

2. DOUBLE COMPLETE. Many threads complete the SAME transaction at once. The
   transaction row is locked first, so exactly one wins with 200 and the rest
   get 409 -- and stock is decremented once, not once per caller.

Usage:  python3 scripts/test-oversell-and-double-complete.py
Requires a freshly restarted server.
"""
import sys
import threading
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from _client import check, req, sql  # noqa: E402

SOLD_OUT_SKU = "SKU-001500"
DOUBLE_SKU = "SKU-000500"


def sold_out_clamp():
    print("=== 1. sold-out clamp ===")
    # Force the shelf almost empty so we can reach the sold-out path in a
    # second, instead of scanning 10,000 units to get there naturally.
    sql(f"UPDATE items SET stock = 5 WHERE sku = '{SOLD_OUT_SKU}'")
    print(f"  forced {SOLD_OUT_SKU} stock to 5; 4 baskets will scan 3 units each (12 total)")

    ids = []
    for i in range(4):
        tid = req("POST", "/transactions", {"stationId": f"soldout-{i}"})[1]["transactionId"]
        for _ in range(3):
            req("POST", f"/transactions/{tid}/items", {"sku": SOLD_OUT_SKU})
        ids.append(tid)

    barrier = threading.Barrier(len(ids))

    def go(tid):
        barrier.wait()
        return req("POST", f"/transactions/{tid}/complete")

    with ThreadPoolExecutor(max_workers=len(ids)) as pool:
        results = list(pool.map(go, ids))

    ok = check("all four completed with 200", [c for c, _ in results], [200] * 4)
    ok &= check("receipt still reports the scanned quantity",
                [r["lines"][0]["quantity"] for _, r in results], [3] * 4)
    ok &= check("stock clamped at zero, never negative",
                sql(f"SELECT stock FROM items WHERE sku='{SOLD_OUT_SKU}'"), "0")
    ok &= check("SUM(applied_quantity) equals what was actually on the shelf",
                sql(f"SELECT SUM(applied_quantity) FROM transaction_items WHERE sku='{SOLD_OUT_SKU}'"), "5")
    ok &= check("SUM(quantity) still records everything scanned",
                sql(f"SELECT SUM(quantity) FROM transaction_items WHERE sku='{SOLD_OUT_SKU}'"), "12")
    return ok


def double_complete(threads=20):
    print("\n=== 2. concurrent double-complete of one transaction ===")
    tid = req("POST", "/transactions", {"stationId": "double"})[1]["transactionId"]
    for _ in range(4):
        req("POST", f"/transactions/{tid}/items", {"sku": DOUBLE_SKU})
    # Record stock first: the real assertion is that it drops by 4 (one
    # basket) and not by 80 (once per calling thread).
    before = int(sql(f"SELECT stock FROM items WHERE sku='{DOUBLE_SKU}'"))

    barrier = threading.Barrier(threads)

    def go(_):
        barrier.wait()
        return req("POST", f"/transactions/{tid}/complete")[0]

    with ThreadPoolExecutor(max_workers=threads) as pool:
        codes = list(pool.map(go, range(threads)))

    counts = {}
    for c in codes:
        counts[c] = counts.get(c, 0) + 1
    print(f"  statuses: {counts}")
    after = int(sql(f"SELECT stock FROM items WHERE sku='{DOUBLE_SKU}'"))

    ok = check("exactly one winner", counts.get(200, 0), 1)
    ok &= check("everyone else rejected with 409", counts.get(409, 0), threads - 1)
    ok &= check("stock decremented once, not once per caller", before - after, 4)
    return ok


if __name__ == "__main__":
    passed = sold_out_clamp() & double_complete()
    print("\nRESULT:", "PASS" if passed else "FAIL")
    sys.exit(0 if passed else 1)

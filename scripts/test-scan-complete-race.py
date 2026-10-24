#!/usr/bin/env python3
"""
PROVES: a scan arriving at the same instant as /complete can never corrupt a
paid transaction.

Why this case exists. The load client never produces it -- each station drives
one basket strictly sequentially -- so none of the other scripts can catch it.
But a real till can: a double-tapped scan button, a retried request after a
timeout, or a barcode gun firing twice all put a scan and a complete in flight
together for the same basket.

What a correct server must guarantee for every such basket:
  1. it ends COMPLETED -- a scan that lost the race must not resurrect it,
  2. a scan that returned 200 happened BEFORE the completion, so its unit is
     on the receipt and was taken off the shelf,
  3. a scan that returned 409 changed nothing at all,
  4. the stored header item_count equals the sum of the stored line
     quantities, and
  5. stock removed equals units applied, for every completed line.

Usage:  python3 scripts/test-scan-complete-race.py [rounds] [scans_per_round]
Requires a freshly restarted server. Uses a SKU nothing else touches.
"""
import sys
import threading
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from _client import req, sql, INVENTORY_DB, CHECKOUT_DB, ANALYTICS_DB  # noqa: E402

SKU = "SKU-001900"
PRESCAN = 2          # units scanned normally before the race starts


def one_round(index, racing_scans):
    """Build a basket, then fire complete + N scans at the same instant."""
    tid = req("POST", "/transactions", {"stationId": f"race-{index}"})[1]["transactionId"]
    for _ in range(PRESCAN):
        req("POST", f"/transactions/{tid}/items", {"sku": SKU})

    # Everyone waits here, then goes at once, so the scans really do overlap
    # the complete rather than trickling in after it.
    barrier = threading.Barrier(racing_scans + 1)

    def do_complete():
        barrier.wait()
        return ("complete",) + req("POST", f"/transactions/{tid}/complete")

    def do_scan(_):
        barrier.wait()
        return ("scan",) + req("POST", f"/transactions/{tid}/items", {"sku": SKU})

    with ThreadPoolExecutor(max_workers=racing_scans + 1) as pool:
        futures = [pool.submit(do_complete)] + [pool.submit(do_scan, i) for i in range(racing_scans)]
        results = [f.result() for f in futures]

    completed_ok = any(k == "complete" and s == 200 for k, s, _ in results)
    scans_won = sum(1 for k, s, _ in results if k == "scan" and s == 200)
    receipt = next((b for k, s, b in results if k == "complete" and s == 200), None)
    return tid, completed_ok, scans_won, receipt


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    racing_scans = int(sys.argv[2]) if len(sys.argv) > 2 else 3

    start_stock = int(sql(f"SELECT qty FROM stock WHERE sku='{SKU}'", db=INVENTORY_DB))
    print(f"racing {rounds} baskets, {racing_scans} scans against each complete")
    print(f"  {SKU} starting stock: {start_stock}")

    bad_status, bad_header, bad_receipt = [], [], []
    expected_units = 0

    for i in range(rounds):
        tid, completed_ok, scans_won, receipt = one_round(i, racing_scans)

        # 1. the basket must end COMPLETED
        status = sql(f"SELECT status FROM transactions WHERE id='{tid}'", db=CHECKOUT_DB)
        if status != "COMPLETED":
            bad_status.append((tid, status, scans_won))

        # 2 + 3. a scan that returned 200 must be on the receipt
        stored_qty = int(sql(f"SELECT COALESCE(SUM(quantity),0) FROM transaction_items "
                             f"WHERE transaction_id='{tid}'", db=CHECKOUT_DB) or 0)
        if completed_ok and receipt is not None:
            receipt_qty = sum(l["quantity"] for l in receipt.get("lines", []))
            if receipt_qty != stored_qty:
                bad_receipt.append((tid, receipt_qty, stored_qty))

        # 4. header counter must agree with the stored lines
        header = int(sql(f"SELECT item_count FROM transactions WHERE id='{tid}'", db=CHECKOUT_DB) or -1)
        if header != stored_qty:
            bad_header.append((tid, header, stored_qty))

        expected_units += stored_qty

    end_stock = int(sql(f"SELECT qty FROM stock WHERE sku='{SKU}'", db=INVENTORY_DB))
    applied = int(sql(f"SELECT COALESCE(SUM(ti.applied_quantity),0) FROM transaction_items ti "
                      f"JOIN transactions t ON t.id=ti.transaction_id "
                      f"WHERE t.status='COMPLETED' AND ti.sku='{SKU}'", db=CHECKOUT_DB) or 0)
    removed = start_stock - end_stock

    print()
    ok = True
    def check(label, actual, expected):
        nonlocal ok
        good = actual == expected
        ok = ok and good
        print(f"  [{'PASS' if good else 'FAIL'}] {label}: got {actual!r}, expected {expected!r}")

    check("every raced basket ends COMPLETED", len(bad_status), 0)
    check("header item_count matches the stored lines", len(bad_header), 0)
    check("the receipt matches what was stored", len(bad_receipt), 0)
    # 5. the global stock invariant for this SKU
    check("stock removed equals units applied", removed, applied)

    if bad_status:
        print(f"\n  {len(bad_status)} of {rounds} baskets ended in the wrong state. First few:")
        for tid, st, won in bad_status[:5]:
            print(f"    {tid[:8]}... status={st} (a losing scan wrote the basket back to {st})")
    if bad_header:
        print(f"\n  {len(bad_header)} baskets have a header that disagrees with their lines. First few:")
        for tid, h, s in bad_header[:5]:
            print(f"    {tid[:8]}... item_count={h} but lines sum to {s}")

    print("\nRESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

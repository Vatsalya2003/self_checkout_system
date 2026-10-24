#!/usr/bin/env python3
"""
PROVES the low-stock alert semantics.

The spec says alerts fire when stock "drops below" a threshold, so the
comparison is strict "<" -- landing exactly ON the threshold is not low. The
reference mock server uses "<" too.

Unlike the mock server, alerts here are recorded in a table at the moment of
the crossing, from inside the completing transaction while that item's row lock
is held. That is what makes them "queryable after the fact" with a real
triggeredAt rather than the time somebody happened to ask.

An ad-hoc ?threshold applies to that query only and is never persisted. A SKU
that qualifies under an ad-hoc threshold but never recorded a crossing has no
real trigger time, so it reports generatedAt.

Usage:  python3 scripts/test-low-stock.py
Requires a freshly restarted server.
"""
import sys

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from _client import check, req, sql, INVENTORY_DB, CHECKOUT_DB, ANALYTICS_DB  # noqa: E402

SKU = "SKU-000900"


def buy(n):
    # Helper: one whole transaction that scans n units of SKU and pays.
    # Stock only moves at completion, so the complete() call is what matters.
    tid = req("POST", "/transactions", {"stationId": "lowstock"})[1]["transactionId"]
    for _ in range(n):
        req("POST", f"/transactions/{tid}/items", {"sku": SKU})
    req("POST", f"/transactions/{tid}/complete")


def main():
    threshold = req("GET", "/inventory/low-stock")[1]["threshold"]
    print(f"configured threshold = {threshold}")
    ok = check("no alerts before anything sells", len(req("GET", "/inventory/low-stock")[1]["alerts"]), 0)

    print(f"\n=== land exactly ON the threshold ({threshold}) -- must NOT alert ===")
    sql(f"UPDATE stock SET qty = {threshold + 2} WHERE sku = '{SKU}'", db=INVENTORY_DB)
    buy(2)
    ok &= check(f"stock is exactly {threshold}", sql(f"SELECT qty FROM stock WHERE sku='{SKU}'", db=INVENTORY_DB), str(threshold))
    ok &= check("no alert recorded (strict '<')", sql(f"SELECT count(*) FROM low_stock_alerts WHERE sku='{SKU}'", db=INVENTORY_DB), "0")

    print("\n=== one more unit -- now strictly below ===")
    buy(1)
    ok &= check(f"stock is {threshold - 1}", sql(f"SELECT qty FROM stock WHERE sku='{SKU}'", db=INVENTORY_DB), str(threshold - 1))
    ok &= check("alert recorded", sql(f"SELECT count(*) FROM low_stock_alerts WHERE sku='{SKU}'", db=INVENTORY_DB), "1")
    first_trigger = sql(f"SELECT triggered_at FROM low_stock_alerts WHERE sku='{SKU}'", db=INVENTORY_DB)

    alerts = req("GET", "/inventory/low-stock")[1]["alerts"]
    ok &= check("endpoint reports exactly one alert", len(alerts), 1)
    ok &= check("alert carries the effective threshold", alerts[0]["threshold"], threshold)
    ok &= check("alert carries current stock", alerts[0]["currentStock"], threshold - 1)

    print("\n=== stock keeps falling -- alert must not duplicate or move ===")
    buy(5)
    ok &= check("still exactly one alert row", sql(f"SELECT count(*) FROM low_stock_alerts WHERE sku='{SKU}'", db=INVENTORY_DB), "1")
    ok &= check("triggeredAt unchanged", sql(f"SELECT triggered_at FROM low_stock_alerts WHERE sku='{SKU}'", db=INVENTORY_DB), first_trigger)

    print("\n=== ad-hoc ?threshold applies to the query only ===")
    body = req("GET", "/inventory/low-stock?threshold=10001")[1]
    ok &= check("every catalog SKU qualifies at 10001", len(body["alerts"]), 2000)
    ok &= check("echoed threshold is the ad-hoc one", body["threshold"], 10001)
    ok &= check("all rows stamped with the ad-hoc threshold",
                all(a["threshold"] == 10001 for a in body["alerts"]), True)

    crossed = next(a for a in body["alerts"] if a["sku"] == SKU)
    untouched = next(a for a in body["alerts"] if a["currentStock"] == 10000)
    ok &= check("a real crossing keeps its recorded trigger time",
                crossed["triggeredAt"] != body["generatedAt"], True)
    ok &= check("a SKU with no recorded crossing falls back to generatedAt",
                untouched["triggeredAt"], body["generatedAt"])
    ok &= check("ad-hoc query persisted nothing", sql("SELECT count(*) FROM low_stock_alerts", db=INVENTORY_DB), "1")

    print("\nRESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

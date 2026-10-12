#!/usr/bin/env python3
"""
Golden-response harness: proves a refactor did not change observable behaviour.

Sends one fixed sequence of requests covering every endpoint in the spec and every
400 / 404 / 409 case, then writes each response (status line + body) to a file.
Transaction ids and timestamps change on every run, so they are replaced with
stable placeholders (<TX1>, <TX2>, ... in order of first appearance, and <TS>).
Everything else -- status codes, field names, field order, error codes, numbers,
receipt line order -- is kept exactly as the server sent it.

Usage:
    python3 scripts/golden.py capture reports/golden/single-app.txt [base_url]
    python3 scripts/golden.py diff    reports/golden/single-app.txt [base_url]

Needs a freshly reset database (stock at 10,000, no transactions), because the
low-stock and popular-items answers depend on what has been sold and scanned.
Standard library only, like the other scripts.
"""
import http.client
import json
import re
import sys
import urllib.parse
import difflib

UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
# ISO-8601 instants as Jackson writes them, e.g. 2026-10-12T21:35:50.123456Z
TIMESTAMP = re.compile(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z")


class Golden:
    def __init__(self, base):
        u = urllib.parse.urlparse(base)
        self.conn = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=30)
        self.ids = {}
        self.out = []

    def norm(self, text):
        def tx(m):
            return self.ids.setdefault(m.group(0), f"<TX{len(self.ids) + 1}>")
        return TIMESTAMP.sub("<TS>", UUID.sub(tx, text))

    def call(self, label, method, path, body=None, raw=None):
        """raw: send these exact bytes as the body (for malformed-JSON cases)."""
        payload = raw if raw is not None else (json.dumps(body) if body is not None else None)
        headers = {"Content-Type": "application/json"} if payload is not None else {}
        self.conn.request(method, path, payload, headers)
        r = self.conn.getresponse()
        text = r.read().decode()
        self.out.append(f"### {label}\n{method} {self.norm(path)}\n{r.status}\n{self.norm(text)}\n")
        return r.status, (json.loads(text) if text.startswith(("{", "[")) else None)


def scenario(g):
    # catalog
    g.call("catalog", "GET", "/items")
    # start: success and the three ways to get a 400
    _, t1 = g.call("start ok", "POST", "/transactions", {"stationId": "golden-1"})
    a = t1["transactionId"]
    g.call("start missing stationId", "POST", "/transactions", {})
    g.call("start blank stationId", "POST", "/transactions", {"stationId": ""})
    g.call("start malformed json", "POST", "/transactions", raw="{not json")
    # get an open transaction, and a missing one
    g.call("get open", "GET", f"/transactions/{a}")
    g.call("get missing", "GET", "/transactions/does-not-exist")
    # scans, in a deliberately non-sorted order so receipt line order is checked
    for sku in ("SKU-000009", "SKU-000004", "SKU-000007", "SKU-000001", "SKU-000002", "SKU-000009"):
        g.call(f"scan {sku}", "POST", f"/transactions/{a}/items", {"sku": sku})
    # scan errors
    g.call("scan unknown sku", "POST", f"/transactions/{a}/items", {"sku": "SKU-999999"})
    g.call("scan blank sku", "POST", f"/transactions/{a}/items", {"sku": ""})
    g.call("scan malformed json", "POST", f"/transactions/{a}/items", raw="{not json")
    g.call("scan missing transaction", "POST", "/transactions/does-not-exist/items", {"sku": "SKU-000001"})
    # complete: empty basket (409), success, twice (409), missing (404)
    _, t2 = g.call("start second", "POST", "/transactions", {"stationId": "golden-2"})
    b = t2["transactionId"]
    g.call("complete empty basket", "POST", f"/transactions/{b}/complete", raw="{}")
    g.call("complete ok", "POST", f"/transactions/{a}/complete", raw="{}")
    g.call("complete again", "POST", f"/transactions/{a}/complete", raw="{}")
    g.call("scan completed transaction", "POST", f"/transactions/{a}/items", {"sku": "SKU-000001"})
    g.call("get completed", "GET", f"/transactions/{a}")
    g.call("complete missing transaction", "POST", "/transactions/does-not-exist/complete", raw="{}")
    # inventory and analytics
    g.call("low-stock default", "GET", "/inventory/low-stock")
    g.call("low-stock threshold 9999", "GET", "/inventory/low-stock?threshold=9999")
    g.call("popular default", "GET", "/analytics/popular-items")
    g.call("popular limit 3", "GET", "/analytics/popular-items?limit=3")
    # unknown route
    g.call("unknown route", "GET", "/no-such-route")


def main():
    mode, path = sys.argv[1], sys.argv[2]
    base = sys.argv[3] if len(sys.argv) > 3 else "http://localhost:8080"
    g = Golden(base)
    scenario(g)
    text = "\n".join(g.out)
    if mode == "capture":
        open(path, "w").write(text)
        print(f"captured {len(g.out)} responses to {path}")
        return 0
    expected = open(path).read()
    if text == expected:
        print(f"IDENTICAL: all {len(g.out)} responses match {path}")
        return 0
    sys.stdout.writelines(difflib.unified_diff(expected.splitlines(True), text.splitlines(True),
                                               "golden", "now"))
    print(f"\nDIFFERENT from {path}")
    return 1


if __name__ == "__main__":
    sys.exit(main())

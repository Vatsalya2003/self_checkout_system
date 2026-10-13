#!/usr/bin/env python3
"""How much latency does the nginx hop add?

Sends the same small request (POST /transactions, then POST .../items with one
scan) straight to checkout-service on :8082 and through the gateway on :8080,
alternating in blocks, each over one keep-alive connection, and prints the
median and p99 of each. Standard library only. Run against a started system:

    scripts/start-all.sh && python3 scripts/measure-gateway-hop.py [n]
"""
import http.client, json, statistics, sys, time

N = int(sys.argv[1]) if len(sys.argv) > 1 else 5000
BLOCK = 500
HDR = {"Content-Type": "application/json"}


def run(port, n, out):
    c = http.client.HTTPConnection("localhost", port)
    c.request("POST", "/transactions", json.dumps({"stationId": "hop-probe"}), HDR)
    tx = json.loads(c.getresponse().read())["transactionId"]
    body = json.dumps({"sku": "SKU-000001"})
    for _ in range(n):
        t = time.perf_counter_ns()
        c.request("POST", f"/transactions/{tx}/items", body, HDR)
        r = c.getresponse(); r.read()
        out.append((time.perf_counter_ns() - t) / 1e6)
        assert r.status == 200, r.status
    c.close()


def pct(xs, p):
    xs = sorted(xs); return xs[min(len(xs) - 1, int(len(xs) * p))]


direct, gateway = [], []
run(8082, 200, []); run(8080, 200, [])          # warm both paths
for _ in range(N // BLOCK):
    run(8082, BLOCK, direct)
    run(8080, BLOCK, gateway)
for name, xs in (("direct :8082", direct), ("gateway :8080", gateway)):
    print(f"{name:14} n={len(xs)}  median={statistics.median(xs):.3f} ms  p99={pct(xs, .99):.3f} ms")
print(f"added by the hop: median {statistics.median(gateway) - statistics.median(direct):+.3f} ms, "
      f"p99 {pct(gateway, .99) - pct(direct, .99):+.3f} ms")

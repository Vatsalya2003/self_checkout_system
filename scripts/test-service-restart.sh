#!/usr/bin/env bash
# Stop each service once; the others keep serving; the stopped one recovers.
#
#   scripts/test-service-restart.sh [outdir]     (resets the database first)
#
# Checks, in order:
#   catalog / inventory   stopped: only their own route returns 502; restarted: back to 200
#   analytics             stopped while 10 s of load runs: checkout is unaffected, scans pile
#                         up in scan_log; restarted: it restores its window and catches up
#   checkout              restarted with an open basket: the basket is gone (404) because
#                         unpaid baskets live in memory; scan_log numbering carries on
#                         from MAX(seq) with no gap
# Ends with check-windows.sql (gapless, every window matches) and the stock invariant.
set -u
R=$HOME/Desktop/ADV_SOFT_DEV/self-checkout; cd "$R"
CLIENT=$HOME/Desktop/ADV_SOFT_DEV/CS6510-2026/load-client
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=${1:-reports/service-based/restart-test}; mkdir -p "$OUT"
port() { case $1 in catalog-service) echo 8081;; checkout-service) echo 8082;; inventory-service) echo 8083;; analytics-service) echo 8084;; esac; }
heap() { [ "$1" = checkout-service ] && echo 1g || echo 512m; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
probe() {   # status of each service's endpoint through the gateway
  printf "  via :8080  items=%s  start-tx=%s  low-stock=%s  popular=%s\n" \
    "$(code localhost:8080/items)" \
    "$(code -X POST -H 'Content-Type: application/json' -d '{"stationId":"probe"}' localhost:8080/transactions)" \
    "$(code localhost:8080/inventory/low-stock)" "$(code localhost:8080/analytics/popular-items)"
}
stop1()  { local p; p=$(cat logs/$1.pid); kill "$p"; while kill -0 "$p" 2>/dev/null; do sleep 0.2; done; }
start1() { nohup "$JAVA" -Xmx$(heap $1) -jar "$1/target/$1-0.0.1-SNAPSHOT.jar" >> "logs/$1.log" 2>&1 & echo $! > logs/$1.pid
           local t0=$(python3 -c 'import time;print(time.time())')
           until [ "$(code localhost:$(port $1)/items localhost:$(port $1)/inventory/low-stock localhost:$(port $1)/analytics/popular-items localhost:$(port $1)/transactions/x | grep -c -E '200|404')" -gt 0 ]; do sleep 0.2; done
           echo "  $1 back up after $(python3 -c "import time;print(round(time.time()-$t0,1))") s"; }
load()   { (cd "$CLIENT" && "$JAVA" -cp out Main --stations=10 --duration=$1 --reportDir="$OUT") > "$OUT/load-$2.txt" 2>&1; grep -E "Transactions/sec|tx/s|errors" -i "$OUT/load-$2.txt" | head -3; }
scanlog(){ psql -qtA self_checkout -c "select count(*)||' rows, max seq '||coalesce(max(seq),0)||', gaps '||(coalesce(max(seq),0)-count(*)) from scan_log"; }

scripts/stop-all.sh >/dev/null; db/reset.sh >/dev/null; scripts/start-all.sh >/dev/null || exit 1
echo "== warm-up load 10 s"; load 10 warm; echo "  scan_log: $(scanlog)"; probe

for s in catalog-service inventory-service; do
  echo "== stop $s"; stop1 $s; probe; start1 $s; probe
done

echo "== stop analytics-service, keep scanning for 10 s without it"
before=$(psql -qtA self_checkout -c "select coalesce(max(window_end),0) from popular_snapshots")
stop1 analytics-service; load 10 no-analytics; probe
echo "  scan_log: $(scanlog); last stored window_end before restart: $before"
start1 analytics-service
until [ $(psql -qtA self_checkout -c "select (select coalesce(max(seq),0) from scan_log)/500*500 - (select coalesce(max(window_end),0) from popular_snapshots)") -le 0 ]; do sleep 0.5; done
echo "  caught up: last window_end $(psql -qtA self_checkout -c 'select max(window_end) from popular_snapshots')"
grep -h "restored\|resum" logs/analytics-service.log | tail -2 | cut -c1-220
probe

echo "== checkout-service restart with an open basket"
TX=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"stationId":"restart-probe"}' localhost:8080/transactions | sed 's/.*"transactionId":"\([^"]*\)".*/\1/')
curl -s -o /dev/null -X POST -H 'Content-Type: application/json' -d '{"sku":"SKU-000001"}' localhost:8080/transactions/$TX/items
echo "  open basket $TX before restart: $(curl -s localhost:8080/transactions/$TX)"
seq0=$(psql -qtA self_checkout -c "select max(seq) from scan_log")
stop1 checkout-service; probe; start1 checkout-service
echo "  same basket after restart:  $(code localhost:8080/transactions/$TX) $(curl -s localhost:8080/transactions/$TX)"
echo "  scan into it after restart: $(code -X POST -H 'Content-Type: application/json' -d '{"sku":"SKU-000001"}' localhost:8080/transactions/$TX/items)"
grep -h "publisher started" logs/checkout-service.log | tail -1 | sed 's/.*: scan-log/  scan-log/'
echo "  max seq before stop: $seq0"
load 10 after-checkout-restart; echo "  scan_log: $(scanlog)"; probe
sleep 3
psql self_checkout -f scripts/check-windows.sql > "$OUT/windows.txt" 2>&1
psql self_checkout -f scripts/check-invariant.sql > "$OUT/invariant.txt" 2>&1
grep -A3 "rows  \|windows_checked" "$OUT/windows.txt" | grep -v -- "--" ; tail -3 "$OUT/invariant.txt"

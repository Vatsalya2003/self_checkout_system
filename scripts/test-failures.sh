#!/usr/bin/env bash
# Failure tests for the microservices build (Week 5).
#
#   scripts/test-failures.sh [outdir]      (resets the databases several times)
#
#   A  inventory DOWN during a payment: clean 503, basket reopens, stock
#      untouched; the same payment succeeds once inventory is back
#   B  inventory FROZEN (no answer = the ambiguous case): 503, basket stays
#      closed, a retry gets 409; when inventory wakes up the resolver finishes
#      the sale, GET shows COMPLETED, stock was taken exactly once
#   C  checkout CRASHES between the stock commit and its own write (test hook):
#      after a normal restart, recovery makes both databases agree
#   D  analytics DOWN under load: checkout keeps serving, drops are counted,
#      analytics catches up gapless when it returns
#   E  checkout STARTS while analytics is down: it serves customers, buffers
#      scans, and syncs its seq once analytics answers
#   F  /internal/** is not reachable through the gateway
#
# Every section ends with scripts/check-invariant-micro.sh.
set -u
R="$(cd "$(dirname "$0")/.." && pwd)"; cd "$R"
OUT=${1:-reports/microservices/failure-tests}; mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
CLIENT=$HOME/Desktop/ADV_SOFT_DEV/CS6510-2026/load-client
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
GW=http://localhost:8080
fails=0

say()   { printf '%s\n' "$*"; }
ok()    { printf '  ok    %s\n' "$*"; }
bad()   { printf '  FAIL  %s\n' "$*"; fails=$((fails + 1)); }
expect(){ if [ "$2" = "$3" ]; then ok "$1: $2"; else bad "$1: got $2, expected $3"; fi; }
q()     { psql -X -tA -d "$1" -c "$2"; }
pid()   { cat "logs/$1.pid"; }
stop1() { local p; p=$(pid "$1"); kill "$p" 2>/dev/null; while kill -0 "$p" 2>/dev/null; do sleep 0.2; done; }
start1(){ # name port heap [extra args...]
  local n=$1 port=$2 heap=$3; shift 3
  nohup "$JAVA" -Xmx"$heap" -jar "$n/target/$n-0.0.1-SNAPSHOT.jar" "$@" >> "logs/$n.log" 2>&1 &
  echo $! > "logs/$n.pid"
  for i in $(seq 120); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "localhost:$port/actuator-none")" != 000 ] && return 0
    sleep 0.25
  done; return 1; }
fresh() { scripts/stop-all.sh >/dev/null 2>&1; db/reset.sh >/dev/null; scripts/start-all.sh >/dev/null || exit 1; }
basket(){ # stationId sku... -> prints transaction id
  local tx; tx=$(curl -s -X POST $GW/transactions -H 'Content-Type: application/json' -d "{\"stationId\":\"$1\"}" \
                 | python3 -c 'import json,sys;print(json.load(sys.stdin)["transactionId"])'); shift
  for s in "$@"; do curl -s -o /dev/null -X POST $GW/transactions/$tx/items -H 'Content-Type: application/json' -d "{\"sku\":\"$s\"}"; done
  echo "$tx"; }
status(){ curl -s $GW/transactions/$1 | python3 -c 'import json,sys;print(json.load(sys.stdin).get("status"))'; }
invariant(){ scripts/check-invariant-micro.sh > "$OUT/$1-invariant.txt" 2>&1; expect "cross-database invariant" "$(tail -1 "$OUT/$1-invariant.txt")" PASS; }
saga()  { grep -h "saga |" logs/checkout-service.log | tail -1 | sed 's/.*saga |/  saga |/'; }

# ---------------------------------------------------------------------------
say "== A  inventory down during a payment"
fresh
TX=$(basket fail-a SKU-000010 SKU-000010)
stop1 inventory-service
t0=$(python3 -c 'import time;print(time.time())')
code=$(curl -s -o "$OUT/A-503.json" -D "$OUT/A-503.headers" -w '%{http_code}' -X POST $GW/transactions/$TX/complete)
secs=$(python3 -c "import time;print(round(time.time()-$t0,1))")
expect "complete while inventory is down" "$code" 503
expect "error code" "$(python3 -c "import json;print(json.load(open('$OUT/A-503.json'))['error'])")" SERVICE_UNAVAILABLE
expect "Retry-After header" "$(grep -i '^retry-after' "$OUT/A-503.headers" | tr -d '\r' | awk '{print $2}')" 1
say "     (answered after ${secs}s: retried for up to 3 s first)"
expect "basket reopened (GET status)" "$(status $TX)" OPEN
expect "a further scan is accepted" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/transactions/$TX/items -H 'Content-Type: application/json' -d '{"sku":"SKU-000010"}')" 200
expect "stock untouched (inventory_db)" "$(q inventory_db "SELECT qty FROM stock WHERE sku='SKU-000010'")" 10000
expect "no sale row left behind (checkout_db)" "$(q checkout_db "SELECT count(*) FROM transactions WHERE id='$TX'")" 0
start1 inventory-service 8083 512m
code=$(curl -s -o "$OUT/A-200.json" -w '%{http_code}' -X POST $GW/transactions/$TX/complete)
expect "same payment after inventory is back" "$code" 200
expect "receipt itemCount (2 before + 1 after the outage)" "$(python3 -c "import json;print(json.load(open('$OUT/A-200.json'))['itemCount'])")" 3
expect "GET status" "$(status $TX)" COMPLETED
expect "stock taken once (inventory_db)" "$(q inventory_db "SELECT qty FROM stock WHERE sku='SKU-000010'")" 9997
saga; invariant A

# ---------------------------------------------------------------------------
say "== B  inventory frozen: no answer (the ambiguous case)"
fresh
TX=$(basket fail-b SKU-000020 SKU-000021)
kill -STOP "$(pid inventory-service)"      # connections are accepted, nothing answers
t0=$(python3 -c 'import time;print(time.time())')
code=$(curl -s -o "$OUT/B-503.json" -w '%{http_code}' -X POST $GW/transactions/$TX/complete)
secs=$(python3 -c "import time;print(round(time.time()-$t0,1))")
expect "complete while inventory does not answer" "$code" 503
say "     (answered after ${secs}s) message: $(python3 -c "import json;print(json.load(open('$OUT/B-503.json'))['message'])")"
expect "sale saved as PENDING (checkout_db)" "$(q checkout_db "SELECT status FROM transactions WHERE id='$TX'")" PENDING
code=$(curl -s -o "$OUT/B-409.json" -w '%{http_code}' -X POST $GW/transactions/$TX/complete)
expect "a retry of complete meanwhile" "$code" 409
expect "basket NOT reopened: a scan is refused" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/transactions/$TX/items -H 'Content-Type: application/json' -d '{"sku":"SKU-000020"}')" 409
kill -CONT "$(pid inventory-service)"
for i in $(seq 60); do [ "$(status $TX)" = COMPLETED ] && break; sleep 0.5; done
expect "after inventory wakes up, GET shows the real outcome" "$(status $TX)" COMPLETED
say "     (resolved about $((i / 2))s after inventory woke up)"
expect "stock commits for this transaction (inventory_db)" "$(q inventory_db "SELECT count(*) FROM stock_commits WHERE transaction_id='$TX'")" 1
expect "SKU-000020 taken exactly once" "$(q inventory_db "SELECT qty FROM stock WHERE sku='SKU-000020'")" 9999
expect "a retry now" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/transactions/$TX/complete)" 409
sleep 11; saga; invariant B

# ---------------------------------------------------------------------------
say "== C  checkout crashes after the stock commit, before its own write"
fresh
stop1 checkout-service
start1 checkout-service 8082 1g --selfcheckout.test.halt-after-stock-commit=true
grep -h "halt-after-stock-commit" logs/checkout-service.log | tail -1 | sed 's/.*TEST HOOK/  log: TEST HOOK/'
TX=$(basket fail-c SKU-000030 SKU-000030 SKU-000031)
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/transactions/$TX/complete)
sleep 1
say "     complete answered $code (the process halted mid-request; nginx reports the lost upstream)"
if kill -0 "$(pid checkout-service)" 2>/dev/null; then bad "checkout-service should have halted"; else ok "checkout-service halted"; fi
expect "inventory committed the stock" "$(q inventory_db "SELECT count(*) FROM stock_commits WHERE transaction_id='$TX'")" 1
expect "checkout_db still says PENDING" "$(q checkout_db "SELECT status FROM transactions WHERE id='$TX'")" PENDING
scripts/check-invariant-micro.sh > "$OUT/C-before-recovery.txt" 2>&1
expect "invariant BEFORE recovery (databases disagree)" "$(tail -1 "$OUT/C-before-recovery.txt")" FAIL
start1 checkout-service 8082 1g          # normal restart, hook off
for i in $(seq 40); do [ "$(q checkout_db "SELECT status FROM transactions WHERE id='$TX'")" = COMPLETED ] && break; sleep 0.5; done
expect "after restart, recovery finished the sale" "$(q checkout_db "SELECT status FROM transactions WHERE id='$TX'")" COMPLETED
expect "GET status" "$(status $TX)" COMPLETED
expect "applied quantities recorded" "$(q checkout_db "SELECT string_agg(sku||'='||applied_quantity, ',' ORDER BY sku) FROM transaction_items WHERE transaction_id='$TX'")" "SKU-000030=2,SKU-000031=1"
expect "still exactly one stock commit" "$(q inventory_db "SELECT count(*) FROM stock_commits WHERE transaction_id='$TX'")" 1
grep -h "recovery:" logs/checkout-service.log | tail -1 | sed 's/.*recovery:/  log: recovery:/'
sleep 11; saga; invariant C

# ---------------------------------------------------------------------------
say "== D  analytics down under load (10 stations, 15 s)"
fresh
stop1 analytics-service
(cd "$CLIENT" && "$JAVA" -cp out Main --stations=10 --duration=15 --reportDir="$OUT") > "$OUT/D-load.txt" 2>&1
mv "$(ls -t "$OUT"/report-*.json | head -1)" "$OUT/D-report.json"
python3 - "$OUT/D-report.json" <<'PY'
import json,sys; r=json.load(open(sys.argv[1]))
e=sum(o['errorCount'] for o in r['operations'])
print(f"  load without analytics: {r['transactionsPerSecond']:.1f} tx/s, {e} errors on start/scan/complete")
PY
sleep 11; grep -h "scan-log publisher |" logs/checkout-service.log | tail -1 | sed 's/.*scan-log publisher |/  publisher |/'
start1 analytics-service 8084 512m
for i in $(seq 60); do
  pub=$(grep -h "scan-log publisher |" logs/checkout-service.log | tail -1)
  depth=$(echo "$pub" | sed -E 's/.*queue depth=([0-9]+).*/\1/')
  [ "$depth" = 0 ] && [ "$(q analytics_db 'SELECT count(*) FROM scan_log')" -gt 0 ] && break; sleep 1; done
sleep 12
pub=$(grep -h "scan-log publisher |" logs/checkout-service.log | tail -1); echo "$pub" | sed 's/.*scan-log publisher |/  publisher |/'
acc=$(echo "$pub" | sed -E 's/.*accepted=([0-9]+).*/\1/'); dro=$(echo "$pub" | sed -E 's/.*dropped=([0-9]+).*/\1/')
# "accepted" = taken into the bounded queue; "dropped" = refused because it
# was full (counted, never numbered). Every accepted scan must arrive.
expect "every accepted scan reached analytics (dropped=$dro counted, not numbered)" "$(q analytics_db 'SELECT count(*) FROM scan_log')" "$acc"
invariant D

# ---------------------------------------------------------------------------
say "== E  checkout starts while analytics is down"
fresh
stop1 analytics-service
stop1 checkout-service
start1 checkout-service 8082 1g
expect "checkout healthy without analytics" "$(curl -s -o /dev/null -w '%{http_code}' localhost:8082/transactions/health-probe)" 404
TX=$(basket fail-e SKU-000040 SKU-000041 SKU-000042)
expect "customer can pay" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/transactions/$TX/complete)" 200
sleep 11; grep -h "scan-log publisher |" logs/checkout-service.log | tail -1 | sed 's/.*scan-log publisher |/  publisher |/'
expect "nothing reached analytics yet" "$(q analytics_db 'SELECT count(*) FROM scan_log')" 0
start1 analytics-service 8084 512m
for i in $(seq 40); do [ "$(q analytics_db 'SELECT count(*) FROM scan_log')" = 3 ] && break; sleep 0.5; done
expect "buffered scans delivered once analytics answered" "$(q analytics_db "SELECT string_agg(seq || ':' || sku, ',' ORDER BY seq) FROM scan_log")" "1:SKU-000040,2:SKU-000041,3:SKU-000042"
grep -h "numbering continues" logs/checkout-service.log | tail -1 | sed 's/.*scan-log publisher:/  log:/'
invariant E

# ---------------------------------------------------------------------------
say "== F  internal endpoints through the gateway"
expect "POST /internal/stock/commit via :8080" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/internal/stock/commit -H 'Content-Type: application/json' -d '{}')" 404
expect "POST /internal/scans via :8080" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/internal/scans -H 'Content-Type: application/json' -d '{}')" 404
expect "GET /internal/scans/max-seq via :8080" "$(curl -s -o /dev/null -w '%{http_code}' $GW/internal/scans/max-seq)" 404
expect "...but reachable directly on analytics' own port" "$(curl -s -o /dev/null -w '%{http_code}' localhost:8084/internal/scans/max-seq)" 200

scripts/stop-all.sh >/dev/null 2>&1
say ""
if [ $fails = 0 ]; then say "RESULT: PASS"; else say "RESULT: FAIL ($fails)"; exit 1; fi

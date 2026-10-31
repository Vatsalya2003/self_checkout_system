#!/usr/bin/env bash
# Failure tests for the event-driven build (Week 6).
#
#   scripts/test-failures.sh [outdir]      (resets the databases and the broker several times)
#
#   A  the BROKER stops and restarts during load: only clean 503s while it is
#      gone, payments resume when it is back, nothing oversold, every
#      unfinished payment resolved, every accepted scan reaches analytics
#   B  inventory DOWN during a payment: the request is safely queued, so the
#      client gets 503 and the basket stays closed (a retry gets 409); when
#      inventory returns it processes the queued request and the sale
#      completes in the background, stock taken exactly once
#   C  checkout CRASHES between inventory's commit and its own write (test
#      hook): after a normal restart, recovery makes both databases agree
#   D  analytics DOWN under load: checkout keeps serving, scans wait in the
#      analytics.scans queue, analytics catches up gapless when it returns
#   E  a DUPLICATED message is harmless: a re-published StockCommitRequested
#      takes no more stock; a re-published scan batch is not stored twice
#   F  publisher confirm TIMES OUT (broker blocks publishers with a disk
#      alarm): possibly delivered, so the ambiguous path -- 503, basket stays
#      closed, the resolver finishes it once the broker accepts messages again
#   G  /internal/** is gone: 404 through the gateway AND on every service port
#   H  no service-to-service HTTP: each service's TCP connections go only to
#      its own database (5432) and the broker (5672)
#
# Every section ends with scripts/check-invariant-micro.sh and a check that no
# message was dead-lettered.
set -u
R="$(cd "$(dirname "$0")/.." && pwd)"; cd "$R"
export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:$PATH
OUT=${1:-reports/event-driven/failure-tests}; mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
CLIENT=$HOME/Desktop/ADV_SOFT_DEV/CS6510-2026/load-client
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
RMQ="${RABBITMQ_SBIN:-/opt/homebrew/sbin}/rabbitmqctl"
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
fresh() { scripts/stop-all.sh >/dev/null 2>&1; db/reset.sh >/dev/null; scripts/start-all.sh >/dev/null || exit 1; sleep 2; }
basket(){ # stationId sku... -> prints transaction id
  local tx; tx=$(curl -s -X POST $GW/transactions -H 'Content-Type: application/json' -d "{\"stationId\":\"$1\"}" \
                 | python3 -c 'import json,sys;print(json.load(sys.stdin)["transactionId"])'); shift
  for s in "$@"; do curl -s -o /dev/null -X POST $GW/transactions/$tx/items -H 'Content-Type: application/json' -d "{\"sku\":\"$s\"}"; done
  echo "$tx"; }
status(){ curl -s $GW/transactions/$1 | python3 -c 'import json,sys;print(json.load(sys.stdin).get("status"))'; }
now()   { python3 -c 'import time;print(time.time())'; }
since() { python3 -c "import time;print(round(time.time()-$1,1))"; }
depth() { "$RMQ" -q list_queues name messages 2>/dev/null | awk -v q="$1" '$1==q{print $2}'; }
invariant(){ scripts/check-invariant-micro.sh > "$OUT/$1-invariant.txt" 2>&1; expect "cross-database invariant" "$(tail -1 "$OUT/$1-invariant.txt")" PASS
             expect "dead-lettered messages (all DLQs)" "$("$RMQ" -q list_queues name messages | awk '$1 ~ /\.dlq$/ {t+=$2} END{print t+0}')" 0; }
lastlog(){ grep -h "$2 |" "logs/$1.log" | tail -1 | sed "s/.*$2 |/  $2 |/"; }
# Wait (up to 25 s) until a service's 10-second metrics line shows field>=min.
counter(){ # service prefix field min -> prints the value seen
  local v=0
  for i in $(seq 50); do
    v=$(grep -h "$2 |" "logs/$1.log" | tail -1 | sed -E "s/.* $3=([0-9]+).*/\1/")
    [ "${v:-0}" -ge "$4" ] 2>/dev/null && break; sleep 0.5
  done; echo "${v:-0}"; }
pending0(){ for i in $(seq 120); do [ "$(q checkout_db "SELECT count(*) FROM transactions WHERE status='PENDING'")" = 0 ] && return 0; sleep 0.5; done; return 1; }
# Publish one message straight to a queue through the management API, exactly
# as a service would (JSON body + the __TypeId__ header Spring reads). Test only.
publish(){ # queue type-class json
  python3 - "$1" "$2" "$3" <<'PY'
import base64, json, sys, urllib.request
queue, cls, body = sys.argv[1:4]
req = urllib.request.Request("http://localhost:15672/api/exchanges/%2F/amq.default/publish",
    data=json.dumps({"routing_key": queue, "payload": body, "payload_encoding": "string",
        "properties": {"delivery_mode": 2, "content_type": "application/json",
                       "headers": {"__TypeId__": cls}}}).encode(),
    headers={"Content-Type": "application/json",
             "Authorization": "Basic " + base64.b64encode(b"guest:guest").decode()})
print(json.load(urllib.request.urlopen(req)).get("routed"))
PY
}
EV=com.selfcheckout.self_checkout.messaging.Events

# ---------------------------------------------------------------------------
say "== A  the broker stops and restarts during load (10 stations, 30 s)"
fresh
(cd "$CLIENT" && "$JAVA" -cp out Main --stations=10 --duration=30 --reportDir="$OUT") > "$OUT/A-load.txt" 2>&1 &
LOAD=$!
sleep 6
"$RMQ" -q stop_app; t0=$(now); say "     broker stopped at t=6 s"
sleep 6
"$RMQ" -q start_app; say "     broker started again after $(since $t0) s"
wait $LOAD
mv "$(ls -t "$OUT"/report-*.json | head -1)" "$OUT/A-report.json"
python3 - "$OUT/A-report.json" <<'PY'
import json,sys; r=json.load(open(sys.argv[1]))
ops={o['operation']:o for o in r['operations']}
print(f"  load over 30 s: {r['totalTransactions']} payments ok, errors start/scan/complete = "
      f"{ops['START_TRANSACTION']['errorCount']}/{ops['SCAN_ITEM']['errorCount']}/{ops['COMPLETE_TRANSACTION']['errorCount']}")
PY
other=$(grep "transaction failed" "$OUT/A-load.txt" | grep -v -c "Unexpected status 503")
expect "client errors that were not a clean 503" "$other" 0
expect "payments resumed after the broker came back (sales after restart > 0)" \
       "$(q checkout_db "SELECT (count(*) > 0)::int FROM transactions WHERE status='COMPLETED' AND completed_at > to_timestamp($t0 + 6)")" 1
if pending0; then ok "every unfinished payment was resolved (PENDING = 0)"; else bad "PENDING left: $(q checkout_db "SELECT count(*) FROM transactions WHERE status='PENDING'")"; fi
for i in $(seq 60); do [ "$(depth analytics.scans)" = 0 ] && break; sleep 1; done; sleep 11
pub=$(grep -h "scan-log publisher |" logs/checkout-service.log | tail -1); echo "$pub" | sed 's/.*scan-log publisher |/  publisher |/'
acc=$(echo "$pub" | sed -E 's/.*accepted=([0-9]+).*/\1/'); dro=$(echo "$pub" | sed -E 's/.*dropped=([0-9]+).*/\1/')
expect "every accepted scan reached analytics (dropped=$dro counted)" "$(q analytics_db 'SELECT count(*) FROM scan_log')" "$acc"
lastlog checkout-service "stock-commit client"; lastlog checkout-service saga
invariant A

# ---------------------------------------------------------------------------
say "== B  inventory down during a payment"
fresh
TX=$(basket fail-b SKU-000020 SKU-000021)
stop1 inventory-service
t0=$(now)
code=$(curl -s -o "$OUT/B-503.json" -w '%{http_code}' -X POST $GW/transactions/$TX/complete)
expect "complete while inventory is down" "$code" 503
say "     (answered after $(since $t0) s) message: $(python3 -c "import json;print(json.load(open('$OUT/B-503.json'))['message'])")"
expect "the request is waiting in the queue" "$( [ "$(depth inventory.stock-commit)" -ge 1 ] && echo yes || echo no)" yes
expect "sale saved as PENDING (checkout_db)" "$(q checkout_db "SELECT status FROM transactions WHERE id='$TX'")" PENDING
expect "a retry of complete meanwhile" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/transactions/$TX/complete)" 409
expect "basket NOT reopened: a scan is refused" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/transactions/$TX/items -H 'Content-Type: application/json' -d '{"sku":"SKU-000020"}')" 409
expect "stock untouched while inventory is down" "$(q inventory_db "SELECT qty FROM stock WHERE sku='SKU-000020'")" 10000
start1 inventory-service 8083 512m; t0=$(now)
for i in $(seq 80); do [ "$(status $TX)" = COMPLETED ] && break; sleep 0.5; done
expect "after inventory returns, GET shows the real outcome" "$(status $TX)" COMPLETED
say "     (completed $(since $t0) s after inventory was started)"
expect "stock commits for this transaction (inventory_db)" "$(q inventory_db "SELECT count(*) FROM stock_commits WHERE transaction_id='$TX'")" 1
expect "SKU-000020 taken exactly once" "$(q inventory_db "SELECT qty FROM stock WHERE sku='SKU-000020'")" 9999
expect "a retry now" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/transactions/$TX/complete)" 409
sleep 11; lastlog inventory-service "stock-commit listener"; lastlog checkout-service saga
invariant B

# ---------------------------------------------------------------------------
say "== C  checkout crashes after inventory's commit, before its own write"
fresh
stop1 checkout-service
start1 checkout-service 8082 1g --selfcheckout.test.halt-after-stock-commit=true
sleep 2
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
for i in $(seq 60); do [ "$(q checkout_db "SELECT status FROM transactions WHERE id='$TX'")" = COMPLETED ] && break; sleep 0.5; done
expect "after restart, recovery finished the sale" "$(q checkout_db "SELECT status FROM transactions WHERE id='$TX'")" COMPLETED
expect "applied quantities recorded" "$(q checkout_db "SELECT string_agg(sku||'='||applied_quantity, ',' ORDER BY sku) FROM transaction_items WHERE transaction_id='$TX'")" "SKU-000030=2,SKU-000031=1"
expect "still exactly one stock commit" "$(q inventory_db "SELECT count(*) FROM stock_commits WHERE transaction_id='$TX'")" 1
grep -h "recovery:" logs/checkout-service.log | tail -1 | sed 's/.*recovery:/  log: recovery:/'
sleep 11; lastlog checkout-service saga
invariant C

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
sleep 2
say "     waiting in analytics.scans: $(depth analytics.scans) batches; scan_log rows: $(q analytics_db 'SELECT count(*) FROM scan_log')"
start1 analytics-service 8084 512m
for i in $(seq 90); do [ "$(depth analytics.scans)" = 0 ] && break; sleep 1; done
sleep 11
pub=$(grep -h "scan-log publisher |" logs/checkout-service.log | tail -1); echo "$pub" | sed 's/.*scan-log publisher |/  publisher |/'
acc=$(echo "$pub" | sed -E 's/.*accepted=([0-9]+).*/\1/'); dro=$(echo "$pub" | sed -E 's/.*dropped=([0-9]+).*/\1/')
expect "every accepted scan reached analytics (dropped=$dro counted)" "$(q analytics_db 'SELECT count(*) FROM scan_log')" "$acc"
expect "scan_log gapless" "$(q analytics_db 'SELECT COALESCE(max(seq),0) - count(*) FROM scan_log')" 0
lastlog analytics-service "scan ingest"
invariant D

# ---------------------------------------------------------------------------
say "== E  a duplicated message is harmless"
fresh
TX=$(basket fail-e SKU-000060 SKU-000060)
expect "normal payment" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/transactions/$TX/complete)" 200
expect "SKU-000060 after the payment" "$(q inventory_db "SELECT qty FROM stock WHERE sku='SKU-000060'")" 9998
# JSON built in a variable first: macOS's bash 3.2 brace-expands a quoted
# "{a,b}" inside "$( ... )", which would split the message in three.
DUP="{\"transactionId\":\"$TX\",\"lines\":[{\"sku\":\"SKU-000060\",\"quantity\":2}]}"
expect "re-published StockCommitRequested routed" "$(publish inventory.stock-commit "$EV\$StockCommitRequested" "$DUP")" True
sleep 2
expect "stock unchanged by the duplicate" "$(q inventory_db "SELECT qty FROM stock WHERE sku='SKU-000060'")" 9998
expect "still one stock commit for it" "$(q inventory_db "SELECT count(*) FROM stock_commits WHERE transaction_id='$TX'")" 1
sleep 3
B=$(q analytics_db "SELECT batch_id FROM scan_batches ORDER BY first_seq LIMIT 1")
n0=$(q analytics_db 'SELECT count(*) FROM scan_log')
DUP="{\"batchId\":\"$B\",\"scans\":[{\"sku\":\"SKU-000060\",\"scannedAt\":\"2026-10-30T12:00:00Z\"}]}"
expect "re-published scan batch (same batchId) routed" "$(publish analytics.scans "$EV\$ScansRecorded" "$DUP")" True
sleep 2
expect "scan_log unchanged by the duplicate batch" "$(q analytics_db 'SELECT count(*) FROM scan_log')" "$n0"
expect "a NEW batch id is stored and numbered next" \
       "$(publish analytics.scans "$EV\$ScansRecorded" '{"batchId":"test-new-batch","scans":[{"sku":"SKU-000061","scannedAt":"2026-10-30T12:00:01Z"}]}')" True
sleep 2
expect "scan_log grew by one, still gapless" "$(q analytics_db "SELECT count(*) - $n0 || ',' || (max(seq) - count(*)) FROM scan_log")" "1,0"
expect "inventory answered the duplicate from its stored commit (replayed)" "$(counter inventory-service "stock-commit listener" replayed 1)" 1
expect "checkout dropped the extra reply (stray)" "$(counter checkout-service "stock-commit client" stray 1)" 1
expect "analytics skipped the duplicate batch (duplicates)" "$(counter analytics-service "scan ingest" duplicates 1)" 1
lastlog inventory-service "stock-commit listener"; lastlog checkout-service "stock-commit client"; lastlog analytics-service "scan ingest"
expect "nothing dead-lettered" "$("$RMQ" -q list_queues name messages | awk '$1 ~ /\.dlq$/ {t+=$2} END{print t+0}')" 0
# The extra test scan is not a sale, so the invariant's checkout-vs-inventory
# part is unaffected; the windows check still recounts every window.
invariant E

# ---------------------------------------------------------------------------
say "== F  publisher confirm times out (broker blocks publishers: disk alarm)"
fresh
restore_disk(){ "$RMQ" -q set_disk_free_limit 50MB >/dev/null 2>&1; }
trap restore_disk EXIT
TX=$(basket fail-f SKU-000070 SKU-000071)
"$RMQ" -q set_disk_free_limit 100000GB; sleep 3
say "     disk alarm raised: the broker accepts connections but stops reading published messages"
t0=$(now)
code=$(curl -s -o "$OUT/F-503.json" -w '%{http_code}' -X POST $GW/transactions/$TX/complete)
expect "complete while the broker gives no confirm" "$code" 503
say "     (answered after $(since $t0) s) message: $(python3 -c "import json;print(json.load(open('$OUT/F-503.json'))['message'])")"
expect "sale PENDING: possibly delivered, so not undone" "$(q checkout_db "SELECT status FROM transactions WHERE id='$TX'")" PENDING
expect "basket NOT reopened: a scan is refused" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/transactions/$TX/items -H 'Content-Type: application/json' -d '{"sku":"SKU-000070"}')" 409
sleep 9; c=$(grep -h "stock-commit client |" logs/checkout-service.log | tail -1); echo "$c" | sed 's/.*stock-commit client |/  client |/'
expect "attempts classified as unconfirmed (no confirm), not as not-delivered" \
       "$(echo "$c" | sed -E 's/.*notDelivered=([0-9]+) unconfirmed=([0-9]+).*/\1,\2/' | awk -F, '{print ($1==0 && $2>0) ? "yes" : "no"}')" yes
restore_disk; t0=$(now)
for i in $(seq 80); do [ "$(status $TX)" = COMPLETED ] && break; sleep 0.5; done
expect "after the alarm clears, the resolver finished the sale" "$(status $TX)" COMPLETED
say "     (completed $(since $t0) s after the alarm cleared)"
expect "stock taken exactly once" "$(q inventory_db "SELECT qty FROM stock WHERE sku='SKU-000070'")" 9999
expect "one stock commit" "$(q inventory_db "SELECT count(*) FROM stock_commits WHERE transaction_id='$TX'")" 1
sleep 11; lastlog checkout-service saga
invariant F

# ---------------------------------------------------------------------------
say "== G  internal endpoints are gone"
expect "POST /internal/stock/commit via :8080" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/internal/stock/commit -H 'Content-Type: application/json' -d '{}')" 404
expect "POST /internal/scans via :8080" "$(curl -s -o /dev/null -w '%{http_code}' -X POST $GW/internal/scans -H 'Content-Type: application/json' -d '{}')" 404
expect "...and directly on inventory's port" "$(curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8083/internal/stock/commit -H 'Content-Type: application/json' -d '{}')" 404
expect "...and directly on analytics' port" "$(curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8084/internal/scans -H 'Content-Type: application/json' -d '{}')" 404
expect "GET /internal/scans/max-seq on analytics' port" "$(curl -s -o /dev/null -w '%{http_code}' localhost:8084/internal/scans/max-seq)" 404

# ---------------------------------------------------------------------------
say "== H  no service-to-service HTTP (after 5 s of load)"
(cd "$CLIENT" && "$JAVA" -cp out Main --stations=10 --duration=5 --reportDir="$OUT") > "$OUT/H-load.txt" 2>&1
rm -f "$OUT"/report-*.json
scripts/check-connections.sh > "$OUT/H-connections.txt" 2>&1; rc=$?
sed 's/^/  /' "$OUT/H-connections.txt"
expect "check-connections.sh" "$([ $rc = 0 ] && echo PASS || echo FAIL)" PASS

scripts/stop-all.sh >/dev/null 2>&1
say ""
if [ $fails = 0 ]; then say "RESULT: PASS"; else say "RESULT: FAIL ($fails)"; exit 1; fi

#!/usr/bin/env bash
# One measured load run: wait for an idle machine, reset, start, load, record.
#   run-load.sh <outdir> <label> <stations> <duration>
# Writes <label>-{start,client,summary,checks}.txt and <label>-report.json.
set -uo pipefail
export PATH=/opt/homebrew/bin:/opt/homebrew/opt/postgresql@16/bin:$PATH
R=$HOME/Desktop/ADV_SOFT_DEV/self-checkout; cd "$R"
CLIENT=$HOME/Desktop/ADV_SOFT_DEV/CS6510-2026/load-client
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=$1 LABEL=$2 ST=$3 DUR=$4
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)

free_gb() { df -g /System/Volumes/Data | awk 'NR==2{print $4}'; }
[ "$(free_gb)" -ge 3 ] || { echo "ABORT: only $(free_gb) GB free" >&2; exit 2; }

# idle: 1-minute load below 3.0 (up to 6 min), and settle after the previous run
for i in $(seq 72); do
  l1=$(sysctl -n vm.loadavg | awk '{print $2}')
  awk "BEGIN{exit !($l1 < 3.0)}" && break; sleep 5
done

scripts/stop-all.sh >/dev/null 2>&1
db/reset.sh >/dev/null || { echo "reset failed" >&2; exit 1; }
scripts/start-all.sh > "$OUT/$LABEL-start.txt" 2>&1 || { echo "start failed" >&2; cat "$OUT/$LABEL-start.txt"; exit 1; }
sleep 3

svcs="catalog-service checkout-service inventory-service analytics-service"
secs() { awk -F: '{ if (NF==3) print $1*3600+$2*60+$3; else print $1*60+$2 }'; }
cpu_snapshot() {   # name total-cpu-seconds
  for s in $svcs; do echo "$s $(ps -o cputime= -p "$(cat logs/$s.pid)" | secs)"; done
  echo "postgres $(ps -A -o ucomm=,cputime= | awk '$1 ~ /postgres$/ {print $2}' | secs | awk '{t+=$1} END{print t}')"
  echo "nginx $(ps -A -o ucomm=,cputime= | awk '$1 ~ /nginx$/ {print $2}' | secs | awk '{t+=$1} END{print t}')"
}
before=$(cpu_snapshot); lb=$(sysctl -n vm.loadavg); t0=$(date +%s)
(cd "$CLIENT" && "$JAVA" -cp out Main --baseUrl=http://localhost:8080 --stations="$ST" --duration="$DUR" --reportDir="$OUT") > "$OUT/$LABEL-client.txt" 2>&1
t1=$(date +%s); after=$(cpu_snapshot); la=$(sysctl -n vm.loadavg)
mv "$(ls -t "$OUT"/report-*.json | head -1)" "$OUT/$LABEL-report.json"
sleep 11   # let every service print its next metrics line
{
  echo "label=$LABEL stations=$ST duration=$DUR"
  echo "load before: $lb"; echo "load after:  $la"
  join <(echo "$before" | sort) <(echo "$after" | sort) | while read n a b; do
    awk -v n="$n" -v a="$a" -v b="$b" -v w=$((t1 - t0)) 'BEGIN{printf "cpu %-20s %6.1f %%  (of one core, avg over %d s)\n", n, (b-a)*100/w, w}'
  done
  grep -h "scan-log publisher |" logs/checkout-service.log | tail -1
  grep -h "saga |" logs/checkout-service.log | tail -1
  grep -h "analytics lag |" logs/analytics-service.log | tail -1
  python3 - "$OUT/$LABEL-report.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
print(f"  tx/s={r['transactionsPerSecond']:.1f} tx={r['totalTransactions']} items={r['totalItemsScanned']} wall={r['wallClockSeconds']:.1f}s")
for o in r['operations']:
    print(f"  {o['operation']:<22} ok={o['successCount']} err={o['errorCount']} mean={o['meanMs']:.3f} "
          f"p50={o['p50Ms']:.3f} p95={o['p95Ms']:.3f} p99={o['p99Ms']:.3f} max={o['maxMs']:.1f} ms")
PY
  grep -h "stock commit timings |" logs/inventory-service.log | tail -1 | sed 's/.*stock commit timings/  stock commit timings/'
  echo "postgres: $(psql -X -tA -d postgres -c "SELECT string_agg(name || '=' || setting, ' ' ORDER BY name) FROM pg_settings WHERE name IN ('synchronous_commit','commit_delay','commit_siblings','fsync')")"
  echo "disk free after: $(free_gb) GB"
} > "$OUT/$LABEL-summary.txt"
scripts/check-invariant-micro.sh > "$OUT/$LABEL-checks.txt" 2>&1
echo "$LABEL: $(sed -n 's/^  tx\/s=\([0-9.]*\).*/\1 tx\/s/p' "$OUT/$LABEL-summary.txt"), invariant $(tail -1 "$OUT/$LABEL-checks.txt")"

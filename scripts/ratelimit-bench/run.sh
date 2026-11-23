#!/usr/bin/env bash
# One measured rate-limiter run: check the machine, reset, start, load, record.
#
#   run.sh <outdir> <label> <course|polite> <stations> <duration> <on|off> [start-all flags...]
#
#   course  the course load client (unchanged; retries a 429 immediately)
#   polite  scripts/demo/RateLimitClient.java (waits at least X-RateLimit-Retry-After-Ms, with backoff)
#   on/off  limiter on (checkout's configured rule) or started with --no-limiter
#   extra flags go to start-all.sh, e.g. --checkout-rate=100000
#
# Writes into <outdir>/<label>/: machine.txt, client output, client report
# (JSON), errors by kind, server samples (one line per second), limiter log
# lines, invariant check, nginx errors, and summary.txt.
#
# Refuses to run (exit 2/3) on battery, with < 10 GB free, or if the 1-minute
# load does not drop below 3 within 10 minutes.
set -uo pipefail
export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:/opt/homebrew/opt/postgresql@16/bin:$PATH
R=$HOME/Desktop/ADV_SOFT_DEV/self-checkout; cd "$R"
CLIENT=$HOME/Desktop/ADV_SOFT_DEV/CS6510-2026/load-client
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
OUT=$1 LABEL=$2 KIND=$3 ST=$4 DUR=$5 LIMITER=$6; shift 6
D="$OUT/$LABEL"; mkdir -p "$D"; D=$(cd "$D" && pwd)

# ---- gates: power, disk, idle ----------------------------------------------
pmset -g batt | grep -q "AC Power" || { echo "ABORT: on battery" >&2; exit 2; }
free=$(df -g /System/Volumes/Data | awk 'NR==2{print $4}')
[ "$free" -ge 10 ] || { echo "ABORT: only $free GB free" >&2; exit 2; }
scripts/stop-all.sh >/dev/null 2>&1
for i in $(seq 120); do
  l1=$(sysctl -n vm.loadavg | awk '{print $2}')
  awk "BEGIN{exit !($l1 < 3.0)}" && break
  sleep 5
done
awk "BEGIN{exit !($l1 < 3.0)}" || { echo "ABORT: machine not idle (1-min load $l1 after 10 min)" >&2; exit 3; }

# ---- reset and start ---------------------------------------------------------
db/reset.sh >/dev/null || { echo "reset failed" >&2; exit 1; }
old_keys=$(redis-cli --scan --pattern 'rl:*' 2>/dev/null)   # start every run with full buckets
[ -n "$old_keys" ] && redis-cli DEL $old_keys >/dev/null
flags=("$@"); [ "$LIMITER" = off ] && flags+=(--no-limiter)
scripts/start-all.sh ${flags[@]+"${flags[@]}"} > "$D/start.txt" 2>&1 || { echo "start failed" >&2; tail "$D/start.txt"; exit 1; }
: > gateway/run/error.log
sleep 3
{
  echo "label=$LABEL client=$KIND stations=$ST duration=${DUR}s limiter=$LIMITER start-all flags: ${flags[*]-}"
  echo "power: $(pmset -g batt | head -1 | sed "s/.*'\(.*\)'.*/\1/") | free disk: ${free} GB | 1-min load before: $l1"
  echo "date: $(date '+%Y-%m-%d %H:%M:%S')"
  grep -h 'ratelimit | rule ' logs/checkout-service.log | sed 's/.*ratelimit |/checkout ratelimit |/'
} > "$D/machine.txt"

# ---- load, with the server sampled every second -------------------------------
scripts/ratelimit-bench/sample.sh "$D/samples.csv" &
SAMPLER=$!
lb=$(sysctl -n vm.loadavg)
if [ "$KIND" = course ]; then
  (cd "$CLIENT" && "$JAVA" -cp out Main --baseUrl=http://localhost:8080 --stations="$ST" --duration="$DUR" --reportDir="$D") \
    > "$D/client.txt" 2> "$D/client-stderr.txt"
  mv "$(ls -t "$D"/report-*.json | head -1)" "$D/client-report.json" 2>/dev/null
else
  "$JAVA" scripts/demo/RateLimitClient.java --mode=polite --stations="$ST" --duration="$DUR" \
    --report="$D/client-report.json" > "$D/client.txt" 2> "$D/client-stderr.txt"
fi
la=$(sysctl -n vm.loadavg)
sleep 6   # one more limiter summary line
kill $SAMPLER 2>/dev/null; wait $SAMPLER 2>/dev/null

# ---- record -----------------------------------------------------------------
scripts/demo/count-client-errors.sh "$D/client-stderr.txt" > "$D/client-errors.txt"
bytes=$(wc -c < "$D/client-stderr.txt" | tr -d ' ')
head -3 "$D/client-stderr.txt" | cut -c1-300 > "$D/client-stderr-first-lines.txt"
gzip -f "$D/client-stderr.txt"   # can be many MB under a 429 storm: kept compressed, not committed
sed -i '' "s#$R/##" "$D/client.txt"
grep -h 'ratelimit |' logs/checkout-service.log | sed 's/^[0-9-]*T\([0-9:]*\)\.[0-9]*.*ratelimit |/\1 |/' > "$D/limiter-log.txt"
cp gateway/run/error.log "$D/nginx-error.log"
curl -s localhost:8082/internal/ratelimit/stats > "$D/limiter-stats.json"
# After a heavy overload the saga can still be catching up when the client stops
# (stock commits queued in RabbitMQ, payments still PENDING). Checking at that
# moment reports in-flight sagas as failures, so wait up to 120 s for the queues
# to empty, PENDING to reach 0 and the completed count to settle, and record how long that took.
drain_start=$(date +%s); stable=0; done_prev=
while :; do
  qd=$(rabbitmqctl -q list_queues name messages 2>/dev/null | awk '$1=="inventory.stock-commit"||$1=="checkout.stock-replies" {t+=$2} END{print t+0}')
  pend=$(psql -X -tA -d checkout_db -c "SELECT count(*) FROM transactions WHERE status = 'PENDING'")
  # also wait for the completed count to stop moving for 3 s: requests still in
  # flight inside checkout when the client exits can still complete afterwards
  done_now=$(psql -X -tA -d checkout_db -c "SELECT count(*) FROM transactions WHERE status = 'COMPLETED'")
  if [ "$qd" = 0 ] && [ "$pend" = 0 ] && [ "$done_now" = "${done_prev:-x}" ]; then
    stable=$((stable + 1)); [ "$stable" -ge 3 ] && break
  else
    stable=0
  fi
  done_prev=$done_now
  [ $(( $(date +%s) - drain_start )) -ge 120 ] && break
  sleep 1
done
echo "saga drain before the check: $(( $(date +%s) - drain_start )) s (queued $qd, pending $pend at the end)" > "$D/invariant.txt"
scripts/check-invariant-micro.sh >> "$D/invariant.txt" 2>&1
dl=$(rabbitmqctl -q list_queues name messages 2>/dev/null | awk '$1 ~ /\.dlq$/ {t+=$2} END{print t+0}')
echo "dead-lettered messages across all DLQs: $dl" >> "$D/invariant.txt"
{
  echo "load before: $lb"; echo "load after:  $la"
  echo "client stderr was $bytes bytes"
  cat "$D/client-errors.txt"
  echo "nginx errors: $(grep -c . "$D/nginx-error.log") lines$(grep -m1 -o 'worker_connections are not enough' "$D/nginx-error.log" | sed 's/^/ (/;s/$/)/')"
  echo "invariant: $(grep -E '^(PASS|FAIL)' "$D/invariant.txt" | tr '\n' ' ')dead letters $dl"
} > "$D/summary.txt"
echo "$LABEL: $(grep -m1 -E 'Transactions:|completed transactions' "$D/client.txt" | sed 's/^ *//') | $(sed -n 1p "$D/client-errors.txt") | invariant $(grep -E '^(PASS|FAIL)' "$D/invariant.txt" | tr '\n' ' ')| load before $l1"

#!/usr/bin/env bash
# One CSV line per second of what the server side is doing, until killed.
#   sample.sh <out.csv>
#
# Columns: time, 1-min load, the limiter's counters on checkout (cumulative;
# per-second rates come from the difference between lines), Redis memory and
# key count, and per process: total CPU seconds used so far, resident memory
# (MB) and thread count. Cheap on purpose (ps, curl, redis-cli only), so the
# sampler itself does not change what it measures.
set -uo pipefail
export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:$PATH
R=$HOME/Desktop/ADV_SOFT_DEV/self-checkout; cd "$R"
OUT=$1

secs() { awk -F: '{ if (NF==3) print $1*3600+$2*60+$3; else print $1*60+$2 }'; }
pidof_svc() { cat "logs/$1.pid" 2>/dev/null; }

echo "t,load1,rl_allowed,rl_rejected,rl_rejected_no_redis,rl_fallback,rl_redis,redis_used_mb,redis_keys,redis_rl_keys,checkout_cpu_s,checkout_rss_mb,checkout_threads,inventory_cpu_s,analytics_cpu_s,catalog_cpu_s,nginx_cpu_s,postgres_cpu_s,redis_cpu_s,rabbitmq_cpu_s" > "$OUT"
ck=$(pidof_svc checkout-service); inv=$(pidof_svc inventory-service); an=$(pidof_svc analytics-service); ca=$(pidof_svc catalog-service)
while true; do
  l1=$(sysctl -n vm.loadavg | awk '{print $2}')
  st=$(curl -s -m 1 localhost:8082/internal/ratelimit/stats || true)
  # time (with milliseconds) and the limiter counters, in one small python call
  tr=$(printf '%s' "$st" | python3 -c '
import json,sys,time
t="%.3f" % time.time()
try:
    d=json.load(sys.stdin); r=d["rules"][0]
    print(t,r["allowed"],r["rejected"],r["rejectedWithoutRedisCall"],r["decidedByLocalFallback"],d["redis"],sep=",")
except Exception:
    print(t+",,,,,")' 2>/dev/null)
  mem=$(redis-cli INFO memory 2>/dev/null | awk -F: '/^used_memory:/{printf "%.2f", $2/1048576}')
  keys=$(redis-cli DBSIZE 2>/dev/null)
  rlkeys=$(redis-cli --scan --pattern 'rl:*' 2>/dev/null | wc -l | tr -d ' ')
  cpu() { ps -o cputime= -p "$1" 2>/dev/null | secs; }
  ckc=$(cpu "$ck"); ckr=$(ps -o rss= -p "$ck" 2>/dev/null | awk '{printf "%.0f", $1/1024}'); ckt=$(ps -M -p "$ck" 2>/dev/null | tail -n +2 | wc -l | tr -d ' ')
  sum() { ps -A -o ucomm=,cputime= | awk -v re="$1" '$1 ~ re {print $2}' | secs | awk '{t+=$1} END{printf "%.2f", t+0}'; }
  echo "${tr%%,*},$l1,${tr#*,},$mem,$keys,$rlkeys,$ckc,$ckr,$ckt,$(cpu "$inv"),$(cpu "$an"),$(cpu "$ca"),$(sum 'nginx$'),$(sum 'postgres$'),$(sum '^redis-server'),$(sum '^beam')" >> "$OUT"
  sleep 1
done

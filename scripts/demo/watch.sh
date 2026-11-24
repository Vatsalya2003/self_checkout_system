#!/usr/bin/env bash
# Live view of the rate limiter, one line per second. Put it in a terminal
# split next to the load client.
#
#   scripts/demo/watch.sh            # checkout: the new-transactions limit
#   scripts/demo/watch.sh --catalog  # the two catalog instances (:8081, :8085)
#
# Columns (per second, from each service's own counters; each rate is the
# counter's change divided by the measured time between the two samples, so a
# slow sample doesn't inflate it):
#   admitted   requests the limiter let through
#   refused    429s sent
#   no-redis   refusals answered from the process's "empty until T" cache
#   fallback   decisions made by the local fallback (Redis unreachable)
#   redis      UP, or DOWN while the fallback is in use
#   keys / mem the rate-limit keys in Redis and Redis's total memory
set -uo pipefail
export PATH=/opt/homebrew/bin:$PATH
ports=(8082); [ "${1:-}" = --catalog ] && ports=(8081 8085)

stats() {   # port -> "time allowed rejected nocache fallback redis enabled"
  # time = when the counters arrived (seconds, microsecond precision), taken in
  # the same process that read them, so the rates use the real elapsed time
  python3 -c '
import json, sys, time, urllib.request
try:
    body = urllib.request.urlopen("http://localhost:%s/internal/ratelimit/stats" % sys.argv[1], timeout=0.5).read()
    t = time.time()
    d = json.loads(body); r = d["rules"][0]
    print("%.6f" % t, r["allowed"], r["rejected"], r["rejectedWithoutRedisCall"], r["decidedByLocalFallback"], d["redis"], "on" if d["enabled"] else "OFF")
except Exception:
    print("- - - - - no-answer -")' "$1" 2>/dev/null
}

rate() {   # delta seconds -> per second, rounded
  awk -v d="$1" -v s="$2" 'BEGIN { if (s > 0) printf "%d", d / s + 0.5; else print "-" }'
}

now() { python3 -c 'import time; print("%.6f" % time.time())'; }

prev=()   # previous counters per instance (plain array: macOS bash 3.2 has no maps)
n=0
while true; do
  if (( n % 20 == 0 )); then
    printf '\n%-8s' "time"
    for p in "${ports[@]}"; do printf '| :%-4s %9s %9s %8s %8s %-6s %-4s' "$p" admitted refused no-redis fallback redis lim; done
    printf '| %5s %8s\n' keys "redis mem"
  fi
  start=$(now)
  printf '%-8s' "$(date +%T)"
  for i in "${!ports[@]}"; do
    read -r t a r c f redis en <<< "$(stats "${ports[$i]}")"
    if [[ "$a" =~ ^[0-9]+$ ]] && [ -n "${prev[$i]:-}" ]; then
      read -r pt pa pr pc pf <<< "${prev[$i]}"
      dt=$(awk -v a="$t" -v b="$pt" 'BEGIN { printf "%.6f", a - b }')
      printf '|       %9s %9s %8s %8s %-6s %-4s' "$(rate $((a - pa)) "$dt")" "$(rate $((r - pr)) "$dt")" \
        "$(rate $((c - pc)) "$dt")" "$(rate $((f - pf)) "$dt")" "$redis" "$en"
    else
      printf '|       %9s %9s %8s %8s %-6s %-4s' - - - - "$redis" "$en"
    fi
    [[ "$a" =~ ^[0-9]+$ ]] && prev[$i]="$t $a $r $c $f"
  done
  if redis-cli ping >/dev/null 2>&1; then
    printf '| %5s %8s\n' "$(redis-cli --scan --pattern 'rl:*' | wc -l | tr -d ' ')" "$(redis-cli INFO memory | awk -F: '/^used_memory_human:/{gsub(/\r/,"",$2); print $2}')"
  else
    printf '| %5s %8s\n' - "DOWN"
  fi
  n=$((n + 1))
  # sleep only what is left of the second, so lines stay about 1 s apart
  sleep "$(awk -v a="$(now)" -v b="$start" 'BEGIN { s = 1 - (a - b); if (s < 0.05) s = 0.05; printf "%.3f", s }')"
done

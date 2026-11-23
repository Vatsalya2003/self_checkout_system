#!/usr/bin/env bash
# Live view of the rate limiter, one line per second. Put it in a terminal
# split next to the load client.
#
#   scripts/demo/watch.sh            # checkout: the new-transactions limit
#   scripts/demo/watch.sh --catalog  # the two catalog instances (:8081, :8085)
#
# Columns (per second, from each service's own counters):
#   admitted   requests the limiter let through
#   refused    429s sent
#   no-redis   refusals answered from the process's "empty until T" cache
#   fallback   decisions made by the local fallback (Redis unreachable)
#   redis      UP, or DOWN while the fallback is in use
#   keys / mem the rate-limit keys in Redis and Redis's total memory
set -uo pipefail
export PATH=/opt/homebrew/bin:$PATH
ports=(8082); [ "${1:-}" = --catalog ] && ports=(8081 8085)

stats() {   # port -> "allowed rejected nocache fallback redis enabled"
  curl -s -m 0.5 "localhost:$1/internal/ratelimit/stats" | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin); r=d["rules"][0]
    print(r["allowed"], r["rejected"], r["rejectedWithoutRedisCall"], r["decidedByLocalFallback"], d["redis"], "on" if d["enabled"] else "OFF")
except Exception:
    print("- - - - no-answer -")' 2>/dev/null
}

prev=()   # previous counters per instance (plain array: macOS bash 3.2 has no maps)
n=0
while true; do
  if (( n % 20 == 0 )); then
    printf '\n%-8s' "time"
    for p in "${ports[@]}"; do printf '| :%-4s %9s %9s %8s %8s %-6s %-4s' "$p" admitted refused no-redis fallback redis lim; done
    printf '| %5s %8s\n' keys "redis mem"
  fi
  printf '%-8s' "$(date +%T)"
  for i in "${!ports[@]}"; do
    read -r a r c f redis en <<< "$(stats "${ports[$i]}")"
    if [[ "$a" =~ ^[0-9]+$ ]] && [ -n "${prev[$i]:-}" ]; then
      read -r pa pr pc pf <<< "${prev[$i]}"
      printf '|       %9d %9d %8d %8d %-6s %-4s' $((a - pa)) $((r - pr)) $((c - pc)) $((f - pf)) "$redis" "$en"
    else
      printf '|       %9s %9s %8s %8s %-6s %-4s' - - - - "$redis" "$en"
    fi
    [[ "$a" =~ ^[0-9]+$ ]] && prev[$i]="$a $r $c $f"
  done
  if redis-cli ping >/dev/null 2>&1; then
    printf '| %5s %8s\n' "$(redis-cli --scan --pattern 'rl:*' | wc -l | tr -d ' ')" "$(redis-cli INFO memory | awk -F: '/^used_memory_human:/{gsub(/\r/,"",$2); print $2}')"
  else
    printf '| %5s %8s\n' - "DOWN"
  fi
  n=$((n + 1))
  sleep 1
done

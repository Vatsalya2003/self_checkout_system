#!/usr/bin/env bash
# Demo step 6: fault tolerance. Stop Redis while load is running; traffic keeps
# flowing on the local fallback; start it again; the shared limit comes back.
#   scripts/demo/6-redis-down.sh [stations] [seconds]
source "$(dirname "$0")/_common.sh"
ST=${1:-300}; DUR=${2:-30}

settle
header "STEP 6  Fault tolerance: Redis goes down under load"
limiter on 8082 >/dev/null
say "polite client, $ST stations for ${DUR}s; Redis stops after 10 s and comes back after 20 s"
"$JAVA" scripts/demo/RateLimitClient.java --mode=polite --stations="$ST" --duration="$DUR" > "$DEMO_OUT/step6-client.txt" 2>&1 &
BG=$!
sleep 10
echo "${RED}$(date +%T)  stopping Redis${RESET}"; brew services stop redis >/dev/null 2>&1
printf '         redis-cli ping -> %s\n' "$(redis-cli ping 2>/dev/null || echo 'no answer (Redis is down)')"
sleep 4
limiter_log 3
sleep 6
echo "${GREEN}$(date +%T)  starting Redis again${RESET}"; brew services start redis >/dev/null 2>&1
sleep 3
limiter_log 2
wait $BG
echo
echo "${BOLD}The client's view, second by second${RESET} (errors column = anything but 201/200/429)"
sed -n '2,$p' "$DEMO_OUT/step6-client.txt" | awk '/^ +[0-9]+ \|/ {if (++n % 2 == 0) print; next} {print}'
echo
echo "${BOLD}After the run${RESET}"
curl -s localhost:8082/internal/ratelimit/stats | python3 -c 'import json,sys;d=json.load(sys.stdin);r=d["rules"][0];print("  redis", d["redis"], "| decided by the local fallback:", r["decidedByLocalFallback"], "| Redis calls that failed:", d["redisErrors"])'
scripts/check-invariant-micro.sh 2>&1 | tail -1 | sed 's/^/  cross-database invariant: /'

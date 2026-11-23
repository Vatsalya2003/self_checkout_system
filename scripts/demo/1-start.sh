#!/usr/bin/env bash
# Demo step 1: start everything (limiter OFF) and show it is healthy.
source "$(dirname "$0")/_common.sh"

header "STEP 1  Week 6 event-driven system + rate limiter, limiter OFF"
say "branch $(git branch --show-current) at $(git log --oneline -1 | cut -c1-60)"
say "stopping anything running, resetting the four databases, starting fresh"
scripts/stop-all.sh >/dev/null 2>&1
db/reset.sh | sed 's/^/  /'
# Two catalog instances from the start (used in step 5). Limiter off in every
# service; it gets switched on live later, so no restart between steps.
scripts/start-all.sh --two-catalogs --no-limiter | sed 's/^/  /'

echo
echo "${BOLD}Health${RESET}"
printf '  GET /items through the gateway      -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/items)"
printf '  Redis                                -> %s\n' "$(redis-cli ping)"
printf '  RabbitMQ                             -> %s\n' "$(rabbitmq-diagnostics -q check_running >/dev/null 2>&1 && echo running || echo DOWN)"
for p in 8082 8081 8085; do
  printf '  limiter on :%s                     -> %s\n' "$p" \
    "$(curl -s localhost:$p/internal/ratelimit/stats | python3 -c 'import json,sys;d=json.load(sys.stdin);r=d["rules"][0];print(("ON" if d["enabled"] else "OFF"), "|", r["name"], r["method"], r["path"], str(int(r["ratePerSecond"]))+"/s burst", r["burst"], "| redis", d["redis"])')"
done

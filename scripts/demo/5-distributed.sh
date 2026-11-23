#!/usr/bin/env bash
# Demo step 5: two catalog-service instances (:8081 and :8085) behind the same
# gateway share ONE limit (100/s on GET /items) through Redis.
source "$(dirname "$0")/_common.sh"
DUR=${1:-15}

header "STEP 5  Distributed: two catalog instances, one shared limit of 100/s"
say "both instances, same rule, same Redis key rl:catalog-reads:global"
limiter on 8081 8085
echo
"$JAVA" scripts/demo/RateLimitClient.java --mode=probe --path=/items --concurrency=16 --duration="$DUR" --instances=8081,8085 \
  | awk 'NR<=2 || /summary|instance|together/ {print; next} /^ +[0-9]+ \|/ {if (++n % 3 == 0) print}'
echo
say "if each instance limited on its own, together they would admit ~200/s; shared, they admit ~100/s"

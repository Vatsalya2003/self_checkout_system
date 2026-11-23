#!/usr/bin/env bash
# Demo step 3: the SAME overload with the limiter switched on (live, no restart).
#   scripts/demo/3-overload-with-limiter.sh [stations] [seconds] [course|polite]
# course = the course load client (retries every 429 at once)
# polite = scripts/demo/RateLimitClient.java (waits as long as the 429 says)
source "$(dirname "$0")/_common.sh"
ST=${1:-4000}; DUR=${2:-40}; KIND=${3:-course}

settle
header "STEP 3  Same overload, limiter ON ($KIND client, $ST stations, ${DUR}s)"
limiter on 8082
: > gateway/run/error.log
if [ "$KIND" = course ]; then
  course_client "$ST" "$DUR" step3-with-limiter-course
else
  "$JAVA" scripts/demo/RateLimitClient.java --mode=polite --stations="$ST" --duration="$DUR" \
    | awk 'NR<=2 {print; next} /^ +[0-9]+ \|/ {if (++n % 5 == 0) print; next} {print}'

fi
echo
echo "${BOLD}The limiter's own log${RESET} (checkout-service)"
limiter_log 4
echo
echo "${BOLD}Gateway errors during this run:${RESET} $(grep -v ':8085' gateway/run/error.log | grep -c .)"

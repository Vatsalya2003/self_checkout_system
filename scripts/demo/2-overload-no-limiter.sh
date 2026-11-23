#!/usr/bin/env bash
# Demo step 2: overload WITHOUT the limiter. The course load client with many
# stations; watch latency explode and requests fail.
#   scripts/demo/2-overload-no-limiter.sh [stations] [seconds]
source "$(dirname "$0")/_common.sh"
ST=${1:-4000}; DUR=${2:-40}

settle
header "STEP 2  Overload, limiter OFF: course load client, $ST stations, ${DUR}s"
limiter off 8082
: > gateway/run/error.log
course_client "$ST" "$DUR" step2-no-limiter

echo
echo "${BOLD}What the gateway saw${RESET} (nginx error log, $(grep -c . gateway/run/error.log) lines; first 3)"
grep -v ':8085' gateway/run/error.log | head -3 | cut -c1-170 | sed 's/^/  /'

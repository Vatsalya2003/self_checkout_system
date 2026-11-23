#!/usr/bin/env bash
# Demo step 4: one throttled request, with curl -i, so the 429, its headers and
# the JSON body are on screen. Runs a short burst of load in the background so
# the bucket is empty, then asks.
source "$(dirname "$0")/_common.sh"

settle
header "STEP 4  What a throttled client sees: curl -i POST /transactions"
limiter on 8082 >/dev/null
say "keeping the limit busy with 300 polite stations for a few seconds..."
"$JAVA" scripts/demo/RateLimitClient.java --mode=polite --stations=300 --duration=8 >/dev/null 2>&1 &
BG=$!
sleep 4
for i in $(seq 50); do
  out=$(curl -s -i -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{"stationId":"curl-demo"}')
  if grep -q '^HTTP/1.1 429' <<< "$out"; then break; fi
done
echo
echo '$ curl -i -X POST localhost:8080/transactions -d '"'"'{"stationId":"curl-demo"}'"'"
echo
grep -v -i -E '^(date|keep-alive|connection):' <<< "$out" | tr -d '\r'
echo
echo
say "an ADMITTED request gets the same X-RateLimit-* headers on its normal 201:"
sleep 0.2
wait $BG 2>/dev/null
curl -s -i -X POST localhost:8080/transactions -H 'Content-Type: application/json' -d '{"stationId":"curl-demo"}' \
  | grep -i -E '^(HTTP|X-RateLimit)' | tr -d '\r' | sed 's/^/  /'

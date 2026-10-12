#!/usr/bin/env bash
# Stop the gateway and the four services. Safe to run when nothing is running.
#
# Services get SIGTERM, so Spring shuts down cleanly: checkout-service's
# scan-log writer flushes what it has queued, and analytics-service's pipeline
# drains before exiting.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [ -f gateway/run/nginx.pid ] && kill -0 "$(cat gateway/run/nginx.pid)" 2>/dev/null; then
  nginx -p "$ROOT/gateway/" -c nginx.conf -s quit
  echo "gateway stopping"
fi

for name in catalog-service checkout-service inventory-service analytics-service; do
  pidfile="logs/$name.pid"
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    kill "$(cat "$pidfile")"
    echo "$name stopping"
  fi
done

# Wait for every port to be free (the scan-log writer may need a moment to flush).
for i in $(seq 120); do
  busy=0
  for p in 8080 8081 8082 8083 8084; do
    lsof -tiTCP:$p -sTCP:LISTEN >/dev/null && busy=1
  done
  [ $busy = 0 ] && { rm -f logs/*.pid; echo "all stopped"; exit 0; }
  sleep 0.5
done
echo "something is still listening on 8080-8084:" >&2
lsof -iTCP:8080-8084 -sTCP:LISTEN >&2
exit 1

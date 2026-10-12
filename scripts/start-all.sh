#!/usr/bin/env bash
# Start the four services and the gateway, each as its own process.
#
#   scripts/start-all.sh            # start (build the jars first if any is missing)
#   scripts/start-all.sh --build    # rebuild all jars, then start
#
# Reset first if you want a clean run:
#   scripts/stop-all.sh && db/reset.sh && scripts/start-all.sh
#
# Each service is its own deployable unit: its own jar, its own JVM, its own
# port and its own log file (logs/<service>.log). The gateway (nginx) starts
# last, once every service answers, so the load client never sees a 502.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
JAVA="$JAVA_HOME/bin/java"
mkdir -p logs gateway/run

# name  port  heap   health check (path and the status that means "up")
SERVICES=(
  "catalog-service   8081 512m /items                     200"
  "checkout-service  8082 1g   /transactions/health-probe 404"
  "inventory-service 8083 512m /inventory/low-stock       200"
  "analytics-service 8084 512m /analytics/popular-items   200"
)

need_build=0
[ "${1:-}" = "--build" ] && need_build=1
for s in "${SERVICES[@]}"; do
  set -- $s
  [ -f "$1/target/$1-0.0.1-SNAPSHOT.jar" ] || need_build=1
done
if [ $need_build = 1 ]; then
  echo "building all jars..."
  ./mvnw -B -q -DskipTests package
fi

for s in "${SERVICES[@]}"; do
  set -- $s
  if lsof -tiTCP:"$2" -sTCP:LISTEN >/dev/null; then
    echo "port $2 is already in use -- run scripts/stop-all.sh first" >&2; exit 1
  fi
  nohup "$JAVA" -Xmx"$3" -jar "$1/target/$1-0.0.1-SNAPSHOT.jar" > "logs/$1.log" 2>&1 &
  echo $! > "logs/$1.pid"
  echo "started $1 (pid $!) on :$2"
done

for s in "${SERVICES[@]}"; do
  set -- $s
  for i in $(seq 120); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "localhost:$2$4" || true)
    [ "$code" = "$5" ] && break
    if ! kill -0 "$(cat "logs/$1.pid")" 2>/dev/null; then
      echo "$1 exited during startup; last lines of logs/$1.log:" >&2; tail -20 "logs/$1.log" >&2; exit 1
    fi
    sleep 0.5
  done
  [ "$code" = "$5" ] || { echo "$1 did not become healthy" >&2; exit 1; }
  echo "  $1 healthy"
done

if lsof -tiTCP:8080 -sTCP:LISTEN >/dev/null; then
  echo "port 8080 is already in use -- run scripts/stop-all.sh first" >&2; exit 1
fi
nginx -p "$ROOT/gateway/" -c nginx.conf
for i in $(seq 40); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/items || true)" = 200 ] && break
  sleep 0.25
done
echo "gateway up on :8080 -> all services reachable through one base URL"

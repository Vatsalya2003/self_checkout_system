#!/usr/bin/env bash
# Shared bits for the demo scripts (sourced, not run).
set -uo pipefail
export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:/opt/homebrew/opt/postgresql@16/bin:$PATH
R=$HOME/Desktop/ADV_SOFT_DEV/self-checkout; cd "$R"
CLIENT=$HOME/Desktop/ADV_SOFT_DEV/CS6510-2026/load-client
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
DEMO_OUT=${DEMO_OUT:-$R/reports/rate-limiter/demo}
mkdir -p "$DEMO_OUT"

BOLD=$'\e[1m'; DIM=$'\e[2m'; GREEN=$'\e[32m'; RED=$'\e[31m'; RESET=$'\e[0m'

header() {   # big title for the video
  echo
  echo "${BOLD}==================================================================${RESET}"
  echo "${BOLD}  $*${RESET}"
  echo "${BOLD}==================================================================${RESET}"
}
say() { echo "${DIM}# $*${RESET}"; }

limiter() {   # on|off  port...  -- switch the limiter live on those instances
  local v=$1; shift
  for p in "$@"; do
    printf '  :%s limiter -> %s\n' "$p" "$(curl -s -X POST "localhost:$p/internal/ratelimit/enabled?value=$([ "$v" = on ] && echo true || echo false)")"
  done
}

course_client() {   # stations seconds label -- the course load client, unchanged
  local st=$1 dur=$2 label=$3
  (cd "$CLIENT" && "$JAVA" -cp out Main --stations="$st" --duration="$dur" --reportDir="$DEMO_OUT") \
    2> "$DEMO_OUT/$label-stderr.txt" | grep -E -v '^\s+SKU-|^\s*#[0-9]+ |^Most popular|^Low-stock|^\s*\(none\)|JSON report'
  echo
  echo "${BOLD}Why did the failed transactions fail?${RESET} (counted from the client's stderr)"
  scripts/demo/count-client-errors.sh "$DEMO_OUT/$label-stderr.txt"
  gzip -f "$DEMO_OUT/$label-stderr.txt"
}

limiter_log() {   # last N limiter lines from checkout
  grep -h 'ratelimit |' logs/checkout-service.log | tail -"${1:-4}" | sed 's/^[0-9-]*T\([0-9:]*\)\.[0-9]*.*ratelimit |/  \1 ratelimit |/'
}

settle() {   # wait for the OS to free the ports the previous step left in TIME_WAIT
  # A heavy step leaves thousands of closed connections in TIME_WAIT for 30 s
  # (macOS: 2 x 15 s), each holding one of ~16k local ports. Starting the next
  # step on top of them makes nginx run out of ports to reach checkout.
  local n i
  for i in $(seq 60); do
    n=$(netstat -an -p tcp | grep -c TIME_WAIT)
    [ "$n" -lt 1500 ] && break
    [ "$i" = 1 ] && say "waiting for the previous step's $n closed connections to clear (TIME_WAIT)..."
    sleep 1
  done
}

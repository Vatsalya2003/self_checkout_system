#!/usr/bin/env bash
# Proof that no service talks to another service directly (Week 6).
#
#   scripts/check-connections.sh        (with the services running, ideally under load)
#
# Lists every established TCP connection of each service process and sorts it:
#   -> :5432   its own PostgreSQL database
#   -> :5672   the RabbitMQ broker
#   <- :<own>  a request arriving on its own port (from nginx or a test)
# Anything else -- above all a connection TO another service's port
# (8081-8084) -- would be service-to-service HTTP, and fails the check.
# Which database each one opens is enforced separately by the roles
# (db/roles.sql): a service's role may connect to its own database only.
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0
for entry in catalog-service:8081 checkout-service:8082 inventory-service:8083 analytics-service:8084; do
  name=${entry%%:*}; own=${entry##*:}
  p=$(cat "logs/$name.pid" 2>/dev/null) || { echo "$name: not running"; fail=1; continue; }
  db=0; broker=0; inbound=0; other=""
  while read -r local remote; do
    lport=${local##*:}; rport=${remote##*:}
    if [ "$rport" = 5432 ]; then db=$((db + 1))
    elif [ "$rport" = 5672 ]; then broker=$((broker + 1))
    elif [ "$lport" = "$own" ]; then inbound=$((inbound + 1))
    else other="$other $local->$remote"
    fi
  done < <(lsof -a -p "$p" -iTCP -sTCP:ESTABLISHED -nP -Fn 2>/dev/null | sed -n 's/^n//p' | sed 's/->/ /')
  printf "%-18s postgres=%-3s rabbitmq=%-3s inbound-on-:%s=%-3s other=%s\n" \
         "$name" "$db" "$broker" "$own" "$inbound" "${other:-none}"
  [ -n "$other" ] && fail=1
done
if [ $fail = 0 ]; then echo "PASS: every service connects only to its database and the broker"
else echo "FAIL: a service has a connection that is neither its database nor the broker"; exit 1; fi

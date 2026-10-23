#!/usr/bin/env bash
# Reset all four service databases: drop, recreate, schema, seed, grants.
#
# Week 5: every service has its own database --
#   catalog_db  inventory_db  checkout_db  analytics_db
# -- and each one may be opened only by that service's role (db/roles.sql).
#
# Run with the services stopped:
#   scripts/stop-all.sh && db/reset.sh && scripts/start-all.sh
#
# Needs a user that may create databases and roles (the Homebrew default
# user is a superuser). Connects to the "postgres" maintenance database to
# drop and create the others; override with ADMIN_DB if yours differs.
# The Week 4 shared database (self_checkout) is left untouched.
set -euo pipefail
cd "$(dirname "$0")"
ADMIN_DB="${ADMIN_DB:-postgres}"
export PGOPTIONS="-c client_min_messages=warning"
PSQL=(psql -X -q -v ON_ERROR_STOP=1)

"${PSQL[@]}" -d "$ADMIN_DB" -f roles.sql

for svc in catalog inventory checkout analytics; do
    db="${svc}_db"
    # WITH (FORCE) drops it even if a stray connection is still open.
    # Each -c runs on its own: DROP/CREATE DATABASE cannot run in a transaction.
    "${PSQL[@]}" -d "$ADMIN_DB" \
        -c "DROP DATABASE IF EXISTS $db WITH (FORCE)" \
        -c "CREATE DATABASE $db" \
        -c "REVOKE ALL ON DATABASE $db FROM PUBLIC" \
        -c "GRANT CONNECT ON DATABASE $db TO ${svc}_svc"
    "${PSQL[@]}" -d "$db" -f "$svc/schema.sql"
    if [ -f "$svc/data.sql" ]; then "${PSQL[@]}" -d "$db" -f "$svc/data.sql"; fi
    "${PSQL[@]}" -d "$db" -f "$svc/grants.sql"
done

q() { psql -X -tA -d "$1" -c "$2"; }
echo "reset: catalog_db $(q catalog_db 'SELECT count(*) FROM items') items |" \
     "inventory_db $(q inventory_db 'SELECT count(*) || '"' SKUs, qty '"' || min(qty) || '"'..'"' || max(qty) FROM stock') |" \
     "checkout_db $(q checkout_db 'SELECT count(*) FROM transactions') sales |" \
     "analytics_db $(q analytics_db 'SELECT count(*) FROM scan_log') scans"

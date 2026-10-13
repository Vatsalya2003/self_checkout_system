#!/usr/bin/env bash
# Reset the shared database: drop every table, recreate them, reseed the catalog,
# and re-apply each service's grants (db/roles.sql).
#
# This is the ONE thing allowed to run schema.sql and data.sql. Since Week 4
# there are four services sharing the database, so "every boot rebuilds the
# schema" (Weeks 1-3) would mean four rebuilds racing each other; instead every
# service runs with spring.sql.init.mode=never and this runs first.
#
# Between load runs:   scripts/stop-all.sh && db/reset.sh && scripts/start-all.sh
#
# Database and user default to the same values the services use; override
# with PGDATABASE / PGUSER / PGHOST / PGPORT if yours differ.
set -euo pipefail
cd "$(dirname "$0")"
export PGDATABASE="${PGDATABASE:-self_checkout}"
PGOPTIONS="-c client_min_messages=warning" psql -q -v ON_ERROR_STOP=1 -f schema.sql -f data.sql -f roles.sql
echo "reset $PGDATABASE: $(psql -tAc 'SELECT count(*) FROM items') items, stock $(psql -tAc 'SELECT min(stock) || '"'"'..'"'"' || max(stock) FROM items'), $(psql -tAc 'SELECT count(*) FROM scan_log') scan_log rows"

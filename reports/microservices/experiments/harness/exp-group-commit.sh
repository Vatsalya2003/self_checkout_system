#!/usr/bin/env bash
# Experiment 1: commit_delay (group commit) at each value, commit_siblings=5.
# synchronous_commit stays on. Always restores the defaults on exit.
#   exp-group-commit.sh <experiments-dir> <delay>...
export PATH=/opt/homebrew/bin:$PATH
D=$(dirname "$0"); E=$1; shift
pg() { psql -X -q -d postgres -c "$1"; }
restore() { pg "ALTER SYSTEM RESET commit_delay"; pg "ALTER SYSTEM RESET commit_siblings"; pg "SELECT pg_reload_conf()" >/dev/null
            echo "restored: $(psql -X -tA -d postgres -c "SELECT string_agg(name||'='||setting,' ') FROM pg_settings WHERE name IN ('commit_delay','commit_siblings','synchronous_commit')")"; }
trap restore EXIT
for us in "$@"; do
  pg "ALTER SYSTEM SET commit_delay = $us"; pg "ALTER SYSTEM SET commit_siblings = 5"; pg "SELECT pg_reload_conf()" >/dev/null
  sleep 1
  echo "commit_delay=$us: $(psql -X -tA -d postgres -c "SELECT string_agg(name||'='||setting,' ') FROM pg_settings WHERE name IN ('commit_delay','commit_siblings','synchronous_commit')")"
  bash "$D/run-set.sh" "$E/group-commit-$us" || exit $?
done

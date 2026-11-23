#!/usr/bin/env bash
# Run a list of measured runs one after another (each waits for an idle machine).
#   run-set.sh <outdir> <file with one run per line: label kind stations duration on|off [flags]>
# Lines starting with # are skipped. Stops at the first run that refuses to start.
set -uo pipefail
R=$HOME/Desktop/ADV_SOFT_DEV/self-checkout; cd "$R"
OUT=$1 LIST=$2
grep -v -E '^\s*(#|$)' "$LIST" | while read -r label kind st dur lim flags; do
  # shellcheck disable=SC2086
  scripts/ratelimit-bench/run.sh "$OUT" "$label" "$kind" "$st" "$dur" "$lim" $flags < /dev/null || { echo "run $label failed or refused; stopping the set" >&2; exit 1; }
done
scripts/stop-all.sh >/dev/null 2>&1
echo "set done"

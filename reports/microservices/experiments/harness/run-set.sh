#!/usr/bin/env bash
# The 2 default + 2 stress runs for one setting, interleaved: run-set.sh <outdir>
D=$(dirname "$0"); OUT=$1
for spec in "default-run1 10 60" "stress-run1 100 120" "default-run2 10 60" "stress-run2 100 120"; do
  set -- $spec
  bash "$D/run-load.sh" "$OUT" "$1" "$2" "$3" || exit $?
done

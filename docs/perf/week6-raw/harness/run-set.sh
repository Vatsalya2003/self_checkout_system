#!/usr/bin/env bash
# The measured runs for one setting: run-set.sh <outdir> <label-prefix>... e.g.
#   run-set.sh docs/perf/week6-raw/baseline-week5-after-restart default-run1 default-run2
#   run-set.sh docs/perf/week6-raw default-run1 stress-run1 default-run2 stress-run2
# A label starting with "stress" runs 100 stations x 120 s, otherwise 10 x 60 s.
D=$(dirname "$0"); OUT=$1; shift
for label in "$@"; do
  case $label in stress*) st=100; dur=120;; *) st=10; dur=60;; esac
  bash "$D/run-load.sh" "$OUT" "$label" "$st" "$dur" || exit $?
done

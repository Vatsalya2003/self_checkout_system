#!/usr/bin/env bash
# Correctness suite, each check on a fresh reset + restart:
# unit/ArchUnit tests, golden diff, the 5 behavioural scripts (+ cross-DB
# invariant after each), the restart walkthrough, and (unless --no-failures)
# the failure tests.   validate.sh <outdir> [--no-failures]
export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:$PATH
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
R=$HOME/Desktop/ADV_SOFT_DEV/self-checkout; cd "$R"
OUT=$1; mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
fresh() { scripts/stop-all.sh >/dev/null 2>&1; db/reset.sh >/dev/null && scripts/start-all.sh >/dev/null; }
line() { printf '%-40s %s\n' "$1" "$2" | tee -a "$OUT/RESULT.txt"; }
: > "$OUT/RESULT.txt"
echo "branch $(git branch --show-current) @ $(git rev-parse --short HEAD)" | tee -a "$OUT/RESULT.txt"
./mvnw -B test > "$OUT/mvn-test.txt" 2>&1; rc=$?
line "mvnw test" "$([ $rc = 0 ] && echo PASS || echo FAIL) ($(grep -h '^\[INFO\] Tests run:' "$OUT/mvn-test.txt" | grep -v ' - in ' | awk -F'[ ,]+' '{t+=$4} END{print t}') tests)"
fresh; python3 scripts/golden.py diff reports/golden/single-app.txt > "$OUT/golden.txt" 2>&1; rc=$?
line "golden diff" "$([ $rc = 0 ] && echo PASS || echo FAIL): $(tail -1 "$OUT/golden.txt")"
for t in test-concurrent-completions test-oversell-and-double-complete test-low-stock test-popular-window test-scan-complete-race; do
  fresh; python3 scripts/$t.py > "$OUT/$t.txt" 2>&1; rc=$?
  sleep 2; scripts/check-invariant-micro.sh > "$OUT/$t-invariant.txt" 2>&1
  line "$t" "$([ $rc = 0 ] && echo PASS || echo FAIL); invariant $(tail -1 "$OUT/$t-invariant.txt") ($(grep -c '^  FAIL' "$OUT/$t-invariant.txt") failing check(s))"
done
scripts/test-service-restart.sh "$OUT/restart-test" > "$OUT/restart-test.txt" 2>&1
line "test-service-restart.sh" "final invariant: $(grep -E '^(PASS|FAIL)$' "$OUT/restart-test.txt" | tail -1)"
if [ "${2:-}" != "--no-failures" ]; then
  scripts/test-failures.sh "$OUT/failure-tests" > "$OUT/failure-tests.txt" 2>&1; rc=$?
  line "test-failures.sh" "$([ $rc = 0 ] && echo PASS || echo FAIL): $(tail -1 "$OUT/failure-tests.txt")"
fi
scripts/stop-all.sh >/dev/null 2>&1

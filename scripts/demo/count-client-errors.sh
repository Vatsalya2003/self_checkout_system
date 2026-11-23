#!/usr/bin/env bash
# Count the course load client's errors by kind, from its stderr.
#
#   scripts/demo/count-client-errors.sh load-client.err      (plain or .gz)
#
# The load client (which must not be changed) only reports an error COUNT per
# operation. It does print one line per failed transaction to stderr, though,
# and that line has the status code (or "Transport error" for a timeout or a
# refused connection) and the URL. This splits them up, so a run can show
# "these were 429s, nothing else failed" -- or the opposite, without the limiter.
set -euo pipefail
f="${1:?usage: count-client-errors.sh <load-client stderr file>}"
cat_it() { case "$f" in *.gz) gzip -dc "$f" ;; *) cat "$f" ;; esac; }

total=$(cat_it | grep -c 'transaction failed' || true)
echo "failed transactions: $total"
[ "$total" = 0 ] && exit 0

echo "by kind and operation:"
cat_it | grep 'transaction failed' | awk '
  {
    kind = "other"
    if (match($0, /Unexpected status [0-9]+/)) kind = substr($0, RSTART + 18, RLENGTH - 18)
    else if ($0 ~ /Transport error/) kind = "transport"
    op = "start"
    if (match($0, /localhost:[0-9]+\/[^ :]*/)) {
      path = substr($0, RSTART, RLENGTH)
      if (path ~ /\/items$/) op = "scan"
      else if (path ~ /\/complete$/) op = "complete"
    }
    if (kind == "429") label = "429 rate limited"
    else if (kind ~ /^5/) label = kind " server error"
    else if (kind == "transport") label = "no answer (timeout / connection)"
    else label = kind
    n[label "|" op]++
  }
  END { for (k in n) { split(k, p, "|"); printf "  %8d %-36s %s\n", n[k], p[1], p[2] } }' | sort -k2

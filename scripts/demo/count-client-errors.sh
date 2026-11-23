#!/usr/bin/env bash
# Count the course load client's errors by kind, from its stderr.
#
#   scripts/demo/count-client-errors.sh load-client.err
#
# The load client (which must not be changed) only reports an error COUNT per
# operation. It does print one line per failed transaction to stderr, though,
# and that line has the status code (or "Transport error" for a timeout or a
# refused connection) and the URL. This splits them up, so a run can show
# "these were 429s, nothing else failed" -- or the opposite, without the limiter.
set -euo pipefail
f="${1:?usage: count-client-errors.sh <load-client stderr file>}"

total=$(grep -c 'transaction failed' "$f" || true)
echo "failed transactions: $total"
[ "$total" = 0 ] && exit 0

# kind = status code or "transport"; operation = which request of the basket failed
echo "by kind and operation:"
grep 'transaction failed' "$f" \
  | sed -E 's/.*(Unexpected status ([0-9]+)|Transport error).* (from|calling) (http[^ :]*(:[0-9]+)?[^ :]*).*/\2 \4/' \
  | awk '{kind = ($1 == "" ? "transport" : $1); print kind, $2}' \
  | while read -r kind url; do
      case "$url" in
        */items)    o=scan ;;
        */complete) o=complete ;;
        *)          o=start ;;
      esac
      case "$kind" in
        429)       k="429 rate limited" ;;
        5??)       k="$kind server error" ;;
        transport) k="no answer (timeout / connection)" ;;
        *)         k="$kind" ;;
      esac
      echo "$k|$o"
    done | sort | uniq -c | awk -F'|' '{printf "  %-45s %s\n", $1, $2}'

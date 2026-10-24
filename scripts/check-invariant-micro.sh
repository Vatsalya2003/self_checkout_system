#!/usr/bin/env bash
# The stock invariant, across microservices that each have their own database.
#
#   scripts/check-invariant-micro.sh          (after a load run, before db/reset.sh)
#
# Week 5: stock (inventory_db) and sales (checkout_db) live in different
# databases that no SQL query can join, so this script reads each one on its
# own and compares the results. It checks:
#
#   1. inventory_db  no SKU has negative stock
#   2. inventory_db  for every SKU: 10000 - stock = SUM(applied) over its stock commits
#   3. checkout_db   no payment is left PENDING (every saga finished)
#   4. both          the committed lines match EXACTLY: every (transaction, sku,
#                    quantity, applied) in checkout_db has the same row in
#                    inventory_db and vice versa -- no sale without a stock
#                    commit, no stock commit without a sale
#   5. analytics_db  scan_log is gapless (seqs exactly 1..max)
#   6. analytics_db  every stored window equals a brute-force recount
#
# Read-only. Prints one line per check, then PASS or FAIL; exits 1 on FAIL.
set -uo pipefail
cd "$(dirname "$0")"
q() { psql -X -tA -d "$1" -c "$2"; }
fail=0
check() {   # label  value  expected
    if [ "$2" = "$3" ]; then printf "  ok    %-62s %s\n" "$1" "$2"
    else printf "  FAIL  %-62s %s (expected %s)\n" "$1" "$2" "$3"; fail=1; fi
}
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

echo "== stock (inventory_db)"
check "SKUs with negative stock" "$(q inventory_db 'SELECT count(*) FROM stock WHERE qty < 0')" 0
check "SKUs where 10000 - stock <> SUM(applied)" "$(q inventory_db "
    SELECT count(*) FROM stock s
    LEFT JOIN (SELECT sku, SUM(applied) AS applied FROM stock_commit_lines GROUP BY sku) c ON c.sku = s.sku
    WHERE 10000 - s.qty <> COALESCE(c.applied, 0)")" 0

echo "== sales (checkout_db)"
check "payments still PENDING" "$(q checkout_db "SELECT count(*) FROM transactions WHERE status = 'PENDING'")" 0

echo "== checkout_db vs inventory_db"
q checkout_db "SELECT t.id || '|' || i.sku || '|' || i.quantity || '|' || COALESCE(i.applied_quantity::text, 'NULL')
               FROM transactions t JOIN transaction_items i ON i.transaction_id = t.id
               WHERE t.status = 'COMPLETED' ORDER BY 1" > "$tmp/checkout"
q inventory_db "SELECT transaction_id || '|' || sku || '|' || quantity || '|' || applied
               FROM stock_commit_lines ORDER BY 1" > "$tmp/inventory"
check "completed sales (checkout) = stock commits (inventory)" \
      "$(q checkout_db "SELECT count(*) FROM transactions WHERE status = 'COMPLETED'")" \
      "$(q inventory_db 'SELECT count(*) FROM stock_commits')"
only_checkout=$(comm -23 "$tmp/checkout" "$tmp/inventory" | wc -l | tr -d ' ')
only_inventory=$(comm -13 "$tmp/checkout" "$tmp/inventory" | wc -l | tr -d ' ')
check "sale lines with no matching stock-commit line" "$only_checkout" 0
check "stock-commit lines with no matching sale line" "$only_inventory" 0
if [ "$only_checkout$only_inventory" != "00" ]; then
    echo "  first differences (< checkout only, > inventory only):"
    diff "$tmp/checkout" "$tmp/inventory" | grep '^[<>]' | head -10 | sed 's/^/    /'
fi
echo "     (lines compared: $(wc -l < "$tmp/checkout" | tr -d ' ') in checkout, $(wc -l < "$tmp/inventory" | tr -d ' ') in inventory)"

echo "== popularity windows (analytics_db)"
check "scan_log gaps (max(seq) - count)" "$(q analytics_db 'SELECT COALESCE(max(seq),0) - count(*) FROM scan_log')" 0
check "scan_log starts at seq 1 (or is empty)" "$(q analytics_db 'SELECT COALESCE(min(seq),1) FROM scan_log')" 1
mismatched=$(psql -X -d analytics_db -f check-windows.sql 2>&1 | awk '/windows_checked/{getline; getline; print $3; exit}')
check "windows not equal to a brute-force recount" "${mismatched:-?}" 0

echo "== totals"
echo "     sales $(q checkout_db "SELECT count(*) FROM transactions WHERE status = 'COMPLETED'")," \
     "units taken $(q inventory_db 'SELECT COALESCE(SUM(applied),0) FROM stock_commit_lines')," \
     "units short (sold out) $(q inventory_db 'SELECT COALESCE(SUM(quantity - applied),0) FROM stock_commit_lines')," \
     "low-stock alerts $(q inventory_db 'SELECT count(*) FROM low_stock_alerts')," \
     "scans $(q analytics_db 'SELECT count(*) FROM scan_log')," \
     "windows $(q analytics_db 'SELECT count(*) FROM popular_snapshots')"
if [ $fail = 0 ]; then echo "PASS"; else echo "FAIL"; exit 1; fi

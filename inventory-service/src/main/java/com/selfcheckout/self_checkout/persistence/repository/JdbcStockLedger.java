package com.selfcheckout.self_checkout.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// INVENTORY-SERVICE, persistence layer. Plain JDBC on inventory_db.
//
// Week 5: the stock SQL that used to be in checkout-service (JdbcStockUpdater,
// JdbcLowStockAlertWriter) lives here now, because stock moved into
// inventory's own database. The statements are the same ones, with the same
// reasons; only the table names changed (items.stock -> stock.qty).
@Repository
public class JdbcStockLedger implements StockLedger {

    private final JdbcTemplate jdbc;

    public JdbcStockLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claimCommit(String transactionId, Instant at) {
        // The primary key on transaction_id is the whole idempotency check.
        // If a commit for this id is still in flight on another connection,
        // this INSERT waits for it to finish, then reports the conflict -- so
        // two copies of the same commit can never both take stock.
        return jdbc.update("INSERT INTO stock_commits (transaction_id, committed_at) VALUES (?, ?) "
                           + "ON CONFLICT (transaction_id) DO NOTHING",
                transactionId, Timestamp.from(at)) == 1;
    }

    @Override
    public List<CommitLine> storedCommit(String transactionId) {
        return jdbc.query("SELECT sku, quantity, applied FROM stock_commit_lines "
                          + "WHERE transaction_id = ? ORDER BY sku",
                (rs, i) -> new CommitLine(rs.getString(1), rs.getInt(2), rs.getInt(3)), transactionId);
    }

    @Override
    public Map<String, Integer> lockAndReadStock(List<String> skusAscending) {
        if (skusAscending.isEmpty()) {
            return Map.of();
        }
        // FOR NO KEY UPDATE, not FOR UPDATE: low_stock_alerts has a foreign
        // key to stock, and the FK check takes FOR KEY SHARE on the parent
        // row, which FOR UPDATE would conflict with (Week 3 measured that as a
        // collapse from 669 to 3 tx/s). FOR NO KEY UPDATE still excludes every
        // other commit from the same row, which is all the invariant needs.
        //
        // ORDER BY sku must stay even when EXPLAIN shows no Sort node: a
        // bitmap heap scan would return rows in physical order, and then the
        // lock order -- the deadlock-freedom argument -- would depend on the
        // plan.
        String placeholders = String.join(",", Collections.nCopies(skusAscending.size(), "?"));
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query("SELECT sku, qty FROM stock WHERE sku IN (" + placeholders + ") "
                   + "ORDER BY sku FOR NO KEY UPDATE",
                rs -> { out.put(rs.getString(1), rs.getInt(2)); }, skusAscending.toArray());
        return out;
    }

    @Override
    public void applyStock(Map<String, Integer> newQtyBySku) {
        if (newQtyBySku.isEmpty()) {
            return;
        }
        // One UPDATE for the whole basket, driven by a VALUES list.
        StringBuilder sql = new StringBuilder("UPDATE stock SET qty = v.qty FROM (VALUES ");
        List<Object> args = new ArrayList<>(newQtyBySku.size() * 2);
        boolean first = true;
        for (Map.Entry<String, Integer> e : newQtyBySku.entrySet()) {
            // the first row needs casts so Postgres knows the VALUES column types
            sql.append(first ? "(?::varchar,?::integer)" : ",(?,?)");
            first = false;
            args.add(e.getKey());
            args.add(e.getValue());
        }
        sql.append(") AS v(sku, qty) WHERE stock.sku = v.sku");
        jdbc.update(sql.toString(), args.toArray());
    }

    @Override
    public void insertLines(String transactionId, List<CommitLine> lines) {
        if (lines.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder(
                "INSERT INTO stock_commit_lines (transaction_id, sku, quantity, applied) VALUES ");
        List<Object> args = new ArrayList<>(lines.size() * 4);
        for (int i = 0; i < lines.size(); i++) {
            sql.append(i == 0 ? "(?,?,?,?)" : ",(?,?,?,?)");
            CommitLine l = lines.get(i);
            args.add(transactionId); args.add(l.sku()); args.add(l.quantity()); args.add(l.applied());
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    @Override
    public void insertCrossings(List<String> skus, Instant at) {
        if (skus.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO low_stock_alerts (sku, triggered_at) VALUES ");
        List<Object> args = new ArrayList<>(skus.size() * 2);
        for (int i = 0; i < skus.size(); i++) {
            sql.append(i == 0 ? "(?,?)" : ",(?,?)");
            args.add(skus.get(i));
            args.add(Timestamp.from(at));
        }
        // A SKU crosses once and its row is locked while we decide, so a
        // duplicate is impossible; DO NOTHING is belt-and-braces against one
        // turning a good commit into a failed one.
        sql.append(" ON CONFLICT (sku) DO NOTHING");
        jdbc.update(sql.toString(), args.toArray());
    }

    @Override
    public List<LowStockRow> findBelowThreshold(int threshold) {
        // LEFT JOIN: under an ad-hoc ?threshold= above the configured one, a
        // SKU can qualify without ever having had an alert row (triggered_at
        // is then NULL and the caller falls back to "now"). Strictly "<".
        return jdbc.query("SELECT s.sku, s.qty, a.triggered_at FROM stock s "
                          + "LEFT JOIN low_stock_alerts a ON a.sku = s.sku "
                          + "WHERE s.qty < ? ORDER BY s.qty ASC, s.sku ASC",
                (rs, i) -> {
                    Timestamp t = rs.getTimestamp(3);
                    return new LowStockRow(rs.getString(1), rs.getInt(2), t == null ? null : t.toInstant());
                }, threshold);
    }
}

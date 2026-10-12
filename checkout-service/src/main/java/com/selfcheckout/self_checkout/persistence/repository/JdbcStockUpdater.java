package com.selfcheckout.self_checkout.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// CHECKOUT-SERVICE, persistence layer.
@Repository
public class JdbcStockUpdater implements StockUpdater {

    private final JdbcTemplate jdbc;

    public JdbcStockUpdater(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Integer> lockAndReadStock(List<String> skusAscending) {
        if (skusAscending.isEmpty()) {
            return Map.of();
        }
        // FOR NO KEY UPDATE, not FOR UPDATE. This is NOT a detail.
        //
        // scan_log, transaction_items and low_stock_alerts all have a foreign
        // key to items, and Postgres takes a FOR KEY SHARE lock on the parent
        // row whenever it checks one. FOR KEY SHARE is compatible with
        // FOR NO KEY UPDATE but CONFLICTS with FOR UPDATE.
        //
        // Hibernate's PESSIMISTIC_WRITE emitted "for no key update" here, so
        // checkouts and the analytics scan_log writer never contended. Writing
        // plain "FOR UPDATE" instead -- which looks like the obvious choice --
        // made every scan_log batch insert fight the checkout holding those
        // rows, and the two deadlocked: throughput fell from 669 to 3 tx/s
        // with 20% of completes timing out. FOR NO KEY UPDATE still excludes
        // other checkouts from the same row, which is all the stock invariant
        // needs.
        //
        // ORDER BY sku MUST STAY, even though EXPLAIN often shows no Sort node.
        //
        // With "sku = ANY(...)" Postgres usually picks an index scan on the
        // primary key, which already yields rows in SKU order, so the planner
        // drops the Sort as redundant and LockRows sits straight on top. But
        // the planner is free to choose a bitmap heap scan instead -- and that
        // returns rows in PHYSICAL order, not key order. Without this clause
        // the lock order would then depend on the plan, two checkouts could
        // take the same two rows in opposite orders, and the whole
        // deadlock-freedom argument quietly stops holding.
        String placeholders = String.join(",", java.util.Collections.nCopies(skusAscending.size(), "?"));
        String sql = "SELECT sku, stock FROM items WHERE sku IN (" + placeholders + ") "
                   + "ORDER BY sku FOR NO KEY UPDATE";
        Map<String, Integer> out = new LinkedHashMap<>();
        jdbc.query(sql, rs -> { out.put(rs.getString(1), rs.getInt(2)); }, skusAscending.toArray());
        return out;
    }

    @Override
    public void applyStock(Map<String, Integer> newStockBySku) {
        if (newStockBySku.isEmpty()) {
            return;
        }
        // One UPDATE for the whole basket, driven by a VALUES list:
        //   UPDATE items SET stock = v.stock FROM (VALUES (?,?),(?,?)) AS v(sku,stock)
        //   WHERE items.sku = v.sku
        // Note it writes ONLY stock. Hibernate used to rewrite name and price
        // on every single stock change, because it updates whole rows.
        StringBuilder sql = new StringBuilder("UPDATE items SET stock = v.stock FROM (VALUES ");
        List<Object> args = new ArrayList<>(newStockBySku.size() * 2);
        boolean first = true;
        for (Map.Entry<String, Integer> e : newStockBySku.entrySet()) {
            // the first row needs casts so Postgres knows the VALUES column types
            sql.append(first ? "(?::varchar,?::integer)" : ",(?,?)");
            first = false;
            args.add(e.getKey());
            args.add(e.getValue());
        }
        sql.append(") AS v(sku, stock) WHERE items.sku = v.sku");
        jdbc.update(sql.toString(), args.toArray());
    }
}

package com.selfcheckout.self_checkout.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// CHECKOUT-SERVICE, persistence layer.
@Repository
public class JdbcLowStockAlertWriter implements LowStockAlertWriter {

    private final JdbcTemplate jdbc;

    public JdbcLowStockAlertWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertCrossings(List<String> skus, Instant triggeredAt) {
        if (skus.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO low_stock_alerts (sku, triggered_at) VALUES ");
        List<Object> args = new ArrayList<>(skus.size() * 2);
        for (int i = 0; i < skus.size(); i++) {
            sql.append(i == 0 ? "(?,?)" : ",(?,?)");
            args.add(skus.get(i));
            args.add(Timestamp.from(triggeredAt));
        }
        // The crossing test already guarantees a SKU crosses once, and the
        // item row is locked while we decide, so two checkouts cannot both
        // see the same crossing. DO NOTHING is belt-and-braces against a
        // duplicate turning a successful checkout into a failed one.
        sql.append(" ON CONFLICT (sku) DO NOTHING");
        jdbc.update(sql.toString(), args.toArray());
    }
}

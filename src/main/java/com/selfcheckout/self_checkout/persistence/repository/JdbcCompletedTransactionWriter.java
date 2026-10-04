package com.selfcheckout.self_checkout.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// PERSISTENCE layer (database access).
//
// Plain JDBC rather than JPA, for one concrete reason: these rows are brand
// new, but their primary keys are assigned by us (a UUID, and a composite
// (transaction_id, sku)). Spring Data's save() sees a non-null id, decides the
// entity might already exist, and issues a merge -- a SELECT before every
// INSERT. For a 9-line basket that is 10 pointless round trips. A direct
// INSERT has none.
@Repository
public class JdbcCompletedTransactionWriter implements CompletedTransactionWriter {

    private final JdbcTemplate jdbc;

    public JdbcCompletedTransactionWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertHeader(String id, String stationId, int itemCount, BigDecimal runningTotal,
                             Instant startedAt, Instant completedAt) {
        jdbc.update("INSERT INTO transactions "
                        + "(id, station_id, status, item_count, running_total, started_at, completed_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                id, stationId, "COMPLETED", itemCount, runningTotal,
                Timestamp.from(startedAt), Timestamp.from(completedAt));
    }

    @Override
    public void insertLines(String id, List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        // One multi-row INSERT for every line, instead of one statement each.
        StringBuilder sql = new StringBuilder(
                "INSERT INTO transaction_items (transaction_id, sku, quantity, applied_quantity) VALUES ");
        List<Object> args = new ArrayList<>(lines.size() * 4);
        for (int i = 0; i < lines.size(); i++) {
            sql.append(i == 0 ? "(?,?,?,?)" : ",(?,?,?,?)");
            Line l = lines.get(i);
            args.add(id); args.add(l.sku()); args.add(l.quantity()); args.add(l.appliedQuantity());
        }
        jdbc.update(sql.toString(), args.toArray());
    }
}

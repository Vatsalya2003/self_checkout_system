package com.selfcheckout.self_checkout.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// CHECKOUT-SERVICE, persistence layer. Plain JDBC on checkout_db, for the
// same reason as Week 3: our primary keys are assigned by us, so Spring
// Data's save() would SELECT before every INSERT.
//
// Each method is one short local transaction. Crucially, none of them is open
// while checkout waits for inventory over HTTP: tx A commits, THEN the call
// is made, THEN tx B starts. Holding a connection across the network call
// would tie up the pool for as long as inventory's row-lock queue is.
@Repository
public class JdbcSaleWriter implements SaleWriter {

    private final JdbcTemplate jdbc;

    public JdbcSaleWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void insertPending(PendingSale sale) {
        jdbc.update("INSERT INTO transactions "
                        + "(id, station_id, status, item_count, running_total, started_at, completed_at) "
                        + "VALUES (?,?,'PENDING',?,?,?,?)",
                sale.id(), sale.stationId(), sale.itemCount(), sale.runningTotal(),
                Timestamp.from(sale.startedAt()), Timestamp.from(sale.completedAt()));
        // One multi-row INSERT for every line; applied_quantity stays NULL
        // until inventory says how much it actually took.
        StringBuilder sql = new StringBuilder(
                "INSERT INTO transaction_items (transaction_id, sku, quantity) VALUES ");
        List<Object> args = new ArrayList<>(sale.lines().size() * 3);
        boolean first = true;
        for (Map.Entry<String, Integer> l : sale.lines().entrySet()) {
            sql.append(first ? "(?,?,?)" : ",(?,?,?)");
            first = false;
            args.add(sale.id()); args.add(l.getKey()); args.add(l.getValue());
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    @Override
    @Transactional
    public void complete(String id, Map<String, Integer> appliedBySku) {
        // One UPDATE for every line, driven by a VALUES list (as the Week 3
        // stock update was).
        StringBuilder sql = new StringBuilder("UPDATE transaction_items SET applied_quantity = v.applied FROM (VALUES ");
        List<Object> args = new ArrayList<>(appliedBySku.size() * 2 + 1);
        boolean first = true;
        for (Map.Entry<String, Integer> e : appliedBySku.entrySet()) {
            sql.append(first ? "(?::varchar,?::integer)" : ",(?,?)");
            first = false;
            args.add(e.getKey()); args.add(e.getValue());
        }
        sql.append(") AS v(sku, applied) WHERE transaction_items.transaction_id = ? "
                   + "AND transaction_items.sku = v.sku");
        args.add(id);
        jdbc.update(sql.toString(), args.toArray());
        jdbc.update("UPDATE transactions SET status = 'COMPLETED' WHERE id = ? AND status = 'PENDING'", id);
    }

    @Override
    @Transactional
    public void deletePending(String id) {
        // Only ever a PENDING row: a COMPLETED sale is never deleted.
        jdbc.update("DELETE FROM transaction_items WHERE transaction_id = ? "
                            + "AND EXISTS (SELECT 1 FROM transactions WHERE id = ? AND status = 'PENDING')", id, id);
        jdbc.update("DELETE FROM transactions WHERE id = ? AND status = 'PENDING'", id);
    }

    @Override
    public List<String> pendingIds() {
        return jdbc.queryForList("SELECT id FROM transactions WHERE status = 'PENDING' ORDER BY completed_at",
                String.class);
    }

    @Override
    @Transactional(readOnly = true)
    public PendingSale loadPending(String id) {
        List<PendingSale> header = jdbc.query(
                "SELECT id, station_id, item_count, running_total, started_at, completed_at "
                        + "FROM transactions WHERE id = ? AND status = 'PENDING'",
                (rs, i) -> new PendingSale(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), null), id);
        if (header.isEmpty()) {
            return null;
        }
        Map<String, Integer> lines = new LinkedHashMap<>();
        jdbc.query("SELECT sku, quantity FROM transaction_items WHERE transaction_id = ? ORDER BY sku",
                rs -> { lines.put(rs.getString(1), rs.getInt(2)); }, id);
        PendingSale h = header.get(0);
        return new PendingSale(h.id(), h.stationId(), h.itemCount(), h.runningTotal(),
                h.startedAt(), h.completedAt(), lines);
    }
}

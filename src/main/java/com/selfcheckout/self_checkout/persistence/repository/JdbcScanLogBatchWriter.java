package com.selfcheckout.self_checkout.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

// PERSISTENCE layer (database access).
//
// Plain JDBC rather than JPA on purpose. scan_log rows are write-only facts:
// nothing ever loads them as managed entities, so Hibernate's dirty checking
// and first-level cache would be pure overhead. One multi-row INSERT of a few
// hundred rows replaces a few hundred single-row INSERTs.
@Repository
public class JdbcScanLogBatchWriter implements ScanLogBatchWriter {

    private final JdbcTemplate jdbcTemplate;

    public JdbcScanLogBatchWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void insertBatch(List<ScanLogRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        // Build "VALUES (?,?,?),(?,?,?),..." once for this batch size.
        // 3 parameters per row, so even a 1000-row batch is 3000 bind
        // parameters -- far below Postgres' 65535 limit.
        StringBuilder sql = new StringBuilder("INSERT INTO scan_log (seq, sku, scanned_at) VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            sql.append(i == 0 ? "(?,?,?)" : ",(?,?,?)");
        }
        List<Object> args = new ArrayList<>(rows.size() * 3);
        for (ScanLogRow r : rows) {
            args.add(r.seq());
            args.add(r.sku());
            args.add(Timestamp.from(r.scannedAt()));
        }
        jdbcTemplate.update(sql.toString(), args.toArray());
    }
}

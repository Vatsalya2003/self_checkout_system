package com.selfcheckout.self_checkout.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

// ANALYTICS-SERVICE, persistence layer. Plain JDBC on analytics_db.
//
// Week 5: checkout-service can no longer write into analytics' tables, so the
// INSERT that used to run in checkout (Week 4's JdbcScanLogBatchWriter) runs
// here, on the batches checkout pushes to POST /internal/scans.
@Repository
public class JdbcScanStore implements ScanStore {

    private final JdbcTemplate jdbc;

    public JdbcScanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int insertNew(List<ScanLogRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        // 3 parameters per row: a 1,000-row batch is 3,000 bind parameters,
        // far below Postgres' 65,535 limit.
        StringBuilder sql = new StringBuilder("INSERT INTO scan_log (seq, sku, scanned_at) VALUES ");
        List<Object> args = new ArrayList<>(rows.size() * 3);
        for (int i = 0; i < rows.size(); i++) {
            sql.append(i == 0 ? "(?,?,?)" : ",(?,?,?)");
            ScanLogRow r = rows.get(i);
            args.add(r.seq());
            args.add(r.sku());
            args.add(Timestamp.from(r.scannedAt()));
        }
        // A batch checkout retries after a lost answer arrives a second time.
        // The primary key absorbs it: a scan can never be counted twice.
        sql.append(" ON CONFLICT (seq) DO NOTHING");
        return jdbc.update(sql.toString(), args.toArray());
    }

    @Override
    public long maxSeq() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM scan_log", Long.class);
        return max == null ? 0 : max;
    }
}

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
// here. Week 6: the batches arrive as ScansRecorded events, and the seqs are
// assigned here too (ScanIngestService), so this class also owns the
// numbering lock and the batch-id table that makes redelivery harmless.
@Repository
public class JdbcScanStore implements ScanStore {

    // Any fixed number: the key of the transaction-level advisory lock that
    // serializes numbering. Released automatically at commit or rollback.
    private static final long NUMBERING_LOCK = 6_001L;

    private final JdbcTemplate jdbc;

    public JdbcScanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockNumbering() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)::text", String.class, NUMBERING_LOCK);
    }

    @Override
    public long maxSeq() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM scan_log", Long.class);
        return max == null ? 0 : max;
    }

    @Override
    public boolean claimBatch(String batchId, long firstSeq, long lastSeq) {
        return jdbc.update("INSERT INTO scan_batches (batch_id, first_seq, last_seq) VALUES (?, ?, ?) "
                           + "ON CONFLICT (batch_id) DO NOTHING", batchId, firstSeq, lastSeq) == 1;
    }

    @Override
    public void insert(List<ScanLogRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        // 3 parameters per row: a 1,000-row batch is 3,000 bind parameters,
        // far below Postgres' 65,535 limit. No ON CONFLICT any more: the seqs
        // were just assigned above MAX(seq) under the lock, so a conflict
        // would be a bug -- and should fail loudly, not be skipped.
        StringBuilder sql = new StringBuilder("INSERT INTO scan_log (seq, sku, scanned_at) VALUES ");
        List<Object> args = new ArrayList<>(rows.size() * 3);
        for (int i = 0; i < rows.size(); i++) {
            sql.append(i == 0 ? "(?,?,?)" : ",(?,?,?)");
            ScanLogRow r = rows.get(i);
            args.add(r.seq());
            args.add(r.sku());
            args.add(Timestamp.from(r.scannedAt()));
        }
        jdbc.update(sql.toString(), args.toArray());
    }
}

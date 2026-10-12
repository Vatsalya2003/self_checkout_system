package com.selfcheckout.self_checkout.persistence.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

// ANALYTICS-SERVICE, persistence layer.
//
// Plain SELECTs on the scan_log primary key, so every read is an index range
// scan that starts exactly where the last one stopped. Never writes.
//
// Why "seq > last ORDER BY seq" is safe to poll: checkout-service's single
// writer thread commits batch N before it even builds batch N+1, so the
// committed rows are always exactly 1..K with no holes. A reader can therefore
// never see seq K+1 while K is still uncommitted. The poller still checks for
// gaps itself (defence in depth) -- see ScanLogPoller.
@Repository
public class JdbcScanLogReader implements ScanLogReader {

    private static final RowMapper<ScanLogRow> ROW = (rs, n) ->
            new ScanLogRow(rs.getLong("seq"), rs.getString("sku"), rs.getTimestamp("scanned_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcScanLogReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ScanLogRow> after(long afterSeq, int limit) {
        return jdbc.query("SELECT seq, sku, scanned_at FROM scan_log WHERE seq > ? ORDER BY seq LIMIT ?",
                ROW, afterSeq, limit);
    }

    @Override
    public List<ScanLogRow> between(long fromExclusive, long toInclusive) {
        return jdbc.query("SELECT seq, sku, scanned_at FROM scan_log WHERE seq > ? AND seq <= ? ORDER BY seq",
                ROW, fromExclusive, toInclusive);
    }

    @Override
    public long maxSeq() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) FROM scan_log", Long.class);
        return max == null ? 0 : max;
    }
}

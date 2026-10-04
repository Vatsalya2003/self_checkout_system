package com.selfcheckout.self_checkout.persistence.repository;

import com.selfcheckout.self_checkout.persistence.AnalyticsWriterPool;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// PERSISTENCE layer (database access).
//
// Takes the connection itself rather than going through JdbcTemplate, for one
// reason: so the three costs can be told apart. "The sink is slow" is not an
// answer -- waiting for a pooled connection, executing the statement and
// committing are three different problems with three different fixes, and
// before this the numbers were indistinguishable.
@Repository
public class JdbcSnapshotWriter implements SnapshotWriter {

    private final DataSource dataSource;

    private final AtomicLong connectionWait = new AtomicLong();
    private final AtomicLong execute = new AtomicLong();
    private final AtomicLong commit = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();

    // The dedicated analytics pool, NOT the main one. See AnalyticsWriterPool
    // for the measurement that forced this.
    public JdbcSnapshotWriter(AnalyticsWriterPool pool) {
        this.dataSource = pool.dataSource();
    }

    @Override
    public void insertBatch(List<SnapshotRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder(
                "INSERT INTO popular_snapshots (window_start, window_end, computed_at, items_json) VALUES ");
        for (int i = 0; i < rows.size(); i++) {
            sql.append(i == 0 ? "(?,?,?,?)" : ",(?,?,?,?)");
        }

        long t0 = System.nanoTime();
        try (Connection c = dataSource.getConnection()) {
            long t1 = System.nanoTime();              // <- pool wait ends here
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int p = 1;
                for (SnapshotRow r : rows) {
                    ps.setLong(p++, r.windowStart());
                    ps.setLong(p++, r.windowEnd());
                    ps.setTimestamp(p++, Timestamp.from(r.computedAt()));
                    ps.setString(p++, r.itemsJson());
                }
                ps.executeUpdate();
            }
            long t2 = System.nanoTime();              // <- execute ends here
            c.commit();
            long t3 = System.nanoTime();              // <- commit ends here

            connectionWait.addAndGet(t1 - t0);
            execute.addAndGet(t2 - t1);
            commit.addAndGet(t3 - t2);
            batches.incrementAndGet();
        } catch (SQLException e) {
            throw new IllegalStateException("failed writing " + rows.size() + " popular_snapshots rows", e);
        }
    }

    @Override public long connectionWaitNanos() { return connectionWait.get(); }
    @Override public long executeNanos()        { return execute.get(); }
    @Override public long commitNanos()         { return commit.get(); }
    @Override public long batches()             { return batches.get(); }
}

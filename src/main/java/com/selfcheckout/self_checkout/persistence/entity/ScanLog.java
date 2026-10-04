package com.selfcheckout.self_checkout.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;

// PERSISTENCE layer (database access).
/**
 * One row per physical scan, across every station.
 *
 * seq is the global scan sequence. As of Week 3 it is assigned by the
 * pipeline's ingest filter and written by the scan-log writer, NOT by a
 * BIGSERIAL. Only committed scans are numbered, one at a time by one thread,
 * so unlike the old BIGSERIAL the sequence has no gaps.
 *
 * Nothing writes this entity any more -- rows go in through a batched JDBC
 * INSERT. It stays mapped because the windowed aggregate in
 * ScanLogRepository.topInWindow is the brute-force oracle the tests check the
 * in-memory window against.
 */
@Entity
@Table(name = "scan_log")
public class ScanLog {

    @Id
    @Column(name = "seq")        // assigned by the pipeline, not generated
    private Long seq;

    @Column(name = "sku")
    private String sku;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    protected ScanLog() {}

    public ScanLog(long seq, String sku, Instant scannedAt) {
        this.seq = seq;
        this.sku = sku;
        this.scannedAt = scannedAt;
    }

    public Long getSeq() { return seq; }
    public String getSku() { return sku; }
    public Instant getScannedAt() { return scannedAt; }
}

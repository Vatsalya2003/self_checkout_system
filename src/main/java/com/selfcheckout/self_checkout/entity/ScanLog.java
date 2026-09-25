package com.selfcheckout.self_checkout.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One row per physical scan, across every station.
 *
 * seq is a BIGSERIAL, so Postgres hands out a global, monotonically increasing
 * scan number. Phase 4 uses that number as the "global scan sequence" for the
 * popular-items hopping window.
 */
// Watch out: "monotonically increasing" is NOT the same as "no gaps".
// A rolled-back INSERT still burns its sequence number, so numbers can be
// skipped. That's exactly why the window boundary check can't just test
// seq % 500 == 0 -- see PopularItemsService.onScanRecorded().
@Entity
@Table(name = "scan_log")
public class ScanLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // Postgres BIGSERIAL assigns it
    @Column(name = "seq")
    private Long seq;

    @Column(name = "sku")
    private String sku;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    protected ScanLog() {}

    // seq is deliberately not set here -- the database fills it in on INSERT,
    // and Hibernate reads it back so the caller can see which number it got.
    public ScanLog(String sku) {
        this.sku = sku;
        this.scannedAt = Instant.now();
    }

    public Long getSeq() { return seq; }
    public String getSku() { return sku; }
    public Instant getScannedAt() { return scannedAt; }
}

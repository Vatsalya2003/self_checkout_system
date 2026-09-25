package com.selfcheckout.self_checkout.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One computed hopping window, persisted. The assignment requires the top 10
 * to be written to the database, so each recompute appends a row here and
 * GET /analytics/popular-items just reads the newest one back.
 *
 * The ranked items are stored as a small JSON document rather than as ten
 * child rows: it is written once, read whole, and never queried by field.
 */
// Rows are only ever appended, never updated, so "the current window" is
// simply the row with the biggest id (see findFirstByOrderByIdDesc).
@Entity
@Table(name = "popular_snapshots")
public class PopularSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // These two are scan sequence numbers, not timestamps: the window is
    // the scans with seq in (window_start, window_end].
    @Column(name = "window_start")
    private long windowStart;

    @Column(name = "window_end")
    private long windowEnd;

    @Column(name = "computed_at")
    private Instant computedAt;

    @Column(name = "items_json")
    private String itemsJson;   // a JSON array of PopularItem

    protected PopularSnapshot() {}

    public PopularSnapshot(long windowStart, long windowEnd, Instant computedAt, String itemsJson) {
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.computedAt = computedAt;
        this.itemsJson = itemsJson;
    }

    public Long getId() { return id; }
    public long getWindowStart() { return windowStart; }
    public long getWindowEnd() { return windowEnd; }
    public Instant getComputedAt() { return computedAt; }
    public String getItemsJson() { return itemsJson; }
}

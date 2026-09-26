package com.selfcheckout.self_checkout.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;

// PERSISTENCE layer (database access).
/**
 * A recorded low-stock event: "this SKU first went below the threshold at this time".
 *
 * Written once, at the moment of the crossing, from inside the completing
 * transaction while that item's row lock is held. Keeping the row means
 * triggeredAt is the real crossing time rather than the time somebody
 * happened to ask -- which is what "queryable after the fact" requires.
 */
// The SKU is the primary key, so a SKU can have at most one alert row ever.
// That's fine here because stock only ever goes down in this simulation
// (nothing restocks), so a SKU crosses the threshold exactly once.
@Entity
@Table(name = "low_stock_alerts")
public class LowStockAlert {

    @Id
    @Column(name = "sku")
    private String sku;

    @Column(name = "triggered_at")
    private Instant triggeredAt;

    protected LowStockAlert() {}

    public LowStockAlert(String sku, Instant triggeredAt) {
        this.sku = sku;
        this.triggeredAt = triggeredAt;
    }

    public String getSku() { return sku; }
    public Instant getTriggeredAt() { return triggeredAt; }
}

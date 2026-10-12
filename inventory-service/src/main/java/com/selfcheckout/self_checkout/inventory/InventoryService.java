package com.selfcheckout.self_checkout.inventory;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.inventory.result.LowStockEntry;
import com.selfcheckout.self_checkout.inventory.result.LowStockView;
import com.selfcheckout.self_checkout.persistence.repository.LowStockRow;
import com.selfcheckout.self_checkout.persistence.repository.LowStockAlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

// INVENTORY-SERVICE, business layer. Answers "what is running low right now?".
//
// Note this service only READS alerts. Alerts are WRITTEN by checkout-service,
// inside the same database transaction as the payment, because that's the only
// moment stock changes and therefore the only moment a threshold can be crossed.
// The two services never call each other: the low_stock_alerts table in the
// shared database is the whole connection between them.
@Service
public class InventoryService {

    private final LowStockAlertRepository lowStockAlertRepository;
    private final SelfCheckoutProperties properties;

    public InventoryService(LowStockAlertRepository lowStockAlertRepository,
                            SelfCheckoutProperties properties) {
        this.lowStockAlertRepository = lowStockAlertRepository;
        this.properties = properties;
    }

    /**
     * @param thresholdOverride optional ?threshold=, applies to this query only
     *                          and is never persisted
     */
    @Transactional(readOnly = true)
    public LowStockView lowStock(Integer thresholdOverride) {
        // No override -> fall back to selfcheckout.low-stock-threshold (50).
        int threshold = thresholdOverride != null ? thresholdOverride : properties.lowStockThreshold();
        // Captured once so every alert in this response shares one timestamp,
        // and so rows with no recorded crossing can fall back to it.
        Instant generatedAt = Instant.now();
        List<LowStockEntry> alerts = lowStockAlertRepository
                .findBelowThreshold(threshold, generatedAt).stream()
                // the query leaves the threshold out, so stamp it on here
                .map(r -> new LowStockEntry(r.sku(), r.name(), r.currentStock(), threshold, r.triggeredAt()))
                .toList();
        return new LowStockView(threshold, generatedAt, alerts);
    }
}

package com.selfcheckout.self_checkout.inventory;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.inventory.result.LowStockEntry;
import com.selfcheckout.self_checkout.inventory.result.LowStockView;
import com.selfcheckout.self_checkout.persistence.repository.CatalogCache;
import com.selfcheckout.self_checkout.persistence.repository.StockLedger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

// INVENTORY-SERVICE, business layer. Answers "what is running low right now?".
//
// Week 5: inventory owns stock and the low-stock alerts in its own database,
// and WRITES them too (StockCommitService). The one thing it does not own is
// item names -- those come from catalog-service, loaded once over HTTP
// (HttpCatalogCache), because inventory may not open catalog_db.
@Service
public class InventoryService {

    private final StockLedger ledger;
    private final CatalogCache catalog;
    private final SelfCheckoutProperties properties;

    public InventoryService(StockLedger ledger, CatalogCache catalog, SelfCheckoutProperties properties) {
        this.ledger = ledger;
        this.catalog = catalog;
        this.properties = properties;
    }

    /**
     * @param thresholdOverride optional ?threshold=, applies to this query only
     *                          and is never persisted
     */
    public LowStockView lowStock(Integer thresholdOverride) {
        // No override -> fall back to selfcheckout.low-stock-threshold (50).
        int threshold = thresholdOverride != null ? thresholdOverride : properties.lowStockThreshold();
        // Captured once so every alert in this response shares one timestamp,
        // and so rows with no recorded crossing can fall back to it.
        Instant generatedAt = Instant.now();
        List<LowStockEntry> alerts = ledger.findBelowThreshold(threshold).stream()
                .map(r -> new LowStockEntry(r.sku(), catalog.nameOf(r.sku()), r.qty(), threshold,
                        r.triggeredAt() != null ? r.triggeredAt() : generatedAt))
                .toList();
        return new LowStockView(threshold, generatedAt, alerts);
    }
}

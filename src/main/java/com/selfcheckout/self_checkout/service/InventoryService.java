package com.selfcheckout.self_checkout.service;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.dto.LowStockAlertDto;
import com.selfcheckout.self_checkout.dto.LowStockResponse;
import com.selfcheckout.self_checkout.repository.LowStockRow;
import com.selfcheckout.self_checkout.repository.LowStockAlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

// SERVICE layer. Answers "what is running low right now?".
//
// Note this service only READS alerts. Alerts are WRITTEN by
// TransactionService.complete(), because that's the only moment stock changes
// and therefore the only moment a threshold can be crossed.
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
    public LowStockResponse lowStock(Integer thresholdOverride) {
        // No override -> fall back to selfcheckout.low-stock-threshold (50).
        int threshold = thresholdOverride != null ? thresholdOverride : properties.lowStockThreshold();
        // Captured once so every alert in this response shares one timestamp,
        // and so rows with no recorded crossing can fall back to it.
        Instant generatedAt = Instant.now();
        List<LowStockAlertDto> alerts = lowStockAlertRepository
                .findBelowThreshold(threshold, generatedAt).stream()
                // the query leaves the threshold out, so stamp it on here
                .map(r -> new LowStockAlertDto(r.sku(), r.name(), r.currentStock(), threshold, r.triggeredAt()))
                .toList();
        return new LowStockResponse(threshold, generatedAt, alerts);
    }
}

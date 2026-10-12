package com.selfcheckout.self_checkout.inventory.result;

import java.time.Instant;
import java.util.List;

// INVENTORY-SERVICE, business layer, result record. The answer to "what is running low?".
public record LowStockView(int threshold, Instant generatedAt, List<LowStockEntry> alerts) {}

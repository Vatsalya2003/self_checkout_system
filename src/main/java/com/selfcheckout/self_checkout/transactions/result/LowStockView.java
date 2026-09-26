package com.selfcheckout.self_checkout.transactions.result;

import java.time.Instant;
import java.util.List;

// TRANSACTIONS layer, result record. The answer to "what is running low?".
public record LowStockView(int threshold, Instant generatedAt, List<LowStockEntry> alerts) {}

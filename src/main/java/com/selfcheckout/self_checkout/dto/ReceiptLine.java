package com.selfcheckout.self_checkout.dto;

import java.math.BigDecimal;

// One printed line on the receipt.
// IMPORTANT: quantity here is the SCANNED quantity, not the amount we managed
// to subtract from stock. If an item sold out mid-run we still print what the
// customer is physically holding. The "actually subtracted" number lives in
// transaction_items.applied_quantity and is only used for the stock invariant.
public record ReceiptLine(String sku, String name, BigDecimal unitPrice, int quantity) {}

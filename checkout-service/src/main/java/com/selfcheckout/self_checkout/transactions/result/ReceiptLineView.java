package com.selfcheckout.self_checkout.transactions.result;

import java.math.BigDecimal;

// TRANSACTIONS layer, result record. One line of the receipt.
// quantity is what was SCANNED, not what we managed to subtract from stock --
// the customer is holding those items either way.
public record ReceiptLineView(String sku, String name, BigDecimal unitPrice, int quantity) {}

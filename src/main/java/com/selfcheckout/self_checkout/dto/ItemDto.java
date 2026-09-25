package com.selfcheckout.self_checkout.dto;

import java.math.BigDecimal;

// One catalog item as the customer sees it (GET /items).
// Note there's no "stock" here on purpose: the catalog endpoint is not an
// inventory endpoint, and the spec doesn't ask for stock.
// BigDecimal not double, because money should never be a float.
public record ItemDto(String sku, String name, BigDecimal price) {}

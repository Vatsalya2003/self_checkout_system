package com.selfcheckout.self_checkout.dto;

import java.util.List;

// Response body for GET /items.
// The spec wraps the list in an object ({"items":[...]}) instead of returning
// a bare array, so we need this little wrapper record.
public record CatalogResponse(List<ItemDto> items) {}

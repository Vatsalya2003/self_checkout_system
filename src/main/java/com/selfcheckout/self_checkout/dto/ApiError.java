package com.selfcheckout.self_checkout.dto;

// The one and only error shape this API ever returns.
// The OpenAPI spec fixes these two field names, so don't rename them.
// GlobalExceptionHandler is what actually builds these.
public record ApiError(String error, String message) {}

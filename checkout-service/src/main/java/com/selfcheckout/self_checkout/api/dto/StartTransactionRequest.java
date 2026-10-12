package com.selfcheckout.self_checkout.api.dto;

// CHECKOUT-SERVICE, API layer, wire shape (matches the OpenAPI spec).
// Request body for POST /transactions.
// If stationId is missing or blank the service throws a 400 INVALID_REQUEST.
public record StartTransactionRequest(String stationId) {}

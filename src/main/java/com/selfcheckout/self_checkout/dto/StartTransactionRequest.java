package com.selfcheckout.self_checkout.dto;

// Request body for POST /transactions.
// If stationId is missing or blank the service throws a 400 INVALID_REQUEST.
public record StartTransactionRequest(String stationId) {}

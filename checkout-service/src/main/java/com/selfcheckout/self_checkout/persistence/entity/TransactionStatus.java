package com.selfcheckout.self_checkout.persistence.entity;

// CHECKOUT-SERVICE, persistence layer.
// The states a SAVED sale can be in. Stored as text, so rows stay readable in psql.
//
// Week 5: PENDING is new and internal. It marks a payment whose stock commit
// may be in progress at inventory-service (the saga's tx A was written, tx B
// not yet). GET /transactions/{id} reports it as OPEN -- the contract has no
// "being paid" status, and it has not been paid yet. An unpaid basket that
// never reached payment is still memory-only and never appears here.
// CANCELLED exists because the spec lists it; nothing in this build sets it.
public enum TransactionStatus { OPEN, PENDING, COMPLETED, CANCELLED }

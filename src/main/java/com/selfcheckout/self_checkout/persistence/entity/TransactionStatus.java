package com.selfcheckout.self_checkout.persistence.entity;

// PERSISTENCE layer (database access).
// The three states a basket can be in. Stored in the DB as text ("OPEN"),
// not as a number, so the rows stay readable in psql -- see @Enumerated on
// Transaction.status.
// CANCELLED exists because the spec lists it; nothing in this build sets it.
public enum TransactionStatus { OPEN, COMPLETED, CANCELLED }

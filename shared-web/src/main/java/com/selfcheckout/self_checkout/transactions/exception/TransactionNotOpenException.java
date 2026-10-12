package com.selfcheckout.self_checkout.transactions.exception;

// TRANSACTIONS layer. The basket was already completed or cancelled.
// The api layer maps this to 409 TRANSACTION_NOT_OPEN.
public class TransactionNotOpenException extends DomainException {

    public TransactionNotOpenException(String message) {
        super(message);
    }
}

package com.selfcheckout.self_checkout.transactions.exception;

// TRANSACTIONS layer. Cannot pay for a basket with nothing scanned into it.
// The api layer maps this to 409 TRANSACTION_EMPTY.
public class EmptyTransactionException extends DomainException {

    public EmptyTransactionException(String message) {
        super(message);
    }
}

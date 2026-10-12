package com.selfcheckout.self_checkout.transactions.exception;

// TRANSACTIONS layer. No basket exists with that id.
// The api layer maps this to 404 TRANSACTION_NOT_FOUND.
public class TransactionNotFoundException extends DomainException {

    public TransactionNotFoundException(String message) {
        super(message);
    }
}

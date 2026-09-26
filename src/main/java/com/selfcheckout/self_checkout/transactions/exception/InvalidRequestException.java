package com.selfcheckout.self_checkout.transactions.exception;

// TRANSACTIONS layer. The caller left out something required.
// The api layer maps this to 400 INVALID_REQUEST.
public class InvalidRequestException extends DomainException {

    public InvalidRequestException(String message) {
        super(message);
    }
}

package com.selfcheckout.self_checkout.inventory.exception;

import com.selfcheckout.self_checkout.transactions.exception.DomainException;

// INVENTORY-SERVICE. A stock commit arrived for a transaction id that was
// already committed, but with different lines. A genuine retry always sends
// the same lines (checkout saved them before the first attempt), so this means
// a bug; it is refused with 409 rather than guessed at.
public class CommitConflictException extends DomainException {
    public CommitConflictException(String message) {
        super(message);
    }
}

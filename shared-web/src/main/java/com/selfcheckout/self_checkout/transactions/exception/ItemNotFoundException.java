package com.selfcheckout.self_checkout.transactions.exception;

// TRANSACTIONS layer. No catalog item with that SKU (also covers a blank or missing SKU).
// The api layer maps this to 404 ITEM_NOT_FOUND.
public class ItemNotFoundException extends DomainException {

    public ItemNotFoundException(String message) {
        super(message);
    }
}

package com.selfcheckout.self_checkout.transactions.exception;

// SHARED-WEB library. "Another service this request needs is not answering."
//
// New in Week 5, when the services became microservices that call each other:
// a payment needs inventory-service, and every service needs catalog-service
// once at startup. The api layer maps this to 503 SERVICE_UNAVAILABLE with a
// Retry-After header.
//
// Why 503 and not one of the contract's 4xx codes: the OpenAPI spec has no
// 5xx response at all, but 409 would claim the basket is in the wrong state,
// which is not true -- the request was fine, a dependency was down. 503 is
// HTTP's own word for "temporarily unavailable, try again", and it only ever
// appears while a service is stopped, never in a normal run.
public class ServiceUnavailableException extends DomainException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}

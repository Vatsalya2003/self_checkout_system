package com.selfcheckout.self_checkout.transactions.exception;

// SHARED-WEB library. Base type for "the business rules say no".
// Lives next to GlobalExceptionHandler so every service maps the same error to
// the same status and code; the package name is unchanged from Week 3.
//
// Week 1 had a single ApiException that carried an HttpStatus, which meant the
// service layer had to know it was being served over HTTP. These replace it:
// the business layer says WHAT went wrong, and the api layer decides what that
// looks like on the wire. Swap HTTP for gRPC tomorrow and none of this changes.
//
// Unchecked (extends RuntimeException) for two reasons, same as before:
//   1. no "throws" clauses on every method signature
//   2. Spring only auto-rolls-back a @Transactional method on unchecked
//      exceptions, and a failed rule must roll back
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}

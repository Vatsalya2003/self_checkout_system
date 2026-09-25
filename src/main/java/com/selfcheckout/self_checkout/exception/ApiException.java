package com.selfcheckout.self_checkout.exception;

import org.springframework.http.HttpStatus;

// Our own exception type: carries the HTTP status and the machine-readable
// error code along with the message, so the service layer can decide the
// whole response without knowing anything about HTTP plumbing.
//
// Extends RuntimeException (unchecked) on purpose -- two reasons:
//  1. no "throws" clauses cluttering every method signature
//  2. Spring only rolls a @Transactional method back automatically on
//     unchecked exceptions, and we DO want a rollback when one of these flies
public class ApiException extends RuntimeException {

    private final HttpStatus status;   // e.g. 404 NOT_FOUND
    private final String code;         // e.g. "TRANSACTION_NOT_FOUND"

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}

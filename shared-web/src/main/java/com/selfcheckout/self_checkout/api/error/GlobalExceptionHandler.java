package com.selfcheckout.self_checkout.api.error;

import com.selfcheckout.self_checkout.transactions.exception.EmptyTransactionException;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import com.selfcheckout.self_checkout.transactions.exception.ItemNotFoundException;
import com.selfcheckout.self_checkout.transactions.exception.TransactionNotFoundException;
import com.selfcheckout.self_checkout.transactions.exception.TransactionNotOpenException;
import com.selfcheckout.self_checkout.transactions.exception.ServiceUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// SHARED-WEB library (API layer). The single place where a business failure
// becomes an HTTP answer -- shared by all four services, so a 404 or 409 looks
// byte-for-byte the same whichever service sent it. It was one class in the
// single application; making it a library is what keeps that true now that
// there are four.
//
// This is the piece that lets the layers below stay HTTP-free. The business
// layer throws "the transaction wasn't open"; this class decides that means
// 409 with the code TRANSACTION_NOT_OPEN. Every status and code below is
// identical to Week 1 -- only the exception types changed.
@RestControllerAdvice   // "applies to every controller"
public class GlobalExceptionHandler {

    // ---- 404s ----

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ApiError> handleTransactionNotFound(TransactionNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ItemNotFoundException.class)
    public ResponseEntity<ApiError> handleItemNotFound(ItemNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", e.getMessage());
    }

    // ---- 409s ----

    @ExceptionHandler(TransactionNotOpenException.class)
    public ResponseEntity<ApiError> handleNotOpen(TransactionNotOpenException e) {
        return build(HttpStatus.CONFLICT, "TRANSACTION_NOT_OPEN", e.getMessage());
    }

    @ExceptionHandler(EmptyTransactionException.class)
    public ResponseEntity<ApiError> handleEmpty(EmptyTransactionException e) {
        return build(HttpStatus.CONFLICT, "TRANSACTION_EMPTY", e.getMessage());
    }

    // ---- 503 (Week 5) ----
    // A service this request depends on is down. Retry-After: 1 tells a
    // well-behaved client to try again in a second, not hammer us.
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleUnavailable(ServiceUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(new ApiError("SERVICE_UNAVAILABLE", e.getMessage()));
    }

    // ---- 400s ----

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    // Body missing or not valid JSON at all.
    // Spring throws this before our controller method even runs, so no
    // business code is involved -- that's why it needs its own handler.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleBadJson(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is missing or malformed");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}

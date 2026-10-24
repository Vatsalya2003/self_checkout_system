package com.selfcheckout.self_checkout.api;

import com.selfcheckout.self_checkout.api.error.ApiError;
import com.selfcheckout.self_checkout.inventory.exception.CommitConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// INVENTORY-SERVICE, API layer. The one error that only the internal stock
// endpoint can produce; every other error goes through shared-web's
// GlobalExceptionHandler, so it looks the same from every service.
@RestControllerAdvice
public class InternalErrorHandler {

    @ExceptionHandler(CommitConflictException.class)
    public ResponseEntity<ApiError> handleConflict(CommitConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("COMMIT_CONFLICT", e.getMessage()));
    }
}

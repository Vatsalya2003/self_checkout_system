package com.selfcheckout.self_checkout.exception;

import com.selfcheckout.self_checkout.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// One place that turns exceptions into the spec's error JSON, so no controller
// needs a try/catch. This is why the controllers are so short.
@RestControllerAdvice   // "applies to every controller"
public class GlobalExceptionHandler {

    // Our own expected errors (400, 404, 409...)
    // The status and code both come from the exception the service threw.
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ApiError(e.getCode(), e.getMessage()));
    }

    // Body missing or not valid JSON at all.
    // Spring throws this before our controller method even runs, so the
    // service never sees it -- that's why it needs its own handler.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleBadJson(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_REQUEST", "Request body is missing or malformed"));
    }
}

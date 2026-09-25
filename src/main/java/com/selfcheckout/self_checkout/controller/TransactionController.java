package com.selfcheckout.self_checkout.controller;

import com.selfcheckout.self_checkout.dto.Receipt;
import com.selfcheckout.self_checkout.dto.ScanItemRequest;
import com.selfcheckout.self_checkout.dto.ScanResult;
import com.selfcheckout.self_checkout.dto.StartTransactionRequest;
import com.selfcheckout.self_checkout.dto.TransactionResponse;
import com.selfcheckout.self_checkout.service.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// CONTROLLER layer. The three endpoints a checkout station actually uses,
// plus a debug one.
//
// There is no try/catch anywhere in here: the service throws ApiException and
// GlobalExceptionHandler turns it into the right status + JSON body.
@RestController
@RequestMapping("/transactions")   // every method below starts with /transactions
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    // Returns ResponseEntity (not a bare record) only because the spec wants
    // 201 Created here, and the default for a @RestController method is 200.
    @PostMapping
    public ResponseEntity<TransactionResponse> start(@RequestBody StartTransactionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.start(req.stationId()));
    }

    // One call = one unit scanned. @RequestBody parses the JSON into the record;
    // if the body is missing or malformed Spring throws before we get here and
    // GlobalExceptionHandler answers 400 INVALID_REQUEST.
    @PostMapping("/{transactionId}/items")
    public ScanResult scan(@PathVariable String transactionId, @RequestBody ScanItemRequest req) {
        return transactionService.scan(transactionId, req.sku());
    }

    // No request body: paying takes no extra input in this simulation.
    @PostMapping("/{transactionId}/complete")
    public Receipt complete(@PathVariable String transactionId) {
        return transactionService.complete(transactionId);
    }

    // Debug / instructor endpoint; the load client does not use it.
    @GetMapping("/{transactionId}")
    public TransactionResponse get(@PathVariable String transactionId) {
        return transactionService.get(transactionId);
    }
}

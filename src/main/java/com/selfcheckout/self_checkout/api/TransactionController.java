package com.selfcheckout.self_checkout.api;

import com.selfcheckout.self_checkout.api.dto.Receipt;
import com.selfcheckout.self_checkout.api.dto.ReceiptLine;
import com.selfcheckout.self_checkout.api.dto.ScanItemRequest;
import com.selfcheckout.self_checkout.api.dto.ScanResult;
import com.selfcheckout.self_checkout.api.dto.StartTransactionRequest;
import com.selfcheckout.self_checkout.api.dto.TransactionResponse;
import com.selfcheckout.self_checkout.transactions.TransactionService;
import com.selfcheckout.self_checkout.transactions.result.ReceiptView;
import com.selfcheckout.self_checkout.transactions.result.ScanOutcome;
import com.selfcheckout.self_checkout.transactions.result.TransactionView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// API layer. The three endpoints a checkout station actually uses, plus a
// debug one.
//
// Two jobs only: translate HTTP into a service call, and translate the
// service's domain result into the spec's JSON shape. No business rules, and
// no try/catch -- the service throws a domain exception and
// GlobalExceptionHandler turns it into the right status and error code.
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
        TransactionView view = transactionService.start(req.stationId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(view));
    }

    // One call = one unit scanned. @RequestBody parses the JSON into the record;
    // if the body is missing or malformed Spring throws before we get here and
    // GlobalExceptionHandler answers 400 INVALID_REQUEST.
    @PostMapping("/{transactionId}/items")
    public ScanResult scan(@PathVariable String transactionId, @RequestBody ScanItemRequest req) {
        ScanOutcome outcome = transactionService.scan(transactionId, req.sku());
        return new ScanResult(outcome.transactionId(), outcome.sku(), outcome.name(),
                outcome.unitPrice(), outcome.itemCount(), outcome.runningTotal());
    }

    // No request body: paying takes no extra input in this simulation.
    @PostMapping("/{transactionId}/complete")
    public Receipt complete(@PathVariable String transactionId) {
        ReceiptView view = transactionService.complete(transactionId);
        return new Receipt(view.transactionId(), view.stationId(), view.itemCount(),
                view.totalAmount(), view.startedAt(), view.completedAt(),
                view.lines().stream()
                        .map(l -> new ReceiptLine(l.sku(), l.name(), l.unitPrice(), l.quantity()))
                        .toList());
    }

    // Debug / instructor endpoint; the load client does not use it.
    @GetMapping("/{transactionId}")
    public TransactionResponse get(@PathVariable String transactionId) {
        return toResponse(transactionService.get(transactionId));
    }

    // Domain result -> wire shape. The field names here are the spec's, and
    // they are allowed to drift from the domain names without the transactions
    // layer ever being touched.
    private TransactionResponse toResponse(TransactionView v) {
        return new TransactionResponse(v.transactionId(), v.stationId(), v.status(),
                v.itemCount(), v.runningTotal(), v.startedAt());
    }
}

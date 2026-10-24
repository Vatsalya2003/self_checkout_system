package com.selfcheckout.self_checkout.api;

import com.selfcheckout.self_checkout.analytics.ScanIngestService;
import com.selfcheckout.self_checkout.analytics.exception.ScanGapException;
import com.selfcheckout.self_checkout.api.dto.ScanBatch;
import com.selfcheckout.self_checkout.api.dto.ScanBatchResult;
import com.selfcheckout.self_checkout.api.error.ApiError;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// ANALYTICS-SERVICE, API layer, INTERNAL endpoints: how scans reach analytics
// now that the services no longer share a database. Called only by
// checkout-service's scan-log writer; never reachable through the gateway.
@RestController
public class InternalScanController {

    private final ScanIngestService ingest;

    public InternalScanController(ScanIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping("/internal/scans")
    public ScanBatchResult push(@RequestBody ScanBatch batch) {
        if (batch == null || batch.scans() == null) {
            throw new InvalidRequestException("scans are required");
        }
        var r = ingest.accept(batch.scans().stream()
                .map(s -> s == null ? null : new ScanIngestService.IncomingScan(s.seq(), s.sku(), s.scannedAt()))
                .toList());
        return new ScanBatchResult(r.inserted(), r.duplicates(), r.maxSeq());
    }

    /** Where checkout's numbering continues from after it restarts. */
    @GetMapping("/internal/scans/max-seq")
    public ScanBatchResult maxSeq() {
        return new ScanBatchResult(0, 0, ingest.maxSeq());
    }

    // 409 SCAN_GAP carries the stored maximum, so checkout can renumber from it.
    @ExceptionHandler(ScanGapException.class)
    public ResponseEntity<ApiError> gap(ScanGapException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Max-Seq", String.valueOf(e.maxSeq()))
                .body(new ApiError("SCAN_GAP", e.getMessage()));
    }
}

package com.selfcheckout.self_checkout.api;

import com.selfcheckout.self_checkout.api.dto.StockCommitRequest;
import com.selfcheckout.self_checkout.api.dto.StockCommitResponse;
import com.selfcheckout.self_checkout.inventory.StockCommitService;
import com.selfcheckout.self_checkout.inventory.StockCommitService.RequestedLine;
import com.selfcheckout.self_checkout.inventory.result.StockCommitResult;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// INVENTORY-SERVICE, API layer, INTERNAL endpoint (not part of the public
// contract; the gateway answers 404 for /internal/** before any service sees
// it). checkout-service, the payment saga's orchestrator, calls it directly.
//
// Responds 200 only after the stock commit is durable. Any error status means
// the database transaction rolled back and no stock moved -- which is what
// lets checkout treat an error answer as "definitely not committed".
@RestController
public class InternalStockController {

    private final StockCommitService commits;

    public InternalStockController(StockCommitService commits) {
        this.commits = commits;
    }

    @PostMapping("/internal/stock/commit")
    public StockCommitResponse commit(@RequestBody StockCommitRequest request) {
        if (request == null || request.lines() == null) {
            throw new InvalidRequestException("transactionId and lines are required");
        }
        StockCommitResult r = commits.commit(request.transactionId(), request.lines().stream()
                .map(l -> l == null ? null : new RequestedLine(l.sku(), l.quantity()))
                .toList());
        return new StockCommitResponse(r.transactionId(), r.replayed(), r.lines().stream()
                .map(l -> new StockCommitResponse.Line(l.sku(), l.quantity(), l.applied()))
                .toList());
    }
}

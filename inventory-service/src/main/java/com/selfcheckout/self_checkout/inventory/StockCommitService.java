package com.selfcheckout.self_checkout.inventory;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.inventory.exception.CommitConflictException;
import com.selfcheckout.self_checkout.inventory.result.StockCommitResult;
import com.selfcheckout.self_checkout.persistence.repository.StockLedger;
import com.selfcheckout.self_checkout.persistence.repository.StockLedger.CommitLine;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import com.selfcheckout.self_checkout.transactions.exception.ItemNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// INVENTORY-SERVICE, business layer: the stock half of a payment.
//
// Week 5. checkout-service no longer owns stock, so when a customer pays it
// calls POST /internal/stock/commit here. This is the PIVOT of the payment
// saga: before this commit nothing irreversible has happened; once it commits,
// units have really left the shelf, and checkout must finish the sale.
//
// Everything below is one local database transaction on inventory_db, in the
// same order as the Week 4 checkout:
//
//   1. claim the transaction id (INSERT ... ON CONFLICT DO NOTHING) -- BEFORE
//      taking any lock, so a retry is answered without touching the hot rows
//   2. lock every SKU in the basket, ascending SKU order (no deadlocks)
//   3. clamp: applied = min(asked, in stock); stock never goes negative
//   4. write the new stock, the commit lines and any low-stock crossings
//   5. commit -- only then does the caller hear "done"
//
// Idempotent: the same transaction id always gets the same answer, however
// many times it is sent. That is what lets checkout retry after a timeout
// (and recover after a crash) without ever taking the stock twice.
@Service
public class StockCommitService {

    /** One line as checkout asks for it. */
    public record RequestedLine(String sku, int quantity) {}

    private final StockLedger ledger;
    private final SelfCheckoutProperties properties;
    private final CommitTimings timings;
    // An explicit transaction boundary: the claim, the lock and every write
    // must commit or roll back together.
    private final TransactionTemplate tx;

    public StockCommitService(StockLedger ledger, SelfCheckoutProperties properties,
                              CommitTimings timings, PlatformTransactionManager txManager) {
        this.ledger = ledger;
        this.properties = properties;
        this.timings = timings;
        this.tx = new TransactionTemplate(txManager);
    }

    public StockCommitResult commit(String transactionId, List<RequestedLine> requested) {
        // Canonical form: one entry per SKU, ascending. That is both the lock
        // order and what a retry is compared against.
        Map<String, Integer> wanted = validate(transactionId, requested);
        List<String> skus = new ArrayList<>(wanted.keySet());

        long startedAt = System.nanoTime();
        long[] lockGrantedAt = new long[1];
        StockCommitResult result = tx.execute(status -> {
            Instant now = Instant.now();

            // ---- 1. idempotency, before any lock
            if (!ledger.claimCommit(transactionId, now)) {
                List<CommitLine> stored = ledger.storedCommit(transactionId);
                if (!sameRequest(stored, wanted)) {
                    // Should never happen: checkout always retries with the
                    // lines it saved. Refuse rather than guess.
                    throw new CommitConflictException("Transaction " + transactionId
                            + " was already committed with different lines");
                }
                return new StockCommitResult(transactionId, true, toResult(stored));
            }

            // ---- 2. the only statement that takes locks
            Map<String, Integer> before = ledger.lockAndReadStock(skus);
            lockGrantedAt[0] = System.nanoTime();
            for (String sku : skus) {
                if (!before.containsKey(sku)) {
                    // rolls back the claim too, so a corrected retry can commit
                    throw new ItemNotFoundException("Item " + sku + " not found");
                }
            }

            // ---- 3. arithmetic in Java, holding no new locks
            int threshold = properties.lowStockThreshold();
            Map<String, Integer> newQty = new LinkedHashMap<>();
            List<CommitLine> lines = new ArrayList<>(skus.size());
            List<String> crossings = new ArrayList<>();
            for (String sku : skus) {
                int asked = wanted.get(sku);
                int have = before.get(sku);
                int applied = Math.min(asked, have);     // the clamp
                int after = have - applied;
                newQty.put(sku, after);
                lines.add(new CommitLine(sku, asked, applied));
                // strict "<" on both sides: landing exactly ON the threshold is not below it
                if (have >= threshold && after < threshold) {
                    crossings.add(sku);
                }
            }

            // ---- 4. the writes that need the lock; 5. commit on return
            ledger.applyStock(newQty);
            ledger.insertLines(transactionId, lines);
            ledger.insertCrossings(crossings, now);
            return new StockCommitResult(transactionId, false, toResult(lines));
        });

        if (!result.replayed()) {
            // measured after execute() returns, so the commit is inside the
            // critical section -- the rows stay locked until it completes
            timings.record(lockGrantedAt[0] - startedAt, System.nanoTime() - lockGrantedAt[0]);
        }
        return result;
    }

    private static List<StockCommitResult.Line> toResult(List<CommitLine> lines) {
        return lines.stream().map(l -> new StockCommitResult.Line(l.sku(), l.quantity(), l.applied())).toList();
    }

    private static Map<String, Integer> validate(String transactionId, List<RequestedLine> requested) {
        if (transactionId == null || transactionId.isBlank()) {
            throw new InvalidRequestException("transactionId is required");
        }
        if (requested == null || requested.isEmpty()) {
            throw new InvalidRequestException("lines must not be empty");
        }
        Map<String, Integer> wanted = new TreeMap<>();
        for (RequestedLine l : requested) {
            if (l == null || l.sku() == null || l.sku().isBlank() || l.quantity() <= 0) {
                throw new InvalidRequestException("every line needs a sku and a positive quantity");
            }
            if (wanted.put(l.sku(), l.quantity()) != null) {
                throw new InvalidRequestException("sku " + l.sku() + " appears twice");
            }
        }
        return wanted;
    }

    private static boolean sameRequest(List<CommitLine> stored, Map<String, Integer> wanted) {
        if (stored.size() != wanted.size()) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (CommitLine l : stored) {
            Integer q = wanted.get(l.sku());
            if (q == null || q != l.quantity() || !seen.add(l.sku())) {
                return false;
            }
        }
        return true;
    }
}

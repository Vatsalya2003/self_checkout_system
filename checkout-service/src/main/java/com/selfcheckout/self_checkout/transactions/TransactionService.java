package com.selfcheckout.self_checkout.transactions;

import com.selfcheckout.self_checkout.persistence.entity.Transaction;
import com.selfcheckout.self_checkout.persistence.entity.TransactionStatus;
import com.selfcheckout.self_checkout.persistence.repository.CatalogCache;
import com.selfcheckout.self_checkout.persistence.repository.CatalogEntry;
import com.selfcheckout.self_checkout.persistence.repository.SaleWriter;
import com.selfcheckout.self_checkout.persistence.repository.SaleWriter.PendingSale;
import com.selfcheckout.self_checkout.persistence.repository.TransactionRepository;
import com.selfcheckout.self_checkout.scanlog.ScanRecorder;
import com.selfcheckout.self_checkout.transactions.PaymentSaga.StockNotCommittedException;
import com.selfcheckout.self_checkout.transactions.PaymentSaga.StockOutcomeUnknownException;
import com.selfcheckout.self_checkout.transactions.exception.EmptyTransactionException;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import com.selfcheckout.self_checkout.transactions.exception.ItemNotFoundException;
import com.selfcheckout.self_checkout.transactions.exception.ServiceUnavailableException;
import com.selfcheckout.self_checkout.transactions.exception.TransactionNotFoundException;
import com.selfcheckout.self_checkout.transactions.exception.TransactionNotOpenException;
import com.selfcheckout.self_checkout.transactions.result.ReceiptLineView;
import com.selfcheckout.self_checkout.transactions.result.ReceiptView;
import com.selfcheckout.self_checkout.transactions.result.ScanOutcome;
import com.selfcheckout.self_checkout.transactions.result.TransactionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// CHECKOUT-SERVICE, TRANSACTIONS layer -- the heart of the project.
//
// Where the data lives, since Week 5 (microservices, a database per service):
//   OPEN basket   -> this process's memory only (OpenTransactionStore)
//   PAID basket   -> checkout_db, this service's own database
//   stock         -> inventory_db, owned by inventory-service -- NOT reachable
//                    from here; changed only through its stock-commit call
//
// So paying is no longer one database transaction: it changes two databases
// owned by two services. It is a SAGA, and this class is its orchestrator
// (see complete() and MICROSERVICES.md). Start and scan are unchanged: pure
// memory, no SQL, no network.
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    // How long a payment keeps retrying the stock commit before it answers
    // 503. Well inside the load client's 10 s request timeout.
    private static final Duration STOCK_COMMIT_BUDGET = Duration.ofSeconds(3);
    // tx B is a local write; it gets its own short budget before the resolver
    // takes over.
    private static final Duration FINISH_BUDGET = Duration.ofSeconds(3);

    private final OpenTransactionStore openTransactions;
    private final CatalogCache catalog;
    private final TransactionRepository transactionRepository;
    private final SaleWriter sales;
    private final PaymentSaga saga;
    private final SagaResolver resolver;
    private final SagaCounters counters;
    private final ScanRecorder scanRecorder;

    public TransactionService(OpenTransactionStore openTransactions,
                              CatalogCache catalog,
                              TransactionRepository transactionRepository,
                              SaleWriter sales,
                              PaymentSaga saga,
                              SagaResolver resolver,
                              SagaCounters counters,
                              ScanRecorder scanRecorder) {
        this.openTransactions = openTransactions;
        this.catalog = catalog;
        this.transactionRepository = transactionRepository;
        this.sales = sales;
        this.saga = saga;
        this.resolver = resolver;
        this.counters = counters;
        this.scanRecorder = scanRecorder;
    }

    /**
     * Open a basket. Pure memory -- no transaction, no SQL.
     *
     * This used to be BEGIN + SELECT + INSERT + COMMIT, and the SELECT was
     * pure waste: Spring Data saw our assigned UUID, assumed the row might
     * already exist, and checked before inserting.
     */
    public TransactionView start(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            throw new InvalidRequestException("stationId is required");
        }
        OpenBasket basket = new OpenBasket(UUID.randomUUID().toString(), stationId, Instant.now());
        openTransactions.put(basket);
        return new TransactionView(basket.id(), basket.stationId(), "OPEN",
                0, java.math.BigDecimal.ZERO, basket.startedAt());
    }

    /**
     * Scan one unit. Also pure memory.
     *
     * Checks stay in exactly the order they were, so the status codes are
     * unchanged:
     *   1. basket missing            -> 404
     *   2. basket not OPEN           -> 409
     *   3. SKU missing (or blank)    -> 404
     *
     * Still never reads or locks stock: the customer already has the item in
     * hand, and the spec says stock moves at completion.
     */
    public ScanOutcome scan(String transactionId, String sku) {
        OpenBasket basket = openTransactions.find(transactionId);
        if (basket == null) {
            // Either it never existed or it has already been paid for. Both
            // answers have to stay distinguishable, so ask the database before
            // deciding -- a completed basket is 409, an unknown one is 404.
            throw notFoundOrNotOpen(transactionId);
        }

        CatalogEntry item = (sku == null || sku.isBlank()) ? null : catalog.find(sku);
        if (basket.snapshot().state() != OpenBasket.State.OPEN) {
            throw new TransactionNotOpenException("Transaction " + transactionId + " is COMPLETED");
        }
        if (item == null) {
            throw new ItemNotFoundException(
                    "Item " + (sku == null ? "<missing>" : sku) + " not found");
        }

        // One lock, on this basket only. Two stations never share a basket, so
        // it is uncontended in practice -- but it is what makes a scan racing
        // a complete safe (see scripts/test-scan-complete-race.py).
        OpenBasket.Snapshot after = basket.addUnit(item.sku(), item.price());
        if (after == null) {
            // A complete claimed the basket between our check and here.
            throw new TransactionNotOpenException("Transaction " + transactionId + " is COMPLETED");
        }

        // The scan is now "committed" as far as the basket is concerned, so
        // analytics can hear about it. This hands the scan to the scan-log
        // writer (a bounded offer that gives up after 10 ms), which pushes it
        // to analytics-service in a numbered batch (Week 5; Week 4 inserted
        // it into a shared table). Never blocks checkout on analytics.
        scanRecorder.recordScan(item.sku());

        return new ScanOutcome(transactionId, item.sku(), item.name(),
                item.price(), after.itemCount(), after.runningTotal());
    }

    /**
     * Take payment and move stock -- the payment SAGA, orchestrated from here.
     *
     *   1. claim the basket in memory (OPEN -> COMPLETING). A second complete,
     *      or a scan arriving at the same moment, now gets 409.
     *   2. tx A (checkout_db): save the sale as PENDING, lines included.
     *      This is the saga log, written BEFORE anything irreversible.
     *   3. call inventory: POST /internal/stock/commit. THE PIVOT. Inventory
     *      locks, clamps, decrements and records the commit in one local
     *      transaction of its own, keyed on this transaction id.
     *   4. tx B (checkout_db): store the applied quantities, status COMPLETED.
     *   5. drop the basket from memory and return the receipt.
     *
     * When step 3 fails, what happens depends on what we KNOW:
     *   inventory certainly did not commit  -> undo tx A, basket back to
     *       OPEN, 503 SERVICE_UNAVAILABLE. Nothing moved; the client retries.
     *   no answer, so it may have committed -> 503, but the basket stays
     *       COMPLETING (reopening it could sell the same units twice) and the
     *       resolver keeps retrying the same commit until inventory answers,
     *       then finishes the sale. A retry of complete meanwhile gets 409;
     *       GET /transactions/{id} shows COMPLETED once it is resolved.
     */
    public ReceiptView complete(String transactionId) {
        OpenBasket basket = openTransactions.find(transactionId);
        if (basket == null) {
            throw notFoundOrNotOpen(transactionId);
        }

        // ---- 1. claim
        OpenBasket.Snapshot claimed = basket.claimForCompletion();
        if (claimed == null) {
            throw new TransactionNotOpenException("Transaction " + transactionId + " is COMPLETED");
        }
        if (claimed.lines().isEmpty()) {
            // Deliberately NOT claimed: an empty basket stays OPEN and can
            // still be scanned into and completed later.
            throw new EmptyTransactionException(
                    "Transaction " + transactionId + " has no scanned items");
        }

        // ---- 2. tx A: the saga log
        Instant completedAt = Instant.now();
        PendingSale sale = new PendingSale(basket.id(), basket.stationId(), claimed.itemCount(),
                claimed.runningTotal(), basket.startedAt(), completedAt, claimed.lines());
        try {
            sales.insertPending(sale);
        } catch (RuntimeException e) {
            basket.releaseClaim();          // nothing happened anywhere yet
            throw e;
        }

        // ---- 3. the pivot
        Map<String, Integer> applied;
        try {
            applied = saga.commitStock(sale, STOCK_COMMIT_BUDGET);
        } catch (StockNotCommittedException e) {
            counters.rolledBack.incrementAndGet();
            log.warn("payment {}: inventory did not commit ({}), basket reopened", transactionId, e.getMessage());
            try {
                sales.deletePending(transactionId);
                basket.releaseClaim();
            } catch (RuntimeException undoFailed) {
                // Could not undo tx A, so the PENDING row stays. Finish it
                // forward instead of reopening a basket with a sale on record.
                resolver.handOff(transactionId);
            }
            throw new ServiceUnavailableException(
                    "Inventory is unavailable; the basket is still open, please try again");
        } catch (StockOutcomeUnknownException e) {
            counters.ambiguous.incrementAndGet();
            log.warn("payment {}: no answer from inventory ({}), handed to the resolver", transactionId, e.getMessage());
            resolver.handOff(transactionId);
            throw new ServiceUnavailableException("Inventory did not answer; the payment for transaction "
                    + transactionId + " will complete shortly, check GET /transactions/" + transactionId);
        }

        // ---- 4. tx B, forward only
        try {
            saga.finishSale(transactionId, applied, FINISH_BUDGET);
        } catch (StockOutcomeUnknownException e) {
            counters.ambiguous.incrementAndGet();
            resolver.handOff(transactionId);
            throw new ServiceUnavailableException("The payment for transaction " + transactionId
                    + " will complete shortly, check GET /transactions/" + transactionId);
        }

        // ---- 5. done
        openTransactions.remove(transactionId);
        counters.payments.incrementAndGet();

        // The receipt lists lines in the order they were first scanned, and
        // shows the SCANNED quantity -- that is what the customer is holding,
        // regardless of the clamp.
        List<ReceiptLineView> receiptLines = new ArrayList<>(claimed.lines().size());
        for (Map.Entry<String, Integer> e : claimed.lines().entrySet()) {
            CatalogEntry c = catalog.find(e.getKey());
            receiptLines.add(new ReceiptLineView(e.getKey(), c.name(), c.price(), e.getValue()));
        }
        return new ReceiptView(basket.id(), basket.stationId(), claimed.itemCount(),
                claimed.runningTotal(), basket.startedAt(), completedAt, receiptLines);
    }

    /** Debug/instructor endpoint: memory first for open baskets, then the database. */
    @Transactional(readOnly = true)
    public TransactionView get(String transactionId) {
        OpenBasket basket = openTransactions.find(transactionId);
        if (basket != null) {
            OpenBasket.Snapshot s = basket.snapshot();
            return new TransactionView(basket.id(), basket.stationId(), "OPEN",
                    s.itemCount(), s.runningTotal(), basket.startedAt());
        }
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transaction " + transactionId + " not found"));
        // PENDING (a payment in progress, only visible here between a crash
        // and its recovery) is not a status the contract knows; it has not
        // been paid yet, so it is reported as OPEN.
        String status = tx.getStatus() == TransactionStatus.PENDING ? "OPEN" : tx.getStatus().name();
        return new TransactionView(tx.getId(), tx.getStationId(), status,
                tx.getItemCount(), tx.getRunningTotal(), tx.getStartedAt());
    }

    /**
     * Not in memory: either it was already paid for (409) or it never existed
     * (404). Only the database can tell them apart, and this is an error path,
     * so the extra read costs nothing on the hot path.
     */
    private RuntimeException notFoundOrNotOpen(String transactionId) {
        return transactionRepository.findById(transactionId)
                .<RuntimeException>map(tx -> new TransactionNotOpenException(
                        "Transaction " + transactionId + " is " + tx.getStatus()))
                .orElseGet(() -> new TransactionNotFoundException(
                        "Transaction " + transactionId + " not found"));
    }
}

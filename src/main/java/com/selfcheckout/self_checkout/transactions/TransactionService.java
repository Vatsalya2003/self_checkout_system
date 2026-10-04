package com.selfcheckout.self_checkout.transactions;

import com.selfcheckout.self_checkout.analytics.ScanRecorder;
import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.persistence.entity.Transaction;
import com.selfcheckout.self_checkout.persistence.repository.CatalogCache;
import com.selfcheckout.self_checkout.persistence.repository.CatalogEntry;
import com.selfcheckout.self_checkout.persistence.repository.CompletedTransactionWriter;
import com.selfcheckout.self_checkout.persistence.repository.LowStockAlertWriter;
import com.selfcheckout.self_checkout.persistence.repository.StockUpdater;
import com.selfcheckout.self_checkout.persistence.repository.TransactionRepository;
import com.selfcheckout.self_checkout.transactions.exception.EmptyTransactionException;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import com.selfcheckout.self_checkout.transactions.exception.ItemNotFoundException;
import com.selfcheckout.self_checkout.transactions.exception.TransactionNotFoundException;
import com.selfcheckout.self_checkout.transactions.exception.TransactionNotOpenException;
import com.selfcheckout.self_checkout.transactions.result.ReceiptLineView;
import com.selfcheckout.self_checkout.transactions.result.ReceiptView;
import com.selfcheckout.self_checkout.transactions.result.ScanOutcome;
import com.selfcheckout.self_checkout.transactions.result.TransactionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// TRANSACTIONS layer -- the heart of the project.
//
// Where the data lives, after the performance work:
//   OPEN basket   -> memory only (OpenTransactionStore). Nobody has paid.
//   PAID basket   -> the database, in one transaction, fully durable.
//   stock         -> the database, always, under row locks.
//
// So start and scan do NO database work at all, and complete is the only
// endpoint that talks to Postgres on the checkout path.
@Service
public class TransactionService {

    private final OpenTransactionStore openTransactions;
    private final CatalogCache catalog;
    private final TransactionRepository transactionRepository;
    private final CompletedTransactionWriter completedWriter;
    private final StockUpdater stockUpdater;
    private final LowStockAlertWriter lowStockAlertWriter;
    private final SelfCheckoutProperties properties;
    private final ScanRecorder scanRecorder;

    // An explicit transaction boundary instead of @Transactional on a private
    // helper. complete() calls that helper from inside this same class, which
    // means Spring's proxy is never involved and the annotation would be
    // silently ignored -- every statement would autocommit and the item row
    // locks would be released the instant they were taken.
    private final TransactionTemplate transactionTemplate;

    public TransactionService(OpenTransactionStore openTransactions,
                              CatalogCache catalog,
                              TransactionRepository transactionRepository,
                              CompletedTransactionWriter completedWriter,
                              StockUpdater stockUpdater,
                              LowStockAlertWriter lowStockAlertWriter,
                              SelfCheckoutProperties properties,
                              ScanRecorder scanRecorder,
                              PlatformTransactionManager transactionManager) {
        this.openTransactions = openTransactions;
        this.catalog = catalog;
        this.transactionRepository = transactionRepository;
        this.completedWriter = completedWriter;
        this.stockUpdater = stockUpdater;
        this.lowStockAlertWriter = lowStockAlertWriter;
        this.properties = properties;
        this.scanRecorder = scanRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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
        // the analytics pipeline can hear about it. There is no database
        // transaction to wait for any more, so this is submitted directly --
        // still the same non-blocking offer, still never blocking checkout.
        scanRecorder.recordScan(item.sku());

        return new ScanOutcome(transactionId, item.sku(), item.name(),
                item.price(), after.itemCount(), after.runningTotal());
    }

    /**
     * Take payment and move stock. The only checkout endpoint that touches the
     * database.
     *
     * Order of events:
     *   1. claim the basket in memory (OPEN -> COMPLETING) so a second
     *      complete gets 409 and a late scan gets 409
     *   2. one database transaction: lock the item rows in ascending SKU
     *      order, clamp, decrement, write the header + lines + any low-stock
     *      crossings
     *   3. drop the basket from memory
     *
     * If step 2 fails the claim is released, so the basket goes back to OPEN
     * and can be retried rather than being stranded.
     */
    public ReceiptView complete(String transactionId) {
        OpenBasket basket = openTransactions.find(transactionId);
        if (basket == null) {
            throw notFoundOrNotOpen(transactionId);
        }

        OpenBasket.Snapshot claimed = basket.claimForCompletion();
        if (claimed == null) {
            throw new TransactionNotOpenException("Transaction " + transactionId + " is COMPLETED");
        }
        if (claimed.lines().isEmpty()) {
            // Deliberately NOT claimed: an empty basket stays OPEN and can
            // still be scanned into and completed later, exactly as it did
            // when this was a rolled-back database transaction.
            throw new EmptyTransactionException(
                    "Transaction " + transactionId + " has no scanned items");
        }

        try {
            ReceiptView receipt = transactionTemplate.execute(
                    status -> writePaidTransaction(basket, claimed));
            openTransactions.remove(transactionId);
            return receipt;
        } catch (RuntimeException e) {
            basket.releaseClaim();      // let the customer try again
            throw e;
        }
    }

    /**
     * Everything that touches the database, inside one transaction opened by
     * the TransactionTemplate above. Every item lock taken here is held until
     * that transaction commits.
     */
    private ReceiptView writePaidTransaction(OpenBasket basket, OpenBasket.Snapshot claimed) {
        // Ascending SKU order. Every checkout takes its item locks in this one
        // global order, which is what makes a deadlock impossible: a cycle
        // would need some transaction to hold a higher SKU while waiting for a
        // lower one, and sorted acquisition never does that.
        List<String> skus = new ArrayList<>(claimed.lines().keySet());
        skus.sort(String::compareTo);

        Instant completedAt = Instant.now();
        int threshold = properties.lowStockThreshold();

        // ---- the only statement that takes locks: one, for the whole basket
        Map<String, Integer> stockBefore = stockUpdater.lockAndReadStock(skus);
        if (stockBefore.size() != skus.size()) {
            for (String sku : skus) {
                if (!stockBefore.containsKey(sku)) {
                    throw new ItemNotFoundException("Item " + sku + " not found");
                }
            }
        }

        // ---- everything else is arithmetic in Java, holding no new locks
        Map<String, Integer> newStock = new LinkedHashMap<>();
        List<String> crossings = new ArrayList<>();
        List<CompletedTransactionWriter.Line> lines = new ArrayList<>(skus.size());
        for (String sku : skus) {
            int quantity = claimed.lines().get(sku);
            int before = stockBefore.get(sku);

            // Clamp: scans are never gated by stock, so a hot SKU can be
            // scanned more often than it has units. Take what is really there
            // so stock can never go negative, and record it separately so the
            // shortfall stays visible instead of corrupting the invariant.
            int applied = Math.min(before, quantity);
            int after = before - applied;

            newStock.put(sku, after);
            // strict "<" on both sides: landing exactly ON the threshold is
            // not below it
            if (before >= threshold && after < threshold) {
                crossings.add(sku);
            }
            lines.add(new CompletedTransactionWriter.Line(sku, quantity, applied));
        }

        // ---- three bulk writes, then commit
        stockUpdater.applyStock(newStock);
        completedWriter.insertCompleted(basket.id(), basket.stationId(), claimed.itemCount(),
                claimed.runningTotal(), basket.startedAt(), completedAt, lines);
        lowStockAlertWriter.insertCrossings(crossings, completedAt);

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
        return new TransactionView(tx.getId(), tx.getStationId(), tx.getStatus().name(),
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

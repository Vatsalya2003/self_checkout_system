package com.selfcheckout.self_checkout.transactions;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.analytics.ScanRecorder;
import com.selfcheckout.self_checkout.transactions.exception.EmptyTransactionException;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import com.selfcheckout.self_checkout.transactions.exception.ItemNotFoundException;
import com.selfcheckout.self_checkout.transactions.exception.TransactionNotFoundException;
import com.selfcheckout.self_checkout.transactions.exception.TransactionNotOpenException;
import com.selfcheckout.self_checkout.transactions.result.ReceiptLineView;
import com.selfcheckout.self_checkout.transactions.result.ReceiptView;
import com.selfcheckout.self_checkout.transactions.result.ScanOutcome;
import com.selfcheckout.self_checkout.transactions.result.TransactionView;
import com.selfcheckout.self_checkout.persistence.entity.Item;
import com.selfcheckout.self_checkout.persistence.entity.LowStockAlert;
import com.selfcheckout.self_checkout.persistence.entity.Transaction;
import com.selfcheckout.self_checkout.persistence.entity.TransactionItem;
import com.selfcheckout.self_checkout.persistence.entity.TransactionItemId;
import com.selfcheckout.self_checkout.persistence.entity.TransactionStatus;
import com.selfcheckout.self_checkout.persistence.repository.ItemRepository;
import com.selfcheckout.self_checkout.persistence.repository.LowStockAlertRepository;
import com.selfcheckout.self_checkout.persistence.repository.TransactionItemRepository;
import com.selfcheckout.self_checkout.persistence.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// SERVICE layer -- the heart of the project. Everything interesting about
// concurrency happens in complete().
//
// Layer rules I'm sticking to: controllers do HTTP only, services hold the
// business rules and the transaction boundaries, repositories do SQL.
// Services are the ONLY place @Transactional appears.
@Service
public class TransactionService {

    // All dependencies are final and set in the constructor ("constructor
    // injection"). Spring sees the constructor and passes the beans in; no
    // @Autowired needed, and the object can never exist half-built.
    private final TransactionRepository transactionRepository;
    private final ItemRepository itemRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final LowStockAlertRepository lowStockAlertRepository;
    private final SelfCheckoutProperties properties;
    // Only a ScanRecorder, not the whole analytics service: this layer is not
    // allowed to know how windows are computed.
    private final ScanRecorder scanRecorder;

    public TransactionService(TransactionRepository transactionRepository,
                              ItemRepository itemRepository,
                              TransactionItemRepository transactionItemRepository,
                              LowStockAlertRepository lowStockAlertRepository,
                              SelfCheckoutProperties properties,
                              ScanRecorder scanRecorder) {
        this.transactionRepository = transactionRepository;
        this.itemRepository = itemRepository;
        this.transactionItemRepository = transactionItemRepository;
        this.lowStockAlertRepository = lowStockAlertRepository;
        this.properties = properties;
        this.scanRecorder = scanRecorder;
    }

    // Open a new basket. @Transactional = everything in this method is one
    // database transaction: it all commits together or none of it does.
    @Transactional
    public TransactionView start(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            throw new InvalidRequestException("stationId is required");
        }
        // Random UUID so two stations can never collide on an id.
        Transaction tx = new Transaction(UUID.randomUUID().toString(), stationId);
        transactionRepository.save(tx);
        return toView(tx);
    }

    /**
     * Scan exactly one physical unit of one SKU into an open basket.
     *
     * Checks run in the order the reference mock server uses, so the same
     * request gets the same status code from either server:
     *   1. transaction missing            -> 404
     *   2. transaction not OPEN           -> 409
     *   3. SKU missing (or blank/absent)  -> 404
     * Only after all three pass do we write anything.
     *
     * Deliberately does NOT look at or touch items.stock. The customer already
     * has the item in hand; the spec says stock moves at completion, and scans
     * are never gated by stock.
     */
    // Because we never touch items here, scans take NO row locks at all --
    // that's what keeps this path fast even when completions are queuing up.
    @Transactional
    public ScanOutcome scan(String transactionId, String sku) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transaction " + transactionId + " not found"));

        if (tx.getStatus() != TransactionStatus.OPEN) {
            throw new TransactionNotOpenException(
                    "Transaction " + transactionId + " is " + tx.getStatus());
        }

        // A null/blank sku can never match a catalog row, so it lands here as a
        // 404 rather than a 400: the spec documents only 404 and 409 for this
        // endpoint, and the mock server answers a missing sku the same way.
        Item item = (sku == null || sku.isBlank())
                ? null
                : itemRepository.findById(sku).orElse(null);
        if (item == null) {
            throw new ItemNotFoundException(
                    "Item " + (sku == null ? "<missing>" : sku) + " not found");
        }

        // Upsert the basket line: first unit of this SKU creates it, later ones bump quantity.
        // Read-then-write is safe here ONLY because one station drives one
        // transaction one scan at a time, so two scans of the same basket never
        // overlap. If the client ever scanned in parallel this would need to
        // become a single INSERT ... ON CONFLICT DO UPDATE.
        TransactionItem line = transactionItemRepository
                .findById(new TransactionItemId(transactionId, item.getSku()))
                .orElse(null);
        if (line == null) {
            transactionItemRepository.save(new TransactionItem(transactionId, item.getSku()));
        } else {
            line.addUnit();   // managed entity: JPA dirty-checking writes the UPDATE at flush
        }

        tx.addScan(item.getPrice());          // itemCount + 1, runningTotal + price
                                              // tx is managed too, so no save() call is needed
        // Hand the scan to the analytics layer. It writes the scan_log row
        // (joining this same DB transaction) and decides whether a window
        // boundary was crossed. Same statement order as before -- this call
        // sits exactly where the scan_log insert used to.
        scanRecorder.recordScan(item.getSku());

        return new ScanOutcome(tx.getId(), item.getSku(), item.getName(),
                item.getPrice(), tx.getItemCount(), tx.getRunningTotal());
    }

    /**
     * Take payment and move stock. This is the one place stock changes.
     *
     * Why this needs locking at all: Tomcat serves each request on its own
     * thread, and each thread gets its own JDBC connection, so two stations
     * completing baskets that share a SKU are two independent Postgres
     * sessions with independent snapshots. If both read stock = 10000 and both
     * write back an absolute number, one decrement is silently lost and no
     * error is raised anywhere. So every read-check-write below happens under
     * a row lock held until commit.
     *
     * Why the basket is sorted by SKU: locks are held until commit, so a
     * transaction holding SKU-A while waiting for SKU-B can deadlock against
     * one holding SKU-B and waiting for SKU-A. Postgres would detect that
     * after deadlock_timeout and kill one of them with an error. Acquiring
     * locks in one global order (ascending SKU) makes that cycle impossible
     * rather than merely unlikely -- it removes the circular-wait condition.
     */
    @Transactional
    public ReceiptView complete(String transactionId) {
        // Lock the transaction row first: two concurrent completes of the SAME
        // transaction serialize here, and the loser sees COMPLETED below.
        Transaction tx = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transaction " + transactionId + " not found"));

        // The loser of that race arrives here and sees COMPLETED -> clean 409,
        // instead of decrementing stock a second time.
        if (tx.getStatus() != TransactionStatus.OPEN) {
            throw new TransactionNotOpenException(
                    "Transaction " + transactionId + " is " + tx.getStatus());
        }

        // ...OrderBySkuAsc is the deadlock prevention. Do not change the sort.
        List<TransactionItem> lines = transactionItemRepository.findByTransactionIdOrderBySkuAsc(transactionId);
        if (lines.isEmpty()) {
            throw new EmptyTransactionException(
                    "Transaction " + transactionId + " has no scanned items");
        }

        // Pass 1 -- acquire every item lock, ascending SKU, before mutating
        // anything. Keeping acquisition separate from mutation means no item
        // is left dirty while we are still taking locks.
        // (If we mutated as we went, Hibernate would auto-flush those pending
        // UPDATEs before each following query, scattering writes through the
        // locking phase. Two passes keep it "lock everything, then change it".)
        Map<String, Item> locked = new LinkedHashMap<>();
        for (TransactionItem line : lines) {
            Item item = itemRepository.findBySkuForUpdate(line.getSku())
                    .orElseThrow(() -> new ItemNotFoundException(
                            "Item " + line.getSku() + " not found"));
            locked.put(line.getSku(), item);
        }

        // Pass 2 -- every lock is held, so these reads and writes are atomic.
        Instant completedAt = Instant.now();
        int threshold = properties.lowStockThreshold();
        List<LowStockAlert> crossings = new ArrayList<>();
        List<ReceiptLineView> receiptLines = new ArrayList<>(lines.size());
        for (TransactionItem line : lines) {
            Item item = locked.get(line.getSku());

            // Clamp: scans are never gated by stock, so a hot SKU can be
            // scanned more times than it has units. Take what is actually
            // there and record it, so stock can never go negative and the
            // shortfall stays visible instead of corrupting the invariant.
            int before = item.getStock();
            int applied = Math.min(before, line.getQuantity());
            int after = before - applied;
            item.setStock(after);
            line.setAppliedQuantity(applied);

            // Low-stock alert, recorded in this same DB transaction while we
            // still hold this item's row lock -- so exactly one completer can
            // observe any given crossing. Stock is never replenished here, so
            // "crossed below" happens at most once per SKU for the whole run.
            // Strictly "<" on both sides: landing exactly ON the threshold is
            // not "below" it.
            if (before >= threshold && after < threshold) {
                crossings.add(new LowStockAlert(item.getSku(), completedAt));
            }

            // The receipt shows the SCANNED quantity: that is what the
            // customer physically has, regardless of the clamp.
            receiptLines.add(new ReceiptLineView(item.getSku(), item.getName(),
                    item.getPrice(), line.getQuantity()));
        }

        // Saved after the loop, not inside it: a native/modifying write mid-loop
        // would force Hibernate to flush the dirty Items on every iteration.
        if (!crossings.isEmpty()) {
            lowStockAlertRepository.saveAll(crossings);
        }

        tx.complete(completedAt);

        // No explicit save() anywhere in pass 2: tx, the Items and the basket
        // lines are all managed entities, so Hibernate diffs them against what
        // it loaded (dirty checking) and writes the UPDATEs at commit. The
        // locks are released by that same commit.
        return new ReceiptView(tx.getId(), tx.getStationId(), tx.getItemCount(),
                tx.getRunningTotal(), tx.getStartedAt(), completedAt, receiptLines);
    }

    // Debug/instructor endpoint. readOnly = true tells Hibernate not to bother
    // dirty-checking anything, since we promise not to change it.
    @Transactional(readOnly = true)
    public TransactionView get(String transactionId) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(
                        "Transaction " + transactionId + " not found"));
        return toView(tx);
    }

    // Entity -> domain result. id becomes transactionId, and the enum becomes
    // text here so the persistence enum never travels up to the api layer.
    private TransactionView toView(Transaction tx) {
        return new TransactionView(tx.getId(), tx.getStationId(), tx.getStatus().name(),
                tx.getItemCount(), tx.getRunningTotal(), tx.getStartedAt());
    }
}

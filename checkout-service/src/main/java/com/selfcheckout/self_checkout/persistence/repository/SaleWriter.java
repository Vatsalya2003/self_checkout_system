package com.selfcheckout.self_checkout.persistence.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// CHECKOUT-SERVICE, persistence layer: the two local transactions of the
// payment saga, on checkout_db.
//
//   tx A  insertPending   header (status PENDING) + lines (scanned quantity)
//   tx B  complete        applied quantities from inventory, status COMPLETED
//
// A PENDING row is the saga log: proof that a payment started, holding
// everything needed to finish it (the same transaction id and lines) if this
// process dies before tx B.
public interface SaleWriter {

    /** A payment as checkout saved it in tx A. lines: SKU -> units scanned, in first-scan order. */
    record PendingSale(String id, String stationId, int itemCount, BigDecimal runningTotal,
                       Instant startedAt, Instant completedAt, Map<String, Integer> lines) {}

    /** tx A: one database transaction, header + every line. */
    void insertPending(PendingSale sale);

    /** tx B: set applied_quantity per SKU and mark COMPLETED. Safe to run twice. */
    void complete(String id, Map<String, Integer> appliedBySku);

    /** Undo tx A -- only when inventory certainly never committed. */
    void deletePending(String id);

    /** Every payment that started but has not finished (recovery after a crash). */
    List<String> pendingIds();

    /** Reload a PENDING sale, lines included, so it can be finished. Null if not PENDING. */
    PendingSale loadPending(String id);
}

package com.selfcheckout.self_checkout.persistence.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// PERSISTENCE layer (database access).
//
// Writes a paid transaction: the header row plus all of its lines.
//
// An interface so the business layer never names SQL, and so the
// implementation can change (one statement per line today, one multi-row
// INSERT after the batching phase) without touching the caller.
public interface CompletedTransactionWriter {

    /** One line of a paid basket: what was scanned, and what was actually taken. */
    record Line(String sku, int quantity, int appliedQuantity) {}

    /**
     * Insert the COMPLETED transaction header, in the caller's transaction.
     *
     * Split from the lines on purpose. The header depends on nothing that the
     * item lock protects, so the caller writes it BEFORE taking that lock and
     * keeps one statement out of the critical section. The lines cannot move:
     * their applied_quantity is only known once the stock has been read under
     * the lock.
     */
    void insertHeader(String id, String stationId, int itemCount, BigDecimal runningTotal,
                      Instant startedAt, Instant completedAt);

    /** Insert every line of the basket as one multi-row INSERT. */
    void insertLines(String id, List<Line> lines);
}

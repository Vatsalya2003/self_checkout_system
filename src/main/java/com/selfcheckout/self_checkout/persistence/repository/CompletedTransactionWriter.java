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
     * Insert the COMPLETED transaction header and every line, in the caller's
     * transaction. Both are new rows -- nothing about this basket was written
     * while it was open.
     */
    void insertCompleted(String id, String stationId, int itemCount, BigDecimal runningTotal,
                         Instant startedAt, Instant completedAt, List<Line> lines);
}

package com.selfcheckout.self_checkout.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

// CHECKOUT-SERVICE, persistence layer.
// ENTITY layer. One row of "transactions" = one customer's basket at a station.
// Holds the running totals so we never have to re-add the basket lines.

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private String id;      // a UUID string, generated in TransactionService.start()

    @Column(name = "station_id")   // Java camelCase -> SQL snake_case, spelled out
    private String stationId;      // so it can't silently break if defaults change

    @Enumerated(EnumType.STRING)   // store as text "OPEN", not as a number 0/1/2
    private TransactionStatus status;

    @Column(name = "item_count")
    private int itemCount;         // units scanned (not distinct SKUs)

    @Column(name = "running_total")
    private BigDecimal runningTotal;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;   // stays null until complete() is called

    protected Transaction() {}

    // A brand new basket always starts OPEN and empty.
    public Transaction(String id, String stationId) {
        this.id = id;
        this.stationId = stationId;
        this.status = TransactionStatus.OPEN;
        this.itemCount = 0;
        this.runningTotal = BigDecimal.ZERO;
        this.startedAt = Instant.now();
    }

    /**
     * Record one scanned unit: one more item in the basket, and its price
     * added to the running total. No stock is touched here -- stock is only
     * decremented at completion (see the spec's domain rules).
     */
    public void addScan(BigDecimal unitPrice) {
        this.itemCount += 1;
        this.runningTotal = this.runningTotal.add(unitPrice);
    }

    /** Finalize: payment always succeeds in this simulation, so there is no failure path. */
    public void complete(Instant when) {
        this.status = TransactionStatus.COMPLETED;
        this.completedAt = when;
    }

    public String getId() { return id; }
    public String getStationId() { return stationId; }
    public TransactionStatus getStatus() { return status; }
    public int getItemCount() { return itemCount; }
    public BigDecimal getRunningTotal() { return runningTotal; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}

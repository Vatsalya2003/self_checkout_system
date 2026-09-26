package com.selfcheckout.self_checkout.persistence.entity;

import jakarta.persistence.*;

// PERSISTENCE layer (database access).
/**
 * One line of a basket: "this transaction has N units of this SKU".
 *
 * We store the foreign keys as plain Strings rather than @ManyToOne
 * associations. We never need to navigate from a basket line to the whole
 * Transaction or Item object, so plain columns keep the SQL predictable:
 * no surprise extra SELECTs on the scan hot path.
 */
// Why that matters concretely: @ManyToOne defaults to EAGER fetching, so
// reading a 7-line basket would fire up to 7 extra SELECTs (the classic N+1),
// and it would pull UNLOCKED copies of Item into memory right before we go and
// read those same rows again WITH a lock. Plain strings avoid both problems.
@Entity
@Table(name = "transaction_items")
@IdClass(TransactionItemId.class)   // two-column primary key, defined next door
public class TransactionItem {

    @Id
    @Column(name = "transaction_id")
    private String transactionId;

    @Id
    @Column(name = "sku")
    private String sku;

    @Column(name = "quantity")
    private int quantity;

    /**
     * How many units were actually taken off the shelf at completion.
     * Equal to quantity unless the SKU ran out, in which case the decrement
     * is clamped and applied < quantity. The receipt still shows quantity --
     * that's what the customer physically has -- but the stock invariant is
     * checked against this.
     */
    @Column(name = "applied_quantity")
    private int appliedQuantity;

    protected TransactionItem() {}

    public TransactionItem(String transactionId, String sku) {
        this.transactionId = transactionId;
        this.sku = sku;
        this.quantity = 1;   // a line only exists because something was scanned
    }

    /** One more physical unit of this SKU was scanned into the basket. */
    public void addUnit() {
        this.quantity += 1;
    }

    public String getTransactionId() { return transactionId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public int getAppliedQuantity() { return appliedQuantity; }
    public void setAppliedQuantity(int appliedQuantity) { this.appliedQuantity = appliedQuantity; }
}

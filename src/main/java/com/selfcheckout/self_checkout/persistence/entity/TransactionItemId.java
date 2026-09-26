package com.selfcheckout.self_checkout.persistence.entity;

import java.io.Serializable;
import java.util.Objects;

// PERSISTENCE layer (database access).
/**
 * The composite primary key of transaction_items: (transaction_id, sku).
 *
 * JPA needs a separate little class to represent a two-column key. The field
 * names here must match the @Id field names on TransactionItem exactly, and it
 * must be Serializable with equals/hashCode so JPA can use it as a map key
 * inside the persistence context.
 */
// Gotcha worth remembering: because this key is always fully populated,
// Spring Data's save() treats the entity as "not new" and issues a merge
// (SELECT then INSERT) rather than a plain INSERT. Correct, just one extra
// read -- see the note in TransactionService.scan().
public class TransactionItemId implements Serializable {

    private String transactionId;
    private String sku;

    public TransactionItemId() {}   // JPA needs the no-arg one

    public TransactionItemId(String transactionId, String sku) {
        this.transactionId = transactionId;
        this.sku = sku;
    }

    public String getTransactionId() { return transactionId; }
    public String getSku() { return sku; }

    // equals/hashCode are mandatory for an @IdClass -- JPA uses this object as
    // a key in its internal map, so two keys with the same values must match.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransactionItemId other)) return false;
        return Objects.equals(transactionId, other.transactionId) && Objects.equals(sku, other.sku);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, sku);
    }
}

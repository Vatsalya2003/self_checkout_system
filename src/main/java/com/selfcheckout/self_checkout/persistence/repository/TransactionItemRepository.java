package com.selfcheckout.self_checkout.persistence.repository;

import com.selfcheckout.self_checkout.persistence.entity.TransactionItem;
import com.selfcheckout.self_checkout.persistence.entity.TransactionItemId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// PERSISTENCE layer (database access).
// REPOSITORY layer: the basket lines. Note the id type is the composite
// TransactionItemId, not a String.
public interface TransactionItemRepository extends JpaRepository<TransactionItem, TransactionItemId> {

    /**
     * All basket lines of one transaction, SKU ascending.
     * The ordering matters in Phase 2: locking item rows in a single global
     * order (alphabetical by SKU) is what stops two baskets from deadlocking.
     */
    // Another derived query -- "OrderBySkuAsc" in the name becomes the ORDER BY.
    // Do NOT drop that suffix to "tidy up" the method name: the sort IS the
    // deadlock prevention, not a cosmetic detail.
    List<TransactionItem> findByTransactionIdOrderBySkuAsc(String transactionId);
}

package com.selfcheckout.self_checkout.persistence.repository;

import com.selfcheckout.self_checkout.persistence.entity.Transaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// CHECKOUT-SERVICE, persistence layer.
// REPOSITORY layer: access to the transactions table.
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Lock the transaction row for the rest of this database transaction.
     *
     * Guards against the same transaction being completed twice at once: the
     * second caller blocks here, and when it wakes up it re-reads the row and
     * sees status = COMPLETED, so it gets a clean 409 instead of decrementing
     * stock a second time.
     */
    // Two different completes of the SAME basket meet here; two completes of
    // DIFFERENT baskets never do, because each locks its own row.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transaction t where t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") String id);
}

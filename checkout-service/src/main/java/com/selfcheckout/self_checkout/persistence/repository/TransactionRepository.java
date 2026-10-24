package com.selfcheckout.self_checkout.persistence.repository;

import com.selfcheckout.self_checkout.persistence.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

// CHECKOUT-SERVICE, persistence layer.
// Read access to saved sales (GET /transactions/{id} and the 404-vs-409
// decision). Writes go through JdbcSaleWriter.
//
// Week 5: the row-locking findByIdForUpdate is gone. Two completes of the same
// basket are already kept apart in memory (OpenBasket's OPEN -> COMPLETING
// claim), and since Week 3 nothing ever locked this row.
public interface TransactionRepository extends JpaRepository<Transaction, String> {
}

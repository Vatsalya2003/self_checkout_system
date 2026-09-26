package com.selfcheckout.self_checkout.persistence.repository;

import com.selfcheckout.self_checkout.persistence.entity.Item;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

// PERSISTENCE layer (database access).
// REPOSITORY layer: the only place that talks to the items table.
// Extending JpaRepository gives us findById/save/findAll for free -- we never
// write those ourselves.
public interface ItemRepository extends JpaRepository<Item, String> {

    // A "derived query": Spring Data reads the METHOD NAME and writes the SQL.
    // findAllByOrderBySkuAsc -> "select * from items order by sku asc".
    // No body needed, but it also means a typo in the name is a startup error.
    List<Item> findAllByOrderBySkuAsc();

    /**
     * Read one item row and hold an exclusive row lock on it until this
     * database transaction commits -- i.e. SELECT ... FOR UPDATE.
     *
     * This is what makes "read stock, decide, write stock" atomic. Without it
     * two stations completing baskets that share a SKU can both read the same
     * stock value and both write back an absolute number, so one decrement is
     * silently lost. With it, the second caller blocks until the first commits
     * and then re-reads the committed value.
     *
     * Callers MUST acquire these locks in ascending SKU order (see
     * TransactionService.complete) so that no two transactions can ever wait
     * on each other in a cycle.
     */
    // PESSIMISTIC_WRITE is what adds "FOR UPDATE" to the generated SQL.
    // "Pessimistic" = assume a conflict and take the lock up front, instead of
    // hoping there isn't one and retrying if there was (that's optimistic).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.sku = :sku")
    Optional<Item> findBySkuForUpdate(@Param("sku") String sku);
}

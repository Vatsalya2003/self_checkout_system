package com.selfcheckout.self_checkout.persistence.repository;

import com.selfcheckout.self_checkout.persistence.entity.LowStockAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

// PERSISTENCE layer (database access).
// REPOSITORY layer: recorded low-stock crossings, plus the query that joins
// them against live stock for GET /inventory/low-stock.
public interface LowStockAlertRepository extends JpaRepository<LowStockAlert, String> {

    /**
     * Every SKU currently below the threshold, with the time its alert was
     * recorded.
     *
     * LEFT JOIN + coalesce handles the ad-hoc ?threshold case: if the caller
     * asks about a threshold higher than the configured one, SKUs can qualify
     * that never had an alert row written, and those fall back to the query
     * time. Strictly "<", matching the spec's "drops below" and the reference
     * mock server.
     */
    // Reading the JPQL:
    //   "left join LowStockAlert a on a.sku = i.sku" -- an ad-hoc join between
    //      two entities that have no mapped relationship. LEFT so that items
    //      with no alert row still come back (with a = null).
    //   "coalesce(a.triggeredAt, :generatedAt)" -- first non-null wins, so a
    //      real crossing keeps its own time and everything else reports "now".
    //   order by stock asc -- most urgent (lowest stock) first.
    @Query("""
           select new com.selfcheckout.self_checkout.persistence.repository.LowStockRow(
                      i.sku, i.name, i.stock, coalesce(a.triggeredAt, :generatedAt))
           from Item i
           left join LowStockAlert a on a.sku = i.sku
           where i.stock < :threshold
           order by i.stock asc, i.sku asc
           """)
    List<LowStockRow> findBelowThreshold(@Param("threshold") int threshold,
                                              @Param("generatedAt") Instant generatedAt);
}

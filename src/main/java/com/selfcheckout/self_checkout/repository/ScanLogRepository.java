package com.selfcheckout.self_checkout.repository;

import com.selfcheckout.self_checkout.entity.ScanLog;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// REPOSITORY layer: the global scan log, plus the aggregate behind
// popular-items. save() here is on the scan hot path, so keep it cheap.
public interface ScanLogRepository extends JpaRepository<ScanLog, Long> {

    /**
     * Top SKUs by scan count inside the half-open window (start, end].
     *
     * Half-open so that consecutive windows neither double-count nor skip a
     * scan at the boundary. seq is the primary key, so this is an index range
     * scan. Ties break on sku ascending to keep the ranking deterministic.
     */
    // "join Item i on i.sku = s.sku" is only there to fetch the display name.
    // Limit is passed in by the caller (Spring Data turns it into LIMIT n)
    // rather than baked into the method name, so the top-N stays configurable.
    @Query("""
           select new com.selfcheckout.self_checkout.repository.ScanCount(
                      s.sku, i.name, count(s))
           from ScanLog s
           join Item i on i.sku = s.sku
           where s.seq > :start and s.seq <= :end
           group by s.sku, i.name
           order by count(s) desc, s.sku asc
           """)
    List<ScanCount> topInWindow(@Param("start") long start, @Param("end") long end, Limit limit);
}

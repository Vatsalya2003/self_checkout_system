package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.analytics.result.PopularEntry;
import com.selfcheckout.self_checkout.persistence.repository.CatalogCache;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * FILTER 3 of 5 -- ranking.
 *
 * Turns a map of counts into the ordered top N, exactly matching the Week 2
 * SQL: count descending, then SKU ascending so ties are deterministic and the
 * same input always produces the same ranking.
 */
public class RankFilter {

    private final int topN;
    private final CatalogCache names;

    public RankFilter(int topN, CatalogCache names) {
        this.topN = topN;
        this.names = names;
    }

    public RankedWindow rank(WindowCounts window) {
        List<Map.Entry<String, Long>> ordered = new ArrayList<>(window.counts().entrySet());
        // "ORDER BY count(s) desc, s.sku asc" from the Week 2 query, in Java.
        ordered.sort(Comparator
                .comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed()
                .thenComparing(Map.Entry::getKey));

        int size = Math.min(topN, ordered.size());   // fewer than topN distinct SKUs is fine
        List<PopularEntry> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Map.Entry<String, Long> e = ordered.get(i);
            // rank is 1-based, and position in the sorted list IS the rank
            items.add(new PopularEntry(e.getKey(), names.nameOf(e.getKey()), e.getValue(), i + 1));
        }
        return new RankedWindow(window.windowStart(), window.windowEnd(), Instant.now(),
                List.copyOf(items));
    }
}

package com.selfcheckout.self_checkout.analytics.pipeline;

import com.selfcheckout.self_checkout.analytics.result.PopularEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RankFilterTest {

    // the name lookup is an interface, so the stub is one lambda
    private final RankFilter rank = new RankFilter(10, new StubCatalog("Name of "));

    private static WindowCounts counts(Map<String, Long> m) {
        return new WindowCounts(0, 1000, Map.copyOf(m));
    }

    @Test
    @DisplayName("returns the top 10 ordered by count descending")
    void topTenByCount() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 1; i <= 25; i++) m.put(String.format("SKU-%03d", i), (long) i);
        List<PopularEntry> items = rank.rank(counts(m)).items();

        assertEquals(10, items.size());
        assertEquals("SKU-025", items.get(0).sku());
        assertEquals(25L, items.get(0).scanCount());
        assertEquals("SKU-016", items.get(9).sku());
        for (int i = 0; i < items.size(); i++) {
            assertEquals(i + 1, items.get(i).rank(), "rank is 1-based and dense");
        }
    }

    @Test
    @DisplayName("ties break on SKU ascending, so the ranking is deterministic")
    void tiesBreakBySkuAscending() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("SKU-ZZZ", 5L);
        m.put("SKU-AAA", 5L);
        m.put("SKU-MMM", 5L);
        List<PopularEntry> items = rank.rank(counts(m)).items();
        assertEquals(List.of("SKU-AAA", "SKU-MMM", "SKU-ZZZ"),
                items.stream().map(PopularEntry::sku).toList());
    }

    @Test
    @DisplayName("fewer than 10 distinct SKUs returns only what exists")
    void fewerThanTen() {
        List<PopularEntry> items = rank.rank(counts(Map.of("SKU-001", 3L, "SKU-002", 1L))).items();
        assertEquals(2, items.size());
        assertEquals("SKU-001", items.get(0).sku());
    }

    @Test
    @DisplayName("an empty window produces an empty ranking, not a crash")
    void emptyWindow() {
        assertTrue(rank.rank(counts(Map.of())).items().isEmpty());
    }

    @Test
    @DisplayName("names come from the lookup and window bounds are carried through")
    void attachesNamesAndBounds() {
        RankedWindow w = rank.rank(new WindowCounts(500, 1500, Map.of("SKU-001", 2L)));
        assertEquals(500, w.windowStart());
        assertEquals(1500, w.windowEnd());
        assertEquals("Name of SKU-001", w.items().get(0).name());
    }
}

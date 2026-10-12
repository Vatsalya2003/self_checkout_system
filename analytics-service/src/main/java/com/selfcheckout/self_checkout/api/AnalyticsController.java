package com.selfcheckout.self_checkout.api;

import com.selfcheckout.self_checkout.analytics.AnalyticsService;
import com.selfcheckout.self_checkout.api.dto.PopularItem;
import com.selfcheckout.self_checkout.api.dto.PopularItemsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// ANALYTICS-SERVICE, API layer. Read-only analytics.
//
// It only READS the newest stored window; it never computes one. The
// computing happens on the pipeline's background threads as scans arrive
// through scan_log, so this endpoint is always a cheap single-row lookup.
@RestController
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    // Integer so "no ?limit given" is null and the service can default it to 10.
    @GetMapping("/analytics/popular-items")
    public PopularItemsResponse popularItems(@RequestParam(required = false) Integer limit) {
        var window = analyticsService.latest(limit);
        return new PopularItemsResponse(
                window.windowSize(), window.slideInterval(),
                window.windowStart(), window.windowEnd(), window.computedAt(),
                window.items().stream()
                        .map(i -> new PopularItem(i.sku(), i.name(), i.scanCount(), i.rank()))
                        .toList());
    }
}

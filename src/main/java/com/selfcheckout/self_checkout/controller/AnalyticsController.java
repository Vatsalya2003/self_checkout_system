package com.selfcheckout.self_checkout.controller;

import com.selfcheckout.self_checkout.dto.PopularItemsResponse;
import com.selfcheckout.self_checkout.service.PopularItemsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// CONTROLLER layer. Read-only analytics.
// This only READS the newest stored window -- it never computes one. The
// computing happens on a background thread after a scan commits, so this
// endpoint is always a cheap single-row lookup.
@RestController
public class AnalyticsController {

    private final PopularItemsService popularItemsService;

    public AnalyticsController(PopularItemsService popularItemsService) {
        this.popularItemsService = popularItemsService;
    }

    // Integer again so "no ?limit given" is null and the service can default it to 10.
    @GetMapping("/analytics/popular-items")
    public PopularItemsResponse popularItems(@RequestParam(required = false) Integer limit) {
        return popularItemsService.latest(limit);
    }
}

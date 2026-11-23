package com.selfcheckout.self_checkout.ratelimit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

// RATE-LIMIT library: what the demo scripts and watch.sh read, per process.
// Under /internal/, so the gateway refuses it (404): it is only reachable on
// each service's own port, e.g. localhost:8082/internal/ratelimit/stats.
@RestController
public class RateLimitController {

    private final RateLimiter limiter;

    public RateLimitController(RateLimiter limiter) {
        this.limiter = limiter;
    }

    /** Counters since start-up: allowed, rejected, fallback decisions, Redis status, check time. */
    @GetMapping("/internal/ratelimit/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("process", ManagementFactory.getRuntimeMXBean().getName());
        out.putAll(limiter.stats());
        return out;
    }

    /** Turn the limiter on or off without a restart (the rules themselves need a restart to change). */
    @PostMapping("/internal/ratelimit/enabled")
    public Map<String, Object> setEnabled(@RequestParam("value") boolean value) {
        limiter.setEnabled(value);
        return Map.of("enabled", limiter.isEnabled());
    }
}

package com.selfcheckout.self_checkout.analytics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// CONFIG layer. Defines the one background thread used for analytics.
@Configuration
public class RecomputeExecutorConfig {

    /**
     * One background thread for popular-items recomputes.
     *
     * Single-threaded on purpose: windows are computed once per slideInterval
     * scans and must be written in order, and one thread means the analytics
     * work can never contend for more than one extra database connection no
     * matter how heavy the checkout load gets.
     */
    // destroyMethod = "shutdown" so the pool is closed when the app stops.
    // Daemon thread so a stuck recompute can never keep the JVM alive.
    @Bean(destroyMethod = "shutdown")
    public ExecutorService recomputeExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "popular-recompute");   // named, so it's easy to spot in a thread dump
            t.setDaemon(true);
            return t;
        });
    }
}

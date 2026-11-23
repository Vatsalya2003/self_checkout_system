package com.selfcheckout.self_checkout.ratelimit;

/**
 * What the limiter decided for one request, with everything the filter needs
 * for the response headers.
 */
public record Decision(boolean allowed, RateLimitProperties.Rule rule, long remaining,
                       long retryAfterMicros, long resetMicros, Source source) {

    /** Where the answer came from. */
    public enum Source {
        /** The shared bucket in Redis. */
        REDIS,
        /** This process already knew the bucket was empty (no Redis call). */
        DENY_CACHE,
        /** Redis unreachable: this process's own bucket. */
        LOCAL_FALLBACK
    }
}

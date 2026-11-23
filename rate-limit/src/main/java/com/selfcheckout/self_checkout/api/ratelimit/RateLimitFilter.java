package com.selfcheckout.self_checkout.api.ratelimit;

import com.selfcheckout.self_checkout.ratelimit.Decision;
import com.selfcheckout.self_checkout.ratelimit.RateLimitProperties;
import com.selfcheckout.self_checkout.ratelimit.RateLimiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// RATE-LIMIT library: the servlet filter every limited service runs first.
//
// A request that matches no rule (scans, payments, reads) goes straight on:
// one small loop over the rules, no Redis call. A request that matches a rule
// is checked; if allowed it continues with X-RateLimit-* headers added (its
// status and body are exactly what the API contract says); if refused it gets
// a 429 written right here, so no controller, database or broker work is done.
//
// It runs FIRST in the filter chain (RateLimitWebConfig) and never reads the
// request body, so a refused request costs Tomcat's header parsing and this
// check, nothing more.
//
// The limiter must never break a request by itself: if the check throws for
// any reason, the request is let through and the error is logged.
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    public static final String LIMIT = "X-RateLimit-Limit";
    public static final String REMAINING = "X-RateLimit-Remaining";
    public static final String RESET = "X-RateLimit-Reset";
    public static final String RETRY_AFTER = "Retry-After";
    /**
     * NOT a standard header: the wait in milliseconds. Retry-After must be whole
     * seconds, so it says "1" even when the next token is 2 ms away, and a client
     * that obeys it waits far too long (with a 550/s limit, a well-behaved client
     * left ~12 % of the allowed rate unused). Clients that know this header can
     * wait exactly as long as needed; everyone else just ignores it.
     */
    public static final String RETRY_AFTER_MS = "X-RateLimit-Retry-After-Ms";

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitProperties.Rule rule;
        Decision d;
        try {
            rule = limiter.isEnabled() ? limiter.match(request.getMethod(), request.getRequestURI()) : null;
            if (rule == null) {
                chain.doFilter(request, response);
                return;
            }
            d = limiter.check(rule, keyFor(rule, request));
        } catch (RuntimeException e) {
            log.error("ratelimit | check failed, letting the request through: {}", e.toString());
            chain.doFilter(request, response);
            return;
        }

        response.setHeader(LIMIT, RateLimiter.formatRate(rule.getRatePerSecond()));
        response.setHeader(REMAINING, Long.toString(d.remaining()));
        response.setHeader(RESET, Long.toString(ceilSeconds(d.resetMicros())));
        if (d.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        // Retry-After must be whole seconds (RFC 9110), so it is at least 1 even
        // when the next token is only milliseconds away.
        long retryAfter = Math.max(1, ceilSeconds(d.retryAfterMicros()));
        response.setStatus(429);
        response.setHeader(RETRY_AFTER, Long.toString(retryAfter));
        response.setHeader(RETRY_AFTER_MS, Long.toString(Math.max(1, (d.retryAfterMicros() + 999) / 1_000)));
        // Same Content-Type as the API's other errors ("application/json", no charset).
        response.setContentType("application/json");
        String message = "Too many requests for " + rule.getMethod() + " " + rule.getPath() + ": the limit is "
                + RateLimiter.formatRate(rule.getRatePerSecond()) + " per second (burst " + rule.getBurst()
                + "). Retry after " + retryAfter + " s.";
        byte[] body = ("{\"error\":\"RATE_LIMITED\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** The bucket key for this request: one shared bucket, or one per client address. */
    static String keyFor(RateLimitProperties.Rule rule, HttpServletRequest request) {
        if (rule.getKey() == RateLimitProperties.KeyStrategy.GLOBAL) {
            return "global";
        }
        // Behind nginx every request comes from 127.0.0.1, so the gateway passes
        // the real client address in X-Real-IP (gateway/nginx.conf).
        String ip = request.getHeader("X-Real-IP");
        return "ip:" + (ip == null || ip.isBlank() ? request.getRemoteAddr() : ip.trim());
    }

    private static long ceilSeconds(long micros) {
        return (micros + 999_999) / 1_000_000;
    }
}

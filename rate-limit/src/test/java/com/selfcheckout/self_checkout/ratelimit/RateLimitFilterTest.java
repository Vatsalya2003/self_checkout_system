package com.selfcheckout.self_checkout.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

// What a client actually sees: headers on allowed requests, the 429 on refused ones.
class RateLimitFilterTest {

    private final AtomicLong now = new AtomicLong();

    private RateLimiter limiter(RateLimitProperties.KeyStrategy key) {
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule();
        rule.setName("new-transactions");
        rule.setMethod("POST");
        rule.setPath("/transactions");
        rule.setKey(key);
        rule.setRatePerSecond(2);
        rule.setBurst(2);
        RateLimitProperties props = new RateLimitProperties();
        props.setRules(List.of(rule));
        java.util.Map<String, LocalTokenBucket> buckets = new java.util.HashMap<>();
        return new RateLimiter(props, (k, rate, burst) ->
                buckets.computeIfAbsent(k, x -> new LocalTokenBucket(rate, burst, now.get())).tryAcquire(now.get()), now::get);
    }

    private static MockHttpServletResponse send(RateLimitFilter f, String method, String path, String ip, MockFilterChain chain)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        if (ip != null) {
            req.addHeader("X-Real-IP", ip);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, chain);
        return res;
    }

    @Test
    void aRequestWithNoRulePassesUntouched() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limiter(RateLimitProperties.KeyStrategy.GLOBAL));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = send(f, "POST", "/transactions/tx-1/items", null, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getHeader(RateLimitFilter.LIMIT)).isNull();
    }

    @Test
    void anAllowedRequestGoesOnWithTheRateLimitHeaders() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limiter(RateLimitProperties.KeyStrategy.GLOBAL));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = send(f, "POST", "/transactions", null, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);   // whatever the controller sets; the filter leaves it alone
        assertThat(res.getHeader(RateLimitFilter.LIMIT)).isEqualTo("2");
        assertThat(res.getHeader(RateLimitFilter.REMAINING)).isEqualTo("1");
        assertThat(res.getHeader(RateLimitFilter.RESET)).isEqualTo("1");
        assertThat(res.getHeader(RateLimitFilter.RETRY_AFTER)).isNull();
        assertThat(res.getHeader(RateLimitFilter.RETRY_AFTER_MS)).isNull();
    }

    @Test
    void aRefusedRequestGetsA429WithHeadersAndTheUsualErrorJson() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limiter(RateLimitProperties.KeyStrategy.GLOBAL));
        send(f, "POST", "/transactions", null, new MockFilterChain());
        send(f, "POST", "/transactions", null, new MockFilterChain());
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = send(f, "POST", "/transactions", null, chain);

        assertThat(chain.getRequest()).isNull();   // no controller, no database, no broker
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader(RateLimitFilter.RETRY_AFTER)).isEqualTo("1");
        assertThat(res.getHeader(RateLimitFilter.RETRY_AFTER_MS)).isEqualTo("500");   // 2 per second: next token in 500 ms
        assertThat(res.getHeader(RateLimitFilter.REMAINING)).isEqualTo("0");
        assertThat(res.getContentType()).startsWith("application/json");
        assertThat(res.getContentAsString())
                .startsWith("{\"error\":\"RATE_LIMITED\",\"message\":\"Too many requests for POST /transactions")
                .endsWith("\"}");
    }

    @Test
    void perClientKeysGiveEachAddressItsOwnBucket() throws Exception {
        RateLimitFilter f = new RateLimitFilter(limiter(RateLimitProperties.KeyStrategy.CLIENT_IP));
        send(f, "POST", "/transactions", "10.0.0.1", new MockFilterChain());
        send(f, "POST", "/transactions", "10.0.0.1", new MockFilterChain());
        assertThat(send(f, "POST", "/transactions", "10.0.0.1", new MockFilterChain()).getStatus()).isEqualTo(429);
        assertThat(send(f, "POST", "/transactions", "10.0.0.2", new MockFilterChain()).getStatus()).isEqualTo(200);
    }

    @Test
    void ifTheCheckItselfBreaksTheRequestStillGoesThrough() throws Exception {
        RateLimiter broken = new RateLimiter(new RateLimitProperties(), null, now::get) {
            @Override
            public RateLimitProperties.Rule match(String method, String path) {
                RateLimitProperties.Rule r = new RateLimitProperties.Rule();
                r.setName("x");
                r.setMethod("POST");
                r.setPath("/transactions");
                return r;
            }

            @Override
            public Decision check(RateLimitProperties.Rule rule, String key) {
                throw new IllegalStateException("bug");
            }
        };
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = send(new RateLimitFilter(broken), "POST", "/transactions", null, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }
}

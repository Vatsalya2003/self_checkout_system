package com.selfcheckout.self_checkout.api.ratelimit;

import com.selfcheckout.self_checkout.ratelimit.RateLimiter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The HTTP side of the rate limiter. The limiter itself (the ratelimit
 * package) knows nothing about HTTP; this package turns its decisions into
 * a servlet filter, response headers and a 429, so it sits in the API layer.
 */
@Configuration
public class RateLimitWebConfig {

    /**
     * The very first filter, ahead of even Spring Boot's character-encoding
     * filter, so a refused request does no other work at all. (It never reads
     * the body, so nothing it skips could matter to it.)
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(new RateLimitFilter(limiter));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}

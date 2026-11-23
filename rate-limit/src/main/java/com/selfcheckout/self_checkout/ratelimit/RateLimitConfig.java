package com.selfcheckout.self_checkout.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// RATE-LIMIT library: wires the limiter into any service that depends on this
// module (picked up by the service's component scan, same base package, like
// the messaging library). Redis itself is configured with the usual
// spring.data.redis.* properties; the Redis command timeout is
// spring.data.redis.timeout (20 ms in the services).
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public RateLimiter rateLimiter(RateLimitProperties props, StringRedisTemplate redis) {
        RateLimiter limiter = new RateLimiter(props, new RedisTokenBucket(redis), System::nanoTime);
        limiter.logRules();
        return limiter;
    }

    /** First in the chain, so a refused request does no other work at all. */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(RateLimiter limiter) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(new RateLimitFilter(limiter));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    /**
     * Lettuce's defaults suit a cache, not a limiter: while disconnected it
     * QUEUES commands until they time out. Here a command must fail at once
     * when Redis is gone (so the request falls back immediately), and a
     * connection attempt must give up quickly; Lettuce keeps reconnecting in
     * the background and the breaker notices when Redis is back.
     */
    @Bean
    public LettuceClientOptionsBuilderCustomizer rateLimitRedisOptions() {
        return builder -> builder
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(100)).build());
    }

    /** Prints the one-line "ratelimit | ..." summary every log-interval, while there is traffic. */
    @Bean
    public SmartLifecycle rateLimitSummaryLogger(RateLimiter limiter, RateLimitProperties props) {
        return new SmartLifecycle() {
            private ScheduledExecutorService timer;

            @Override
            public void start() {
                timer = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "ratelimit-log");
                    t.setDaemon(true);
                    return t;
                });
                long ms = props.getLogInterval().toMillis();
                timer.scheduleAtFixedRate(limiter::logSummary, ms, ms, TimeUnit.MILLISECONDS);
            }

            @Override
            public void stop() {
                timer.shutdownNow();
            }

            @Override
            public boolean isRunning() {
                return timer != null && !timer.isShutdown();
            }
        };
    }
}

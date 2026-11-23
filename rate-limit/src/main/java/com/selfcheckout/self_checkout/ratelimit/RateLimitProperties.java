package com.selfcheckout.self_checkout.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

// RATE-LIMIT library: everything the limiter reads from configuration, under
// "ratelimit." in a service's application.properties. Changing a rule needs a
// restart of that service, but no code change. Turning the whole limiter on
// or off can also be done live (POST /internal/ratelimit/enabled).
//
// Example (checkout-service):
//   ratelimit.rules[0].name=new-transactions
//   ratelimit.rules[0].method=POST
//   ratelimit.rules[0].path=/transactions
//   ratelimit.rules[0].key=global
//   ratelimit.rules[0].rate-per-second=550
//   ratelimit.rules[0].burst=50
@ConfigurationProperties("ratelimit")
public class RateLimitProperties {

    /** Master switch. Off = the filter lets everything through without any Redis call. */
    private boolean enabled = true;

    /** The rules. A request that matches no rule is never checked. */
    private List<Rule> rules = new ArrayList<>();

    /**
     * How many processes share each limit. Only used by the local fallback:
     * when Redis is unreachable, each process allows rate / instances on its
     * own, so the combined rate stays roughly the configured one.
     */
    private int fallbackInstances = 1;

    /** Consecutive Redis failures before the circuit breaker opens. */
    private int breakerFailureThreshold = 5;

    /** How long the breaker stays open (no Redis calls at all) before one probe. */
    private Duration breakerOpenFor = Duration.ofSeconds(1);

    /**
     * Remember "this bucket is empty until time T" in the process, so the many
     * requests that arrive before T are refused without a Redis call. See
     * RateLimiter for why this never admits more than the limit.
     */
    private boolean denyCache = true;

    /** How often the one-line "ratelimit | ..." summary is logged (only when something happened). */
    private Duration logInterval = Duration.ofSeconds(5);

    public enum KeyStrategy {
        /** One bucket for everybody. */
        GLOBAL,
        /** One bucket per client address (X-Real-IP from the gateway, else the TCP peer). */
        CLIENT_IP
    }

    public static class Rule {
        /** Shown in logs, headers and the Redis key, e.g. "new-transactions". */
        private String name;
        /** HTTP method, e.g. POST. */
        private String method;
        /** Exact request path, e.g. /transactions (no wildcards). */
        private String path;
        private KeyStrategy key = KeyStrategy.GLOBAL;
        /** Sustained rate: tokens added per second. */
        private double ratePerSecond;
        /** Bucket size: how many requests may arrive at once after a quiet spell. */
        private int burst = 1;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public KeyStrategy getKey() { return key; }
        public void setKey(KeyStrategy key) { this.key = key; }
        public double getRatePerSecond() { return ratePerSecond; }
        public void setRatePerSecond(double ratePerSecond) { this.ratePerSecond = ratePerSecond; }
        public int getBurst() { return burst; }
        public void setBurst(int burst) { this.burst = burst; }

        boolean matches(String requestMethod, String requestPath) {
            return method.equalsIgnoreCase(requestMethod) && path.equals(requestPath);
        }

        @Override
        public String toString() {
            return name + " (" + method + " " + path + ", key=" + key.name().toLowerCase()
                    + ", " + RateLimiter.formatRate(ratePerSecond) + "/s, burst " + burst + ")";
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) { this.rules = rules; }
    public int getFallbackInstances() { return fallbackInstances; }
    public void setFallbackInstances(int fallbackInstances) { this.fallbackInstances = fallbackInstances; }
    public int getBreakerFailureThreshold() { return breakerFailureThreshold; }
    public void setBreakerFailureThreshold(int breakerFailureThreshold) { this.breakerFailureThreshold = breakerFailureThreshold; }
    public Duration getBreakerOpenFor() { return breakerOpenFor; }
    public void setBreakerOpenFor(Duration breakerOpenFor) { this.breakerOpenFor = breakerOpenFor; }
    public boolean isDenyCache() { return denyCache; }
    public void setDenyCache(boolean denyCache) { this.denyCache = denyCache; }
    public Duration getLogInterval() { return logInterval; }
    public void setLogInterval(Duration logInterval) { this.logInterval = logInterval; }
}

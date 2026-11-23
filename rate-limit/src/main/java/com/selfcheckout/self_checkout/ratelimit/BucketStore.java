package com.selfcheckout.self_checkout.ratelimit;

/**
 * Where the shared buckets live. In the services this is Redis
 * (RedisTokenBucket); the tests swap in fakes that answer, fail or hang.
 */
public interface BucketStore {

    /**
     * Take one token from the bucket at {@code key} if there is one.
     * Throws if the store cannot be reached; the caller falls back.
     */
    BucketResult tryAcquire(String key, double ratePerSecond, int burst);
}

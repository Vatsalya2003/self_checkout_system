package com.selfcheckout.self_checkout.ratelimit;

/**
 * The answer from one token-bucket check, whether it came from Redis or from
 * the local fallback bucket.
 *
 * @param allowed           true if a token was taken
 * @param remaining         whole tokens left after this request
 * @param retryAfterMicros  how long until the next token (0 when allowed)
 * @param fullInMicros      how long until the bucket is full again
 */
public record BucketResult(boolean allowed, long remaining, long retryAfterMicros, long fullInMicros) {
}

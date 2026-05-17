package com.biblioteca.security;

import io.github.bucket4j.BucketConfiguration;

/**
 * Backing store for per-key token buckets. Two implementations:
 *   - {@link InMemoryRateLimitStore} (default) — ConcurrentHashMap, process-local.
 *   - {@link RedisRateLimitStore} — shared across instances when scaled out.
 *
 * Swap them by setting {@code app.rate-limit.backend} to {@code memory} (default)
 * or {@code redis}.
 */
public interface RateLimitStore {
    /**
     * Attempts to consume one token from the bucket identified by {@code key}.
     * The bucket is created lazily with {@code configSupplier} the first time
     * the key is seen.
     *
     * @return true if a token was available (request allowed), false otherwise.
     */
    boolean tryConsume(String key, java.util.function.Supplier<BucketConfiguration> configSupplier);
}

package com.biblioteca.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-process token buckets. The default — fine for a single-instance deploy
 * (this app's current shape: one JVM behind Cloudflare Tunnel + Nginx). If
 * you ever run more than one instance, switch to {@link RedisRateLimitStore}.
 *
 * Selected when {@code app.rate-limit.backend} is unset or {@code memory}.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "memory", matchIfMissing = true)
public class InMemoryRateLimitStore implements RateLimitStore {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean tryConsume(String key, Supplier<BucketConfiguration> configSupplier) {
        Bucket b = buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(configSupplier.get().getBandwidths()[0])
                .build());
        return b.tryConsume(1);
    }
}

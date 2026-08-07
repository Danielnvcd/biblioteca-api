package com.biblioteca.security;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Redis-backed buckets via Bucket4j's Lettuce proxy manager. Survives across
 * instances and JVM restarts, which is what you need behind a load balancer.
 *
 * Selected when {@code app.rate-limit.backend=redis}. The connection comes
 * from {@code RedisRateLimitConfig}, which only contributes its bean when the
 * same property is set, so neither this class nor its dependencies end up
 * wired in the default (memory) deployment.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "redis")
public class RedisRateLimitStore implements RateLimitStore {

    private final ProxyManager<String> proxyManager;

    /**
     * El @Lazy del punto de inyección acompaña al de RedisRateLimitConfig: sin
     * él, este componente pide el ProxyManager al construirse —durante el
     * arranque— y volvería a forzar la conexión a Redis en ese momento,
     * anulando el diferido. Spring inyecta un proxy y la conexión real se abre
     * en el primer tryConsume.
     */
    public RedisRateLimitStore(@Lazy ProxyManager<String> proxyManager) {
        this.proxyManager = proxyManager;
    }

    @Override
    public boolean tryConsume(String key, Supplier<BucketConfiguration> configSupplier) {
        BucketProxy bucket = proxyManager.builder().build(key, configSupplier);
        return bucket.tryConsume(1);
    }
}

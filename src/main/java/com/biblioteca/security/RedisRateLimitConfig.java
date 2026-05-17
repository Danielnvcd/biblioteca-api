package com.biblioteca.security;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the Lettuce-backed {@link ProxyManager} that Bucket4j uses to talk
 * to Redis. Only active when {@code app.rate-limit.backend=redis}; without
 * that flag the Redis client is never created, so a missing Redis URL won't
 * stop the app from starting.
 */
@Configuration
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "redis")
public class RedisRateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(@Value("${spring.data.redis.url}") String url) {
        return RedisClient.create(url);
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> redisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    public ProxyManager<String> bucket4jProxyManager(StatefulRedisConnection<String, byte[]> connection) {
        // Defaults are fine: each bucket key has a TTL derived from its bandwidth.
        return LettuceBasedProxyManager.builderFor(connection).build();
    }
}

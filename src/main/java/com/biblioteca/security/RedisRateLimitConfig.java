package com.biblioteca.security;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

/**
 * Creates the Lettuce-backed {@link ProxyManager} that Bucket4j uses to talk
 * to Redis. Only active when {@code app.rate-limit.backend=redis}; without
 * that flag the Redis client is never created, so a missing Redis URL won't
 * stop the app from starting.
 */
@Configuration
@ConditionalOnProperty(name = "app.rate-limit.backend", havingValue = "redis")
public class RedisRateLimitConfig {

    /** Techo para conectar y para cada comando. Sin esto, un Redis que no
     *  responde deja el request colgado en vez de fallar y seguir. */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(@Value("${spring.data.redis.url}") String url) {
        RedisClient client = RedisClient.create(url);
        client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .socketOptions(SocketOptions.builder().connectTimeout(TIMEOUT).build())
                .build());
        client.setDefaultTimeout(TIMEOUT);
        return client;
    }

    /**
     * @Lazy es obligatorio, no una optimización.
     *
     * `client.connect()` abre la conexión en el acto y tira si Redis no está.
     * Como bean normal, esa excepción se propaga por bucket4jProxyManager →
     * rateLimitFilter → contexto de Spring, y la API entera no arranca: se
     * comprobó apagando Redis, el contenedor quedó en bucle de reinicio.
     *
     * O sea que sin esto, un servicio auxiliar del limitador se vuelve
     * requisito de arranque de toda la aplicación. Difiriendo la creación, un
     * Redis ausente solo se nota en el primer request con rate-limit, donde
     * RateLimitFilter ya deja pasar y loguea.
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public StatefulRedisConnection<String, byte[]> redisConnection(RedisClient client) {
        return client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    @Lazy
    public ProxyManager<String> bucket4jProxyManager(StatefulRedisConnection<String, byte[]> connection) {
        // Defaults are fine: each bucket key has a TTL derived from its bandwidth.
        return LettuceBasedProxyManager.builderFor(connection).build();
    }
}

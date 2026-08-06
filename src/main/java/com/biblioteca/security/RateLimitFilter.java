package com.biblioteca.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * IP-based rate limiter for sensitive auth endpoints. Backend (memory or
 * Redis) is selected via {@code app.rate-limit.backend}; this filter just
 * delegates the consume() to whichever {@link RateLimitStore} is wired in.
 *
 * Buckets per endpoint:
 *   /api/auth/login            → 8 req / minute / IP
 *   /api/auth/verify-2fa       → 8 req / minute / IP
 *   /api/auth/register         → 20 req / hour / IP
 *   /api/auth/change-password  → 5 req / minute / IP  (defensa contra brute-force
 *                                 del currentPassword con un access token robado)
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final boolean enabled;
    private final ObjectMapper mapper;
    private final RateLimitStore store;

    public RateLimitFilter(@Value("${app.rate-limit.enabled:false}") boolean enabled,
                           ObjectMapper mapper,
                           RateLimitStore store) {
        this.enabled = enabled;
        this.mapper = mapper;
        this.store = store;
    }

    private record Rule(String pathSuffix, Supplier<BucketConfiguration> configSupplier) {}

    private final Rule[] rules = new Rule[] {
            new Rule("/api/auth/login",           () -> config(8,  Duration.ofMinutes(1))),
            new Rule("/api/auth/verify-2fa",      () -> config(8,  Duration.ofMinutes(1))),
            new Rule("/api/auth/register",        () -> config(20, Duration.ofHours(1))),
            // change-password lleva /{id} en el path, así que el matcher
            // contempla exact + startsWith("/").
            new Rule("/api/auth/change-password", () -> config(5,  Duration.ofMinutes(1))),
            // disable-2fa valida un código TOTP de 6 dígitos. El lockout por
            // cuenta que aplica ahí solo cuenta fallos de CONTRASEÑA: con la
            // contraseña correcta, los intentos de código quedaban ilimitados y
            // un espacio de 10^6 se recorre. Este bucket es el único techo.
            new Rule("/api/auth/disable-2fa",     () -> config(5,  Duration.ofMinutes(1))),
    };

    private static BucketConfiguration config(long capacity, Duration window) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillIntervally(capacity, window)
                        .build())
                .build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled || !"POST".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        String path = req.getServletPath();
        for (int i = 0; i < rules.length; i++) {
            Rule rule = rules[i];
            if (matchesRule(path, rule.pathSuffix())) {
                String key = clientIp(req) + "|" + i;
                if (!tryConsumeOrAllow(key, rule)) {
                    res.setStatus(429);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.setHeader("Retry-After", "60");
                    mapper.writeValue(res.getOutputStream(), Map.of(
                            "error", "Demasiados intentos. Espera unos segundos e intenta de nuevo."));
                    return;
                }
                break;
            }
        }
        chain.doFilter(req, res);
    }

    /**
     * Consume una ficha del bucket. Si el store falla, DEJA PASAR el request.
     *
     * Importa con el backend Redis: sin esto, una caída de Redis hace que
     * Lettuce lance excepción, la excepción sube por el filtro y /login empieza
     * a contestar 500 — o sea que perder el limitador dejaría a todo el mundo
     * afuera de la aplicación. Con backend memory el caso no existe.
     *
     * Se elige fail-open y no fail-closed porque el rate-limit por IP no es la
     * única defensa contra fuerza bruta: el bloqueo por cuenta (10 intentos →
     * 15 minutos) vive en Postgres y sigue funcionando aunque Redis no esté.
     * Quedarse sin el limitador degrada la protección; quedarse sin login la
     * elimina junto con el resto del sistema.
     */
    private boolean tryConsumeOrAllow(String key, Rule rule) {
        try {
            return store.tryConsume(key, rule.configSupplier());
        } catch (RuntimeException ex) {
            log.warn("Rate limiter no disponible ({}), se deja pasar el request a {}",
                    ex.getClass().getSimpleName(), rule.pathSuffix());
            return true;
        }
    }

    /** Match exacto o prefijo seguido de "/" — soporta tanto /api/auth/login (exact)
     *  como /api/auth/change-password/{id} (con path param). Evita matches falsos
     *  como /api/auth/loginfoo. */
    private static boolean matchesRule(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static String clientIp(HttpServletRequest req) {
        // Rely on req.getRemoteAddr() — Spring's forward-headers-strategy=framework
        // (set in application-prod.yml) already rewrites this from a trusted
        // X-Forwarded-For when behind a reverse proxy. Reading the header here
        // directly would let any caller spoof it and reset the bucket on each
        // request, defeating the whole rate limit.
        return req.getRemoteAddr();
    }
}

package com.biblioteca.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * IP-based rate limiter for sensitive auth endpoints. Bucket4j in-memory
 * (process-local). For multi-instance prod, swap to a distributed backend
 * (Redis) — interface stays the same.
 *
 * Buckets per endpoint:
 *   /api/auth/login       → 8 req / minute / IP
 *   /api/auth/verify-2fa  → 8 req / minute / IP
 *   /api/auth/register    → 20 req / hour / IP
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final ObjectMapper mapper;

    public RateLimitFilter(@Value("${app.rate-limit.enabled:false}") boolean enabled,
                           ObjectMapper mapper) {
        this.enabled = enabled;
        this.mapper = mapper;
    }

    private record Rule(String pathSuffix, Supplier<Bucket> bucketFactory) {}

    private final Rule[] rules = new Rule[] {
            new Rule("/api/auth/login",      () -> bucket(8,  Duration.ofMinutes(1))),
            new Rule("/api/auth/verify-2fa", () -> bucket(8,  Duration.ofMinutes(1))),
            new Rule("/api/auth/register",   () -> bucket(20, Duration.ofHours(1))),
    };

    /** key = ip + "|" + rule index. */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static Bucket bucket(long capacity, Duration window) {
        return Bucket.builder().addLimit(Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, window)
                .build()).build();
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
            final int idx = i;
            if (path.endsWith(rules[idx].pathSuffix())) {
                Bucket b = buckets.computeIfAbsent(clientIp(req) + "|" + idx, k -> rules[idx].bucketFactory().get());
                if (!b.tryConsume(1)) {
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

    private static String clientIp(HttpServletRequest req) {
        // Rely on req.getRemoteAddr() — Spring's forward-headers-strategy=framework
        // (set in application-prod.yml) already rewrites this from a trusted
        // X-Forwarded-For when behind a reverse proxy. Reading the header here
        // directly would let any caller spoof it and reset the bucket on each
        // request, defeating the whole rate limit.
        return req.getRemoteAddr();
    }
}

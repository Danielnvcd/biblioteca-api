package com.biblioteca.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tags every request with a stable id so we can correlate logs across threads
 * and downstream services. Honors an incoming X-Request-Id header (set by a
 * load balancer or upstream service) and echoes it back so clients can quote
 * it in support tickets.
 *
 * Ordered just before the security filter chain to make sure the id is in MDC
 * before anything user-facing logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER  = "X-Request-Id";

    // Restringimos a chars seguros para logs/headers (UUIDs y los IDs típicos
    // de proxies/LBs entran limpio: hex + guiones + punto + underscore).
    // Cualquier cosa fuera de eso → generamos uno nuevo en vez de reflejar.
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._\\-]+");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank() || id.length() > 64 || !SAFE_ID.matcher(id).matches()) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}

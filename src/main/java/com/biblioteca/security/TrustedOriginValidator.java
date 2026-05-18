package com.biblioteca.security;

import com.biblioteca.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Defensa contra CSRF en endpoints permitAll que usan la cookie de refresh
 * (logout / refresh). Como CSRF global está apagado y la cookie es
 * SameSite=None en prod, sin esta validación un sitio cualquiera puede
 * disparar POSTs cross-site con la cookie del usuario adjunta.
 *
 * Política: si llega Origin y NO está en la allowlist → 403. Si no llega
 * Origin (curl, postman, server-to-server) → se acepta. Los navegadores
 * SIEMPRE incluyen Origin en POSTs cross-origin, así que el ataque CSRF
 * desde un navegador queda bloqueado; clientes legítimos no-browser
 * siguen funcionando sin tocar headers.
 */
@Component
public class TrustedOriginValidator {

    private final Set<String> allowedOrigins;

    public TrustedOriginValidator(@Value("${app.cors.allowed-origins}") String configured) {
        this.allowedOrigins = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public void requireTrustedOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return; // No-browser caller — sin riesgo CSRF
        }
        if (!allowedOrigins.contains(origin)) {
            throw ApiException.forbidden("Origen no permitido");
        }
    }
}

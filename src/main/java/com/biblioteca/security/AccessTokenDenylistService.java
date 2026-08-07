package com.biblioteca.security;

import com.biblioteca.model.RevokedAccessToken;
import com.biblioteca.repository.RevokedAccessTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Invalida access tokens concretos antes de su expiración natural.
 *
 * Existe porque el access token es autocontenido: una vez firmado, vale hasta
 * que vence y el servidor no tiene forma de desdecirse. Sin esto, /logout solo
 * revocaba el refresh token y el access seguía abriendo puertas hasta 15
 * minutos después de que el usuario creyera haber cerrado la sesión.
 *
 * Se revoca por `jti` (un token) y no por usuario a propósito: cerrar sesión en
 * el teléfono no debe echar a la misma persona de la computadora.
 */
@Service
public class AccessTokenDenylistService {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenDenylistService.class);

    private final RevokedAccessTokenRepository repo;
    private final JwtTokenProvider tokenProvider;

    public AccessTokenDenylistService(RevokedAccessTokenRepository repo, JwtTokenProvider tokenProvider) {
        this.repo = repo;
        this.tokenProvider = tokenProvider;
    }

    /**
     * Revoca el token crudo recibido. Tolerante por diseño: un token ausente,
     * vencido, mal formado o sin `jti` es un no-op silencioso.
     *
     * El motivo es que esto corre dentro de /logout, y el logout NUNCA debe
     * fallar: si tirara una excepción, el usuario se quedaría con la sesión
     * abierta justo cuando pidió cerrarla. Ante la duda, se limpia lo que se
     * puede (el refresh token, que revoca el caller) y se sigue.
     */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        try {
            String jti = tokenProvider.getJtiFromToken(rawToken);
            if (jti == null || jti.isBlank()) return; // token anterior al claim

            long expMillis = tokenProvider.getExpirationEpochMillis(rawToken);
            if (expMillis <= System.currentTimeMillis()) return; // ya no vale nada

            LocalDateTime expiresAt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(expMillis), ZoneId.systemDefault());

            if (!repo.existsById(jti)) {
                repo.save(new RevokedAccessToken(jti, expiresAt, LocalDateTime.now()));
            }

            // Barrido oportunista: la tabla solo contiene tokens revocados aún
            // vigentes (≤15 min), así que limpiarla en cada logout la mantiene
            // diminuta sin necesidad de un scheduler.
            repo.deleteExpired(LocalDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("No se pudo revocar el access token en logout: {}", ex.getClass().getSimpleName());
        }
    }

    /** True si el token identificado por este `jti` fue revocado. */
    @Transactional(readOnly = true)
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) return false;
        return repo.existsById(jti);
    }
}

package com.biblioteca.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Un access token invalidado antes de su expiración natural (hoy: por logout).
 *
 * Se guarda el `jti` — el identificador del token — y no el token entero: para
 * decidir si hay que rechazarlo alcanza con el id, y así no queda en la base
 * una credencial completa que sirva para autenticarse si la base se filtra.
 *
 * `expiresAt` es la expiración del propio token. Pasada esa fecha la fila ya no
 * hace falta: el token se rechaza solo por vencido.
 */
@Entity
@Table(name = "revoked_access_tokens")
public class RevokedAccessToken {

    @Id
    @Column(length = 64)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at", nullable = false)
    private LocalDateTime revokedAt;

    protected RevokedAccessToken() {} // JPA

    public RevokedAccessToken(String jti, LocalDateTime expiresAt, LocalDateTime revokedAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    public String getJti() { return jti; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
}

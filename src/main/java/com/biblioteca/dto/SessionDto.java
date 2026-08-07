package com.biblioteca.dto;

import java.time.LocalDateTime;

/**
 * Una sesión abierta, tal como se muestra en el perfil.
 *
 * Deliberadamente NO expone el tokenHash ni el id crudo del refresh token: el
 * listado es informativo y la única acción disponible ("cerrar las demás") no
 * necesita identificar sesiones una por una desde el cliente.
 */
public class SessionDto {

    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    /** true para la sesión desde la que se está mirando la pantalla. */
    private boolean current;

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
}

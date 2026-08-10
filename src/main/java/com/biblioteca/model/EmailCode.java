package com.biblioteca.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Código de un solo uso enviado por correo, para iniciar sesión (segundo
 * factor) o para probar que una dirección recién dada de alta es del usuario.
 *
 * El código NO se guarda: lo que vive aquí es su HMAC-SHA256 con una clave de
 * aplicación. Un código vivo equivale al segundo factor durante su ventana,
 * así que guardarlo legible convertiría cualquier lectura de la base en un
 * bypass del 2FA. Ver V9 para el razonamiento completo.
 */
@Entity
@Table(name = "email_codes", indexes = {
    @Index(name = "idx_email_codes_lookup",     columnList = "user_id, purpose, created_at"),
    @Index(name = "idx_email_codes_expires_at", columnList = "expires_at")
})
public class EmailCode {

    /** Para qué sirve el código. Se guarda como texto para que un dump sea legible. */
    public enum Purpose {
        LOGIN("login"),
        VERIFY_EMAIL("verify_email");

        private final String value;
        Purpose(String value) { this.value = value; }
        public String value() { return value; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(nullable = false, length = 20)
    private String purpose;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    /**
     * Dirección a la que se envió. Se congela en la fila para que un cambio de
     * correo entre la emisión y la verificación no redirija un código en vuelo.
     */
    @Column(nullable = false, length = 254)
    private String destination;

    @Column(nullable = false)
    private short attempts = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** Marcado al usarlo, al quemarlo por intentos, o al emitir uno nuevo. */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(length = 45)
    private String ip;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public short getAttempts() { return attempts; }
    public void setAttempts(short attempts) { this.attempts = attempts; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
}

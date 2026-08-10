package com.biblioteca.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(unique = true, nullable = false, length = 80)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(length = 20)
    private String role = "user";

    @Column(name = "totp_secret", columnDefinition = "TEXT")
    private String totpSecret;

    @Column(name = "full_name", length = 150)
    private String fullName;

    @Column(length = 100)
    private String area;

    @Column(length = 100)
    private String position;

    @Column(length = 100)
    private String factory;

    @Column(name = "contact_info", length = 200)
    private String contactInfo;

    @Column(name = "profile_pic", length = 255)
    private String profilePic = "default.png";

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    /**
     * Updated every time the password is changed. JWTs issued BEFORE this
     * timestamp are rejected — invalidates all sessions on password change.
     */
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    /** Contador de fallos consecutivos al verificar currentPassword en /change-password. */
    @Column(name = "failed_password_attempts", nullable = false)
    private int failedPasswordAttempts = 0;

    /** Si está en el futuro, /change-password rechaza el self-change hasta esa hora. */
    @Column(name = "password_locked_until")
    private LocalDateTime passwordLockedUntil;

    /**
     * Fallos consecutivos al verificar un código TOTP (/verify-2fa, /disable-2fa).
     * Separado de failedPasswordAttempts a propósito: son dos factores distintos
     * y un fallo en uno no debe bloquear el flujo del otro.
     */
    @Column(name = "failed_totp_attempts", nullable = false)
    private int failedTotpAttempts = 0;

    /** Si está en el futuro, se rechaza toda verificación de TOTP hasta esa hora. */
    @Column(name = "totp_locked_until")
    private LocalDateTime totpLockedUntil;

    /**
     * Secret emitido por /setup-2fa y aún sin confirmar, cifrado con
     * EncryptionService. Vive aquí y no en el cliente para que el factor que
     * termina activo sea el que generó el servidor.
     */
    @Column(name = "totp_pending_secret", columnDefinition = "TEXT")
    private String totpPendingSecret;

    /**
     * Correo verificado de la cuenta. Solo se llena desde {@link #pendingEmail}
     * cuando un código enviado a esa dirección volvió correcto: mientras esté
     * aquí, alguien probó que puede leer ese buzón.
     */
    @Column(length = 254)
    private String email;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /** Dirección propuesta y todavía sin probar. Ver V9 para el porqué de la separación. */
    @Column(name = "pending_email", length = 254)
    private String pendingEmail;

    /** Segundo factor por código enviado al correo. Exige emailVerified. */
    @Column(name = "email_2fa_enabled", nullable = false)
    private boolean email2faEnabled = false;

    /** 'off' | 'new_device' | 'always' — ver {@code LoginAlertService}. */
    @Column(name = "login_alerts", nullable = false, length = 16)
    private String loginAlerts = "new_device";

    /**
     * Fallos consecutivos verificando un código enviado por correo. Columna
     * propia, no compartida con los contadores de TOTP ni de contraseña: son
     * factores distintos y un fallo en uno no debe bloquear el flujo del otro.
     */
    @Column(name = "failed_email_code_attempts", nullable = false)
    private int failedEmailCodeAttempts = 0;

    /** Si está en el futuro, no se verifica ningún código por correo. */
    @Column(name = "email_code_locked_until")
    private LocalDateTime emailCodeLockedUntil;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public String getFactory() { return factory; }
    public void setFactory(String factory) { this.factory = factory; }
    public String getContactInfo() { return contactInfo; }
    public void setContactInfo(String contactInfo) { this.contactInfo = contactInfo; }
    public String getProfilePic() { return profilePic; }
    public void setProfilePic(String profilePic) { this.profilePic = profilePic; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }
    public LocalDateTime getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(LocalDateTime passwordChangedAt) { this.passwordChangedAt = passwordChangedAt; }
    public int getFailedPasswordAttempts() { return failedPasswordAttempts; }
    public void setFailedPasswordAttempts(int failedPasswordAttempts) { this.failedPasswordAttempts = failedPasswordAttempts; }
    public LocalDateTime getPasswordLockedUntil() { return passwordLockedUntil; }
    public void setPasswordLockedUntil(LocalDateTime passwordLockedUntil) { this.passwordLockedUntil = passwordLockedUntil; }
    public int getFailedTotpAttempts() { return failedTotpAttempts; }
    public void setFailedTotpAttempts(int failedTotpAttempts) { this.failedTotpAttempts = failedTotpAttempts; }
    public LocalDateTime getTotpLockedUntil() { return totpLockedUntil; }
    public void setTotpLockedUntil(LocalDateTime totpLockedUntil) { this.totpLockedUntil = totpLockedUntil; }
    public String getTotpPendingSecret() { return totpPendingSecret; }
    public void setTotpPendingSecret(String totpPendingSecret) { this.totpPendingSecret = totpPendingSecret; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public String getPendingEmail() { return pendingEmail; }
    public void setPendingEmail(String pendingEmail) { this.pendingEmail = pendingEmail; }
    public boolean isEmail2faEnabled() { return email2faEnabled; }
    public void setEmail2faEnabled(boolean email2faEnabled) { this.email2faEnabled = email2faEnabled; }
    public String getLoginAlerts() { return loginAlerts; }
    public void setLoginAlerts(String loginAlerts) { this.loginAlerts = loginAlerts; }
    public int getFailedEmailCodeAttempts() { return failedEmailCodeAttempts; }
    public void setFailedEmailCodeAttempts(int failedEmailCodeAttempts) { this.failedEmailCodeAttempts = failedEmailCodeAttempts; }
    public LocalDateTime getEmailCodeLockedUntil() { return emailCodeLockedUntil; }
    public void setEmailCodeLockedUntil(LocalDateTime emailCodeLockedUntil) { this.emailCodeLockedUntil = emailCodeLockedUntil; }

    /**
     * true cuando el correo puede usarse como canal de confianza: hay
     * dirección y alguien probó que la lee. Es la precondición de todo lo
     * demás (2FA por correo, avisos de inicio de sesión).
     */
    public boolean hasUsableEmail() {
        return email != null && !email.isBlank() && emailVerified;
    }
}

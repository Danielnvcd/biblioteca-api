package com.biblioteca.dto;

import java.time.LocalDateTime;

public class UserDto {
    private Integer id;
    private String username;
    private String role;
    private String fullName;
    private String area;
    private String position;
    private String factory;
    private String contactInfo;
    private String profilePic;
    private boolean totpEnabled;
    private LocalDateTime lastSeen;

    /**
     * Correo de la cuenta. Solo se llena en la vista del PROPIO usuario (o de
     * un super_admin): en el directorio va siempre null, porque una lista de
     * correos internos es material de phishing servido en bandeja.
     */
    private String email;
    private boolean emailVerified;
    /** Dirección dada de alta y todavía sin confirmar, si hay una en curso. */
    private String pendingEmail;
    private boolean email2faEnabled;
    /**
     * false en las cuentas de administración, que no pueden usar el correo
     * como segundo factor (ver Permissions.canUseEmailAsSecondFactor). Va en
     * el DTO para que la pantalla explique el porqué en vez de dejar un
     * interruptor que devuelve 403 al tocarlo.
     */
    private boolean emailFactorAllowed;
    /** 'off' | 'new_device' | 'always' */
    private String loginAlerts;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
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
    public boolean isTotpEnabled() { return totpEnabled; }
    public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public String getPendingEmail() { return pendingEmail; }
    public void setPendingEmail(String pendingEmail) { this.pendingEmail = pendingEmail; }
    public boolean isEmail2faEnabled() { return email2faEnabled; }
    public void setEmail2faEnabled(boolean email2faEnabled) { this.email2faEnabled = email2faEnabled; }
    public boolean isEmailFactorAllowed() { return emailFactorAllowed; }
    public void setEmailFactorAllowed(boolean emailFactorAllowed) { this.emailFactorAllowed = emailFactorAllowed; }
    public String getLoginAlerts() { return loginAlerts; }
    public void setLoginAlerts(String loginAlerts) { this.loginAlerts = loginAlerts; }
}

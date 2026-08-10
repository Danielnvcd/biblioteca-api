package com.biblioteca.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body de PUT /api/auth/email/preferences.
 *
 * Los dos campos son opcionales: la pantalla manda solo el que el usuario
 * tocó. Un null significa "no cambiar", no "poner en falso".
 *
 * currentPassword solo hace falta para mover {@code email2faEnabled}, que es
 * un cambio de factor de autenticación. Cambiar la preferencia de avisos no la
 * pide: no debilita nada y obligar a tipear la contraseña para un interruptor
 * de notificaciones lleva a que nadie lo use.
 */
public class EmailPreferencesRequest {

    @Pattern(regexp = "off|new_device|always", message = "Preferencia de avisos inválida")
    private String loginAlerts;

    private Boolean email2faEnabled;

    @Size(max = 200)
    private String currentPassword;

    public String getLoginAlerts() { return loginAlerts; }
    public void setLoginAlerts(String loginAlerts) { this.loginAlerts = loginAlerts; }
    public Boolean getEmail2faEnabled() { return email2faEnabled; }
    public void setEmail2faEnabled(Boolean email2faEnabled) { this.email2faEnabled = email2faEnabled; }
    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
}

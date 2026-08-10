package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body de DELETE /api/auth/email.
 *
 * Quitar el correo apaga el 2FA por correo y los avisos de intrusión, o sea
 * que es una operación que BAJA la seguridad de la cuenta — justo lo primero
 * que intentaría quien entró con un token robado. Por eso exige contraseña.
 */
public class RemoveEmailRequest {

    @NotBlank(message = "Ingresa tu contraseña actual")
    @Size(max = 200)
    private String currentPassword;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
}

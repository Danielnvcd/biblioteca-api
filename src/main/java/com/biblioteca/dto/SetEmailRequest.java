package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body de POST /api/auth/email — da de alta (o cambia) el correo de la cuenta.
 *
 * Pide la contraseña actual por el mismo motivo que /setup-2fa: el correo pasa
 * a ser canal de segundo factor y de aviso de intrusión, así que un access
 * token robado no puede alcanzar para redirigirlo a un buzón ajeno.
 */
public class SetEmailRequest {

    @NotBlank(message = "Ingresa tu contraseña actual")
    @Size(max = 200)
    private String currentPassword;

    @NotBlank(message = "Ingresa un correo")
    @Size(max = 254)
    private String email;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}

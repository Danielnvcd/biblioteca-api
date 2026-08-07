package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body de POST /api/auth/disable-2fa.
 *
 * Pide las dos cosas a propósito: la contraseña prueba que quien opera conoce
 * la credencial (no solo que robó un access token) y el código prueba que tiene
 * el dispositivo en la mano.
 */
public class Disable2faRequest {

    @NotBlank(message = "Ingresa tu contraseña actual")
    @Size(max = 200)
    private String currentPassword;

    @NotBlank(message = "Ingresa el código de tu app autenticadora")
    @Size(max = 10)
    private String code;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}

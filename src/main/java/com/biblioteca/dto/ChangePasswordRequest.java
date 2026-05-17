package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChangePasswordRequest {

    /** Required only for self-change. El controller hace la lógica condicional. */
    @Size(max = 200)
    private String currentPassword;

    @NotBlank
    @Size(min = 8, max = 200, message = "La contraseña debe tener al menos 8 caracteres")
    private String newPassword;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}

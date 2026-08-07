package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ChangePasswordRequest {

    /** Required only for self-change. El controller hace la lógica condicional. */
    @Size(max = 200)
    private String currentPassword;

    /**
     * Misma política que RegisterRequest. Antes solo exigía longitud, así que
     * una cuenta creada con "abc12345" podía cambiarse a "12345678" y quedar
     * más débil de lo que el registro permitía — el punto de control más
     * frecuente era el más laxo.
     */
    @NotBlank
    @Size(min = 8, max = 200, message = "La contraseña debe tener al menos 8 caracteres")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
             message = "La contraseña debe incluir al menos una letra y un número")
    private String newPassword;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}

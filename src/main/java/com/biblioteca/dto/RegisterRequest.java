package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class RegisterRequest {

    @NotBlank
    @Size(min = 3, max = 80, message = "El usuario debe tener entre 3 y 80 caracteres")
    private String username;

    @NotBlank
    @Size(min = 8, max = 200, message = "La contraseña debe tener al menos 8 caracteres")
    private String password;

    /** Lo valida {@code Permissions.VALID_ROLES} en el controller, pero limitamos longitud aquí. */
    @Size(max = 20)
    @Pattern(regexp = "^[a-z_]*$", message = "Rol inválido")
    private String role;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}

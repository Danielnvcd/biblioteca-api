package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class Confirm2faRequest {

    @NotBlank
    @Size(max = 200)
    private String currentPassword;

    /** El secret base32 generado por TotpService.newSecret(). */
    @NotBlank
    @Size(max = 64)
    private String secret;

    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "El código debe tener 6 dígitos")
    private String code;

    public String getCurrentPassword() { return currentPassword; }
    public void setCurrentPassword(String currentPassword) { this.currentPassword = currentPassword; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}

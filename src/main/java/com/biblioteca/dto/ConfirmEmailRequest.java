package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body de POST /api/auth/email/confirm — código recibido en la dirección pendiente. */
public class ConfirmEmailRequest {

    @NotBlank(message = "Ingresa el código que te enviamos")
    @Size(max = 10)
    private String code;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}

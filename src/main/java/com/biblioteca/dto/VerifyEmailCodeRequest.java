package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body de POST /api/auth/verify-email-code — segundo paso del login por correo. */
public class VerifyEmailCodeRequest {

    /** Step token emitido por /login. Prueba que el paso 1 se completó. */
    @NotBlank
    private String stepToken;

    @NotBlank(message = "Ingresa el código que te enviamos")
    @Size(max = 10)
    private String code;

    public String getStepToken() { return stepToken; }
    public void setStepToken(String stepToken) { this.stepToken = stepToken; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}

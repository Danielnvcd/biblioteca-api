package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body de POST /api/auth/request-email-code.
 *
 * Solo lleva el step token, y eso es deliberado: NO recibe username ni id de
 * usuario. Si los recibiera, cualquiera podría disparar códigos hacia el buzón
 * de cualquier cuenta con solo saber un nombre de usuario. Aquí el destinatario
 * sale del token, que solo se emite después de validar la contraseña.
 */
public class RequestEmailCodeRequest {

    @NotBlank
    private String stepToken;

    public String getStepToken() { return stepToken; }
    public void setStepToken(String stepToken) { this.stepToken = stepToken; }
}

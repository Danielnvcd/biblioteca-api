package com.biblioteca.dto;

import java.util.List;

public class LoginResponse {
    private String token;
    private UserDto user;
    private boolean requires2fa;
    private String message;
    /** Short-lived token bound to step 1; required by /verify-2fa. */
    private String stepToken;

    /**
     * Segundos factores disponibles para esta cuenta: {@code "totp"} y/o
     * {@code "email"}. La pantalla de verificación la usa para saber qué
     * ofrecer — sin esto tendría que adivinar, o pedir otro round-trip.
     */
    private List<String> methods;

    /**
     * Correo enmascarado ({@code da••••@dominio.com}) cuando el método por
     * correo está disponible. Enmascarado y no completo a propósito: a esta
     * pantalla se llega sabiendo solo la contraseña, y no hay razón para que
     * ese punto del flujo revele la dirección entera.
     */
    private String maskedEmail;

    /** true si /login ya disparó el código por correo y no hay que pedirlo. */
    private boolean codeSent;

    public LoginResponse() {}

    public LoginResponse(String token, UserDto user) {
        this.token = token;
        this.user = user;
        this.requires2fa = false;
    }

    public static LoginResponse twoFactorPending(String stepToken, String message) {
        LoginResponse r = new LoginResponse();
        r.requires2fa = true;
        r.stepToken = stepToken;
        r.message = message;
        return r;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public UserDto getUser() { return user; }
    public void setUser(UserDto user) { this.user = user; }
    public boolean isRequires2fa() { return requires2fa; }
    public void setRequires2fa(boolean requires2fa) { this.requires2fa = requires2fa; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getStepToken() { return stepToken; }
    public void setStepToken(String stepToken) { this.stepToken = stepToken; }
    public List<String> getMethods() { return methods; }
    public void setMethods(List<String> methods) { this.methods = methods; }
    public String getMaskedEmail() { return maskedEmail; }
    public void setMaskedEmail(String maskedEmail) { this.maskedEmail = maskedEmail; }
    public boolean isCodeSent() { return codeSent; }
    public void setCodeSent(boolean codeSent) { this.codeSent = codeSent; }
}

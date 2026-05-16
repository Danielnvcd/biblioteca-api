package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;

public class Verify2faRequest {
    /** Step token issued by /login when 2FA is required. */
    @NotBlank
    private String stepToken;

    @NotBlank
    private String code;

    /** Legacy field — accepted for compatibility but ignored when stepToken is present. */
    private String username;

    public String getStepToken() { return stepToken; }
    public void setStepToken(String stepToken) { this.stepToken = stepToken; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}

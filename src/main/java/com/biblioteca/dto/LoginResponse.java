package com.biblioteca.dto;

public class LoginResponse {
    private String token;
    private UserDto user;
    private boolean requires2fa;
    private String message;
    /** Short-lived token bound to step 1; required by /verify-2fa. */
    private String stepToken;

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
}

package com.biblioteca.dto;

public class LoginResponse {
    private String token;
    private UserDto user;
    private boolean requires2fa;
    private String message;

    public LoginResponse() {}

    public LoginResponse(String token, UserDto user) {
        this.token = token;
        this.user = user;
        this.requires2fa = false;
    }

    public LoginResponse(boolean requires2fa, String message) {
        this.requires2fa = requires2fa;
        this.message = message;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public UserDto getUser() { return user; }
    public void setUser(UserDto user) { this.user = user; }
    public boolean isRequires2fa() { return requires2fa; }
    public void setRequires2fa(boolean requires2fa) { this.requires2fa = requires2fa; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}

package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;

public class LoginRequest {
    @NotBlank
    private String username;
    @NotBlank
    private String password;
    /** "Remember me": if true, the server issues a refresh token (7-day cookie).
     *  If false, only a short-lived access token is issued — when it expires
     *  (15 min), the user has to log in again. */
    private boolean remember;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isRemember() { return remember; }
    public void setRemember(boolean remember) { this.remember = remember; }
}

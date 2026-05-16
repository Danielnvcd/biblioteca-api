package com.biblioteca.security;

public class UserPrincipal {
    private final Integer id;
    private final String username;
    private final String role;

    public UserPrincipal(Integer id, String username, String role) {
        this.id = id;
        this.username = username;
        this.role = role;
    }

    public Integer getId() { return id; }
    public String getUsername() { return username; }
    public String getRole() { return role; }
}

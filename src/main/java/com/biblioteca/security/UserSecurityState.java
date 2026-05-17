package com.biblioteca.security;

import java.time.LocalDateTime;

/**
 * Minimal user snapshot needed by the JWT filter.
 */
public record UserSecurityState(
        Integer id,
        String username,
        String role,
        LocalDateTime passwordChangedAt) {
}

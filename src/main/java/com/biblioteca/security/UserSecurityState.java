package com.biblioteca.security;

import java.time.LocalDateTime;

/**
 * Minimal user snapshot needed by the JWT filter.
 */
public record UserSecurityState(
        Integer id,
        String username,
        String role,
        LocalDateTime passwordChangedAt,
        /** Última actividad registrada. Viaja acá para que el filtro pueda
         *  decidir si toca refrescarla sin hacer una consulta extra. */
        LocalDateTime lastSeen) {
}

package com.biblioteca.repository;

import com.biblioteca.model.RevokedAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface RevokedAccessTokenRepository extends JpaRepository<RevokedAccessToken, String> {

    /**
     * Borra las entradas cuyo token ya venció por su cuenta. A partir de ese
     * momento la fila no aporta nada: el token se rechaza por expirado.
     */
    @Modifying
    @Query("DELETE FROM RevokedAccessToken t WHERE t.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}

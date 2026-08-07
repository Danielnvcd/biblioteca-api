package com.biblioteca.repository;

import com.biblioteca.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now " +
           "WHERE r.userId = :userId AND r.revokedAt IS NULL")
    int revokeAllActiveForUser(@Param("userId") Integer userId,
                               @Param("now") LocalDateTime now);

    /**
     * Sesiones vigentes de un usuario: ni revocadas ni vencidas. Alimenta la
     * pantalla "sesiones activas" del perfil.
     */
    List<RefreshToken> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            Integer userId, LocalDateTime now);

    /**
     * Cierra todas las sesiones activas del usuario MENOS una — la que está
     * usando quien pide la operación. Sin la excepción, "cerrar las demás
     * sesiones" desloguearía también a quien apretó el botón, que es
     * exactamente lo contrario de lo que espera.
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now " +
           "WHERE r.userId = :userId AND r.revokedAt IS NULL AND r.id <> :keepId")
    int revokeAllActiveForUserExcept(@Param("userId") Integer userId,
                                     @Param("keepId") Long keepId,
                                     @Param("now") LocalDateTime now);

    /**
     * Atomically revokes the token only if it is still active. Returns the
     * number of rows affected — 0 means another request already rotated or
     * revoked it (lost race), and the caller should treat that as reuse.
     */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now, r.replacedBy = :replacedBy " +
           "WHERE r.id = :id AND r.revokedAt IS NULL")
    int markRevokedIfActive(@Param("id") Long id,
                            @Param("now") LocalDateTime now,
                            @Param("replacedBy") Long replacedBy);
}

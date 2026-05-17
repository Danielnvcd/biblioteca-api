package com.biblioteca.repository;

import com.biblioteca.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now " +
           "WHERE r.userId = :userId AND r.revokedAt IS NULL")
    int revokeAllActiveForUser(@Param("userId") Integer userId,
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

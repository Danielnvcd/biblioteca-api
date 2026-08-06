package com.biblioteca.repository;

import com.biblioteca.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Integer> {
    List<AuditLog> findAllByOrderByCreatedAtDesc();

    /**
     * Eventos de un usuario puntual, más recientes primero. Alimenta el panel
     * "mi actividad" del perfil: la bitácora completa sigue siendo exclusiva de
     * super_admin, esto solo devuelve lo propio.
     *
     * El campo se llama `user` en la entidad pero mapea a la columna `username`.
     */
    Page<AuditLog> findByUserOrderByCreatedAtDesc(String user, Pageable pageable);

    @Query("SELECT l FROM AuditLog l ORDER BY l.createdAt DESC")
    List<AuditLog> findRecent(Pageable pageable);

    /**
     * Paginated free-text search over user / action / ip.
     *
     * NB: we use {@code :q = ''} (empty string) for "no filter" instead of
     * {@code :q IS NULL}, because PostgreSQL can't infer the bind-variable
     * type from a null and ends up treating it as bytea, then chokes on
     * {@code lower(bytea)}. Callers must coerce null → "" before invoking.
     */
    @Query("SELECT l FROM AuditLog l " +
           "WHERE :q = '' OR LOWER(l.user)   LIKE LOWER(CONCAT('%', :q, '%')) " +
           "             OR LOWER(l.action) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "             OR LOWER(l.ip)     LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<AuditLog> search(@Param("q") String q, Pageable pageable);
}

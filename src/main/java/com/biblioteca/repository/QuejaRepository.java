package com.biblioteca.repository;

import com.biblioteca.model.Queja;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Map;

public interface QuejaRepository extends JpaRepository<Queja, Integer> {
    List<Queja> findAllByOrderByFechaDesc();

    @Query("SELECT q.estado AS estado, COUNT(q.id) AS cnt FROM Queja q GROUP BY q.estado")
    List<Map<String, Object>> countByEstado();

    /** Paginated search with optional estado filter and free-text q over folio/cliente/motivo.
     *  See {@link com.biblioteca.repository.AuditLogRepository#search} for why null params are coerced to ''. */
    @Query("SELECT q FROM Queja q " +
           "WHERE (:estado = '' OR q.estado = :estado) " +
           "AND (:q = '' OR LOWER(q.folio)   LIKE LOWER(CONCAT('%', :q, '%')) " +
           "             OR LOWER(q.cliente) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "             OR LOWER(q.motivo)  LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Queja> search(@Param("estado") String estado,
                       @Param("q") String q,
                       Pageable pageable);
}

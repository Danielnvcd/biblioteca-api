package com.biblioteca.repository;

import com.biblioteca.model.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Integer> {
    List<AuditLog> findAllByOrderByCreatedAtDesc();

    @Query("SELECT l FROM AuditLog l ORDER BY l.createdAt DESC")
    List<AuditLog> findRecent(Pageable pageable);
}

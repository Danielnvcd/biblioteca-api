package com.biblioteca.repository;

import com.biblioteca.model.Queja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Map;

public interface QuejaRepository extends JpaRepository<Queja, Integer> {
    List<Queja> findAllByOrderByFechaDesc();

    @Query("SELECT q.estado AS estado, COUNT(q.id) AS cnt FROM Queja q GROUP BY q.estado")
    List<Map<String, Object>> countByEstado();
}

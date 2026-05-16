package com.biblioteca.repository;

import com.biblioteca.model.Seguridad;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SeguridadRepository extends JpaRepository<Seguridad, Integer> {
    List<Seguridad> findTop3ByOrderByIdDesc();
    List<Seguridad> findAllByOrderByFechaDesc();
}

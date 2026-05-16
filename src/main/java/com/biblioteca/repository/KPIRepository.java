package com.biblioteca.repository;

import com.biblioteca.model.KPI;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KPIRepository extends JpaRepository<KPI, Integer> {
}

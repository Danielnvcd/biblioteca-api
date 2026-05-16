package com.biblioteca.repository;

import com.biblioteca.model.CorrectiveAction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CorrectiveActionRepository extends JpaRepository<CorrectiveAction, Integer> {
    Optional<CorrectiveAction> findByFolio(String folio);
}

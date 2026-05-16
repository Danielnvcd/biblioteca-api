package com.biblioteca.repository;

import com.biblioteca.model.CorrectionActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CorrectionActivityRepository extends JpaRepository<CorrectionActivity, Integer> {
    List<CorrectionActivity> findByEstatusNot(String estatus);
    List<CorrectionActivity> findByActionId(Integer actionId);
}

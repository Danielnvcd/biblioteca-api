package com.biblioteca.repository;

import com.biblioteca.model.CorrectionActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CorrectionActivityRepository extends JpaRepository<CorrectionActivity, Integer> {
    List<CorrectionActivity> findByEstatusNot(String estatus);
    List<CorrectionActivity> findByActionId(Integer actionId);

    /**
     * Eagerly fetches the parent action — needed when open-in-view is off,
     * since we access act.action.folio in the response mapping.
     */
    @Query("SELECT a FROM CorrectionActivity a LEFT JOIN FETCH a.action " +
           "WHERE a.estatus <> 'Realizado' ORDER BY a.fechaCompromiso ASC")
    List<CorrectionActivity> findPendingWithAction();
}

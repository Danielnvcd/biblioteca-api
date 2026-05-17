package com.biblioteca.repository;

import com.biblioteca.model.CorrectiveAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CorrectiveActionRepository extends JpaRepository<CorrectiveAction, Integer> {
    Optional<CorrectiveAction> findByFolio(String folio);

    /**
     * Eager fetch of activities — needed when open-in-view is off, because
     * the list endpoint serializes a.getActivities().
     */
    @Query("SELECT DISTINCT a FROM CorrectiveAction a LEFT JOIN FETCH a.activities " +
           "ORDER BY a.fechaReporte DESC NULLS LAST, a.id DESC")
    List<CorrectiveAction> findAllWithActivities();

    /**
     * Paginated variant. EntityGraph triggers a separate batch fetch for
     * activities per page (instead of the JOIN FETCH used above, which
     * Hibernate would paginate in memory and warn about).
     */
    @EntityGraph(attributePaths = "activities")
    Page<CorrectiveAction> findAllBy(Pageable pageable);

    @Query("SELECT a FROM CorrectiveAction a LEFT JOIN FETCH a.activities WHERE a.id = :id")
    Optional<CorrectiveAction> findByIdWithActivities(Integer id);
}

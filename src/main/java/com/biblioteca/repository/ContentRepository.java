package com.biblioteca.repository;

import com.biblioteca.model.Content;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ContentRepository extends JpaRepository<Content, String> {
    List<Content> findByCategoryOrderByCreatedAtDesc(String category);
    List<Content> findByCategoryAndManualCategoryOrderByCreatedAtDesc(String category, String manualCategory);
    List<Content> findTop3ByOrderByCreatedAtDesc();
    /** Last 3 docs, excluding role-restricted categories (so the dashboard
     *  doesn't leak titles/authors from {@code almacenes} to users without that role). */
    List<Content> findTop3ByCategoryNotInOrderByCreatedAtDesc(java.util.Collection<String> categories);
    List<Content> findByCategoryOrderByCreatedAtDesc(String category, Pageable pageable);

    /**
     * Paginated listing with optional sub-category filter and free-text search.
     * Empty-string params mean "don't filter on that field" — see
     * {@link AuditLogRepository#search} for why we don't use null here.
     */
    @Query("SELECT c FROM Content c WHERE c.category = :category " +
           "AND (:manualCategory = '' OR c.manualCategory = :manualCategory) " +
           "AND (:q = '' OR LOWER(c.title)       LIKE LOWER(CONCAT('%', :q, '%')) " +
           "             OR LOWER(c.description) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "             OR LOWER(c.author)      LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Content> search(@Param("category") String category,
                         @Param("manualCategory") String manualCategory,
                         @Param("q") String q,
                         Pageable pageable);
}

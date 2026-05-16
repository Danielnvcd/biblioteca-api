package com.biblioteca.repository;

import com.biblioteca.model.Content;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ContentRepository extends JpaRepository<Content, String> {
    List<Content> findByCategoryOrderByCreatedAtDesc(String category);
    List<Content> findByCategoryAndManualCategoryOrderByCreatedAtDesc(String category, String manualCategory);
    List<Content> findTop3ByOrderByCreatedAtDesc();
    List<Content> findByCategoryOrderByCreatedAtDesc(String category, org.springframework.data.domain.Pageable pageable);
}

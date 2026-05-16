package com.biblioteca.repository;

import com.biblioteca.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Integer> {
    List<Category> findByTypeOrderBySortOrder(String type);
}

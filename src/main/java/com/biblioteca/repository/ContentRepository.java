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
     * Sentinel para pedir explícitamente los items SIN sub-categoría.
     *
     * Hace falta porque en esta query el string vacío ya significa "no filtres
     * por sub-categoría", así que no había forma de expresar "solo los que no
     * tienen ninguna" — esos items quedaban visibles únicamente en "Todas".
     * No colisiona con ningún nombre real: la tabla categories guarda nombres
     * escritos por humanos ("MSDS", "Información general").
     */
    String NO_CATEGORY = "__none__";

    /**
     * Paginated listing with optional sub-category filter and free-text search.
     * Empty-string params mean "don't filter on that field" — see
     * {@link AuditLogRepository#search} for why we don't use null here.
     */
    @Query("SELECT c FROM Content c WHERE c.category = :category " +
           "AND (:manualCategory = '' " +
           "     OR (:manualCategory = '__none__' AND (c.manualCategory IS NULL OR c.manualCategory = '')) " +
           "     OR c.manualCategory = :manualCategory) " +
           "AND (:q = '' OR LOWER(c.title)       LIKE LOWER(CONCAT('%', :q, '%')) " +
           "             OR LOWER(c.description) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "             OR LOWER(c.author)      LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<Content> search(@Param("category") String category,
                         @Param("manualCategory") String manualCategory,
                         @Param("q") String q,
                         Pageable pageable);

    /**
     * Cuántos archivos hay por sub-categoría dentro de una sección. Alimenta los
     * contadores de los chips del frontend.
     *
     * Respeta el mismo filtro de texto que {@link #search} para que los números
     * concuerden con lo que el usuario está viendo al buscar. Lo que NO filtra
     * es la sub-categoría, a propósito: los chips tienen que seguir mostrando el
     * total de cada una aunque haya una seleccionada, o al elegir "MSDS" el
     * resto mostraría 0 y no se podría comparar.
     *
     * Devuelve filas {manualCategory, count}; NULL y '' se agrupan juntos bajo
     * '' (son lo mismo para el usuario: un archivo sin categoría asignada).
     */
    @Query("SELECT COALESCE(c.manualCategory, ''), COUNT(c) FROM Content c " +
           "WHERE c.category = :category " +
           "AND (:q = '' OR LOWER(c.title)       LIKE LOWER(CONCAT('%', :q, '%')) " +
           "             OR LOWER(c.description) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "             OR LOWER(c.author)      LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "GROUP BY COALESCE(c.manualCategory, '')")
    List<Object[]> countByManualCategory(@Param("category") String category, @Param("q") String q);
}

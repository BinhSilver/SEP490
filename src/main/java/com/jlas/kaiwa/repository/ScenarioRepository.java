package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.Scenario;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Scenario rows carry large flow/learning JSON. MySQL sorts whole rows, so an ORDER BY over them fails with
 * "Out of sort memory" once one row (plus a fetched parent) exceeds sort_buffer_size; order these lists in Java.
 */
public interface ScenarioRepository extends JpaRepository<Scenario, Long> {
    @Query("""
        SELECT s FROM Scenario s
        LEFT JOIN FETCH s.aiRole
        WHERE s.category.id = :categoryId AND s.active = true AND s.parentScenario IS NULL
        """)
    List<Scenario> findActiveRootsInCategory(@Param("categoryId") Long categoryId);

    default List<Scenario> findByCategoryIdAndActiveTrueOrderByIdAsc(Long categoryId) {
        return findActiveRootsInCategory(categoryId).stream()
                .sorted(Comparator.comparing(Scenario::getId))
                .toList();
    }

    @Query("""
        SELECT s FROM Scenario s
        JOIN FETCH s.category
        LEFT JOIN FETCH s.aiRole
        LEFT JOIN FETCH s.userRole
        LEFT JOIN FETCH s.promptTemplate
        LEFT JOIN FETCH s.parentScenario parent
        WHERE s.id = :id AND s.active = true AND (parent IS NULL OR parent.active = true)
        """)
    Optional<Scenario> findActiveDetail(@Param("id") Long id);

    long countByCategoryIdAndActiveTrueAndParentScenarioIsNull(Long categoryId);

    @Query("""
        SELECT s FROM Scenario s
        LEFT JOIN FETCH s.parentScenario parent
        WHERE s.active = true AND (s.id = :rootId OR parent.id = :rootId)
          AND (parent IS NULL OR parent.active = true)
        """)
    List<Scenario> findActiveCasesUnordered(@Param("rootId") Long rootId);

    /** The root case first, then its variants by ID. */
    default List<Scenario> findActiveCases(Long rootId) {
        return findActiveCasesUnordered(rootId).stream()
                .sorted(Comparator.comparing((Scenario s) -> !s.getId().equals(rootId)).thenComparing(Scenario::getId))
                .toList();
    }

    Optional<Scenario> findBySlug(String slug);
}

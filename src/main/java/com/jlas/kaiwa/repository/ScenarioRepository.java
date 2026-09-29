package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.Scenario;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {
    @Query("""
        SELECT s FROM Scenario s
        LEFT JOIN FETCH s.aiRole
        WHERE s.category.id = :categoryId AND s.active = true
        ORDER BY s.id ASC
        """)
    List<Scenario> findByCategoryIdAndActiveTrueOrderByIdAsc(@Param("categoryId") Long categoryId);

    @Query("""
        SELECT s FROM Scenario s
        JOIN FETCH s.category
        LEFT JOIN FETCH s.aiRole
        LEFT JOIN FETCH s.userRole
        LEFT JOIN FETCH s.promptTemplate
        WHERE s.id = :id AND s.active = true
        """)
    Optional<Scenario> findActiveDetail(@Param("id") Long id);

    long countByCategoryIdAndActiveTrue(Long categoryId);

    Optional<Scenario> findBySlug(String slug);
}

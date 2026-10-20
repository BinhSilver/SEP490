package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningPathPackage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LearningPathPackageRepository extends JpaRepository<LearningPathPackage, Long> {
    List<LearningPathPackage> findByLearningPathIdOrderByOrderIndex(Long learningPathId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LearningPathPackage p where p.learningPath.id = :pathId order by p.orderIndex")
    List<LearningPathPackage> findByPathForUpdate(@Param("pathId") Long pathId);
    boolean existsByLearningPathId(Long learningPathId);

    /** [JLAS-W0193] Task 9 — Warm-up B composes independently of GOAL_LEARNING packages and needs its own
     * idempotency check (retrying path creation must not duplicate warm-up packages even though target
     * packages may already exist, or vice versa). */
    boolean existsByLearningPathIdAndPhase(Long learningPathId, String phase);

    @Query("select p from LearningPathPackage p where p.id = :id and p.learningPath.user.id = :userId")
    Optional<LearningPathPackage> findOwnedById(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LearningPathPackage p where p.id = :id and p.learningPath.user.id = :userId")
    Optional<LearningPathPackage> findOwnedByIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}

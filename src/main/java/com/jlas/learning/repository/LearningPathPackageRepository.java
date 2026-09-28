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

    @Query("select p from LearningPathPackage p where p.id = :id and p.learningPath.user.id = :userId")
    Optional<LearningPathPackage> findOwnedById(@Param("id") Long id, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LearningPathPackage p where p.id = :id and p.learningPath.user.id = :userId")
    Optional<LearningPathPackage> findOwnedByIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}

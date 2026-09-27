package com.jlas.learning.repository;

import com.jlas.learning.entity.UserLearningPath;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository cho {@link UserLearningPath}. */
@Repository
public interface UserLearningPathRepository extends JpaRepository<UserLearningPath, Long> {
    Optional<UserLearningPath> findByUserId(Long userId);

    Optional<UserLearningPath> findByUserIdAndStatus(Long userId, String status);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserLearningPath p where p.user.id = :userId and p.status = 'ACTIVE'")
    Optional<UserLearningPath> findActiveForFlashcardUpdate(@Param("userId") Long userId);

    /**
     * [JLAS-W0122] Idempotency lookup: same user + attempt + revision + policy must not create a
     * new version. Filtered by {@code status = ACTIVE} because replan keeps the same generation
     * identity across versions, so multiple rows (one SUPERSEDED + one ACTIVE) may share it.
     */
    Optional<UserLearningPath> findByUserIdAndAssessmentAttemptIdAndAssessmentRevisionAndPolicyVersionAndStatus(
            Long userId, Long assessmentAttemptId, String assessmentRevision, String policyVersion, String status);

    /**
     * [JLAS-W0108] Idempotency lookup for a from-zero path: same user + revision + policy, with no
     * assessment attempt (attempt is {@code NULL} for survey-only learners).
     */
    Optional<UserLearningPath> findByUserIdAndAssessmentAttemptIdIsNullAndAssessmentRevisionAndPolicyVersionAndStatus(
            Long userId, String assessmentRevision, String policyVersion, String status);

    @Query("select coalesce(max(p.version), 0) from UserLearningPath p where p.user.id = :userId")
    int findMaxVersionByUserId(@Param("userId") Long userId);
}

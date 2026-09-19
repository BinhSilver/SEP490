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

    /** Idempotency lookup: same user + attempt + revision + policy must not create a new version. */
    Optional<UserLearningPath> findByUserIdAndAssessmentAttemptIdAndAssessmentRevisionAndPolicyVersion(
            Long userId, Long assessmentAttemptId, String assessmentRevision, String policyVersion);

    @Query("select coalesce(max(p.version), 0) from UserLearningPath p where p.user.id = :userId")
    int findMaxVersionByUserId(@Param("userId") Long userId);
}

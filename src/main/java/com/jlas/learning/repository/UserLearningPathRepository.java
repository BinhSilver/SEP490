package com.jlas.learning.repository;

import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.enums.LearningExperienceVersion;
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

    Optional<UserLearningPath> findByUserIdAndSurveyIdAndExperienceVersion(
            Long userId, Long surveyId, LearningExperienceVersion experienceVersion);

    Optional<UserLearningPath> findByUserIdAndStatusAndExperienceVersion(
            Long userId, String status, LearningExperienceVersion experienceVersion);

    /** [JLAS-W0193] Task 10 — the summary/certificate pages must stay reachable after the path has
     * transitioned to {@code COMPLETED} (see Task 10's path-completion fix in
     * {@code InteractiveLearningPathServiceImpl}), unlike {@link #findByUserIdAndStatusAndExperienceVersion}
     * which is {@code ACTIVE}-only and exists for the roadmap's own "current work" lookup. Picks the
     * most-recently-created V2 path for the user regardless of status (ACTIVE/COMPLETED/SUPERSEDED). */
    Optional<UserLearningPath> findTopByUserIdAndExperienceVersionOrderByIdDesc(
            Long userId, LearningExperienceVersion experienceVersion);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserLearningPath p where p.user.id = :userId and p.status = 'ACTIVE'")
    Optional<UserLearningPath> findActiveForFlashcardUpdate(@Param("userId") Long userId);

    /** [JLAS-W0108] Task 9 — row lock on the learner's ACTIVE interactive path so concurrent Mock Test
     * completions serialize: the loser waits, then no longer finds an ACTIVE path and returns the completed state. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserLearningPath p where p.user.id = :userId and p.status = 'ACTIVE' "
            + "and p.experienceVersion = com.jlas.learning.enums.LearningExperienceVersion.INTERACTIVE_V2")
    Optional<UserLearningPath> lockActiveInteractive(@Param("userId") Long userId);

    /**
     * [JLAS-W0122] Idempotency lookup: same user + attempt + revision + policy must not create a
     * new version. Filtered by {@code status = ACTIVE} because replan keeps the same generation
     * identity across versions, so multiple rows (one SUPERSEDED + one ACTIVE) may share it.
     */
    Optional<UserLearningPath> findByUserIdAndAssessmentAttemptIdAndAssessmentRevisionAndPolicyVersionAndStatus(
            Long userId, Long assessmentAttemptId, String assessmentRevision, String policyVersion, String status);

    Optional<UserLearningPath>
            findByUserIdAndAssessmentAttemptIdAndAssessmentRevisionAndPolicyVersionAndExperienceVersionAndStatus(
                    Long userId, Long assessmentAttemptId, String assessmentRevision, String policyVersion,
                    LearningExperienceVersion experienceVersion, String status);

    /**
     * [JLAS-W0108] Idempotency lookup for a from-zero path: same user + revision + policy, with no
     * assessment attempt (attempt is {@code NULL} for survey-only learners).
     */
    Optional<UserLearningPath> findByUserIdAndAssessmentAttemptIdIsNullAndAssessmentRevisionAndPolicyVersionAndStatus(
            Long userId, String assessmentRevision, String policyVersion, String status);

    Optional<UserLearningPath>
            findByUserIdAndAssessmentAttemptIdIsNullAndAssessmentRevisionAndPolicyVersionAndExperienceVersionAndStatus(
                    Long userId, String assessmentRevision, String policyVersion,
                    LearningExperienceVersion experienceVersion, String status);

    @Query("select coalesce(max(p.version), 0) from UserLearningPath p where p.user.id = :userId")
    int findMaxVersionByUserId(@Param("userId") Long userId);
}

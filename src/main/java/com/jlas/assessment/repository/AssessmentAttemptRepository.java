package com.jlas.assessment.repository;

import com.jlas.assessment.entity.AssessmentAttempt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AssessmentAttemptRepository extends JpaRepository<AssessmentAttempt, Long> {
    Optional<AssessmentAttempt> findFirstByUserIdAndAttemptTypeAndStatusOrderBySubmittedAtDescIdDesc(
            Long userId, String attemptType, String status);
    Optional<AssessmentAttempt> findByIdAndUserId(Long id, Long userId);

    /** I1: locks the learner's attempt row so parallel answer/finish requests run one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AssessmentAttempt a where a.id = :id and a.user.id = :userId")
    Optional<AssessmentAttempt> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);
}

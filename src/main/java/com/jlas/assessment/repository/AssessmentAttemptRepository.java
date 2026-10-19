package com.jlas.assessment.repository;

import com.jlas.assessment.entity.AssessmentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssessmentAttemptRepository extends JpaRepository<AssessmentAttempt, Long> {
    Optional<AssessmentAttempt> findFirstByUserIdAndAttemptTypeAndStatusOrderBySubmittedAtDescIdDesc(
            Long userId, String attemptType, String status);
    Optional<AssessmentAttempt> findByIdAndUserId(Long id, Long userId);
}

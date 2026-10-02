package com.jlas.learning.repository;

import com.jlas.learning.entity.UserGoalSurveySubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface UserGoalSurveySubmissionRepository extends JpaRepository<UserGoalSurveySubmission, Long> {
    @Query("select coalesce(max(s.version), 0) from UserGoalSurveySubmission s "
            + "where s.userId = :userId and s.goalId = :goalId")
    int findMaxVersion(@Param("userId") Long userId, @Param("goalId") Long goalId);

    Optional<UserGoalSurveySubmission> findByIdAndUserId(Long id, Long userId);
}

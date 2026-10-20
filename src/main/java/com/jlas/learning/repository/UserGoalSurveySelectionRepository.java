package com.jlas.learning.repository;

import com.jlas.learning.entity.UserGoalSurveySelection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserGoalSurveySelectionRepository extends JpaRepository<UserGoalSurveySelection, Long> {
    List<UserGoalSurveySelection> findBySubmissionId(Long submissionId);
}

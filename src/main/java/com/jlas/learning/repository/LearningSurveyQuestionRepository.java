package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningSurveyQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LearningSurveyQuestionRepository extends JpaRepository<LearningSurveyQuestion, Long> {
    List<LearningSurveyQuestion> findByGoalIdAndActiveTrueAndReviewStatusOrderByQuestionOrder(
            Long goalId, String reviewStatus);
}

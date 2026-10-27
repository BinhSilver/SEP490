package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningSurveyOption;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LearningSurveyOptionRepository extends JpaRepository<LearningSurveyOption, Long> {
    List<LearningSurveyOption> findByQuestionIdInAndReviewStatusOrderByQuestionIdAscOptionOrderAsc(
            List<Long> questionIds, String reviewStatus);
}

package com.jlas.learning.repository;

import com.jlas.learning.entity.LessonLearningScope;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LessonLearningScopeRepository extends JpaRepository<LessonLearningScope, Long> {
    List<LessonLearningScope> findByGoalIdAndReviewStatusOrderByScopeOrderAsc(Long goalId, String reviewStatus);
}

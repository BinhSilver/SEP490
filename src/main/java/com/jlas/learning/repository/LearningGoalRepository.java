package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface LearningGoalRepository extends JpaRepository<LearningGoal, Long> {
    Optional<LearningGoal> findByGoalCodeAndActiveTrue(String goalCode);
}

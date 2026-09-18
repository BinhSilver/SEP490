package com.jlas.assessment.repository;

import com.jlas.assessment.entity.AssessmentAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssessmentAnswerRepository extends JpaRepository<AssessmentAnswer, Long> {
    boolean existsByAttemptIdAndQuestionId(Long attemptId, Long questionId);

    List<AssessmentAnswer> findByAttemptId(Long attemptId);
}

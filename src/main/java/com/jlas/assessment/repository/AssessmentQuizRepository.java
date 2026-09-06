package com.jlas.assessment.repository;

import com.jlas.assessment.entity.AssessmentQuiz;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link AssessmentQuiz}. */
@Repository
public interface AssessmentQuizRepository extends JpaRepository<AssessmentQuiz, Long> { }

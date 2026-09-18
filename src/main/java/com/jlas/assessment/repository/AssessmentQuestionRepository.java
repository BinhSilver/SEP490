package com.jlas.assessment.repository;

import com.jlas.assessment.entity.AssessmentQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssessmentQuestionRepository extends JpaRepository<AssessmentQuestion, Long> {
    List<AssessmentQuestion> findByIsActiveTrueAndSkillAndJlptLevel(String skill, String jlptLevel);

    List<AssessmentQuestion> findByIsActiveTrueAndSkillOrderByIdAsc(String skill);
}

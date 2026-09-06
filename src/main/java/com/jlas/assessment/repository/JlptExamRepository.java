package com.jlas.assessment.repository;

import com.jlas.assessment.entity.JlptExam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link JlptExam}. */
@Repository
public interface JlptExamRepository extends JpaRepository<JlptExam, Long> { }

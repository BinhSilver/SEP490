package com.jlas.assessment.repository;

import com.jlas.assessment.entity.JlptExam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Repository cho {@link JlptExam}. */
@Repository
public interface JlptExamRepository extends JpaRepository<JlptExam, Long> {
    List<JlptExam> findByStatusOrderByExamYearDescExamMonthDesc(String status);
    Optional<JlptExam> findByExamCodeAndStatus(String examCode, String status);
    Optional<JlptExam> findByExamCode(String examCode);
    boolean existsByExamCode(String examCode);
}

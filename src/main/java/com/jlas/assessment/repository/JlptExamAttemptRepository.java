package com.jlas.assessment.repository;

import com.jlas.assessment.entity.JlptExamAttempt;
import com.jlas.assessment.entity.JlptSectionResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Read-only access to JLPT Mock Test results for the Learning module's qualification check. */
@Repository
public interface JlptExamAttemptRepository extends JpaRepository<JlptExamAttempt, Long> {
    @Query("""
            select ea from JlptExamAttempt ea
              join fetch ea.attempt a
              join fetch a.user
              join fetch ea.exam
            where ea.attemptId = :attemptId
            """)
    Optional<JlptExamAttempt> findDetailedById(@Param("attemptId") Long attemptId);

    @Query("""
            select r from JlptSectionResult r
              join fetch r.section
            where r.examAttempt.attemptId = :attemptId
            """)
    List<JlptSectionResult> findSectionResults(@Param("attemptId") Long attemptId);
}

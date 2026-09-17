package com.jlas.learning.repository;

import com.jlas.assessment.entity.AssessmentAttempt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Access to a quiz attempt, scoped to the owning user for authorization checks. */
@Repository
public interface LessonQuizAttemptRepository extends JpaRepository<AssessmentAttempt, Long> {

    @Query("""
            select attempt from AssessmentAttempt attempt
            where attempt.id = :id and attempt.user.id = :userId
            """)
    Optional<AssessmentAttempt> findOwnedAttempt(@Param("id") Long id, @Param("userId") Long userId);
}

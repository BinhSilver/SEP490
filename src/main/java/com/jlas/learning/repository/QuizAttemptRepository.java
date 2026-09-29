package com.jlas.learning.repository;

import com.jlas.learning.entity.QuizAttempt;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Repository cho {@link QuizAttempt}. */
@Repository
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    /** Số lượt nộp quiz của user trong khoảng từ {@code since} cho một quiz cụ thể (GATE: lessonId = null). */
    @Query("""
            select count(a) from QuizAttempt a
            where a.user.id = :userId
              and a.quizType = :quizType
              and ((:lessonId is null and a.lessonId is null) or a.lessonId = :lessonId)
              and a.attemptedAt >= :since
            """)
    long countSince(@Param("userId") Long userId, @Param("quizType") String quizType,
            @Param("lessonId") Long lessonId, @Param("since") LocalDateTime since);
}

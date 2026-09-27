package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningQuizSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Repository cho {@link LearningQuizSession}. */
@Repository
public interface LearningQuizSessionRepository extends JpaRepository<LearningQuizSession, Long> {

    /**
     * Tìm session theo attemptId + chủ sở hữu + loại quiz; {@code lessonId} null khớp GATE,
     * khác null khớp đúng bài LESSON. Chỉ chủ sở hữu mới đọc được session của mình.
     */
    @Query("""
            select s from LearningQuizSession s
            where s.id = :id
              and s.user.id = :userId
              and s.quizType = :quizType
              and ((:lessonId is null and s.lessonId is null) or s.lessonId = :lessonId)
            """)
    Optional<LearningQuizSession> findOwned(@Param("id") Long id, @Param("userId") Long userId,
            @Param("quizType") String quizType, @Param("lessonId") Long lessonId);
}

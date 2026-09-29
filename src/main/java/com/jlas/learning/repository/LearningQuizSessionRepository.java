package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningQuizSession;
import java.util.List;
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

    /** [JLAS-W0108] P4 — session per-activity (GRAMMAR): khóa theo anchor activity_id thay vì lesson_id. */
    @Query("""
            select s from LearningQuizSession s
            where s.id = :id
              and s.user.id = :userId
              and s.quizType = :quizType
              and s.activityId = :activityId
            """)
    Optional<LearningQuizSession> findOwnedByActivity(@Param("id") Long id, @Param("userId") Long userId,
            @Param("quizType") String quizType, @Param("activityId") Long activityId);

    /** [JLAS-W0108] 2 lần nộp bài final gần nhất cùng chặng, để kiểm điểm có cải thiện qua các lần
     *  retest hay không (D7). */
    List<LearningQuizSession> findTop2ByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(
            Long activityId, String quizType, String status);

    /** [JLAS-W0193] Task 8 — the passing micro-check submission(s) for one activity, used to aggregate a
     * real correct/total package score from actual snapshot data (never a hard-coded division). Once a
     * batch passes it never reopens, so at most one row per (activity, batch) is ever SUBMITTED+passed. */
    List<LearningQuizSession> findByActivityIdAndQuizTypeAndStatusAndPassed(
            Long activityId, String quizType, String status, Boolean passed);
}

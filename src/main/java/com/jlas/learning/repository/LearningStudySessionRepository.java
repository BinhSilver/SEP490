package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningStudySession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** [JLAS-W0193] Repository cho {@link LearningStudySession}. */
public interface LearningStudySessionRepository extends JpaRepository<LearningStudySession, Long> {
    Optional<LearningStudySession> findByActivityId(Long activityId);

    @Query("""
            select s from LearningStudySession s
            where s.activity.id = :activityId and s.activity.learningPath.user.id = :userId
            """)
    Optional<LearningStudySession> findOwnedByActivity(@Param("activityId") Long activityId,
            @Param("userId") Long userId);
}

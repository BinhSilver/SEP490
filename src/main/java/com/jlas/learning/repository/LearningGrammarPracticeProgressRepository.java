package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningGrammarPracticeProgress;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** [JLAS-W0193] Task 6 — repository for {@link LearningGrammarPracticeProgress}. */
public interface LearningGrammarPracticeProgressRepository extends JpaRepository<LearningGrammarPracticeProgress, Long> {
    List<LearningGrammarPracticeProgress> findByActivityIdOrderByGrammarPointId(Long activityId);

    Optional<LearningGrammarPracticeProgress> findByActivityIdAndGrammarPointId(Long activityId, Long grammarPointId);

    /** Lock the point's progress row so a concurrent double-submit of the same practice cannot race. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p from LearningGrammarPracticeProgress p
            where p.activity.id = :activityId and p.grammarPointId = :grammarPointId
            """)
    Optional<LearningGrammarPracticeProgress> findForUpdate(@Param("activityId") Long activityId,
            @Param("grammarPointId") Long grammarPointId);
}

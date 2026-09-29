package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningDialogueProgress;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** [JLAS-W0193] Task 7 — repository for {@link LearningDialogueProgress}. */
public interface LearningDialogueProgressRepository extends JpaRepository<LearningDialogueProgress, Long> {
    Optional<LearningDialogueProgress> findByActivityId(Long activityId);

    /** Locks the activity's single progress row so concurrent heartbeat/reached-bottom submissions serialize
     * and cannot double-count time or double-complete. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from LearningDialogueProgress p where p.activity.id = :activityId")
    Optional<LearningDialogueProgress> findForUpdate(@Param("activityId") Long activityId);
}

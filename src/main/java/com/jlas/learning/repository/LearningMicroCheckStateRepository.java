package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningMicroCheckState;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** [JLAS-W0193] Repository cho {@link LearningMicroCheckState}. */
public interface LearningMicroCheckStateRepository extends JpaRepository<LearningMicroCheckState, Long> {
    List<LearningMicroCheckState> findByActivityIdOrderByBatchIndex(Long activityId);

    Optional<LearningMicroCheckState> findByActivityIdAndBatchIndex(Long activityId, Integer batchIndex);

    /** [JLAS-W0193] Task 10 — the path summary aggregates first/latest/best and unresolved batches across
     * every activity of a skill in the whole path in one query instead of one round-trip per activity.
     * {@code activityIds} always comes from the path's own owned activities (never client-supplied ids), so
     * this cannot leak another user's micro-check state. */
    List<LearningMicroCheckState> findByActivityIdInOrderByActivityIdAscBatchIndexAsc(List<Long> activityIds);

    /** Lock the batch's summary row so concurrent submits of the same batch cannot over-spend attempts. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from LearningMicroCheckState s
            where s.activity.id = :activityId and s.batchIndex = :batchIndex
            """)
    Optional<LearningMicroCheckState> findForUpdate(@Param("activityId") Long activityId,
            @Param("batchIndex") Integer batchIndex);
}

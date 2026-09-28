package com.jlas.learning.repository;

import com.jlas.learning.entity.UserLearningStage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository cho {@link UserLearningStage}. */
@Repository
public interface UserLearningStageRepository extends JpaRepository<UserLearningStage, Long> {
    /** [JLAS-W0108] P6 — các chặng của một lộ trình, theo đúng thứ tự chặng (stage_index tăng dần). */
    List<UserLearningStage> findByLearningPathIdOrderByStageIndexAsc(Long learningPathId);

    /** [JLAS-W0108] Chặng theo id + chủ sở hữu (ownership), không lộ chặng của người khác. */
    @Query("""
            select stage from UserLearningStage stage
            where stage.id = :stageId and stage.learningPath.user.id = :userId
            """)
    Optional<UserLearningStage> findByIdAndLearningPathUserId(@Param("stageId") Long stageId,
            @Param("userId") Long userId);

    /** [JLAS-W0108] Chặng kế tiếp theo stage_index, để kích hoạt khi chặng hiện tại hoàn thành. */
    Optional<UserLearningStage> findByLearningPathIdAndStageIndex(Long learningPathId, int stageIndex);
}

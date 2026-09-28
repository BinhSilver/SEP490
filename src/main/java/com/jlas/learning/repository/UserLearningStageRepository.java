package com.jlas.learning.repository;

import com.jlas.learning.entity.UserLearningStage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link UserLearningStage}. */
@Repository
public interface UserLearningStageRepository extends JpaRepository<UserLearningStage, Long> {
    /** [JLAS-W0108] P6 — các chặng của một lộ trình, theo đúng thứ tự chặng (stage_index tăng dần). */
    List<UserLearningStage> findByLearningPathIdOrderByStageIndexAsc(Long learningPathId);
}

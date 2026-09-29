package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningStudyCard;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** [JLAS-W0193] Repository cho {@link LearningStudyCard}. */
public interface LearningStudyCardRepository extends JpaRepository<LearningStudyCard, Long> {
    List<LearningStudyCard> findBySessionIdOrderByOrderIndex(Long sessionId);

    Optional<LearningStudyCard> findBySessionIdAndCanonicalId(Long sessionId, Long canonicalId);
}

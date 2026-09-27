package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningGeneratedQuestion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Repository cho {@link LearningGeneratedQuestion} (câu quiz Gemini sinh, scoped theo path). */
@Repository
public interface LearningGeneratedQuestionRepository extends JpaRepository<LearningGeneratedQuestion, Long> {

    List<LearningGeneratedQuestion> findByLearningPathIdOrderByIdAsc(Long learningPathId);

    boolean existsByLearningPathIdAndAnchorTypeAndAnchorId(Long learningPathId, String anchorType, String anchorId);
}

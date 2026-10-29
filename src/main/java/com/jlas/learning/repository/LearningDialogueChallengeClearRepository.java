package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningDialogueChallengeClear;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningDialogueChallengeClearRepository
        extends JpaRepository<LearningDialogueChallengeClear, LearningDialogueChallengeClear.Key> {

    boolean existsByUserIdAndLessonId(Long userId, Long lessonId);

    /** 1 when this call recorded the first clear, 0 when the learner had already cleared it (retry, second tab). */
    @Modifying
    @Query(value = "INSERT IGNORE INTO learning_dialogue_challenge_clear (user_id, lesson_id, correct_count, question_count) "
            + "VALUES (:userId, :lessonId, :correct, :total)", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("lessonId") Long lessonId,
            @Param("correct") int correct, @Param("total") int total);
}

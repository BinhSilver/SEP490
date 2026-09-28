package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.*;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.mapper.LearningMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WarmUpFlashcardMappingTest {
    @Test
    void groupsAllWarmUpCardsOnceWithoutCompletingTheirSourceLesson() {
        var path = UserLearningPath.builder().id(1L).user(User.builder().id(7L).build()).build();
        var lesson = Lesson.builder().id(10L).title("Source lesson").build();
        var a = item(lesson, "WARM_UP", "WEAK_KANJI_ITEM", 1);
        var b = item(lesson, "WARM_UP", "WEAK_VOCAB_ITEM", 2);
        var goal = item(lesson, "GOAL_LEARNING", "GOAL_TARGET", 3);
        goal.setVocabularyEntry(null);
        var response = new LearningMapper().toResponse(path, List.of(a, b, goal), Set.of(10L));
        assertEquals(2, response.totalLessons());
        assertEquals(1, response.phases().get(0).items().size());
        assertEquals("WARM_UP_FLASHCARDS", response.phases().get(0).items().get(0).reasonCode());
        assertFalse(response.phases().get(0).items().get(0).completed(),
                "Completing the source lesson must not complete the flashcard activity");
        assertFalse(response.warmUpCompleted());
        assertEquals(1, response.completedLessons());
    }

    private UserLearningPathItem item(Lesson lesson, String phase, String reason, int order) {
        return UserLearningPathItem.builder().lesson(lesson).phase(phase).reasonCode(reason)
                .vocabularyEntry(VocabularyEntry.builder().id((long) order).expression("語" + order).build())
                .dayNumber(order).orderIndex(order).estimatedMinutes(5).build();
    }

    @Test
    void rememberedSetCompletesOnlyTheWarmUpActivityAndNotGateOrGoal() {
        var path = UserLearningPath.builder().id(1L).user(User.builder().id(7L).build()).build();
        var lesson = Lesson.builder().id(10L).title("Source lesson").build();
        var card = item(lesson, "WARM_UP", "WEAK_VOCAB_ITEM", 1);
        var goal = item(lesson, "GOAL_LEARNING", "GOAL_TARGET", 2);
        goal.setVocabularyEntry(null);
        var response = new LearningMapper().toResponse(path, List.of(card, goal), Set.of(), true);
        assertTrue(response.warmUpCompleted());
        assertFalse(response.gatePassed());
        assertFalse(response.phases().get(1).items().get(0).completed());
        assertEquals(1, response.completedLessons());
    }

    @Test
    void studyStatusDrivesItemCompletionIndependentlyOfSourceLesson() {
        var path = UserLearningPath.builder().id(1L).user(User.builder().id(7L).build()).build();
        var lesson = Lesson.builder().id(10L).title("Source lesson").build();
        var studied = item(lesson, "GOAL_LEARNING", "GOAL_TARGET", 1);
        studied.setVocabularyEntry(null);
        studied.setStudyStatus("STUDIED");
        var untouched = item(lesson, "GOAL_LEARNING", "GOAL_TARGET", 2);
        untouched.setVocabularyEntry(null);
        var response = new LearningMapper().toResponse(path, List.of(studied, untouched), false);
        assertEquals(2, response.totalLessons());
        assertEquals(1, response.completedLessons());
        assertTrue(response.phases().get(0).items().get(0).completed(),
                "Item có study_status STUDIED phải completed");
        assertFalse(response.phases().get(0).items().get(1).completed(),
                "Item dùng chung source lesson chưa STUDY không được completed");
    }
}

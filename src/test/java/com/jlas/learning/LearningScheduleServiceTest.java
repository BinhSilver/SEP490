package com.jlas.learning;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.LearningResourceCandidate;
import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.dto.SelectedLearningResource;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.service.impl.LearningScheduleServiceImpl;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LearningScheduleServiceTest {
    private final LearningScheduleServiceImpl scheduler = new LearningScheduleServiceImpl();

    @Test void packsWarmUpThenGoalIntoNewDayAndKeepsLongLessonsWhole() {
        var result = scheduler.schedule(List.of(warm(10), warm(20), warm(45), goal(15), goal(15)), 30);
        assertEquals(List.of(1, 1, 2, 3, 3), result.stream().map(ScheduledLearningResource::dayNumber).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), result.stream().map(ScheduledLearningResource::orderIndex).toList());
    }

    @Test void goalLearningStartsANewDayEvenWhenWarmUpDayHasRoom() {
        var result = scheduler.schedule(List.of(warm(10), goal(10)), 30);
        assertEquals(List.of(1, 2), result.stream().map(ScheduledLearningResource::dayNumber).toList());
    }

    @Test void noWarmUpStartsAtDayOne() {
        var result = scheduler.schedule(List.of(goal(15), goal(15)), 30);
        assertEquals(List.of(1, 1), result.stream().map(ScheduledLearningResource::dayNumber).toList());
    }

    @Test void emptyResourcesProduceEmptySchedule() {
        assertTrue(scheduler.schedule(List.of(), 30).isEmpty());
    }

    @Test void preservesPhaseReasonAndMatchedTags() {
        var result = scheduler.schedule(List.of(warm(10)), 30).getFirst();
        assertEquals(LearningPhase.WARM_UP, result.phase());
        assertEquals("WEAK_TAG", result.reasonCode());
        assertEquals(Set.of("PARTICLE"), result.matchedTagCodes());
        assertEquals(10, result.estimatedMinutes());
    }

    @Test void rejectsNonPositiveBudget() {
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
                () -> scheduler.schedule(List.of(warm(10)), 0)).getErrorCode());
    }

    @Test void warmUpGrammarChunksThreePerDayThenGoalFreshDay() {
        var resources = new java.util.ArrayList<SelectedLearningResource>();
        for (int i = 0; i < 5; i++) resources.add(grammar(10)); // 5 mẫu -> 3 + 2
        resources.add(goal(15));
        var result = scheduler.schedule(resources, 1000);
        assertEquals(List.of(1, 1, 1, 2, 2, 3),
                result.stream().map(ScheduledLearningResource::dayNumber).toList());
    }

    @Test void warmUpReadingAndListeningLimitedToOneEachPerDay() {
        var result = scheduler.schedule(List.of(reading(15), reading(15), listening(15), listening(15)), 1000);
        assertEquals(List.of(1, 2, 2, 3),
                result.stream().map(ScheduledLearningResource::dayNumber).toList());
    }

    @Test void warmUpGrammarChunkStillRespectsMinuteBudget() {
        // 2 grammar (10' mỗi cái) + budget 15: mỗi grammar tự thành 1 ngày do vượt ngân sách.
        var result = scheduler.schedule(List.of(grammar(10), grammar(10)), 15);
        assertEquals(List.of(1, 2), result.stream().map(ScheduledLearningResource::dayNumber).toList());
    }

    private static SelectedLearningResource grammar(int minutes) {
        return selection(LearningPhase.WARM_UP, "WEAK_GRAMMAR_POINT", "GRAMMAR", minutes);
    }

    private static SelectedLearningResource reading(int minutes) {
        return selection(LearningPhase.WARM_UP, "WEAK_READING_ITEM", "READING", minutes);
    }

    private static SelectedLearningResource listening(int minutes) {
        return selection(LearningPhase.WARM_UP, "WEAK_LISTENING_ITEM", "LISTENING", minutes);
    }

    private static SelectedLearningResource warm(int minutes) {
        return selection(LearningPhase.WARM_UP, "WEAK_TAG", "PARTICLE", minutes);
    }

    private static SelectedLearningResource goal(int minutes) {
        return selection(LearningPhase.GOAL_LEARNING, "GOAL_TARGET", "TARGET", minutes);
    }

    private static SelectedLearningResource selection(LearningPhase phase, String reason, String tag, int minutes) {
        var candidate = new LearningResourceCandidate((long) minutes, "Lesson " + minutes, 1L, 1L,
                Set.of(tag), "N5", minutes, 1, 1, null, null);
        return new SelectedLearningResource(candidate, phase, reason, reason + " text", Set.of(tag));
    }
}

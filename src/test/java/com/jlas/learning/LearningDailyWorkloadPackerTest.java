package com.jlas.learning;

import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.service.impl.LearningDailyWorkloadPacker;
import com.jlas.learning.service.impl.LearningWorkloadPolicy.ActivityPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LearningDailyWorkloadPackerTest {
    private final LearningDailyWorkloadPacker packer = new LearningDailyWorkloadPacker();

    @Test
    void keepsExactFitTogetherAndMovesNextActivityToAnotherDay() {
        var first = activity(LearningActivityType.VOCABULARY, 1, 10);
        var exactFit = activity(LearningActivityType.KANJI, 11, 5);
        var next = activity(LearningActivityType.GRAMMAR, 12, 1);

        var days = packer.pack(List.of(first, exactFit, next), 15);

        assertEquals(2, days.size());
        assertEquals(15, days.getFirst().estimatedMinutes());
        assertEquals(List.of(first, exactFit), days.getFirst().activities());
        assertEquals(List.of(next), days.get(1).activities());
        assertTrue(days.stream().allMatch(day -> day.estimatedMinutes() <= 15));
    }

    @Test
    void rejectsOneAtomicActivityAboveTheDailyBudgetInsteadOfSilentlyOverflowing() {
        assertThrows(IllegalArgumentException.class,
                () -> packer.pack(List.of(activity(LearningActivityType.KANJI, 1, 16)), 15));
    }

    @Test
    void appliesDailyMemberQuotasWithoutExceedingTheMinuteBudget(){
        var first = new ActivityPlan(LearningActivityType.GRAMMAR,LearningMemberType.GRAMMAR_POINT,
                List.of(1L,2L,3L),6);
        var second = new ActivityPlan(LearningActivityType.GRAMMAR,LearningMemberType.GRAMMAR_POINT,
                List.of(4L,5L,6L),6);

        var days = packer.pack(List.of(first,second),30,Map.of(LearningActivityType.GRAMMAR,3));

        assertEquals(2,days.size());
        assertTrue(days.stream().allMatch(day -> day.activities().stream()
                .filter(activity -> activity.activityType() == LearningActivityType.GRAMMAR)
                .mapToInt(activity -> activity.memberIds().size()).sum() <= 3));
        assertTrue(days.stream().allMatch(day -> day.estimatedMinutes() <= 30));
    }

    private ActivityPlan activity(LearningActivityType type, long memberId, int minutes) {
        LearningMemberType memberType = switch (type) {
            case VOCABULARY -> LearningMemberType.VOCABULARY_ENTRY;
            case KANJI -> LearningMemberType.KANJI;
            case GRAMMAR -> LearningMemberType.GRAMMAR_POINT;
            default -> throw new IllegalArgumentException("Unsupported fixture activity type");
        };
        return new ActivityPlan(type, memberType, List.of(memberId), minutes);
    }
}

package com.jlas.learning;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.service.impl.LearningWorkloadPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class LearningWorkloadPolicyTest {
    private final LearningWorkloadPolicy policy = new LearningWorkloadPolicy();

    @ParameterizedTest
    @ValueSource(ints = {15, 20, 30, 45, 60})
    void partitionsAllSupportedStudyTypesWithinTheChosenDailyBudget(int budget) {
        List<Long> ids = LongStream.rangeClosed(1, 137).boxed().toList();
        for (LearningActivityType type : List.of(LearningActivityType.FOUNDATION,
                LearningActivityType.VOCABULARY, LearningActivityType.KANJI,
                LearningActivityType.GRAMMAR, LearningActivityType.DIALOGUE)) {
            var groups = policy.partition(type, ids, budget);

            assertEquals(ids, groups.stream().flatMap(List::stream).toList(), type + " membership order");
            assertTrue(groups.stream().allMatch(group -> !group.isEmpty()), type + " has no empty groups");
            assertTrue(groups.stream().allMatch(group -> policy.estimateMinutes(type, group.size()) <= budget),
                    type + " activity estimate must fit the selected day");
            if (List.of(LearningActivityType.FOUNDATION, LearningActivityType.VOCABULARY,
                    LearningActivityType.KANJI).contains(type)) {
                assertTrue(groups.stream().allMatch(group -> group.size() <= 15),
                        type + " content groups are limited to 15 members");
            }
        }
    }

    @Test
    void quizTimeIsIncludedAndTheEstimateGrowsWithRequiredMembership() {
        assertTrue(policy.estimateMinutes(LearningActivityType.VOCABULARY, 15)
                > policy.estimateMinutes(LearningActivityType.VOCABULARY, 5));
        assertTrue(policy.estimateMinutes(LearningActivityType.KANJI, 15)
                > policy.estimateMinutes(LearningActivityType.KANJI, 5));
        assertTrue(policy.estimateMinutes(LearningActivityType.GRAMMAR, 15)
                > policy.estimateMinutes(LearningActivityType.GRAMMAR, 5));
        assertEquals(0, policy.estimateMinutes(LearningActivityType.DIALOGUE, 0));
    }

    @Test
    void partitionsInFirstOccurrenceOrderAndDoesNotDuplicateRepeatedCanonicalIds() {
        var groups = policy.partition(LearningActivityType.VOCABULARY,
                List.of(8L, 3L, 8L, 1L, 3L), 15);

        assertEquals(List.of(8L, 3L, 1L), groups.stream().flatMap(List::stream).toList());
    }

    @Test
    void rejectsAWorkloadThatCannotBeEstimatedSafely() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.estimateMinutes(null, 3));
        assertThrows(IllegalArgumentException.class,
                () -> policy.partition(LearningActivityType.VOCABULARY, List.of(1L), 0));
    }

    @Test
    void rejectsQuizBearingPartitionWhenTheDailyBudgetWouldLeaveASingletonBatch() {
        var error = assertThrows(ApiException.class, () -> policy.partition(LearningActivityType.KANJI,
                List.of(1L, 2L, 3L, 4L, 5L), 5));

        assertEquals(ErrorCode.BLOCKED_CONTENT, error.getErrorCode());
        assertTrue(error.getMessage().contains("KANJI"));
    }

    @Test
    void preservesOrderAndQuizFeasibilityAtApprovedBoundarySizes() {
        assertEquals(List.of(2, 2), policy.partition(LearningActivityType.KANJI,
                LongStream.rangeClosed(1, 4).boxed().toList(), 5).stream().map(List::size).toList());

        for (int count : List.of(15, 16, 54, 61)) {
            var ids = LongStream.rangeClosed(1, count).boxed().toList();
            var groups = policy.partition(LearningActivityType.KANJI, ids, 30);
            assertEquals(ids, groups.stream().flatMap(List::stream).toList());
            assertTrue(groups.stream().allMatch(group -> group.size() >= 2 && group.size() <= 15));
        }
    }
}

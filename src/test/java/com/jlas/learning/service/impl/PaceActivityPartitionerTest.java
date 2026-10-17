package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaceActivityPartitionerTest {
    @Test void sixtyOneKanjiConserveIdentityOrderAndWorkloadAtEveryPreset() {
        var activity = UserLearningPathItem.builder().id(1L).activityType(LearningActivityType.KANJI)
                .estimatedMinutes(67).build();
        var members = members(activity, LearningMemberType.KANJI, 61);
        for (int budget : List.of(15, 20, 30, 45, 60)) {
            var parts = PaceActivityPartitioner.partition(activity, members, budget);
            assertEquals(members, parts.stream().flatMap(p -> p.members().stream()).toList());
            assertTrue(parts.stream().allMatch(p -> p.minutes() <= budget && p.members().size() <= 15
                    && p.members().size() >= 2));
            assertTrue(parts.stream().mapToInt(PaceActivityPartitioner.Part::minutes).sum() >= 67);
            assertTrue(members.stream().allMatch(m -> m.getActivity() == activity && m.getSourceRevision().equals("v1")));
            assertEquals(67, activity.getEstimatedMinutes());
        }
        assertEquals(List.of(11, 10, 10, 10, 10, 10), PaceActivityPartitioner.partition(activity, members, 15)
                .stream().map(p -> p.members().size()).toList());
    }

    @Test void splittingNeverCreatesASingletonVocabularyQuizPart() {
        var activity = UserLearningPathItem.builder().activityType(LearningActivityType.VOCABULARY)
                .estimatedMinutes(60).build();
        assertThrows(ApiException.class, () -> PaceActivityPartitioner.partition(activity,
                members(activity, LearningMemberType.VOCABULARY_ENTRY, 6), 15));
    }

    @Test void aFourWordCurriculumActivityCanBeSplitIntoTwoAnswerableOrderedParts() {
        var activity = UserLearningPathItem.builder().activityType(LearningActivityType.VOCABULARY)
                .estimatedMinutes(20).build();
        var source = members(activity, LearningMemberType.VOCABULARY_ENTRY, 4);

        var parts = PaceActivityPartitioner.partition(activity, source, 15);

        assertEquals(List.of(2, 2), parts.stream().map(part -> part.members().size()).toList());
        assertEquals(source, parts.stream().flatMap(part -> part.members().stream()).toList());
        assertTrue(parts.stream().allMatch(part -> part.minutes() <= 15));
    }

    @Test void sourceDependentPassagesAreNotCutByQuestionCount() {
        var activity = UserLearningPathItem.builder().activityType(LearningActivityType.LISTENING)
                .estimatedMinutes(30).build();
        assertThrows(ApiException.class, () -> PaceActivityPartitioner.partition(activity,
                members(activity, LearningMemberType.COMPREHENSION_QUESTION, 20), 15));
    }

    @Test void duplicateOrWrongKindMembershipIsRejectedInsteadOfSilentlyDiscarded() {
        var activity = UserLearningPathItem.builder().activityType(LearningActivityType.KANJI)
                .estimatedMinutes(60).build();
        var members = members(activity, LearningMemberType.KANJI, 30);
        members.getLast().setCanonicalId(members.getFirst().getCanonicalId());
        assertThrows(ApiException.class, () -> PaceActivityPartitioner.partition(activity, members, 15));
        assertThrows(ApiException.class, () -> PaceActivityPartitioner.partition(activity,
                members(activity, LearningMemberType.VOCABULARY_ENTRY, 30), 15));
    }

    private static List<LearningActivityMember> members(UserLearningPathItem activity, LearningMemberType type, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> LearningActivityMember.builder()
                .id((long) i).activity(activity).memberType(type).canonicalId(100L + i)
                .orderIndex(i).sourceRevision("v1").build()).toList();
    }
}

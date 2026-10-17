package com.jlas.learning;

import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.service.impl.ComprehensionWorkloadPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComprehensionWorkloadPolicyTest {
    private final ComprehensionWorkloadPolicy policy = new ComprehensionWorkloadPolicy();

    @Test void plansReadingAndListeningAgainstTheirOwnQuestionMembers() {
        var plan = policy.plan(LearningActivityType.LISTENING, List.of(31L, 32L));
        assertEquals(LearningMemberType.COMPREHENSION_QUESTION, plan.memberType());
        assertEquals(List.of(31L, 32L), plan.memberIds());
        assertEquals(6, plan.estimatedMinutes());
    }

    @Test void deduplicatesIdsAndRejectsNonComprehensionOrEmptyPlans() {
        assertEquals(List.of(31L), policy.plan(LearningActivityType.READING, List.of(31L, 31L)).memberIds());
        assertThrows(IllegalArgumentException.class, () -> policy.plan(LearningActivityType.DIALOGUE, List.of(31L)));
        assertThrows(IllegalArgumentException.class, () -> policy.plan(LearningActivityType.READING, List.of()));
    }

    @Test void keepsEveryQuestionOfOneAudioClipInAnAtomicFifteenMinuteActivity() {
        var clip = policy.planAudioClip(List.of(41L, 42L, 43L, 41L));
        assertEquals(LearningActivityType.LISTENING, clip.activityType());
        assertEquals(List.of(41L, 42L, 43L), clip.memberIds());
        assertEquals(15, clip.estimatedMinutes());
    }
}

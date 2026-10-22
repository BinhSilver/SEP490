package com.jlas.learning;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.auth.entity.User;
import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.dto.ComprehensionQuestionSpec;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import com.jlas.learning.service.impl.HeldComprehensionQuestionPruner;
import com.jlas.learning.service.impl.engine.ComprehensionRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Owner 2026-10-21 (OD-09): a check switched off at the source (e.g. a Minna passage whose picture is missing) must not
 * block a path that has not reached it yet; started work keeps its snapshot. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HeldComprehensionQuestionPrunerTest {
    @Mock UserLearningPathItemRepository items;
    @Mock LearningActivityMemberRepository members;
    @Mock LearningMicroCheckStateRepository checkStates;
    @Mock LearningQuizSessionRepository quizSessions;
    @Mock LearningStudySessionRepository studySessions;
    @Mock AssessmentQuestionRepository questions;
    @Mock ComprehensionExerciseCatalog catalog;
    HeldComprehensionQuestionPruner pruner;

    final User user = User.builder().id(7L).build();
    final UserLearningPath path = UserLearningPath.builder().id(1L).user(user)
            .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
    final LearningPathPackage chapter = LearningPathPackage.builder().id(20L).build();

    @BeforeEach void setUp() {
        pruner = new HeldComprehensionQuestionPruner(items, members, checkStates, quizSessions, studySessions,
                questions, catalog);
        when(questions.findAllById(any())).thenReturn(List.of(question(11L, true), question(12L, true),
                question(31L, false), question(32L, false)));
        when(catalog.resolve("READING", "N5")).thenReturn(new ComprehensionExerciseCatalog.CatalogResult(List.of(
                exercise("passage-A", 11L, 12L)), List.of()));
        when(checkStates.findByActivityIdOrderByBatchIndex(any())).thenReturn(List.of());
        when(quizSessions.findByActivityIdOrderByIdAsc(any())).thenReturn(List.of());
        when(studySessions.findByActivityId(any())).thenReturn(Optional.empty());
    }

    @Test void anUnstartedActivityOfOnlySwitchedOffChecksLeavesThePath() {
        var held = reading(100L, 2);
        var other = reading(101L, 1);
        when(items.findByPathForUpdate(1L)).thenReturn(List.of(other, held));
        var heldMember = member(held, 31L, 1);
        when(members.findByActivityIdOrderByOrderIndex(100L)).thenReturn(List.of(heldMember));
        when(members.findByActivityIdOrderByOrderIndex(101L)).thenReturn(List.of(member(other, 11L, 1)));

        assertEquals(1, pruner.prune(7L, path).removedActivities());

        // Members go first: a managed member still pointing at a deleted activity fails the flush.
        var order = inOrder(members, items);
        order.verify(members).deleteAll(List.of(heldMember));
        order.verify(items).delete(held);
        verify(items, never()).delete(other);
    }

    @Test void anUnstartedMixedActivityDropsOnlyTheSwitchedOffChecksAndTakesTheNewRevision() {
        var mixed = reading(100L, 1);
        var other = reading(101L, 2);
        var kept = member(mixed, 11L, 1);
        var gone = member(mixed, 31L, 2);
        var keptToo = member(mixed, 12L, 3);
        when(items.findByPathForUpdate(1L)).thenReturn(List.of(mixed, other));
        when(members.findByActivityIdOrderByOrderIndex(100L)).thenReturn(new ArrayList<>(List.of(kept, gone, keptToo)));
        when(members.findByActivityIdOrderByOrderIndex(101L)).thenReturn(List.of(member(other, 12L, 1)));

        assertEquals(1, pruner.prune(7L, path).prunedActivities());

        verify(members).deleteAll(List.of(gone));
        String revision = ComprehensionRevision.combined(LearningActivityType.READING,
                List.of(exercise("passage-A", 11L, 12L)));
        assertEquals(revision, kept.getSourceRevision());
        assertEquals(revision, keptToo.getSourceRevision());
        verify(items, never()).delete(any());
    }

    @Test void startedWorkAndTheOnlyActivityOfAChapterAreLeftAlone() {
        var started = reading(100L, 1);
        var only = UserLearningPathItem.builder().id(102L).learningPath(path).activityType(LearningActivityType.READING)
                .activityState(LearningActivityState.LOCKED).learningPackage(LearningPathPackage.builder().id(21L).build())
                .orderIndex(3).build();
        var sibling = reading(101L, 2);
        when(items.findByPathForUpdate(1L)).thenReturn(List.of(started, sibling, only));
        when(members.findByActivityIdOrderByOrderIndex(100L)).thenReturn(List.of(member(started, 31L, 1)));
        when(members.findByActivityIdOrderByOrderIndex(101L)).thenReturn(List.of(member(sibling, 11L, 1)));
        when(members.findByActivityIdOrderByOrderIndex(102L)).thenReturn(List.of(member(only, 32L, 1)));
        when(studySessions.findByActivityId(100L)).thenReturn(Optional.of(LearningStudySession.builder().id(9L).build()));

        var report = pruner.prune(7L, path);

        assertEquals(0, report.removedActivities() + report.prunedActivities());
        verify(items, never()).delete(any());
        verify(members, never()).deleteAll(any());
    }

    private UserLearningPathItem reading(Long id, int order) {
        return UserLearningPathItem.builder().id(id).learningPath(path).activityType(LearningActivityType.READING)
                .activityState(LearningActivityState.LOCKED).learningPackage(chapter).orderIndex(order).build();
    }

    private LearningActivityMember member(UserLearningPathItem activity, Long questionId, int order) {
        return LearningActivityMember.builder().activity(activity).memberType(LearningMemberType.COMPREHENSION_QUESTION)
                .canonicalId(questionId).orderIndex(order).sourceRevision("old-revision").build();
    }

    private AssessmentQuestion question(Long id, boolean active) {
        AssessmentQuestion row = new AssessmentQuestion();
        row.setId(id);
        row.setIsActive(active);
        row.setSkill("READING");
        row.setJlptLevel("N5");
        return row;
    }

    private ComprehensionExercise exercise(String fingerprint, Long... ids) {
        return new ComprehensionExercise(fingerprint, "READING", "N5", "text", null, null,
                java.util.Arrays.stream(ids).map(id -> new ComprehensionQuestionSpec(id, "Q" + id, List.of("A", "B"), 1, "E"))
                        .toList(), fingerprint + "-revision");
    }
}

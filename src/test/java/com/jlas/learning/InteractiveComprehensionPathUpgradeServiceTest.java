package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.dto.ComprehensionQuestionSpec;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import com.jlas.learning.service.impl.InteractiveComprehensionPathUpgradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractiveComprehensionPathUpgradeServiceTest {
    @Mock UserLearningPathItemRepository items;
    @Mock LearningActivityMemberRepository members;
    @Mock LearningMicroCheckStateRepository states;
    @Mock LearningQuizSessionRepository quizSessions;
    @Mock LearningStudySessionRepository studySessions;
    @Mock LearningStudyCardRepository studyCards;
    @Mock ComprehensionExerciseCatalog catalog;

    InteractiveComprehensionPathUpgradeService service;

    @BeforeEach void setUp() {
        service = new InteractiveComprehensionPathUpgradeService(
                items, members, states, quizSessions, studySessions, studyCards, catalog);
    }

    @Test void splitsLegacyReadingByExerciseAndPreservesAttemptsDraftsAndStudyEvidenceIdempotently() {
        var fixture = fixture(List.of(11L, 12L, 21L));
        when(items.findByPathForUpdate(50L)).thenReturn(fixture.pathItems);
        when(items.save(any(UserLearningPathItem.class))).thenAnswer(invocation -> {
            UserLearningPathItem saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(101L);
                fixture.pathItems.add(saved);
            }
            return saved;
        });
        when(members.findByActivityIdOrderByOrderIndex(100L)).thenReturn(fixture.members);
        when(members.findByActivityIdOrderByOrderIndex(101L)).thenReturn(List.of());
        when(states.findByActivityIdOrderByBatchIndex(100L)).thenReturn(fixture.states);
        when(quizSessions.findByActivityIdOrderByIdAsc(100L)).thenReturn(fixture.attempts);
        when(studySessions.findByActivityId(100L)).thenReturn(Optional.of(fixture.studySession));
        when(studySessions.save(any(LearningStudySession.class))).thenAnswer(invocation -> {
            LearningStudySession saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(301L);
            return saved;
        });
        when(studyCards.findBySessionIdOrderByOrderIndex(300L)).thenReturn(fixture.cards);
        when(catalog.resolve("READING", "N5")).thenReturn(readingCatalog());

        var report = service.reconcile(7L, fixture.path);

        assertEquals(1, report.splitActivities());
        assertTrue(report.diagnostics().isEmpty());
        UserLearningPathItem split = fixture.pathItems.stream().filter(item -> Long.valueOf(101L).equals(item.getId()))
                .findFirst().orElseThrow();
        assertEquals(20L, split.getLearningPackage().getId());
        assertEquals(3, split.getOrderIndex());
        assertEquals(2, split.getPartIndex());
        assertEquals(LearningActivityState.CHECK_READY, split.getActivityState());
        assertEquals(4, fixture.after.getOrderIndex());
        assertEquals(4, fixture.pathItems.stream().filter(item -> Long.valueOf(110L).equals(item.getId()))
                .findFirst().orElseThrow().getOrderIndex());

        assertEquals(100L, fixture.members.get(0).getActivity().getId());
        assertEquals(List.of(11L, 12L), fixture.members.subList(0, 2).stream()
                .map(LearningActivityMember::getCanonicalId).toList());
        assertEquals(List.of(21L), fixture.members.subList(2, 3).stream()
                .map(LearningActivityMember::getCanonicalId).toList());
        assertEquals(List.of(1, 2, 1), fixture.members.stream().map(LearningActivityMember::getOrderIndex).toList());
        assertNotEquals("legacy-revision", fixture.members.get(2).getSourceRevision());

        assertEquals(100L, fixture.states.get(0).getActivity().getId());
        assertEquals(101L, fixture.states.get(1).getActivity().getId());
        assertEquals(0, fixture.states.get(1).getBatchIndex());
        assertEquals(1, fixture.states.get(1).getAttemptsUsed());
        assertEquals(58, fixture.states.get(1).getLatestScore());

        assertEquals(100L, fixture.attempts.get(0).getActivityId());
        assertEquals(101L, fixture.attempts.get(1).getActivityId());
        assertEquals(0, fixture.attempts.get(1).getBatchIndex());
        assertEquals("draft-B", fixture.attempts.get(1).getDraftAnswersJson());
        assertEquals("source-B", fixture.attempts.get(1).getSourceSnapshotJson());
        assertEquals("result-A", fixture.attempts.get(0).getResultJson());

        LearningStudySession newStudySession = fixture.cards.get(2).getSession();
        assertEquals(301L, newStudySession.getId());
        assertEquals(101L, newStudySession.getActivity().getId());
        assertTrue(fixture.cards.get(2).isFlipped());
        assertEquals(1, fixture.cards.get(2).getOrderIndex());
        assertEquals(100L, fixture.cards.get(0).getSession().getActivity().getId());

        when(members.findByActivityIdOrderByOrderIndex(100L)).thenReturn(fixture.members.subList(0, 2));
        when(members.findByActivityIdOrderByOrderIndex(101L)).thenReturn(fixture.members.subList(2, 3));
        var secondReport = service.reconcile(7L, fixture.path);
        assertEquals(0, secondReport.splitActivities());
        assertEquals(2, fixture.pathItems.stream().filter(item -> item.getActivityType() == LearningActivityType.READING).count());
    }

    @Test void leavesAnActivityUntouchedAndReportsWhenAQuestionCannotBeMappedToItsSourceExercise() {
        var fixture = fixture(List.of(11L, 999L));
        when(items.findByPathForUpdate(50L)).thenReturn(fixture.pathItems);
        when(members.findByActivityIdOrderByOrderIndex(100L)).thenReturn(fixture.members);
        when(catalog.resolve("READING", "N5")).thenReturn(readingCatalog());

        var report = service.reconcile(7L, fixture.path);

        assertEquals(0, report.splitActivities());
        assertEquals(1, report.diagnostics().size());
        assertEquals("QUESTION_NOT_IN_CATALOG", report.diagnostics().getFirst().reasonCode());
        assertEquals(2, fixture.members.size());
        assertTrue(fixture.members.stream().allMatch(member -> member.getActivity().getId().equals(100L)));
        verify(items, never()).save(any(UserLearningPathItem.class));
        verify(states, never()).saveAll(anyIterable());
        verify(quizSessions, never()).saveAll(anyIterable());
    }

    private Fixture fixture(List<Long> questionIds) {
        User user = User.builder().id(7L).build();
        UserLearningPath path = UserLearningPath.builder().id(50L).user(user).experienceVersion(LearningExperienceVersion.INTERACTIVE_V2)
                .status("ACTIVE").goal("JLPT").build();
        Textbook book = Textbook.builder().jlptLevel("N5").build();
        Unit unit = Unit.builder().textbook(book).build();
        Lesson lesson = Lesson.builder().id(10L).unit(unit).build();
        LearningPathPackage pkg = LearningPathPackage.builder().id(20L).learningPath(path).sourceLesson(lesson)
                .phase("GOAL_LEARNING").build();
        UserLearningPathItem before = UserLearningPathItem.builder().id(90L).learningPath(path).learningPackage(pkg)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.PASSED).orderIndex(1).build();
        UserLearningPathItem legacy = UserLearningPathItem.builder().id(100L).learningPath(path).learningPackage(pkg)
                .activityType(LearningActivityType.READING).activityState(LearningActivityState.CHECK_READY).partIndex(1)
                .orderIndex(2).dayNumber(1).estimatedMinutes(12).lesson(lesson).phase("GOAL_LEARNING")
                .studyStatus("STUDIED").assessmentStatus("PASSED").build();
        UserLearningPathItem after = UserLearningPathItem.builder().id(110L).learningPath(path).learningPackage(pkg)
                .activityType(LearningActivityType.LISTENING).activityState(LearningActivityState.STUDYING).orderIndex(3).build();
        List<LearningActivityMember> memberRows = new ArrayList<>();
        for (int i = 0; i < questionIds.size(); i++) {
            memberRows.add(LearningActivityMember.builder().id((long) i + 1).activity(legacy)
                    .memberType(LearningMemberType.COMPREHENSION_QUESTION).canonicalId(questionIds.get(i))
                    .orderIndex(i + 1).sourceRevision("legacy-revision").build());
        }
        LearningMicroCheckState first = LearningMicroCheckState.builder().id(201L).activity(legacy).batchIndex(0)
                .cycleNumber(1).attemptsUsed(1).passed(true).firstScore(80).latestScore(90).bestScore(90)
                .reviewRequired(false).build();
        LearningMicroCheckState second = LearningMicroCheckState.builder().id(202L).activity(legacy).batchIndex(1)
                .cycleNumber(1).attemptsUsed(1).passed(false).firstScore(58).latestScore(58).bestScore(58)
                .reviewRequired(false).build();
        LearningQuizSession submitted = quizSession(500L, legacy, 0, "SUBMITTED", "source-A", "result-A", null);
        LearningQuizSession issued = quizSession(501L, legacy, 1, "ISSUED", "source-B", null, "draft-B");
        LearningStudySession studySession = LearningStudySession.builder().id(300L).activity(legacy)
                .sourceRevision("legacy-revision").build();
        List<LearningStudyCard> cards = new ArrayList<>();
        for (int i = 0; i < questionIds.size(); i++) {
            cards.add(LearningStudyCard.builder().id((long) i + 301).session(studySession).canonicalId(questionIds.get(i))
                    .orderIndex(i + 1).flipped(i != 1).remembered(i == 0).build());
        }
        return new Fixture(path, legacy, before, after, new ArrayList<>(List.of(before, legacy, after)), memberRows,
                new ArrayList<>(List.of(first, second)), new ArrayList<>(List.of(submitted, issued)), studySession, cards);
    }

    private LearningQuizSession quizSession(Long id, UserLearningPathItem activity, int batch, String status,
            String source, String result, String draft) {
        return LearningQuizSession.builder().id(id).user(activity.getLearningPath().getUser())
                .quizType("READING_MICRO_CHECK").activityId(activity.getId()).batchIndex(batch).status(status)
                .questionsJson("questions-" + batch).answerKeyJson("answers-" + batch).sourceSnapshotJson(source)
                .resultJson(result).draftAnswersJson(draft).questionCount(1).issuedAt(LocalDateTime.of(2026, 10, 1, 9, 0))
                .submittedAt("SUBMITTED".equals(status) ? LocalDateTime.of(2026, 10, 1, 9, 5) : null)
                .passed("SUBMITTED".equals(status)).build();
    }

    private ComprehensionExerciseCatalog.CatalogResult readingCatalog() {
        var first = new ComprehensionExercise("passage-A", "READING", "N5", "A", null, null,
                List.of(question(11L), question(12L)), "source-A-revision");
        var second = new ComprehensionExercise("passage-B", "READING", "N5", "B", null, null,
                List.of(question(21L)), "source-B-revision");
        return new ComprehensionExerciseCatalog.CatalogResult(List.of(first, second), List.of());
    }

    private ComprehensionQuestionSpec question(Long id) {
        return new ComprehensionQuestionSpec(id, "Question " + id, List.of("A", "B"), 1, "Explanation");
    }

    private record Fixture(UserLearningPath path, UserLearningPathItem legacy, UserLearningPathItem before,
            UserLearningPathItem after, List<UserLearningPathItem> pathItems, List<LearningActivityMember> members,
            List<LearningMicroCheckState> states, List<LearningQuizSession> attempts,
            LearningStudySession studySession, List<LearningStudyCard> cards) { }
}

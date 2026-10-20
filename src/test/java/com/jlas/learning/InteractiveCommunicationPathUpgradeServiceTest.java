package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.InteractiveContentSnapshot;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.InteractiveContentCatalog;
import com.jlas.learning.service.impl.InteractiveCommunicationPathUpgradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractiveCommunicationPathUpgradeServiceTest {
    @Mock UserLearningPathItemRepository items;
    @Mock LearningPathPackageRepository packages;
    @Mock LearningActivityMemberRepository members;
    @Mock InteractiveContentCatalog catalog;

    InteractiveCommunicationPathUpgradeService service;

    @BeforeEach void setUp() {
        service = new InteractiveCommunicationPathUpgradeService(items, packages, members, catalog);
    }

    @Test void addsMissingSituationGrammarAndDialogueWithoutChangingCompletedVocabularyOrLeavingCompletedPathStale() {
        User user = User.builder().id(7L).build();
        UserLearningPath path = UserLearningPath.builder().id(50L).user(user).experienceVersion(LearningExperienceVersion.INTERACTIVE_V2)
                .goal("TRAVEL").status("COMPLETED").minutesPerDay(20).build();
        Textbook book = Textbook.builder().jlptLevel("N5").build();
        Lesson lesson = Lesson.builder().id(10L).title("J-LAS · Làm thủ tục ở sân bay")
                .unit(Unit.builder().textbook(book).build()).build();
        LearningPathPackage pkg = LearningPathPackage.builder().id(20L).learningPath(path).sourceLesson(lesson)
                .phase("GOAL_LEARNING").dayNumber(1).orderIndex(1).status(LearningPackageStatus.COMPLETED)
                .scorePercent(100).title("Sân bay").build();
        UserLearningPathItem vocabulary = UserLearningPathItem.builder().id(100L).learningPath(path).learningPackage(pkg)
                .lesson(lesson).activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.PASSED)
                .partIndex(1).orderIndex(1).phase("GOAL_LEARNING").studyStatus("STUDIED").build();
        LearningActivityMember vocabularyMember = LearningActivityMember.builder().id(501L).activity(vocabulary)
                .memberType(LearningMemberType.VOCABULARY_ENTRY).canonicalId(701L).orderIndex(1)
                .sourceRevision("old-vocab-source").contentSnapshotJson("kept").build();
        var pathItems = new ArrayList<>(List.of(vocabulary));
        var pathMembers = new ArrayList<>(List.of(vocabularyMember));
        when(packages.findByPathForUpdate(50L)).thenReturn(List.of(pkg));
        when(items.findByPathForUpdate(50L)).thenReturn(pathItems);
        when(items.findMaxOrderIndexByLearningPathId(50L)).thenReturn(1);
        when(items.save(any(UserLearningPathItem.class))).thenAnswer(invocation -> {
            UserLearningPathItem saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(100L + pathItems.size());
                pathItems.add(saved);
            }
            return saved;
        });
        when(members.findByActivityIdOrderByOrderIndex(anyLong())).thenAnswer(invocation -> pathMembers.stream()
                .filter(member -> Objects.equals(member.getActivity().getId(), invocation.getArgument(0)))
                .sorted(Comparator.comparing(LearningActivityMember::getOrderIndex)).toList());
        doAnswer(invocation -> {
            Iterable<LearningActivityMember> rows = invocation.getArgument(0);
            rows.forEach(row -> { if (!pathMembers.contains(row)) pathMembers.add(row); });
            return rows;
        }).when(members).saveAll(anyIterable());
        when(catalog.inspect(10L, "N5")).thenReturn(new InteractiveContentSnapshot(lesson, "source-revision-v2",
                List.of(701L), List.of(), List.of(801L, 802L), List.of(901L, 902L), List.of()));

        var report = service.reconcile(7L, path);

        assertEquals(2, report.addedActivities());
        assertTrue(report.diagnostics().isEmpty());
        assertEquals("ACTIVE", path.getStatus());
        assertEquals(LearningPackageStatus.IN_PROGRESS, pkg.getStatus());
        assertNull(pkg.getCompletedAt());
        assertNull(pkg.getScorePercent());
        assertSame(vocabulary, pathItems.getFirst());
        assertEquals(LearningActivityState.PASSED, vocabulary.getActivityState());
        assertEquals("kept", vocabularyMember.getContentSnapshotJson());

        var added = pathItems.stream().filter(item -> !Long.valueOf(100L).equals(item.getId())).toList();
        assertEquals(List.of(LearningActivityType.GRAMMAR, LearningActivityType.DIALOGUE),
                added.stream().map(UserLearningPathItem::getActivityType).toList());
        assertTrue(added.stream().allMatch(item -> item.getActivityState() == LearningActivityState.STUDYING));
        assertTrue(added.stream().allMatch(item -> "NOT_READY".equals(item.getAssessmentStatus())));
        assertEquals(List.of(801L, 802L), pathMembers.stream().filter(row -> row.getMemberType() == LearningMemberType.GRAMMAR_POINT)
                .map(LearningActivityMember::getCanonicalId).toList());
        assertEquals(List.of(901L, 902L), pathMembers.stream().filter(row -> row.getMemberType() == LearningMemberType.DIALOGUE_LINE)
                .map(LearningActivityMember::getCanonicalId).toList());
        assertTrue(pathMembers.stream().filter(row -> row.getMemberType() != LearningMemberType.VOCABULARY_ENTRY)
                .allMatch(row -> "source-revision-v2".equals(row.getSourceRevision())));

        when(items.findByPathForUpdate(50L)).thenReturn(pathItems);
        var secondReport = service.reconcile(7L, path);
        assertEquals(0, secondReport.addedActivities());
        assertEquals(3, pathItems.size());
    }

    @Test void doesNotPartiallyBackfillWhenTheSourceLessonReportsContentGaps() {
        UserLearningPath path = UserLearningPath.builder().id(50L).user(User.builder().id(7L).build())
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).goal("DAILY_LIFE").status("ACTIVE")
                .minutesPerDay(20).build();
        Lesson lesson = Lesson.builder().id(10L).unit(Unit.builder().textbook(Textbook.builder().jlptLevel("N5").build()).build()).build();
        LearningPathPackage pkg = LearningPathPackage.builder().id(20L).learningPath(path).sourceLesson(lesson)
                .phase("GOAL_LEARNING").status(LearningPackageStatus.AVAILABLE).build();
        when(packages.findByPathForUpdate(50L)).thenReturn(List.of(pkg));
        when(items.findByPathForUpdate(50L)).thenReturn(List.of());
        when(catalog.inspect(10L, "N5")).thenReturn(new InteractiveContentSnapshot(lesson, "rev", List.of(), List.of(),
                List.of(801L), List.of(901L), List.of(new com.jlas.learning.dto.CoverageGap("LESSON:10", "GRAMMAR_NOT_READY"))));

        var report = service.reconcile(7L, path);

        assertEquals(0, report.addedActivities());
        assertEquals("SOURCE_CONTENT_NOT_READY", report.diagnostics().getFirst().reasonCode());
        verify(items, never()).save(any(UserLearningPathItem.class));
        verify(members, never()).saveAll(anyIterable());
    }

    @Test void leavesVocabularyTopicsAloneAndDoesNotCreateHalfOfAnUnreadySituation() {
        UserLearningPath path = UserLearningPath.builder().id(50L).user(User.builder().id(7L).build())
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).goal("TRAVEL").status("ACTIVE")
                .minutesPerDay(20).build();
        Textbook textbook = Textbook.builder().jlptLevel("N5").build();
        Lesson topic = Lesson.builder().id(10L).unit(Unit.builder().textbook(textbook).build()).build();
        Lesson situation = Lesson.builder().id(11L).unit(Unit.builder().textbook(textbook).build()).build();
        LearningPathPackage topicPackage = LearningPathPackage.builder().id(20L).learningPath(path).sourceLesson(topic)
                .phase("GOAL_LEARNING").status(LearningPackageStatus.AVAILABLE).build();
        LearningPathPackage situationPackage = LearningPathPackage.builder().id(21L).learningPath(path).sourceLesson(situation)
                .phase("GOAL_LEARNING").status(LearningPackageStatus.AVAILABLE).build();
        when(packages.findByPathForUpdate(50L)).thenReturn(List.of(topicPackage, situationPackage));
        when(items.findByPathForUpdate(50L)).thenReturn(List.of());
        when(catalog.inspect(10L, "N5")).thenReturn(new InteractiveContentSnapshot(topic, "topic-rev",
                List.of(701L), List.of(), List.of(), List.of(), List.of()));
        when(catalog.inspect(11L, "N5")).thenReturn(new InteractiveContentSnapshot(situation, "situation-rev",
                List.of(702L), List.of(), List.of(), List.of(901L), List.of()));

        var report = service.reconcile(7L, path);

        assertEquals(0, report.addedActivities());
        assertEquals(List.of("SITUATION_GRAMMAR_NOT_READY"),
                report.diagnostics().stream().map(InteractiveCommunicationPathUpgradeService.Diagnostic::reasonCode).toList());
        verify(items, never()).save(any(UserLearningPathItem.class));
        verify(members, never()).saveAll(anyIterable());
    }
}

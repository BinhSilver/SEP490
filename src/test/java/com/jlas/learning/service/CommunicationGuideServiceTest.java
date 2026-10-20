package com.jlas.learning.service;

import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.LearningSurveyOption;
import com.jlas.learning.entity.LearningTopic;
import com.jlas.learning.entity.LessonLearningScope;
import com.jlas.learning.entity.UserGoalSurveySelection;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.repository.LearningSurveyOptionRepository;
import com.jlas.learning.repository.LearningTopicRepository;
import com.jlas.learning.repository.LessonLearningScopeRepository;
import com.jlas.learning.repository.UserGoalSurveySelectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunicationGuideServiceTest {
    @Mock LessonLearningScopeRepository scopes;
    @Mock LearningTopicRepository topics;
    @Mock UserGoalSurveySelectionRepository selections;
    @Mock LearningSurveyOptionRepository options;
    @Mock UserLearningSurveyRepository surveys;
    @Mock com.jlas.dictionary.repository.TextbookRepository textbooks;
    CommunicationGuideService service;

    @BeforeEach void setUp() {
        service = new CommunicationGuideService(scopes, topics, selections, options, surveys, textbooks);
    }

    // ---- §2.24 (owner 2026-10-19 Q5): BrSE book suggestions, optional and never gating ----

    @Test
    void brseBelowN4IsOfferedTheLearningN4BookAndTheDraftN3BookOpenButMarkedUnreviewed() {
        // OD-45 (owner 2026-10-20): the N3 link opens the regular J-LAS N3 textbook while it stays DRAFT, labelled unreviewed.
        stubSingleTopicPath("BA", "N5");
        when(textbooks.findBySourceCode("LP-OWNED-SEKAI-N4-CORE")).thenReturn(Optional.of(book(4000002L,
                "Learning N4 — Sekai N4 — Giáo trình tiếng Nhật", "N4", "PUBLISHED")));
        var draftN3 = book(4L, "Tiếng Nhật N3", "N3", "DRAFT");
        draftN3.setIsLearningPathSource(true);
        when(textbooks.findAllByOrderByIdAsc()).thenReturn(List.of(draftN3,
                book(22L, "Đã duyệt — Học liệu bổ sung N3 — Speed Master N3", "N3", "PUBLISHED")));

        var books = service.guide(pathFor("BA"), List.of(1L, 2L)).bookSuggestions();

        assertEquals(List.of("N4", "N3"), books.stream().map(b -> b.level()).toList());
        assertEquals(4000002L, books.get(0).textbookId());
        assertTrue(books.get(0).available());
        assertFalse(books.get(0).unreviewed());
        assertEquals(4L, books.get(1).textbookId(), "the owner named the J-LAS N3 textbook, not another N3 book");
        assertTrue(books.get(1).available(), "learners may open the DRAFT N3 textbook (OD-45)");
        assertTrue(books.get(1).unreviewed(), "and it is never presented as reviewed");
        assertEquals("DRAFT", draftN3.getStatus());
    }

    @Test
    void aDraftBookOutsideTheLearningPathCatalogStaysUnavailable() {
        stubSingleTopicPath("BA", "N4");
        when(textbooks.findAllByOrderByIdAsc()).thenReturn(List.of(book(4L, "Tiếng Nhật N3", "N3", "DRAFT")));

        var n3 = service.guide(pathFor("BA"), List.of(1L, 2L)).bookSuggestions().getFirst();

        assertFalse(n3.available());
        assertTrue(n3.unreviewed());
    }

    @Test
    void brseAtN4OrN3IsOfferedOnlyTheN3BookAndN2UpGetsNoInferredSuggestion() {
        when(textbooks.findAllByOrderByIdAsc()).thenReturn(List.of(book(4L, "Tiếng Nhật N3", "N3", "PUBLISHED")));
        stubSingleTopicPath("BA", "N4");
        assertEquals(List.of("N3"), service.guide(pathFor("BA"), List.of(1L, 2L)).bookSuggestions().stream().map(b -> b.level()).toList());
        stubSingleTopicPath("BA", "N2");
        assertTrue(service.guide(pathFor("BA"), List.of(1L, 2L)).bookSuggestions().isEmpty());
    }

    @Test
    void travelAndDailyLifeHaveNoBookSuggestions() {
        stubSingleTopicPath("TRAVEL", "N5");
        assertTrue(service.guide(pathFor("TRAVEL"), List.of(1L, 2L)).bookSuggestions().isEmpty());
        org.mockito.Mockito.verifyNoInteractions(textbooks);
    }

    private void stubSingleTopicPath(String goal, String selfLevel) {
        org.mockito.Mockito.lenient().when(scopes.findByLessonIdIn(any())).thenReturn(List.of(scope(1L, 100L), scope(2L, 101L)));
        org.mockito.Mockito.lenient().when(topics.findAllById(any())).thenReturn(List.of(topic(100L, null, 1, null), topic(101L, 100L, 2, "N4")));
        org.mockito.Mockito.lenient().when(surveys.findById(7L)).thenReturn(Optional.of(UserLearningSurvey.builder().userId(7L)
                .selfReportedLevel(selfLevel).build()));
    }

    private static UserLearningPath pathFor(String goal) {
        return UserLearningPath.builder().id(9L).goal(goal).user(User.builder().id(7L).build()).build();
    }

    private static com.jlas.dictionary.entity.Textbook book(Long id, String title, String level, String status) {
        return com.jlas.dictionary.entity.Textbook.builder().id(id).title(title).jlptLevel(level).status(status).build();
    }

    @Test
    void groupsUnitsUnderTheirTopicWithLevelsAndPutsSurveyFocusFirst() {
        UserLearningPath path = UserLearningPath.builder().id(9L).goal("TRAVEL").surveyId(60L)
                .user(User.builder().id(7L).build()).build();
        when(scopes.findByLessonIdIn(any())).thenReturn(List.of(scope(1L, 100L), scope(2L, 101L), scope(3L, 200L),
                scope(4L, 201L), scope(5L, 202L)));
        when(topics.findAllById(any())).thenReturn(List.of(topic(100L, null, 1, null), topic(101L, 100L, 2, "N4"),
                topic(200L, null, 3, null), topic(201L, 200L, 4, "N4"), topic(202L, 200L, 5, "N3")));
        when(selections.findBySubmissionId(60L)).thenReturn(List.of(
                UserGoalSurveySelection.builder().submissionId(60L).questionId(9L).optionId(77L).build()));
        when(options.findAllById(any())).thenReturn(List.of(
                LearningSurveyOption.builder().id(77L).questionId(9L).topicId(200L).build()));
        when(surveys.findById(7L)).thenReturn(Optional.of(UserLearningSurvey.builder().userId(7L)
                .selfReportedLevel("N5").build()));

        var guide = service.guide(path, List.of(1L, 2L, 3L, 4L, 5L));

        assertEquals("N5", guide.learnerSelfLevel());
        assertEquals(List.of("T200", "T100"), guide.topics().stream().map(t -> t.code()).toList());
        var focus = guide.topics().get(0);
        assertTrue(focus.priority());
        assertEquals(List.of(3L, 4L, 5L), focus.units().stream().map(u -> u.lessonId()).toList());
        assertTrue(focus.units().get(0).vocabularyTopic());
        assertNull(focus.units().get(0).jlptLevel());
        assertEquals("N3", focus.units().get(2).jlptLevel());
        assertFalse(guide.topics().get(1).priority());
    }

    @Test
    void jlptPathsHaveNoCommunicationGuide() {
        UserLearningPath path = UserLearningPath.builder().id(9L).goal("JLPT").user(User.builder().id(7L).build()).build();
        assertNull(service.guide(path, List.of(1L)));
    }

    private static LessonLearningScope scope(Long lessonId, Long topicId) {
        return LessonLearningScope.builder().lessonId(lessonId).goalId(3L).topicId(topicId).required(true)
                .scopeOrder(lessonId.intValue()).reviewStatus("APPROVED").build();
    }

    private static LearningTopic topic(Long id, Long parentId, int order, String level) {
        return LearningTopic.builder().id(id).goalId(3L).parentTopicId(parentId).topicCode("T" + id)
                .titleVi("Topic " + id).topicOrder(order).jlptLevel(level).build();
    }
}

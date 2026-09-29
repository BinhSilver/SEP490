package com.jlas.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.dictionary.entity.Outcome;
import com.jlas.dictionary.entity.Tag;
import com.jlas.learning.service.impl.JpaComprehensionExerciseCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaComprehensionExerciseCatalogTest {
    @Mock AssessmentQuestionRepository questions;
    private JpaComprehensionExerciseCatalog catalog;

    @BeforeEach void setUp() {
        catalog = new JpaComprehensionExerciseCatalog(questions);
    }

    @Test void exactSkillAndLevelAreQueriedAndSameSourceIsGrouped() {
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(reading(2, "<p>本文 A</p>", "MAIN", "DAILY"),
                        reading(1, "本文 A", "MAIN", "DAILY")));

        var result = catalog.resolve("READING", "N4");

        verify(questions).findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4");
        assertEquals(1, result.exercises().size());
        assertEquals(List.of(1L, 2L), result.exercises().getFirst().questions().stream().map(q -> q.id()).toList());
        assertEquals("本文 A", result.exercises().getFirst().source());
        assertTrue(result.gaps().isEmpty());
    }

    @Test void differentIdentityNeverMergesEvenWhenPassageMatches() {
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(reading(1, "同じ", "MAIN", "DAILY"), reading(2, "同じ", "MAIN", "DAILY"),
                        reading(3, "同じ", "DETAIL", "DAILY"), reading(4, "同じ", "DETAIL", "DAILY")));
        var result = catalog.resolve("READING", "N4");
        assertEquals(2, result.exercises().size());
        assertNotEquals(result.exercises().get(0).fingerprint(), result.exercises().get(1).fingerprint());
    }

    @Test void listeningRequiresSafeAudioAndGroupsNormalizedUrl() {
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("LISTENING", "N3"))
                .thenReturn(List.of(listening(1, "HTTPS://cdn.example.com/a.mp3", "TASK"),
                        listening(2, "https://cdn.example.com/a.mp3", "TASK"),
                        listening(3, "javascript:alert(1)", "TASK")));
        var result = catalog.resolve("LISTENING", "N3");
        assertEquals(1, result.exercises().size());
        assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("AUDIO_MISSING")));
    }

    @Test void invalidQuestionsAndSingleQuestionGroupsAreRejected() {
        var duplicate = reading(1, "本文", "MAIN", "DAILY");
        duplicate.setOption2(" A ");
        var badCorrect = reading(2, "本文", "MAIN", "DAILY");
        badCorrect.setCorrectOption(9);
        var alone = reading(3, "別", "MAIN", "DAILY");
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(duplicate, badCorrect, alone));
        var result = catalog.resolve("READING", "N4");
        assertTrue(result.exercises().isEmpty());
        assertEquals(2, result.gaps().stream().filter(g -> g.reasonCode().equals("QUESTION_INVALID")).count());
        assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("QUESTION_COUNT_INSUFFICIENT")));
    }

    @Test void missingSourceAndIdentityBecomeExplicitGaps() {
        var noPassage = reading(1, " ", "MAIN", "DAILY");
        var noIdentity = reading(2, "本文", null, null);
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(noPassage, noIdentity));
        var result = catalog.resolve("READING", "N4");
        assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("PASSAGE_MISSING")));
        assertTrue(result.gaps().stream().anyMatch(g -> g.reasonCode().equals("SOURCE_AMBIGUOUS")));
    }

    @Test void revisionIsStableAndChangesWithCanonicalContent() {
        var rows = List.of(reading(1, "本文", "MAIN", "DAILY"), reading(2, "本文", "MAIN", "DAILY"));
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4")).thenReturn(rows);
        String first = catalog.resolve("READING", "N4").exercises().getFirst().sourceRevision();
        String second = catalog.resolve("READING", "N4").exercises().getFirst().sourceRevision();
        assertEquals(first, second);
        rows.getFirst().setQuestionText("changed");
        assertNotEquals(first, catalog.resolve("READING", "N4").exercises().getFirst().sourceRevision());
    }

    @Test void internalAnswerIsIgnoredByJsonProjection() throws Exception {
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(reading(1, "本文", "MAIN", "DAILY"), reading(2, "本文", "MAIN", "DAILY")));
        String json = new ObjectMapper().writeValueAsString(catalog.resolve("READING", "N4").exercises().getFirst());
        assertFalse(json.contains("correctOption"));
        assertFalse(json.contains("explanation"));
    }

    private AssessmentQuestion reading(long id, String passage, String outcome, String topic) {
        return question(id, "READING", "N4", passage, null, outcome, topic);
    }

    private AssessmentQuestion listening(long id, String audio, String outcome) {
        return question(id, "LISTENING", "N3", null, audio, outcome, null);
    }

    private AssessmentQuestion question(long id, String skill, String level, String passage, String audio,
            String outcome, String topic) {
        return AssessmentQuestion.builder().id(id).skill(skill).jlptLevel(level).isActive(true)
                .passageHtml(passage).audioUrl(audio).questionType("MULTIPLE_CHOICE")
                .questionText("Question " + id).option1("A").option2("B").option3("C")
                .correctOption(1).explanation("secret")
                .outcome(outcome == null ? null : Outcome.builder().id(id).code(outcome).skill(skill).nameVi(outcome).build())
                .topicTag(topic == null ? null : Tag.builder().id(id).code(topic).name(topic).tagType("TOPIC").build())
                .build();
    }
}

package com.jlas.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.dictionary.entity.Outcome;
import com.jlas.dictionary.entity.Tag;
import com.jlas.learning.dto.ComprehensionQuestionSpec;
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

    @Test void passageSanitizationStripsLiteralTagsAndScriptContent() {
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(
                        reading(1, "<script>alert(1)</script><p onclick=\"x()\">安全 <b>な</b>文章</p>", "MAIN", "DAILY"),
                        reading(2, "<script>alert(1)</script><p onclick=\"x()\">安全 <b>な</b>文章</p>", "MAIN", "DAILY")));
        var result = catalog.resolve("READING", "N4");
        assertEquals(1, result.exercises().size());
        String source = result.exercises().getFirst().source();
        assertFalse(source.contains("<"), "no literal tag markers may survive sanitization: " + source);
        assertFalse(source.contains("alert(1)"), "script block content must be dropped entirely, not just unwrapped: " + source);
        assertTrue(source.contains("安全"));
    }

    /** [JLAS-W0193] RC Task 8 hardening finding — {@code sanitizePassage} used to strip literal {@code <...>}
     * tags FIRST and only unescape HTML entities afterward, so an entity-encoded tag (one that arrives from
     * the canonical bank as {@code &lt;script&gt;...&lt;/script&gt;} rather than a literal tag) survived the
     * tag-stripping regexes untouched and only became a real, literal {@code <script>} tag once unescaped
     * afterward — reappearing in the supposedly "sanitized" passage text. The frontend
     * ({@code interactive-comprehension-study.js}) independently HTML-escapes this value again before
     * insertion, so this was not an exploitable end-to-end XSS through today's only consumer — but the
     * catalog's own contract ("a Reading exercise must have a nonblank sanitized passage", RC plan Task 1 §3
     * item 8) means the value this method returns must be safe on its own, not merely safe because of a
     * second layer elsewhere. Fixed by unescaping entities BEFORE stripping tags, so a decoded tag is caught
     * by the same regexes a literal one already was. */
    @Test void passageSanitizationDoesNotLetEntityEncodedTagsSurviveViaLateUnescape() {
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(
                        reading(1, "&lt;script&gt;alert(1)&lt;/script&gt;安全な文章", "MAIN", "DAILY"),
                        reading(2, "&lt;script&gt;alert(1)&lt;/script&gt;安全な文章", "MAIN", "DAILY")));
        var result = catalog.resolve("READING", "N4");
        assertEquals(1, result.exercises().size());
        String source = result.exercises().getFirst().source();
        assertFalse(source.contains("<script"), "entity-encoded tag must not become a literal tag: " + source);
        assertFalse(source.contains("alert(1)"), "script block content must be dropped entirely: " + source);
        assertTrue(source.contains("安全"));
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

    @Test void listeningWithoutTranscriptIsHeldEvenWithValidAudioAndAnswers() {
        var row = listening(1, "/audio/a.mp3", "TASK");
        row.setPassageHtml(null);
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("LISTENING", "N3"))
                .thenReturn(List.of(row));

        var result = catalog.resolve("LISTENING", "N3");

        assertTrue(result.exercises().isEmpty());
        assertTrue(result.gaps().stream().anyMatch(g -> "TRANSCRIPT_MISSING".equals(g.reasonCode())));
    }

    @Test void groupsAllMinnaQuestionsByTheExactLessonAudioWithoutAnAssessmentTag() {
        var first = question(101, "LISTENING", "N4", "<p>A: そうです。</p>",
                "https://www.vnjpclub.com/Audio/minnaln/minna2/a/01A-1.mp3", null, null);
        first.setSourceCode("LP-OWNED-MINNA-N4-L26-LISTEN-c1-L-c1-1");
        var second = question(102, "LISTENING", "N4", "<p>A: そうです。</p>",
                "https://www.vnjpclub.com/Audio/minnaln/minna2/a/01A-1.mp3", null, null);
        second.setSourceCode("LP-OWNED-MINNA-N4-L26-LISTEN-c1-W-c1-2-1");
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("LISTENING", "N4"))
                .thenReturn(List.of(first, second));

        var result = catalog.resolve("LISTENING", "N4");

        assertEquals(1, result.exercises().size());
        assertEquals(List.of(101L, 102L), result.exercises().getFirst().questions().stream()
                .map(ComprehensionQuestionSpec::id).toList());
        assertTrue(result.gaps().isEmpty(), result.gaps().toString());
    }

    @Test void groupsMinnaReadingQuestionsByTheExactPassageWithinTheLesson() {
        var first = question(201, "READING", "N4", "<p>本文です。</p>", null, null, null);
        first.setSourceCode("LP-OWNED-MINNA-N4-L26-READ-P001-Q001");
        var second = question(202, "READING", "N4", "<p>本文です。</p>", null, null, null);
        second.setSourceCode("LP-OWNED-MINNA-N4-L26-READ-P001-Q002");
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(first, second));

        var result = catalog.resolve("READING", "N4");

        assertEquals(1, result.exercises().size());
        assertEquals(List.of(201L, 202L), result.exercises().getFirst().questions().stream()
                .map(ComprehensionQuestionSpec::id).toList());
        assertTrue(result.gaps().isEmpty(), result.gaps().toString());
    }

    /** OD-44 follow-up (owner 2026-10-20): Speed Master N3 passages and clips group by chapter slot, play local audio. */
    @Test void groupsSpeedMasterN3QuestionsByChapterSlotAndPlaysTheLocalClip() {
        var first = question(301, "READING", "N3", "<p>本文です。</p>", null, null, null);
        first.setSourceCode("LP-OWNED-SPEED-N3-C07-READ-P2-Q001");
        var second = question(302, "READING", "N3", "<p>本文です。</p>", null, null, null);
        second.setSourceCode("LP-OWNED-SPEED-N3-C07-READ-P2-Q002");
        var other = question(303, "READING", "N3", "<p>別の本文です。</p>", null, null, null);
        other.setSourceCode("LP-OWNED-SPEED-N3-C07-READ-P1-Q001");
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N3"))
                .thenReturn(List.of(first, second, other));
        var reading = catalog.resolve("READING", "N3");
        assertEquals(2, reading.exercises().size(), reading.gaps().toString());
        assertTrue(reading.gaps().isEmpty(), reading.gaps().toString());
        assertTrue(reading.exercises().stream().anyMatch(e -> e.questions().stream()
                .map(ComprehensionQuestionSpec::id).toList().equals(List.of(301L, 302L))));

        var clip = question(311, "LISTENING", "N3", "<p>Ⓜ：どうする？</p>",
                "/uploads/content-audio/vnjpclub/N3/speed-n3-nghe-hieu/2.mp3", null, null);
        clip.setSourceCode("LP-OWNED-SPEED-N3-C01-LISTEN-P1-Q001");
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("LISTENING", "N3")).thenReturn(List.of(clip));
        var listening = catalog.resolve("LISTENING", "N3");
        assertTrue(listening.gaps().isEmpty(), listening.gaps().toString());
        assertEquals("/uploads/content-audio/vnjpclub/N3/speed-n3-nghe-hieu/2.mp3", listening.exercises().getFirst().source());
        assertEquals("Ⓜ：どうする？", listening.exercises().getFirst().transcriptJa());
    }

    @Test void localMinnaAudioPreservesExistingExerciseRevisionAndOnlyChangesPlaybackSource() {
        var row = question(101, "LISTENING", "N5", "<p>はい。</p>",
                "https://www.vnjpclub.com/Audio/minnaln/minna1/a/01A-1.mp3", null, null);
        row.setSourceCode("LP-OWNED-MINNA-N5-L01-LISTEN-c1-L-c1-1");
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("LISTENING", "N5"))
                .thenReturn(List.of(row));
        var before = catalog.resolve("LISTENING", "N5").exercises().getFirst();
        row.setContextJson("{\"audio_file\":\"audio/vnjpclub/N5/minna-so-cap-1/bai-01/01A-1.mp3\"}");
        var after = catalog.resolve("LISTENING", "N5").exercises().getFirst();
        assertEquals(before.fingerprint(), after.fingerprint());
        assertEquals(before.sourceRevision(), after.sourceRevision());
        assertEquals("/uploads/content-audio/minna/vnjpclub/N5/minna-so-cap-1/bai-01/01A-1.mp3", after.source());
    }

    @Test void listeningKeepsItsOwnTranscriptForPostAnswerReview() {
        var first = listening(1, "/audio/a.mp3", "TASK");
        var second = listening(2, "/audio/a.mp3", "TASK");
        first.setPassageHtml("<p>女：こんにちは。</p>");
        second.setPassageHtml("<p>女：こんにちは。</p>");
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("LISTENING", "N3"))
                .thenReturn(List.of(first, second));
        var exercise = catalog.resolve("LISTENING", "N3").exercises().getFirst();
        assertEquals("女：こんにちは。", exercise.transcriptJa());
        second.setPassageHtml("<p>女：こんばんは。</p>");
        var mismatched = catalog.resolve("LISTENING", "N3");
        assertEquals(1, mismatched.exercises().size());
        assertEquals(List.of(1L), mismatched.exercises().getFirst().questions().stream()
                .map(ComprehensionQuestionSpec::id).toList(),
                "the mismatched question must never be combined with the original script");
        assertTrue(mismatched.gaps().stream().anyMatch(g -> "TRANSCRIPT_MISMATCH".equals(g.reasonCode())));
    }

    /** [JLAS-W0193] RC Task 8 hardening — a protocol-relative URL ({@code //host/...}) has no scheme, so a
     * naive "is it absolute" check alone would treat it as safely internal; a browser instead silently
     * resolves it against whatever scheme the current page happens to use, reaching an attacker-controlled
     * external host. This is the exact class of gap {@code MockTestDestinationProvider} (RC Task 7) already
     * guards against explicitly for its own redirect target — this test closes the same gap for audio URLs. */
    @Test void listeningRejectsProtocolRelativeUrlsThatWouldResolveToAnExternalHost() {
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("LISTENING", "N3"))
                .thenReturn(List.of(listening(1, "//evil.example.com/a.mp3", "TASK"),
                        listening(2, "//evil.example.com/a.mp3", "TASK")));
        var result = catalog.resolve("LISTENING", "N3");
        assertTrue(result.exercises().isEmpty(), "a protocol-relative audio URL must never be treated as safe");
        assertTrue(result.gaps().stream().allMatch(g -> g.reasonCode().equals("AUDIO_MISSING")));
    }

    @Test void validSingleQuestionReadingWithDistinctChoicesIsAccepted() {
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(reading(3, "別", "MAIN", "DAILY")));

        var result = catalog.resolve("READING", "N4");

        assertEquals(1, result.exercises().size());
        assertEquals(1, result.exercises().getFirst().questions().size());
        assertTrue(result.gaps().isEmpty());
    }

    @Test void invalidQuestionsAreRejectedEvenWhenAValidSingleQuestionExists() {
        var duplicate = reading(1, "本文", "MAIN", "DAILY");
        duplicate.setOption2(" A ");
        var badCorrect = reading(2, "本文", "MAIN", "DAILY");
        badCorrect.setCorrectOption(9);
        var alone = reading(3, "別", "MAIN", "DAILY");
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(duplicate, badCorrect, alone));
        var result = catalog.resolve("READING", "N4");
        assertEquals(1, result.exercises().size());
        assertEquals(2, result.gaps().stream().filter(g -> g.reasonCode().equals("QUESTION_INVALID")).count());
    }

    @Test void blankOptionBeforeAnotherOptionDoesNotShiftTheCorrectAnswer() {
        var row = reading(4, "本文", "MAIN", "DAILY");
        row.setOption1(" ");
        row.setCorrectOption(2);
        when(questions.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(row));

        var result = catalog.resolve("READING", "N4");

        assertTrue(result.exercises().isEmpty());
        assertEquals("QUESTION_INVALID", result.gaps().getFirst().reasonCode());
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
        return question(id, "LISTENING", "N3", "<p>女：こんにちは。</p>", audio, outcome, null);
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

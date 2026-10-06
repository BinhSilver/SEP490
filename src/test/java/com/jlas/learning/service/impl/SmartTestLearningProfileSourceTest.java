package com.jlas.learning.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.assessment.entity.AssessmentAnswer;
import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.entity.SmartTestSession;
import com.jlas.assessment.entity.SmartTestSessionQuestion;
import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Outcome;
import com.jlas.dictionary.entity.Tag;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.WeaknessEvidence;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * [JLAS-W0108][MP01] R/L weakness evidence phải giữ đủ từng outcome đã sai, không chỉ outcome sai
 * nhiều nhất — hai outcome sai bằng nhau đều phải xuất hiện, kèm assessed/wrong riêng.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:smart-test-profile-source-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = SmartTestLearningProfileSourceTest.ProfileSourceConfiguration.class)
class SmartTestLearningProfileSourceTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.assessment.repository", "com.jlas.auth.repository"})
    @Import(SmartTestLearningProfileSource.class)
    static class ProfileSourceConfiguration { }

    @Autowired EntityManager em;
    @Autowired SmartTestLearningProfileSource source;

    @Test
    void preservesWrongGrammarWithoutMappingForCoverageDiagnostics() {
        User user = seedUser();
        var attempt = AssessmentAttempt.builder().user(user).attemptType("SMART_TEST")
                .status("SUBMITTED").startedAt(LocalDateTime.now()).submittedAt(LocalDateTime.now()).build();
        em.persist(attempt);
        em.persist(SmartTestSession.builder().attemptId(attempt.getId()).attempt(attempt)
                .goal("JLPT").targetLevel("N4").selfReportedLevel("N5").estimatedOverallLevel("N4").build());
        em.persist(UserLearningSurvey.builder().userId(user.getId()).user(user).goal("JLPT")
                .targetLevel("N4").selfReportedLevel("N5").skills("GRAMMAR")
                .dailyStudyMinutes(30).completedAt(LocalDateTime.now()).build());
        var tag = Tag.builder().code("GRAMMAR_TEST").name("Grammar").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var question = questionWithOutcome(null, tag);
        question.setSkill("GRAMMAR");
        em.persist(question);
        em.persist(wrongAnswer(attempt, question));
        em.flush();
        em.clear();

        var snapshot = source.loadForUser(user.getId(), attempt.getId());
        var evidence = snapshot.weaknesses().stream().filter(e -> "GRAMMAR".equals(e.skill())).toList();
        assertEquals(1, evidence.size());
        assertEquals("UNMAPPED_QUESTION:" + question.getId(), evidence.getFirst().canonicalId());
        assertEquals(1, evidence.getFirst().wrongCount());
    }

    @Test
    void includesPresentedButUnansweredQuestionsFromAllFiveSkillsAsWrongEvidence() {
        User user = seedUser();
        var attempt = AssessmentAttempt.builder().user(user).attemptType("SMART_TEST")
                .status("SUBMITTED").startedAt(LocalDateTime.now()).submittedAt(LocalDateTime.now()).build();
        em.persist(attempt);
        var session = SmartTestSession.builder().attemptId(attempt.getId()).attempt(attempt)
                .goal("JLPT").targetLevel("N4").selfReportedLevel("N5").estimatedOverallLevel("N4").build();
        em.persist(session);
        em.persist(UserLearningSurvey.builder().userId(user.getId()).user(user).goal("JLPT")
                .targetLevel("N4").selfReportedLevel("N5").skills("GRAMMAR")
                .dailyStudyMinutes(30).completedAt(LocalDateTime.now()).build());
        var tag = Tag.builder().code("GRAMMAR_TEST").name("Grammar").tagType("KNOWLEDGE").build();
        em.persist(tag);
        Outcome readingOutcome = Outcome.builder().code("READ_MAIN_IDEA").nameVi("Ý chính").skill("READING").build();
        Outcome listeningOutcome = Outcome.builder().code("LISTEN_DETAIL").nameVi("Chi tiết nghe").skill("LISTENING").build();
        em.persist(readingOutcome);
        em.persist(listeningOutcome);
        AssessmentQuestion kanji = questionWithOutcome(null, tag);
        kanji.setSkill("KANJI");
        kanji.setContextJson("{\"lexeme\":\"漢字\"}");
        AssessmentQuestion vocabulary = questionWithOutcome(null, tag);
        vocabulary.setSkill("VOCABULARY");
        vocabulary.setContextJson("{\"lexeme\":\"日本語\"}");
        AssessmentQuestion repeatedVocabulary = questionWithOutcome(null, tag);
        repeatedVocabulary.setSkill("VOCABULARY");
        repeatedVocabulary.setContextJson("{\"lexeme\":\"日本語\"}");
        AssessmentQuestion grammar = questionWithOutcome(null, tag);
        grammar.setSkill("GRAMMAR");
        AssessmentQuestion reading = questionWithOutcome(readingOutcome, tag);
        reading.setSkill("READING");
        AssessmentQuestion listening = questionWithOutcome(listeningOutcome, tag);
        listening.setSkill("LISTENING");
        List<AssessmentQuestion> unansweredQuestions = List.of(
                kanji, vocabulary, repeatedVocabulary, grammar, reading, listening);
        unansweredQuestions.forEach(em::persist);
        // One explicit wrong answer plus one presented-but-blank answer for the same lexeme.
        em.persist(wrongAnswer(attempt, vocabulary));
        em.flush();
        for (int index = 0; index < unansweredQuestions.size(); index++) {
            var question = unansweredQuestions.get(index);
            em.persist(SmartTestSessionQuestion.builder()
                    .id(new SmartTestSessionQuestion.SmartTestSessionQuestionId(attempt.getId(), question.getId()))
                    .session(session).question(question).batchNumber(1).orderIndex(index + 1).build());
        }
        em.flush();
        em.clear();

        assertEquals("{\"lexeme\":\"漢字\"}", em.find(AssessmentQuestion.class, kanji.getId()).getContextJson());

        var snapshot = source.loadForUser(user.getId(), attempt.getId());

        assertEquals(List.of("KANJI", "VOCABULARY", "GRAMMAR", "READING", "LISTENING"),
                snapshot.skillScores().stream().map(com.jlas.learning.dto.SkillScore::skill).toList());
        assertTrue(snapshot.skillScores().stream().filter(score -> !"VOCABULARY".equals(score.skill()))
                .allMatch(score -> score.assessedCount() == 1 && score.wrongCount() == 1));
        assertTrue(snapshot.skillScores().stream().filter(score -> "VOCABULARY".equals(score.skill()))
                .allMatch(score -> score.assessedCount() == 2 && score.wrongCount() == 2));
        assertTrue(snapshot.weaknesses().stream().anyMatch(item -> "KANJI".equals(item.skill())
                && item.canonicalId().equals("KANJI:漢字") && item.wrongCount() == 1));
        List<WeaknessEvidence> vocabularyEvidence = snapshot.weaknesses().stream()
                .filter(item -> "VOCABULARY".equals(item.skill())).toList();
        assertEquals(1, vocabularyEvidence.size(), "Repeated errors for one lexeme are aggregated, not duplicated");
        assertEquals("VOCABULARY:日本語", vocabularyEvidence.getFirst().canonicalId());
        assertEquals(2, vocabularyEvidence.getFirst().assessedCount());
        assertEquals(2, vocabularyEvidence.getFirst().wrongCount());
        assertTrue(snapshot.weaknesses().stream().anyMatch(item -> "GRAMMAR".equals(item.skill())
                && item.canonicalId().equals("UNMAPPED_QUESTION:" + grammar.getId()) && item.wrongCount() == 1));
        assertTrue(snapshot.weaknesses().stream().filter(item -> "READING".equals(item.skill()))
                .anyMatch(item -> item.outcomeBreakdown().stream()
                        .anyMatch(outcome -> "READ_MAIN_IDEA".equals(outcome.outcomeCode()) && outcome.wrongCount() == 1)));
        assertTrue(snapshot.weaknesses().stream().filter(item -> "LISTENING".equals(item.skill()))
                .anyMatch(item -> item.outcomeBreakdown().stream()
                        .anyMatch(outcome -> "LISTEN_DETAIL".equals(outcome.outcomeCode()) && outcome.wrongCount() == 1)));
        assertEquals(1L, em.createQuery("select count(a) from AssessmentAnswer a where a.attempt.id = :attemptId", Long.class)
                .setParameter("attemptId", attempt.getId()).getSingleResult(),
                "Synthesized no-response evidence must remain a read-only projection, not a persisted answer");
    }

    @Test
    void preservesWrongKanjiAndVocabularyAnswersWithoutLexemeForCoverageDiagnostics() {
        User user = seedUser();
        var attempt = AssessmentAttempt.builder().user(user).attemptType("SMART_TEST")
                .status("SUBMITTED").startedAt(LocalDateTime.now()).submittedAt(LocalDateTime.now()).build();
        em.persist(attempt);
        em.persist(SmartTestSession.builder().attemptId(attempt.getId()).attempt(attempt)
                .goal("JLPT").targetLevel("N4").selfReportedLevel("N5").estimatedOverallLevel("N4").build());
        em.persist(UserLearningSurvey.builder().userId(user.getId()).user(user).goal("JLPT")
                .targetLevel("N4").selfReportedLevel("N5").skills("KANJI,VOCABULARY")
                .dailyStudyMinutes(30).completedAt(LocalDateTime.now()).build());
        var tag = Tag.builder().code("ITEM_TEST").name("Item").tagType("KNOWLEDGE").build();
        em.persist(tag);
        AssessmentQuestion kanji = questionWithOutcome(null, tag);
        kanji.setSkill("KANJI");
        AssessmentQuestion vocabulary = questionWithOutcome(null, tag);
        vocabulary.setSkill("VOCABULARY");
        em.persist(kanji);
        em.persist(vocabulary);
        em.persist(wrongAnswer(attempt, kanji));
        em.persist(wrongAnswer(attempt, vocabulary));
        em.flush();
        em.clear();

        var evidence = source.loadForUser(user.getId(), attempt.getId()).weaknesses().stream()
                .filter(e -> "KANJI".equals(e.skill()) || "VOCABULARY".equals(e.skill())).toList();
        assertEquals(2, evidence.size());
        assertTrue(evidence.stream().anyMatch(e -> "KANJI".equals(e.skill())
                && e.canonicalId().equals("KANJI:UNMAPPED_QUESTION:" + kanji.getId())));
        assertTrue(evidence.stream().anyMatch(e -> "VOCABULARY".equals(e.skill())
                && e.canonicalId().equals("VOCABULARY:UNMAPPED_QUESTION:" + vocabulary.getId())));
        assertTrue(evidence.stream().allMatch(e -> e.wrongCount() == 1));
    }

    @Test
    void preservesWrongReadingQuestionWithoutOutcomeForCoverageDiagnostics() {
        User user = seedUser();
        var attempt = AssessmentAttempt.builder().user(user).attemptType("SMART_TEST")
                .status("SUBMITTED").startedAt(LocalDateTime.now()).submittedAt(LocalDateTime.now()).build();
        em.persist(attempt);
        em.persist(SmartTestSession.builder().attemptId(attempt.getId()).attempt(attempt)
                .goal("JLPT").targetLevel("N4").selfReportedLevel("N5").estimatedOverallLevel("N4").build());
        em.persist(UserLearningSurvey.builder().userId(user.getId()).user(user).goal("JLPT")
                .targetLevel("N4").selfReportedLevel("N5").skills("READING")
                .dailyStudyMinutes(30).completedAt(LocalDateTime.now()).build());
        var tag = Tag.builder().code("READING_TEST").name("Reading").tagType("KNOWLEDGE").build();
        em.persist(tag);
        AssessmentQuestion question = questionWithOutcome(null, tag);
        question.setSkill("READING");
        em.persist(question);
        em.persist(wrongAnswer(attempt, question));
        em.flush();
        em.clear();

        var reading = source.loadForUser(user.getId(), attempt.getId()).weaknesses().stream()
                .filter(e -> "READING".equals(e.skill())).findFirst().orElseThrow();
        assertEquals(1, reading.wrongCount());
        assertTrue(reading.outcomeBreakdown().stream().anyMatch(o ->
                o.outcomeCode().equals("UNMAPPED_QUESTION:" + question.getId()) && o.wrongCount() == 1));
    }

    @Test
    void keepsEveryWeakOutcomeInsteadOfOnlyTheDominantOne() {
        User user = seedUser();
        AssessmentAttempt attempt = AssessmentAttempt.builder()
                .user(user).attemptType("SMART_TEST").status("SUBMITTED")
                .startedAt(LocalDateTime.now()).submittedAt(LocalDateTime.now())
                .build();
        em.persist(attempt);
        em.persist(SmartTestSession.builder()
                .attemptId(attempt.getId()).attempt(attempt)
                .goal("JLPT").targetLevel("N4").selfReportedLevel("N5")
                .estimatedOverallLevel("N4").build());
        em.persist(UserLearningSurvey.builder()
                .userId(user.getId()).user(user).goal("JLPT").targetLevel("N4")
                .selfReportedLevel("N5").skills("KANJI,VOCABULARY,GRAMMAR,READING,LISTENING")
                .dailyStudyMinutes(30).completedAt(LocalDateTime.now())
                .build());

        Outcome outcomeA = Outcome.builder().code("READ_MAIN_IDEA").nameVi("Ý chính").skill("READING").build();
        Outcome outcomeB = Outcome.builder().code("READ_DETAIL").nameVi("Chi tiết").skill("READING").build();
        Outcome listeningOutcomeA = Outcome.builder().code("LISTEN_DETAIL").nameVi("Chi tiết nghe").skill("LISTENING").build();
        Outcome listeningOutcomeB = Outcome.builder().code("LISTEN_INTENT").nameVi("Ý định người nói").skill("LISTENING").build();
        em.persist(outcomeA);
        em.persist(outcomeB);
        em.persist(listeningOutcomeA);
        em.persist(listeningOutcomeB);

        Tag jlptTag = Tag.builder().code("JLPT").name("JLPT").tagType("SITUATION").build();
        em.persist(jlptTag);

        AssessmentQuestion questionA = questionWithOutcome(outcomeA, jlptTag);
        AssessmentQuestion questionB = questionWithOutcome(outcomeB, jlptTag);
        AssessmentQuestion listeningQuestionA = questionWithOutcome(listeningOutcomeA, jlptTag);
        listeningQuestionA.setSkill("LISTENING");
        AssessmentQuestion listeningQuestionB = questionWithOutcome(listeningOutcomeB, jlptTag);
        listeningQuestionB.setSkill("LISTENING");
        em.persist(questionA);
        em.persist(questionB);
        em.persist(listeningQuestionA);
        em.persist(listeningQuestionB);

        em.persist(wrongAnswer(attempt, questionA));
        em.persist(wrongAnswer(attempt, questionB));
        em.persist(wrongAnswer(attempt, listeningQuestionA));
        em.persist(wrongAnswer(attempt, listeningQuestionB));
        em.flush();
        em.clear();

        LearningProfileSnapshot snapshot = source.loadForUser(user.getId(), attempt.getId());

        WeaknessEvidence reading = snapshot.weaknesses().stream()
                .filter(e -> WeaknessEvidence.SKILL_READING.equals(e.skill()))
                .findFirst().orElseThrow();
        assertEquals(2, reading.assessedCount());
        assertEquals(2, reading.wrongCount());
        assertEquals(2, reading.outcomeBreakdown().size(),
                "Hai outcome sai bằng nhau (1-1) đều phải giữ, không chỉ outcome dominant");
        assertTrue(reading.outcomeBreakdown().stream()
                .anyMatch(o -> "READ_MAIN_IDEA".equals(o.outcomeCode()) && o.wrongCount() == 1 && o.assessedCount() == 1));
        assertTrue(reading.outcomeBreakdown().stream()
                .anyMatch(o -> "READ_DETAIL".equals(o.outcomeCode()) && o.wrongCount() == 1 && o.assessedCount() == 1));

        WeaknessEvidence listening = snapshot.weaknesses().stream()
                .filter(e -> WeaknessEvidence.SKILL_LISTENING.equals(e.skill()))
                .findFirst().orElseThrow();
        assertEquals(2, listening.assessedCount());
        assertEquals(2, listening.wrongCount());
        assertEquals(2, listening.outcomeBreakdown().size(),
                "Hai listening outcome sai phải cùng được giữ để lập lộ trình ôn");
        assertTrue(listening.outcomeBreakdown().stream()
                .anyMatch(o -> "LISTEN_DETAIL".equals(o.outcomeCode()) && o.wrongCount() == 1 && o.assessedCount() == 1));
        assertTrue(listening.outcomeBreakdown().stream()
                .anyMatch(o -> "LISTEN_INTENT".equals(o.outcomeCode()) && o.wrongCount() == 1 && o.assessedCount() == 1));
    }

    private AssessmentQuestion questionWithOutcome(Outcome outcome, Tag knowledgeTag) {
        return AssessmentQuestion.builder()
                .skill("READING").jlptLevel("N4").questionType("MULTIPLE_CHOICE")
                .questionText("Q").option1("1").option2("2").option3("3").option4("4")
                .correctOption(1).outcome(outcome).knowledgeTag(knowledgeTag).isActive(true)
                .build();
    }

    private AssessmentAnswer wrongAnswer(AssessmentAttempt attempt, AssessmentQuestion question) {
        return AssessmentAnswer.builder()
                .attempt(attempt).question(question)
                .selectedOption(2).isCorrect(false).answeredAt(LocalDateTime.now())
                .build();
    }

    private int userSeq = 0;

    private User seedUser() {
        Role role = em.createQuery("select r from Role r where r.name = 'LEARNER'", Role.class)
                .getResultStream().findFirst()
                .orElseGet(() -> {
                    Role created = Role.builder().name("LEARNER").build();
                    em.persist(created);
                    return created;
                });
        User user = User.builder().role(role)
                .email("profile-source" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        em.flush();
        return user;
    }
}

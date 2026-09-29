package com.jlas.learning.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.assessment.entity.AssessmentAnswer;
import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.entity.SmartTestSession;
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
        em.persist(outcomeA);
        em.persist(outcomeB);

        Tag jlptTag = Tag.builder().code("JLPT").name("JLPT").tagType("SITUATION").build();
        em.persist(jlptTag);

        AssessmentQuestion questionA = questionWithOutcome(outcomeA, jlptTag);
        AssessmentQuestion questionB = questionWithOutcome(outcomeB, jlptTag);
        em.persist(questionA);
        em.persist(questionB);

        em.persist(wrongAnswer(attempt, questionA));
        em.persist(wrongAnswer(attempt, questionB));
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

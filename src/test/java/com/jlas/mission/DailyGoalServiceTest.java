package com.jlas.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.mission.service.CreditLedgerService;
import com.jlas.mission.service.DailyGoalService;
import com.jlas.mission.service.StreakService;
import com.jlas.mission.service.impl.CreditLedgerServiceImpl;
import com.jlas.mission.service.impl.DailyGoalServiceImpl;
import com.jlas.mission.service.impl.StreakServiceImpl;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:daily-goal-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = DailyGoalServiceTest.DailyGoalTestConfiguration.class)
class DailyGoalServiceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {
            "com.jlas.auth.repository", "com.jlas.mission.repository", "com.jlas.moderation.repository"})
    @Import({DailyGoalServiceImpl.class, CreditLedgerServiceImpl.class, StreakServiceImpl.class})
    static class DailyGoalTestConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-17T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired EntityManager em;
    @Autowired DailyGoalService service;
    @Autowired CreditLedgerService creditLedger;
    @Autowired StreakService streaks;
    @Autowired UserRepository userRepository;

    private int userSeq = 0;

    @Test
    void summaryDefaultsToTargetsWhenNothingRecorded() {
        var user = seedUser();
        em.flush();

        var summary = service.summary(user.getId());

        assertEquals(0, summary.vocabularyDone());
        assertEquals(15, summary.vocabularyTarget());
        assertEquals(0, summary.quizDone());
        assertEquals(1, summary.quizTarget());
        assertEquals(0, summary.minutesDone());
        assertEquals(20, summary.minutesTarget());
        assertFalse(summary.completed());
    }

    @Test
    void quizCompletionIncrementsQuizCounter() {
        var user = seedUser();
        em.flush();

        service.recordQuiz(user.getId());

        var summary = service.summary(user.getId());
        assertEquals(1, summary.quizDone());
        assertFalse(summary.completed());
    }

    @Test
    void studyMinutesAccumulate() {
        var user = seedUser();
        em.flush();

        service.recordStudyMinutes(user.getId(), 5);
        service.recordStudyMinutes(user.getId(), 5);

        assertEquals(10, service.summary(user.getId()).minutesDone());
    }

    @Test
    void completingAllTargetsAwardsCreditsAndStreak() {
        var user = seedUser();
        em.flush();

        service.recordVocabulary(user.getId(), 15);
        service.recordQuiz(user.getId());
        service.recordStudyMinutes(user.getId(), 20);

        var summary = service.summary(user.getId());
        assertTrue(summary.completed());
        assertEquals(10, creditLedger.balance(user.getId()));
        assertEquals(1, streaks.snapshot(user.getId()).currentStreak());
    }

    @Test
    void repeatedActivityDoesNotDoubleAward() {
        var user = seedUser();
        em.flush();

        service.recordVocabulary(user.getId(), 15);
        service.recordQuiz(user.getId());
        service.recordStudyMinutes(user.getId(), 20);
        service.recordQuiz(user.getId());
        service.recordStudyMinutes(user.getId(), 5);

        var summary = service.summary(user.getId());
        assertEquals(2, summary.quizDone());
        assertEquals(25, summary.minutesDone());
        assertEquals(10, creditLedger.balance(user.getId()));
        assertEquals(1, streaks.snapshot(user.getId()).currentStreak());
    }

    private User seedUser() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User user = User.builder().role(role)
                .email("learner" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        return user;
    }
}

package com.jlas.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.mission.service.CreditLedgerService;
import com.jlas.mission.service.DailyGoalService;
import com.jlas.mission.service.StreakService;
import com.jlas.mission.service.impl.CreditLedgerServiceImpl;
import com.jlas.mission.service.impl.DailyGoalServiceImpl;
import com.jlas.mission.service.impl.StreakServiceImpl;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * QA acceptance checks for JLAS-W0130: day boundaries and repeated learning events must not
 * double-award credits or streaks, and each new day starts from a fresh daily-progress row.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:mission-qa-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = MissionQaTest.MissionQaConfiguration.class)
class MissionQaTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {
            "com.jlas.auth.repository", "com.jlas.mission.repository", "com.jlas.moderation.repository"})
    @Import({DailyGoalServiceImpl.class, CreditLedgerServiceImpl.class, StreakServiceImpl.class})
    static class MissionQaConfiguration {
        @Bean
        Clock clock() {
            return new MutableClock(ZoneOffset.UTC, LocalDate.of(2026, 9, 17));
        }
    }

    @Autowired EntityManager em;
    @Autowired DailyGoalService dailyGoal;
    @Autowired CreditLedgerService creditLedger;
    @Autowired StreakService streaks;
    @Autowired MutableClock clock;

    private int userSeq = 0;

    @BeforeEach
    void resetClock() {
        clock.set(LocalDate.of(2026, 9, 17));
    }

    @Test
    void repeatedEventsOnOneDayDoNotDoubleAward() {
        var user = seedUser();
        em.flush();

        completeGoal(user.getId());
        completeGoal(user.getId());

        assertEquals(10, creditLedger.balance(user.getId()));
        assertEquals(1, streaks.snapshot(user.getId()).currentStreak());
        assertEquals(2, dailyGoal.summary(user.getId()).quizDone());
    }

    @Test
    void consecutiveDaysBuildStreakAndCredits() {
        var user = seedUser();
        em.flush();

        completeGoal(user.getId());
        clock.set(LocalDate.of(2026, 9, 18));
        completeGoal(user.getId());

        assertEquals(20, creditLedger.balance(user.getId()));
        var streak = streaks.snapshot(user.getId());
        assertEquals(2, streak.currentStreak());
        assertEquals(2, streak.longestStreak());
    }

    @Test
    void skippedDayResetsStreak() {
        var user = seedUser();
        em.flush();

        completeGoal(user.getId());
        clock.set(LocalDate.of(2026, 9, 19));
        completeGoal(user.getId());

        var streak = streaks.snapshot(user.getId());
        assertEquals(1, streak.currentStreak());
        assertEquals(1, streak.longestStreak());
    }

    @Test
    void summaryStartsFreshOnANewDay() {
        var user = seedUser();
        em.flush();

        dailyGoal.recordQuiz(user.getId());
        assertEquals(1, dailyGoal.summary(user.getId()).quizDone());

        clock.set(LocalDate.of(2026, 9, 18));
        var summary = dailyGoal.summary(user.getId());
        assertEquals(0, summary.quizDone());
        assertFalse(summary.completed());
    }

    @Test
    void completingGoalMarksSummaryCompleted() {
        var user = seedUser();
        em.flush();

        completeGoal(user.getId());

        assertTrue(dailyGoal.summary(user.getId()).completed());
    }

    private void completeGoal(Long userId) {
        dailyGoal.recordVocabulary(userId, 15);
        dailyGoal.recordQuiz(userId);
        dailyGoal.recordStudyMinutes(userId, 20);
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

    /** Settable clock so tests can move the day forward. */
    static final class MutableClock extends Clock {
        private final ZoneId zone;
        private Instant instant;

        MutableClock(ZoneId zone, LocalDate date) {
            this.zone = zone;
            this.instant = date.atStartOfDay(zone).toInstant();
        }

        void set(LocalDate date) {
            this.instant = date.atStartOfDay(zone).toInstant();
        }

        @Override public ZoneId getZone() { return zone; }

        @Override public Clock withZone(ZoneId zone) { return new MutableClock(zone, LocalDate.ofInstant(instant, zone)); }

        @Override public Instant instant() { return instant; }
    }
}

package com.jlas.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.mission.repository.UserStreaksRepository;
import com.jlas.mission.service.StreakService;
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

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:streak-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = StreakServiceTest.StreakTestConfiguration.class)
class StreakServiceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.mission.repository")
    @Import(StreakServiceImpl.class)
    static class StreakTestConfiguration {
        @Bean
        Clock clock() {
            return new MutableClock(ZoneOffset.UTC, LocalDate.of(2026, 9, 17));
        }
    }

    @Autowired EntityManager em;
    @Autowired StreakService service;
    @Autowired UserStreaksRepository streaksRepository;
    @Autowired MutableClock clock;

    private int userSeq = 0;

    @BeforeEach
    void resetClock() {
        clock.set(LocalDate.of(2026, 9, 17));
    }

    @Test
    void firstCompletionStartsStreakAtOne() {
        var user = seedUser();
        em.flush();

        int current = service.recordCompletion(user.getId());

        assertEquals(1, current);
        assertEquals(1, streaksRepository.findByUserId(user.getId()).orElseThrow().getLongestStreak());
    }

    @Test
    void completionOnSameDayIsIdempotent() {
        var user = seedUser();
        em.flush();

        service.recordCompletion(user.getId());
        int again = service.recordCompletion(user.getId());

        assertEquals(1, again);
        assertEquals(1, streaksRepository.findByUserId(user.getId()).orElseThrow().getCurrentStreak());
    }

    @Test
    void consecutiveDaysIncrement() {
        var user = seedUser();
        em.flush();

        service.recordCompletion(user.getId());
        clock.set(LocalDate.of(2026, 9, 18));
        int current = service.recordCompletion(user.getId());

        assertEquals(2, current);
    }

    @Test
    void gapResetsStreakToOne() {
        var user = seedUser();
        em.flush();

        service.recordCompletion(user.getId());
        clock.set(LocalDate.of(2026, 9, 19));
        int current = service.recordCompletion(user.getId());

        assertEquals(1, current);
    }

    @Test
    void longestStreakIsPreservedAcrossResets() {
        var user = seedUser();
        em.flush();

        service.recordCompletion(user.getId());
        clock.set(LocalDate.of(2026, 9, 18));
        service.recordCompletion(user.getId());
        clock.set(LocalDate.of(2026, 9, 20));
        service.recordCompletion(user.getId());

        var streak = streaksRepository.findByUserId(user.getId()).orElseThrow();
        assertEquals(1, streak.getCurrentStreak());
        assertEquals(2, streak.getLongestStreak());
    }

    @Test
    void snapshotDefaultsToZero() {
        var user = seedUser();
        em.flush();

        var snapshot = service.snapshot(user.getId());

        assertEquals(0, snapshot.currentStreak());
        assertEquals(0, snapshot.longestStreak());
    }

    @Test
    void snapshotKeepsStreakForTodayAndYesterdayCompletion() {
        var user = seedUser();
        em.flush();
        service.recordCompletion(user.getId()); // day 17 -> 1
        clock.set(LocalDate.of(2026, 9, 18));
        service.recordCompletion(user.getId()); // day 18 -> 2

        assertEquals(2, service.snapshot(user.getId()).currentStreak());

        clock.set(LocalDate.of(2026, 9, 19));
        assertEquals(2, service.snapshot(user.getId()).currentStreak());
    }

    @Test
    void snapshotDecaysCurrentStreakAfterMissedDay() {
        var user = seedUser();
        em.flush();
        service.recordCompletion(user.getId()); // day 17 -> 1
        clock.set(LocalDate.of(2026, 9, 18));
        service.recordCompletion(user.getId()); // day 18 -> 2

        clock.set(LocalDate.of(2026, 9, 20)); // day 19 skipped
        var snapshot = service.snapshot(user.getId());

        assertEquals(0, snapshot.currentStreak());
        assertEquals(2, snapshot.longestStreak());
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

    /** Settable clock so tests can move the day forward and back. */
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

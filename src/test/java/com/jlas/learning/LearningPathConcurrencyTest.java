package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.CreateLearningPathResult;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.LearningService;
import com.jlas.learning.service.impl.JpaLearningCatalogSource;
import com.jlas.learning.service.impl.LearningResourceSelectionServiceImpl;
import com.jlas.learning.service.impl.LearningScheduleServiceImpl;
import com.jlas.learning.service.impl.LearningServiceImpl;
import com.jlas.learning.service.impl.LearningClock;
import com.jlas.learning.service.impl.WeakTagPolicy;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two concurrent creates with the same generation identity must serialize on the user-row lock:
 * exactly one creates a version, the other reuses it idempotently, and only one ACTIVE path remains.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:learning-path-concurrency-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "jlas.learning.selection.minimum-assessed-questions=3",
        "jlas.learning.selection.wrong-ratio-threshold=0.5"
})
@ContextConfiguration(classes = LearningPathConcurrencyTest.ConcurrencyConfiguration.class)
class LearningPathConcurrencyTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.dictionary.repository", "com.jlas.assessment.repository"})
    @Import({JpaLearningCatalogSource.class, WeakTagPolicy.class, LearningResourceSelectionServiceImpl.class,
            LearningScheduleServiceImpl.class, LearningMapper.class, LearningServiceImpl.class, LearningClock.class})
    static class ConcurrencyConfiguration {
        @Bean FakeProfileSource profileSource() {
            return new FakeProfileSource();
        }

        @Bean FakeScopeSource scopeSource() {
            return new FakeScopeSource();
        }
    }

    static class FakeProfileSource implements LearningProfileSource {
        LearningProfileSnapshot snapshot;

        @Override
        public LearningProfileSnapshot loadForUser(Long userId, Long assessmentAttemptId) {
            return snapshot;
        }
    }

    static class FakeScopeSource implements LearningScopeSource {
        LearningCatalogScope scope;

        @Override
        public LearningCatalogScope resolve(Long userId, LearningProfileSnapshot profile) {
            return scope;
        }
    }

    @Autowired EntityManager em;
    @Autowired LearningService service;
    @Autowired PlatformTransactionManager txManager;
    @Autowired FakeProfileSource profileSource;
    @Autowired FakeScopeSource scopeSource;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCreateWithSameIdentityProducesOnePath() throws Exception {
        Fixture fixture = seedFixtureCommitted();
        profileSource.snapshot = profile(fixture.userId(), 91L, "rev-1");
        scopeSource.scope = new LearningCatalogScope(List.of(fixture.warmBookId()), List.of(fixture.goalBookId()));

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<CreateLearningPathResult> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        Runnable task = () -> {
            try {
                ready.countDown();
                go.await();
                results.add(service.create(fixture.userId(), 91L));
            } catch (Throwable throwable) {
                errors.add(throwable);
            }
        };
        Thread first = new Thread(task);
        Thread second = new Thread(task);
        first.start();
        second.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS), "threads should be ready");
        go.countDown();
        first.join(30_000);
        second.join(30_000);

        assertTrue(errors.isEmpty(), "concurrent create must not fail: " + errors);
        assertEquals(2, results.size());
        long created = results.stream().filter(CreateLearningPathResult::created).count();
        assertEquals(1L, created, "exactly one creation and one idempotent reuse");
        assertEquals(results.get(0).path().id(), results.get(1).path().id(), "both return the same path");

        long[] counts = new TransactionTemplate(txManager).execute(status -> {
            long total = em.createQuery("select count(p) from UserLearningPath p", Long.class).getSingleResult();
            long active = em.createQuery(
                    "select count(p) from UserLearningPath p where p.status = 'ACTIVE'", Long.class).getSingleResult();
            return new long[] { total, active };
        });
        assertEquals(1L, counts[0], "exactly one path persisted");
        assertEquals(1L, counts[1], "exactly one ACTIVE path");
    }

    private Fixture seedFixtureCommitted() {
        return new TransactionTemplate(txManager).execute(status -> {
            Role role = Role.builder().name("LEARNER").build();
            em.persist(role);
            User user = User.builder().role(role).email("concurrent@test.local").loginType("LOCAL").build();
            em.persist(user);
            em.flush();

            Textbook warmBook = textbook("N5", "Warm-up book");
            Lesson warmLesson = lesson(unit(warmBook), tag("PARTICLE"), 15);
            Textbook goalBook = textbook("N4", "Goal book");
            Lesson goalLesson = lesson(unit(goalBook), tag("TARGET"), 20);
            return new Fixture(user.getId(), warmBook.getId(), goalBook.getId(), warmLesson.getId(), goalLesson.getId());
        });
    }

    private Textbook textbook(String level, String title) {
        Textbook book = Textbook.builder().title(title).status("PUBLISHED").jlptLevel(level).build();
        em.persist(book);
        return book;
    }

    private Unit unit(Textbook book) {
        Unit unit = Unit.builder().textbook(book).title("Unit").orderIndex(1).build();
        em.persist(unit);
        return unit;
    }

    private Tag tag(String code) {
        Tag tag = Tag.builder().code(code).name("Tag " + code).tagType("KNOWLEDGE").build();
        em.persist(tag);
        return tag;
    }

    private Lesson lesson(Unit unit, Tag tag, int minutes) {
        Lesson lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Lesson " + tag.getCode())
                .status("PUBLISHED").estimatedMinutes(minutes).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }

    private LearningProfileSnapshot profile(Long userId, Long attemptId, String revision) {
        return new LearningProfileSnapshot(userId, attemptId, 10L, revision, true, true,
                "N4", "N4", "JLPT", 30, List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 5, 4)));
    }

    private record Fixture(Long userId, Long warmBookId, Long goalBookId, Long warmLessonId, Long goalLessonId) { }
}

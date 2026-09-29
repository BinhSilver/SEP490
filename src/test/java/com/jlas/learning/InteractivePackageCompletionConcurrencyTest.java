package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.InteractiveLearningPackageResponse;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningPackageStatus;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.impl.InteractiveLearningPathServiceImpl;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0193] RC Task 8 hardening — real multi-threaded proof (H2-backed, real
 * {@link InteractiveLearningPathServiceImpl}, no mocks) that two concurrent reads of the same package
 * ({@code GET /api/learning/v2/packages/{id}}, which performs the idempotent completion transition inline)
 * can complete the package exactly once even when both requests race to be "the one that finishes it".
 * Every prior RC task disclosed only "guarded by the caller's pessimistic lock plus a status!=COMPLETED
 * check, proven by sequential logic" for this contract (rules doc §2.15); this test forces genuine overlap
 * with a {@link CountDownLatch} barrier so the pessimistic write lock on the package row
 * ({@code LearningPathPackageRepository#findOwnedByIdForUpdate}) is the thing actually under test.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:package-completion-concurrency-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = InteractivePackageCompletionConcurrencyTest.Cfg.class)
class InteractivePackageCompletionConcurrencyTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg {
        @Bean InteractiveLearningPathServiceImpl service(UserLearningPathRepository paths,
                LearningPathPackageRepository packages, UserLearningPathItemRepository items,
                LearningQuizSessionRepository quizSessions) {
            // Reward/streak repositories are deliberately left unwired (null), same posture the existing
            // slice tests already use for this service — this test's concern is the completion state
            // transition itself, not the credit ledger's own independent de-dupe (covered elsewhere).
            return new InteractiveLearningPathServiceImpl(paths, packages, items, quizSessions);
        }
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired InteractiveLearningPathServiceImpl service;
    @Autowired LearningPathPackageRepository packages;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentPackageDetailReadsCompleteThePackageExactlyOnce() throws Exception {
        Fixture fixture = seedFixtureCommitted();

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<InteractiveLearningPackageResponse> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        Runnable task = () -> {
            try {
                ready.countDown();
                go.await();
                results.add(service.packageDetail(fixture.userId(), fixture.packageId()));
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

        assertTrue(errors.isEmpty(), "concurrent package completion reads must not fail: " + errors);
        assertEquals(2, results.size());
        long justCompletedCount = results.stream().filter(InteractiveLearningPackageResponse::justCompleted).count();
        assertEquals(1L, justCompletedCount,
                "exactly one of the two concurrent reads must be the one that performs completion");

        var reloaded = new TransactionTemplate(txManager).execute(status -> {
            em.clear();
            return packages.findById(fixture.packageId()).orElseThrow();
        });
        assertEquals(LearningPackageStatus.COMPLETED, reloaded.getStatus());
        assertTrue(reloaded.getScorePercent() != null && reloaded.getScorePercent() > 0,
                "score must be computed from real data exactly once");

        var next = new TransactionTemplate(txManager).execute(status -> {
            em.clear();
            return packages.findById(fixture.nextPackageId()).orElseThrow();
        });
        assertEquals(LearningPackageStatus.AVAILABLE, next.getStatus(),
                "next package must be unlocked exactly once, not left LOCKED or double-processed");
    }

    private Fixture seedFixtureCommitted() {
        return new TransactionTemplate(txManager).execute(status -> {
            Role role = Role.builder().name("LEARNER").build();
            em.persist(role);
            User owner = User.builder().role(role).email("package-completion-concurrency@test.local")
                    .loginType("LOCAL").build();
            em.persist(owner);

            UserLearningPath path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE")
                    .version(1).experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
            em.persist(path);

            Textbook textbook = Textbook.builder().title("Goal book").status("PUBLISHED").jlptLevel("N4").build();
            em.persist(textbook);
            Unit unit = Unit.builder().textbook(textbook).title("Unit").orderIndex(1).build();
            em.persist(unit);
            Tag lessonTag = Tag.builder().code("TARGET_PKG_CONCURRENCY").name("Target").tagType("KNOWLEDGE").build();
            em.persist(lessonTag);
            Lesson lesson = Lesson.builder().unit(unit).knowledgeTag(lessonTag).title("Bài học ngày 1")
                    .status("PUBLISHED").estimatedMinutes(30).orderIndex(1).lessonType("CURRICULUM")
                    .skill("GRAMMAR").build();
            em.persist(lesson);

            LearningPathPackage pkg1 = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING")
                    .dayNumber(1).orderIndex(1).title("Ngày 1").estimatedMinutes(30)
                    .status(LearningPackageStatus.IN_PROGRESS).sourceLesson(lesson).contentRevision("rev-1").build();
            em.persist(pkg1);
            LearningPathPackage pkg2 = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING")
                    .dayNumber(2).orderIndex(2).title("Ngày 2").estimatedMinutes(30)
                    .status(LearningPackageStatus.LOCKED).build();
            em.persist(pkg2);

            UserLearningPathItem vocab = activity(path, pkg1, LearningActivityType.VOCABULARY, 1,
                    LearningActivityState.PASSED);
            UserLearningPathItem kanji = activity(path, pkg1, LearningActivityType.KANJI, 2,
                    LearningActivityState.PASSED);
            UserLearningPathItem grammar = activity(path, pkg1, LearningActivityType.GRAMMAR, 3,
                    LearningActivityState.PASSED);
            UserLearningPathItem dialogue = activity(path, pkg1, LearningActivityType.DIALOGUE, 4,
                    LearningActivityState.COMPLETED);
            em.persist(vocab);
            em.persist(kanji);
            em.persist(grammar);
            em.persist(dialogue);

            em.persist(quizSession(owner, "VOCAB_MICRO_CHECK", vocab.getId(), 10, 9));
            em.persist(quizSession(owner, "KANJI_MICRO_CHECK", kanji.getId(), 10, 8));
            em.persist(quizSession(owner, "GRAMMAR_MICRO_CHECK", grammar.getId(), 10, 7));
            em.flush();

            return new Fixture(owner.getId(), pkg1.getId(), pkg2.getId());
        });
    }

    private UserLearningPathItem activity(UserLearningPath path, LearningPathPackage pkg, LearningActivityType type,
            int order, LearningActivityState state) {
        return UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).orderIndex(order)
                .activityType(type).activityState(state).build();
    }

    private LearningQuizSession quizSession(User owner, String quizType, Long activityId, int total, int correct) {
        return LearningQuizSession.builder().user(owner).quizType(quizType).activityId(activityId).batchIndex(0)
                .status("SUBMITTED").passed(true).questionCount(total).correctCount(correct)
                .questionsJson("[]").answerKeyJson("{}").build();
    }

    private record Fixture(Long userId, Long packageId, Long nextPackageId) { }
}

package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Outcome;
import com.jlas.dictionary.entity.Tag;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningMicroCheckState;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.ComprehensionExerciseCatalog;
import com.jlas.learning.service.impl.InteractiveComprehensionServiceImpl;
import com.jlas.learning.service.impl.JpaComprehensionExerciseCatalog;
import com.jlas.learning.service.impl.engine.ComprehensionRevision;
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
 * {@link InteractiveComprehensionServiceImpl}, no mocks) that a genuinely concurrent duplicate submit of the
 * SAME issued micro-check attempt cannot double-spend an attempt or corrupt the persisted score. Every prior
 * RC task disclosed only "pessimistic-locked, proven by sequential logic" for this contract; this test forces
 * two threads to race on {@code submitMicroCheck} with a {@link CountDownLatch} barrier so the pessimistic
 * write lock on the activity row (acquired by {@code InteractiveStudyEngine#loadActivityForUpdate}) is the
 * thing actually under test, not merely present in the code.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:comprehension-concurrency-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = InteractiveComprehensionConcurrencyTest.Cfg.class)
class InteractiveComprehensionConcurrencyTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.assessment.repository", "com.jlas.dictionary.repository"})
    static class Cfg {
        @Bean LearningMapper mapper() {
            return new LearningMapper();
        }

        @Bean ComprehensionExerciseCatalog catalog(AssessmentQuestionRepository questions) {
            return new JpaComprehensionExerciseCatalog(questions);
        }

        @Bean InteractiveComprehensionServiceImpl service(UserLearningPathItemRepository items,
                LearningActivityMemberRepository members, LearningStudySessionRepository studySessions,
                LearningStudyCardRepository studyCards, LearningMicroCheckStateRepository checkStates,
                LearningQuizSessionRepository quizSessions, AssessmentQuestionRepository questions,
                ComprehensionExerciseCatalog catalog, LearningMapper mapper) {
            return new InteractiveComprehensionServiceImpl(items, members, studySessions, studyCards,
                    checkStates, quizSessions, questions, catalog, mapper);
        }
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired InteractiveComprehensionServiceImpl service;
    @Autowired AssessmentQuestionRepository questions;
    @Autowired LearningMicroCheckStateRepository checkStates;
    @Autowired LearningQuizSessionRepository quizSessions;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDuplicateSubmitOfTheSameAttemptSpendsExactlyOneAttempt() throws Exception {
        Fixture fixture = seedFixtureCommitted();

        MicroCheckResponse issued = new TransactionTemplate(txManager)
                .execute(status -> service.issueMicroCheck(fixture.userId(), fixture.activityId()));

        List<MicroCheckSubmitRequest.MicroCheckAnswer> answers = issued.questions().stream()
                .map(q -> new MicroCheckSubmitRequest.MicroCheckAnswer(q.questionId(), 1))
                .toList();
        MicroCheckSubmitRequest request = new MicroCheckSubmitRequest(issued.attemptId(), answers);

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<MicroCheckResult> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        Runnable task = () -> {
            try {
                ready.countDown();
                go.await();
                results.add(service.submitMicroCheck(fixture.userId(), fixture.activityId(), request));
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

        assertTrue(errors.isEmpty(), "concurrent duplicate submit must not fail: " + errors);
        assertEquals(2, results.size());
        assertEquals(results.get(0).scorePercent(), results.get(1).scorePercent());
        assertEquals(results.get(0).passed(), results.get(1).passed());
        assertTrue(results.get(0).passed(), "100% correct answers must pass");

        var state = new TransactionTemplate(txManager).execute(status -> {
            em.clear();
            List<LearningMicroCheckState> all = checkStates.findByActivityIdOrderByBatchIndex(fixture.activityId());
            return all.getFirst();
        });
        assertEquals(1, state.getAttemptsUsed(), "a genuinely concurrent duplicate submit must spend exactly one attempt");

        long submittedSessions = new TransactionTemplate(txManager).execute(status -> {
            em.clear();
            return quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(
                    fixture.activityId(), "READING_MICRO_CHECK", "SUBMITTED", true).size();
        });
        assertEquals(1, submittedSessions, "exactly one submitted+passed session must exist, never two");
    }

    private Fixture seedFixtureCommitted() {
        return new TransactionTemplate(txManager).execute(status -> {
            Role role = Role.builder().name("LEARNER").build();
            em.persist(role);
            User owner = User.builder().role(role).email("comprehension-concurrency@test.local")
                    .loginType("LOCAL").build();
            em.persist(owner);

            UserLearningPath path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE")
                    .version(1).experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
            em.persist(path);

            UserLearningPathItem activity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                    .activityType(LearningActivityType.READING).activityState(LearningActivityState.STUDYING)
                    .build();
            em.persist(activity);

            Tag knowledgeTag = Tag.builder().code("KNOW_READING_CONCURRENCY").name("Reading concurrency")
                    .tagType("KNOWLEDGE").build();
            em.persist(knowledgeTag);
            Outcome outcome = Outcome.builder().code("READ_CONCURRENCY_" + System.nanoTime())
                    .nameVi("Đọc hiểu ý chính").skill("READING").build();
            em.persist(outcome);

            AssessmentQuestion q1 = question(knowledgeTag, outcome, 1);
            AssessmentQuestion q2 = question(knowledgeTag, outcome, 2);
            em.persist(q1);
            em.persist(q2);
            em.flush();

            var resolved = new JpaComprehensionExerciseCatalog(questions).resolve("READING", "N4");
            var exercise = resolved.exercises().stream()
                    .filter(e -> e.questions().stream().anyMatch(qq -> qq.id().equals(q1.getId())))
                    .findFirst().orElseThrow();
            String revision = ComprehensionRevision.combined(LearningActivityType.READING, List.of(exercise));

            em.persist(LearningActivityMember.builder().activity(activity)
                    .memberType(LearningMemberType.COMPREHENSION_QUESTION)
                    .canonicalId(q1.getId()).orderIndex(1).sourceRevision(revision).build());
            em.persist(LearningActivityMember.builder().activity(activity)
                    .memberType(LearningMemberType.COMPREHENSION_QUESTION)
                    .canonicalId(q2.getId()).orderIndex(2).sourceRevision(revision).build());
            em.flush();

            return new Fixture(owner.getId(), activity.getId());
        });
    }

    private AssessmentQuestion question(Tag knowledgeTag, Outcome outcome, int index) {
        return AssessmentQuestion.builder().skill("READING").jlptLevel("N4").isActive(true)
                .knowledgeTag(knowledgeTag).outcome(outcome).passageHtml("<p>読解本文 concurrency</p>")
                .questionType("MULTIPLE_CHOICE").questionText("Question " + index)
                .option1("A").option2("B").option3("C").correctOption(1).explanation("E" + index).build();
    }

    private record Fixture(Long userId, Long activityId) { }
}

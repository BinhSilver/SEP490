package com.jlas.learning;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.entity.LearningQuizSession;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** [JLAS-W0193] Task 8 — H2 round-trip for the new {@code learning_path_package.score_percent} column and
 * the passing-micro-check-session lookup used to aggregate a real package score. */
@DataJpaTest(properties = {"spring.profiles.include=", "spring.datasource.url=jdbc:h2:mem:v2-completion-persistence;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = InteractivePackageCompletionPersistenceTest.Cfg.class)
class InteractivePackageCompletionPersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired LearningPathPackageRepository packages;
    @Autowired UserLearningPathItemRepository items;
    @Autowired LearningQuizSessionRepository quizSessions;

    @Test void scorePercentRoundTripsAndDefaultsToNull() {
        var role = Role.builder().name("LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("v2-score-owner@jlas.test").loginType("LOCAL").build(); em.persist(owner);
        var path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build(); em.persist(path);
        var pkg = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING").dayNumber(1).orderIndex(1)
                .title("Ngày 1").estimatedMinutes(30).status(LearningPackageStatus.AVAILABLE).build(); em.persist(pkg);
        em.flush(); em.clear();

        var reloaded = packages.findById(pkg.getId()).orElseThrow();
        assertNull(reloaded.getScorePercent());

        reloaded.setStatus(LearningPackageStatus.COMPLETED);
        reloaded.setScorePercent(82);
        packages.save(reloaded);
        em.flush(); em.clear();

        assertEquals(82, packages.findById(pkg.getId()).orElseThrow().getScorePercent());
    }

    @Test void findsOnlyPassingSubmittedSessionsForTheGivenActivityAndQuizType() {
        var role = Role.builder().name("LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("v2-score-quiz@jlas.test").loginType("LOCAL").build(); em.persist(owner);
        var path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build(); em.persist(path);
        var pkg = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING").dayNumber(1).orderIndex(1)
                .title("Ngày 1").estimatedMinutes(30).status(LearningPackageStatus.IN_PROGRESS).build(); em.persist(pkg);
        var activity = UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).orderIndex(1)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.PASSED).build();
        em.persist(activity);

        em.persist(LearningQuizSession.builder().user(owner).quizType("VOCAB_MICRO_CHECK").activityId(activity.getId())
                .batchIndex(0).status("SUBMITTED").passed(true).questionCount(10).correctCount(9)
                .questionsJson("[]").answerKeyJson("{}").build());
        // A failed earlier attempt on the same batch must not be counted.
        em.persist(LearningQuizSession.builder().user(owner).quizType("VOCAB_MICRO_CHECK").activityId(activity.getId())
                .batchIndex(0).status("SUBMITTED").passed(false).questionCount(10).correctCount(4)
                .questionsJson("[]").answerKeyJson("{}").build());
        // An unrelated quiz type on the same activity id space must not leak in.
        em.persist(LearningQuizSession.builder().user(owner).quizType("KANJI_MICRO_CHECK").activityId(activity.getId())
                .batchIndex(0).status("SUBMITTED").passed(true).questionCount(10).correctCount(1)
                .questionsJson("[]").answerKeyJson("{}").build());
        em.flush(); em.clear();

        List<LearningQuizSession> passing = quizSessions.findByActivityIdAndQuizTypeAndStatusAndPassed(
                activity.getId(), "VOCAB_MICRO_CHECK", "SUBMITTED", true);

        assertEquals(1, passing.size());
        assertEquals(9, passing.getFirst().getCorrectCount());
    }
}

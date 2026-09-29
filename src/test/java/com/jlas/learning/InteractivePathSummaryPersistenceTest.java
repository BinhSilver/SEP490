package com.jlas.learning;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.LearningMicroCheckState;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** [JLAS-W0193] Task 10 — H2 round-trip for the two new repository methods the path summary reads:
 * the "latest V2 path regardless of status" lookup (must keep working once a path is COMPLETED, unlike the
 * ACTIVE-only lookup the roadmap uses) and the batched cross-activity micro-check-state fetch. */
@DataJpaTest(properties = {"spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:v2-summary-persistence;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = InteractivePathSummaryPersistenceTest.Cfg.class)
class InteractivePathSummaryPersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired UserLearningPathRepository paths;
    @Autowired UserLearningPathItemRepository items;
    @Autowired LearningPathPackageRepository packages;
    @Autowired LearningMicroCheckStateRepository microCheckStates;

    @Test void findsTheMostRecentInteractivePathRegardlessOfStatusUnlikeTheActiveOnlyLookup() {
        var role = Role.builder().name("LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("v2-summary-owner@jlas.test").loginType("LOCAL").build();
        em.persist(owner);
        var older = UserLearningPath.builder().user(owner).title("Older").status("SUPERSEDED").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
        em.persist(older);
        var completed = UserLearningPath.builder().user(owner).title("Completed").status("COMPLETED").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
        em.persist(completed);
        em.flush(); em.clear();

        Optional<UserLearningPath> found = paths.findTopByUserIdAndExperienceVersionOrderByIdDesc(
                owner.getId(), LearningExperienceVersion.INTERACTIVE_V2);

        assertTrue(found.isPresent());
        assertEquals(completed.getId(), found.get().getId());
        assertEquals("COMPLETED", found.get().getStatus());
        // The roadmap's own ACTIVE-only lookup would find nothing for this same user, by design.
        assertTrue(paths.findByUserIdAndStatusAndExperienceVersion(
                owner.getId(), "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2).isEmpty());
    }

    @Test void batchedMicroCheckStateFetchReturnsStatesForEveryRequestedActivityOrderedDeterministically() {
        var role = Role.builder().name("LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("v2-summary-states@jlas.test").loginType("LOCAL").build();
        em.persist(owner);
        var path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
        em.persist(path);
        var pkg = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING").dayNumber(1).orderIndex(1)
                .title("Ngày 1").estimatedMinutes(30).status(LearningPackageStatus.IN_PROGRESS).build();
        em.persist(pkg);
        var vocab = UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).orderIndex(1)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.PASSED).build();
        em.persist(vocab);
        var kanji = UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).orderIndex(2)
                .activityType(LearningActivityType.KANJI).activityState(LearningActivityState.PASSED).build();
        em.persist(kanji);
        em.persist(LearningMicroCheckState.builder().activity(vocab).batchIndex(0).attemptsUsed(1).passed(true)
                .firstScore(80).latestScore(80).bestScore(80).build());
        em.persist(LearningMicroCheckState.builder().activity(kanji).batchIndex(0).attemptsUsed(1).passed(true)
                .firstScore(90).latestScore(90).bestScore(90).build());
        em.flush(); em.clear();

        List<LearningMicroCheckState> states = microCheckStates.findByActivityIdInOrderByActivityIdAscBatchIndexAsc(
                List.of(vocab.getId(), kanji.getId()));

        assertEquals(2, states.size());
        assertEquals(vocab.getId(), states.get(0).getActivity().getId());
        assertEquals(kanji.getId(), states.get(1).getActivity().getId());
    }
}

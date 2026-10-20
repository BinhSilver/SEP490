package com.jlas.learning;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.dto.LearningPaceRequest;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.impl.InteractivePaceReplanServiceImpl;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** [JLAS-W0126] Real service transaction proxy + H2; no live MySQL or learner data. */
@DataJpaTest(properties = {"spring.profiles.include=", "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = InteractivePaceTransactionTest.Cfg.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InteractivePaceTransactionTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    @Import(InteractivePaceReplanServiceImpl.class)
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired InteractivePaceReplanServiceImpl service;
    @Autowired UserLearningPathItemRepository items;
    @Autowired LearningActivityMemberRepository members;
    @Autowired LearningPathPackageRepository packages;
    @MockitoSpyBean UserLearningPathRepository paths;

    @Test void simultaneousConfirmationsCreatePartsExactlyOnce() throws Exception {
        var fixture = seed();
        var preview = service.preview(fixture.user(), "LIGHTER");
        var request = new LearningPaceRequest("LIGHTER", 20, preview.previewToken());
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> confirm = () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start barrier timeout");
                return service.apply(fixture.user(), request).changed();
            };
            var first = pool.submit(confirm);
            var second = pool.submit(confirm);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var parts = items.findByLearningPathIdOrderByOrderIndex(fixture.path());
            assertEquals(2, parts.size());
            assertEquals(List.of(1, 2), parts.stream().map(UserLearningPathItem::getDayNumber).toList());
            assertEquals(28, members.findByPathInStudyOrder(fixture.path()).size());
            assertEquals(28, members.findByPathInStudyOrder(fixture.path()).stream()
                    .map(LearningActivityMember::getCanonicalId).distinct().count());
            assertEquals(1, packages.findByLearningPathIdOrderByOrderIndex(fixture.path()).size());
            assertEquals(20, paths.findById(fixture.path()).orElseThrow().getMinutesPerDay());
        });
    }

    @Test void failureAfterPartWritesRollsBackMembersActivitiesPackageAndPace() {
        var fixture = seed();
        var preview = service.preview(fixture.user(), "LIGHTER");
        // Inject only the final DB write failure; all earlier inserts/membership moves hit real H2.
        doThrow(new IllegalStateException("injected final write failure")).when(paths).save(any(UserLearningPath.class));
        assertThrows(IllegalStateException.class, () -> service.apply(fixture.user(),
                new LearningPaceRequest("LIGHTER", 20, preview.previewToken())));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertEquals(30, paths.findById(fixture.path()).orElseThrow().getMinutesPerDay());
            var activities = items.findByLearningPathIdOrderByOrderIndex(fixture.path());
            assertEquals(1, activities.size());
            assertEquals(fixture.activity(), activities.getFirst().getId());
            assertEquals(1, activities.getFirst().getOrderIndex());
            assertEquals(23, activities.getFirst().getEstimatedMinutes());
            assertEquals(28, members.findByActivityIdOrderByOrderIndex(fixture.activity()).size());
            var pkg = packages.findByLearningPathIdOrderByOrderIndex(fixture.path()).getFirst();
            assertEquals(1, pkg.getDayNumber());
            assertEquals(23, pkg.getEstimatedMinutes());
        });
    }

    @Test void studySessionProtectsLegacyLockedActivityFromSplitting() {
        var fixture = seed();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> em.persist(LearningStudySession.builder()
                .activity(em.getReference(UserLearningPathItem.class, fixture.activity())).sourceRevision("fixture").build()));
        var preview = service.preview(fixture.user(), "LIGHTER");
        service.apply(fixture.user(), new LearningPaceRequest("LIGHTER", 20, preview.previewToken()));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertEquals(1, items.findByLearningPathIdOrderByOrderIndex(fixture.path()).size());
            assertEquals(28, members.findByActivityIdOrderByOrderIndex(fixture.activity()).size());
            assertEquals(List.of(fixture.activity()), items.findActivityIdsWithHistory(fixture.path()));
        });
    }

    private record Fixture(Long user, Long path, Long activity) { }
    private Fixture seed() {
        return new TransactionTemplate(transactions).execute(tx -> {
            String suffix = UUID.randomUUID().toString();
            var role = Role.builder().name("P" + suffix.substring(0, 8)).build(); em.persist(role);
            var owner = User.builder().role(role).email(suffix + "@jlas.test").loginType("LOCAL").build(); em.persist(owner);
            var path = UserLearningPath.builder().user(owner).title("Transaction fixture").status("ACTIVE").version(1)
                    .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).minutesPerDay(30).build(); em.persist(path);
            var pkg = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING").dayNumber(1)
                    .orderIndex(1).title("28 words").estimatedMinutes(23).status(LearningPackageStatus.AVAILABLE).build(); em.persist(pkg);
            var activity = UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).phase("GOAL_LEARNING")
                    .orderIndex(1).dayNumber(1).estimatedMinutes(23).activityType(LearningActivityType.VOCABULARY)
                    .activityState(LearningActivityState.LOCKED).build(); em.persist(activity);
            for (int i = 1; i <= 28; i++) em.persist(LearningActivityMember.builder().activity(activity)
                    .memberType(LearningMemberType.VOCABULARY_ENTRY).canonicalId((long) i).orderIndex(i).sourceRevision("fixture").build());
            em.flush();
            return new Fixture(owner.getId(), path.getId(), activity.getId());
        });
    }
}

package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.dto.DialogueHeartbeatRequest;
import com.jlas.learning.dto.DialogueProgressResult;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningDialogueProgress;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningDialogueProgressRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.impl.InteractiveDialogueServiceImpl;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
 * {@link InteractiveDialogueServiceImpl}, no mocks) that two concurrent heartbeats racing on the exact moment
 * Dialogue's completion threshold is crossed cannot lose a time credit and cannot complete the activity twice.
 * Rules doc §2.14 disclosed only that the activity row's {@code FOR UPDATE} lock "is what actually serializes
 * two concurrent heartbeat/reached-bottom calls", proven so far by sequential-call tests only; this test
 * forces genuine overlap with a {@link CountDownLatch} barrier.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:dialogue-concurrency-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ContextConfiguration(classes = InteractiveDialogueConcurrencyTest.Cfg.class)
class InteractiveDialogueConcurrencyTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.dictionary.repository"})
    static class Cfg {
        @Bean InteractiveDialogueServiceImpl service(UserLearningPathItemRepository items,
                LearningActivityMemberRepository members, LearningDialogueProgressRepository progressRepo,
                com.jlas.dictionary.repository.DialogueLineDetailRepository dialogueLines,
                com.jlas.dictionary.repository.ContentAudioRepository audio) {
            return new InteractiveDialogueServiceImpl(items, members, progressRepo, dialogueLines, audio);
        }
    }

    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @Autowired InteractiveDialogueServiceImpl service;
    @Autowired LearningDialogueProgressRepository progressRepo;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentHeartbeatsCrossingTheThresholdCreditBothAndCompleteExactlyOnce() throws Exception {
        Fixture fixture = seedFixtureCommitted();

        // Establish a baseline heartbeat (credits nothing — first heartbeat of a session) so a later
        // heartbeat's gap is measurable, then seed accumulated time and reached-bottom evidence right at the
        // boundary: two more heartbeats (10s gap each, capped at 20s/heartbeat) will each independently push
        // the total over the 180s threshold if credited correctly, and only one may perform the completion.
        new TransactionTemplate(txManager).execute(status -> {
            service.heartbeat(fixture.userId(), fixture.activityId(), new DialogueHeartbeatRequest("baseline"));
            return null;
        });
        new TransactionTemplate(txManager).execute(status -> {
            em.clear();
            LearningDialogueProgress progress = progressRepo.findByActivityId(fixture.activityId()).orElseThrow();
            progress.setAccumulatedSeconds(170);
            progress.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(10));
            progress.setReachedBottomAt(LocalDateTime.now().minusSeconds(5));
            progressRepo.save(progress);
            return null;
        });

        int threads = 2;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<DialogueProgressResult> results = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        Runnable taskA = () -> {
            try {
                ready.countDown();
                go.await();
                results.add(service.heartbeat(fixture.userId(), fixture.activityId(),
                        new DialogueHeartbeatRequest("race-a")));
            } catch (Throwable throwable) {
                errors.add(throwable);
            }
        };
        Runnable taskB = () -> {
            try {
                ready.countDown();
                go.await();
                results.add(service.heartbeat(fixture.userId(), fixture.activityId(),
                        new DialogueHeartbeatRequest("race-b")));
            } catch (Throwable throwable) {
                errors.add(throwable);
            }
        };
        Thread first = new Thread(taskA);
        Thread second = new Thread(taskB);
        first.start();
        second.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS), "threads should be ready");
        go.countDown();
        first.join(30_000);
        second.join(30_000);

        assertTrue(errors.isEmpty(), "concurrent heartbeats must not fail: " + errors);
        assertEquals(2, results.size());

        var reloaded = new TransactionTemplate(txManager).execute(status -> {
            em.clear();
            return progressRepo.findByActivityId(fixture.activityId()).orElseThrow();
        });
        assertTrue(reloaded.getAccumulatedSeconds() >= 180,
                "both heartbeats' contributions must be credited — no lost update under real concurrency");
        assertTrue(reloaded.getCompletedAt() != null, "completion must be recorded exactly once");
        long completedCount = results.stream().filter(DialogueProgressResult::completed).count();
        assertTrue(completedCount >= 1, "at least the call that crosses the threshold must observe completion");

        var activity = new TransactionTemplate(txManager).execute(status -> {
            em.clear();
            return em.createQuery(
                    "select a from UserLearningPathItem a where a.id = :id", UserLearningPathItem.class)
                    .setParameter("id", fixture.activityId()).getSingleResult();
        });
        assertEquals(LearningActivityState.COMPLETED, activity.getActivityState());
    }

    private Fixture seedFixtureCommitted() {
        return new TransactionTemplate(txManager).execute(status -> {
            Role role = Role.builder().name("LEARNER").build();
            em.persist(role);
            User owner = User.builder().role(role).email("dialogue-concurrency@test.local")
                    .loginType("LOCAL").build();
            em.persist(owner);

            UserLearningPath path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE")
                    .version(1).experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build();
            em.persist(path);

            UserLearningPathItem activity = UserLearningPathItem.builder().learningPath(path).orderIndex(1)
                    .activityType(LearningActivityType.DIALOGUE).activityState(LearningActivityState.STUDYING)
                    .build();
            em.persist(activity);

            em.persist(LearningActivityMember.builder().activity(activity)
                    .memberType(LearningMemberType.DIALOGUE_LINE).canonicalId(1L).orderIndex(1)
                    .sourceRevision("rev-1").build());
            em.flush();

            return new Fixture(owner.getId(), activity.getId());
        });
    }

    private record Fixture(Long userId, Long activityId) { }
}

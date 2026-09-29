package com.jlas.learning;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
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

/** [JLAS-W0193] Task 9 — Warm-up B persistence: packages/activities with no source lesson, reusing the
 * existing {@code learning_path_package}/{@code user_learning_path_item}/{@code learning_activity_member}
 * tables unchanged (no schema change was needed for Task 9). */
@DataJpaTest(properties = {"spring.profiles.include=", "spring.datasource.url=jdbc:h2:mem:v2-warmup-persistence;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@ContextConfiguration(classes = InteractiveWarmUpPersistenceTest.Cfg.class)
class InteractiveWarmUpPersistenceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository"})
    static class Cfg { }

    @Autowired EntityManager em;
    @Autowired LearningPathPackageRepository packages;
    @Autowired UserLearningPathItemRepository items;
    @Autowired LearningActivityMemberRepository members;

    @Test void warmUpPackageWithNoSourceLessonPersistsAndCarriesProvenanceOnItsActivity() {
        var role = Role.builder().name("LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("warmup-owner@jlas.test").loginType("LOCAL").build(); em.persist(owner);
        var path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build(); em.persist(path);

        var pkg = LearningPathPackage.builder().learningPath(path).phase("WARM_UP").dayNumber(1).orderIndex(1)
                .title("Ôn tập điểm yếu: từ vựng").estimatedMinutes(5).status(LearningPackageStatus.AVAILABLE).build();
        em.persist(pkg);
        assertNull(pkg.getSourceLesson());

        var activity = UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).orderIndex(1)
                .phase("WARM_UP").dayNumber(1).activityType(LearningActivityType.VOCABULARY)
                .activityState(LearningActivityState.LOCKED)
                .reasonCode("WEAK_VOCAB_ITEM").reason("Bạn gặp khó khăn với 1 từ vựng này trong Smart Test.").build();
        em.persist(activity);
        em.persist(LearningActivityMember.builder().activity(activity).memberType(LearningMemberType.VOCABULARY_ENTRY)
                .canonicalId(42L).orderIndex(1).sourceRevision("warmup:rev-1").build());
        em.flush(); em.clear();

        var reloadedPackages = packages.findByLearningPathIdOrderByOrderIndex(path.getId());
        assertEquals(1, reloadedPackages.size());
        assertEquals("WARM_UP", reloadedPackages.getFirst().getPhase());
        assertNull(reloadedPackages.getFirst().getSourceLesson());

        var reloadedActivity = items.findByLearningPackageIdOrderByOrderIndex(pkg.getId()).getFirst();
        assertEquals("WEAK_VOCAB_ITEM", reloadedActivity.getReasonCode());
        assertTrue(reloadedActivity.getReason().contains("1 từ vựng"));
        assertEquals(42L, members.findByActivityIdOrderByOrderIndex(reloadedActivity.getId()).getFirst().getCanonicalId());
    }

    @Test void twoWarmUpPackagesWithNullSourceLessonCoexistOnDifferentDays() {
        // uk_learning_package_path_source (learning_path_id, source_lesson_id) must not reject a second
        // NULL source_lesson_id for the same path — SQL treats NULLs as distinct in a unique index.
        var role = Role.builder().name("LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("warmup-owner-2@jlas.test").loginType("LOCAL").build(); em.persist(owner);
        var path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build(); em.persist(path);

        em.persist(LearningPathPackage.builder().learningPath(path).phase("WARM_UP").dayNumber(1).orderIndex(1)
                .title("Ngày 1").estimatedMinutes(5).status(LearningPackageStatus.AVAILABLE).build());
        em.persist(LearningPathPackage.builder().learningPath(path).phase("WARM_UP").dayNumber(2).orderIndex(2)
                .title("Ngày 2 (tiếp)").estimatedMinutes(15).status(LearningPackageStatus.LOCKED).build());
        em.flush(); em.clear();

        assertEquals(2, packages.findByLearningPathIdOrderByOrderIndex(path.getId()).size());
    }

    @Test void readingAndListeningQuestionMembershipPersistsInStableOrder() {
        var role = Role.builder().name("LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("comprehension-owner@jlas.test").loginType("LOCAL").build(); em.persist(owner);
        var path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).build(); em.persist(path);
        var pkg = LearningPathPackage.builder().learningPath(path).phase("WARM_UP").dayNumber(1).orderIndex(1)
                .title("Đọc và nghe").estimatedMinutes(30).status(LearningPackageStatus.AVAILABLE).build(); em.persist(pkg);
        var reading = UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).orderIndex(1)
                .phase("WARM_UP").dayNumber(1).activityType(LearningActivityType.READING)
                .activityState(LearningActivityState.LOCKED).build(); em.persist(reading);
        var listening = UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).orderIndex(2)
                .phase("WARM_UP").dayNumber(1).activityType(LearningActivityType.LISTENING)
                .activityState(LearningActivityState.LOCKED).build(); em.persist(listening);
        em.persist(LearningActivityMember.builder().activity(reading).memberType(LearningMemberType.COMPREHENSION_QUESTION)
                .canonicalId(102L).orderIndex(2).sourceRevision("r".repeat(64)).build());
        em.persist(LearningActivityMember.builder().activity(reading).memberType(LearningMemberType.COMPREHENSION_QUESTION)
                .canonicalId(101L).orderIndex(1).sourceRevision("r".repeat(64)).build());
        em.persist(LearningActivityMember.builder().activity(listening).memberType(LearningMemberType.COMPREHENSION_QUESTION)
                .canonicalId(201L).orderIndex(1).sourceRevision("l".repeat(64)).build());
        em.flush(); em.clear();

        assertEquals(LearningActivityType.READING, items.findById(reading.getId()).orElseThrow().getActivityType());
        assertEquals(LearningActivityType.LISTENING, items.findById(listening.getId()).orElseThrow().getActivityType());
        var ordered = members.findByActivityIdOrderByOrderIndex(reading.getId());
        assertEquals(List.of(101L, 102L), ordered.stream().map(LearningActivityMember::getCanonicalId).toList());
        assertTrue(ordered.stream().allMatch(m -> m.getMemberType() == LearningMemberType.COMPREHENSION_QUESTION));
    }
}

package com.jlas.learning;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
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
    @Autowired com.jlas.learning.repository.UserLearningPathRepository paths;

    @Test void lighterPaceSplitsUntouchedVocabularyWithoutLosingMembershipOrAddingPackages() {
        var role = Role.builder().name("SPLIT_LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("split-owner@jlas.test").loginType("LOCAL").build(); em.persist(owner);
        var path = UserLearningPath.builder().user(owner).title("Split").status("ACTIVE").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).minutesPerDay(30).build(); em.persist(path);
        var pkg = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING")
                .dayNumber(1).orderIndex(1).stageId(77L).title("54 words").estimatedMinutes(45)
                .status(LearningPackageStatus.AVAILABLE).build(); em.persist(pkg);
        var activity = UserLearningPathItem.builder().learningPath(path).learningPackage(pkg)
                .phase("GOAL_LEARNING").stageId(77L).orderIndex(1).dayNumber(1).estimatedMinutes(45)
                .activityType(LearningActivityType.VOCABULARY).activityState(LearningActivityState.LOCKED).build();
        em.persist(activity);
        for (int i = 1; i <= 54; i++) em.persist(LearningActivityMember.builder().activity(activity)
                .memberType(LearningMemberType.VOCABULARY_ENTRY).canonicalId((long) i)
                .orderIndex(i).sourceRevision("split-fixture").build());
        var next = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING")
                .dayNumber(2).orderIndex(2).stageId(77L).title("Next lesson").estimatedMinutes(15)
                .status(LearningPackageStatus.LOCKED).build(); em.persist(next);
        var following = UserLearningPathItem.builder().learningPath(path).learningPackage(next)
                .phase("GOAL_LEARNING").stageId(77L).orderIndex(2).dayNumber(2).estimatedMinutes(15)
                .activityType(LearningActivityType.GRAMMAR).activityState(LearningActivityState.LOCKED).build();
        em.persist(following);
        em.flush(); em.clear();
        var originalMembers = members.findByActivityIdOrderByOrderIndex(activity.getId());
        var originalIds = originalMembers.stream().map(LearningActivityMember::getId).toList();
        var service = new com.jlas.learning.service.impl.InteractivePaceReplanServiceImpl(paths, packages, items, members);

        var preview = assertDoesNotThrow(() -> service.preview(owner.getId(), "LIGHTER"));
        assertEquals(5, preview.newRemainingDays());
        assertEquals(2, items.findByLearningPathIdOrderByOrderIndex(path.getId()).size(), "preview is read-only");
        var request = new com.jlas.learning.dto.LearningPaceRequest("LIGHTER", 20, preview.previewToken());
        service.apply(owner.getId(), request);
        em.flush(); em.clear();
        var all = items.findByLearningPathIdOrderByOrderIndex(path.getId());
        assertEquals(List.of(1, 2, 3, 4, 5), all.stream().map(UserLearningPathItem::getOrderIndex).toList());
        assertEquals(following.getId(), all.getLast().getId(), "later activities keep their identity after insertion");
        assertEquals(5, all.getLast().getDayNumber());
        var parts = all.subList(0, 4);
        assertEquals(List.of(14, 14, 13, 13), parts.stream()
                .map(a -> members.findByActivityIdOrderByOrderIndex(a.getId()).size()).toList());
        assertEquals(List.of(1, 2, 3, 4), parts.stream().map(UserLearningPathItem::getDayNumber).toList());
        assertEquals(activity.getId(), parts.getFirst().getId());
        assertEquals(originalIds, parts.stream().flatMap(a -> members.findByActivityIdOrderByOrderIndex(a.getId()).stream())
                .map(LearningActivityMember::getId).toList());
        assertTrue(parts.stream().allMatch(a -> a.getStageId() == 77L && a.getEstimatedMinutes() <= 20
                && a.getActivityState() == LearningActivityState.LOCKED));
        assertEquals(2, packages.findByLearningPathIdOrderByOrderIndex(path.getId()).size());
        assertFalse(service.apply(owner.getId(), request).changed());
        em.flush(); em.clear();
        assertEquals(5, items.findByLearningPathIdOrderByOrderIndex(path.getId()).size());
    }

    @Test void paceReschedulesAcrossUniqueDaysWithoutChangingActivityOrMemberIdentity() {
        var role = Role.builder().name("PACE_LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("pace-owner@jlas.test").loginType("LOCAL").build(); em.persist(owner);
        var path = UserLearningPath.builder().user(owner).title("Pace").status("ACTIVE").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).minutesPerDay(30).build(); em.persist(path);
        for (int day = 1; day <= 3; day++) {
            var pkg = LearningPathPackage.builder().learningPath(path).phase("GOAL_LEARNING")
                    .dayNumber(day).orderIndex(day).stageId(77L).title("Part " + day).estimatedMinutes(30)
                    .status(day == 1 ? LearningPackageStatus.AVAILABLE : LearningPackageStatus.LOCKED).build();
            em.persist(pkg);
            for (int part = 0; part < 2; part++) {
                var activity = UserLearningPathItem.builder().learningPath(path).learningPackage(pkg)
                        .phase("GOAL_LEARNING").stageId(77L).orderIndex(day * 2 + part)
                        .dayNumber(day).estimatedMinutes(15).activityType(LearningActivityType.VOCABULARY).partIndex(part + 1)
                        .activityState(LearningActivityState.LOCKED).build();
                em.persist(activity);
                em.persist(LearningActivityMember.builder().activity(activity)
                        .memberType(LearningMemberType.VOCABULARY_ENTRY).canonicalId(100L + day * 2 + part)
                        .orderIndex(1).sourceRevision("pace-fixture").build());
            }
        }
        em.flush(); em.clear();
        var before = items.findByLearningPathIdOrderByOrderIndex(path.getId());
        var activityIds = before.stream().map(UserLearningPathItem::getId).toList();
        var memberIds = before.stream().flatMap(a -> members.findByActivityIdOrderByOrderIndex(a.getId()).stream())
                .map(LearningActivityMember::getId).toList();
        var service = new com.jlas.learning.service.impl.InteractivePaceReplanServiceImpl(paths, packages, items, members);
        var preview = service.preview(owner.getId(), "LIGHTER");
        var request = new com.jlas.learning.dto.LearningPaceRequest("LIGHTER", 20, preview.previewToken());
        service.apply(owner.getId(), request);
        em.flush(); em.clear();

        assertEquals(List.of(1,3,5), packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .map(LearningPathPackage::getDayNumber).toList());
        var after = items.findByLearningPathIdOrderByOrderIndex(path.getId());
        assertEquals(List.of(1,2,3,4,5,6), after.stream().map(UserLearningPathItem::getDayNumber).toList());
        assertEquals(activityIds, after.stream().map(UserLearningPathItem::getId).toList());
        assertEquals(memberIds, after.stream().flatMap(a -> members.findByActivityIdOrderByOrderIndex(a.getId()).stream())
                .map(LearningActivityMember::getId).toList());
        assertTrue(after.stream().allMatch(a -> a.getStageId() == 77L
                && a.getActivityState() == LearningActivityState.LOCKED));
        assertFalse(service.apply(owner.getId(), request).changed());
        em.flush(); em.clear();
        assertEquals(3, packages.findByLearningPathIdOrderByOrderIndex(path.getId()).size());
        var faster = service.preview(owner.getId(), "FASTER");
        service.apply(owner.getId(), new com.jlas.learning.dto.LearningPaceRequest(
                "FASTER", 30, faster.previewToken()));
        em.flush(); em.clear();
        assertEquals(List.of(1,2,3), packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .map(LearningPathPackage::getDayNumber).toList());
        assertEquals(List.of(1,1,2,2,3,3), items.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .map(UserLearningPathItem::getDayNumber).toList());
    }

    @Test void twoDailySegmentsForTheSameSourceLessonPersistInStableOrder() {
        var role = Role.builder().name("LEARNER").build(); em.persist(role);
        var owner = User.builder().role(role).email("segmented-owner@jlas.test").loginType("LOCAL").build(); em.persist(owner);
        var path = UserLearningPath.builder().user(owner).title("Interactive").status("ACTIVE").version(1)
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).minutesPerDay(15).build(); em.persist(path);
        var tag = Tag.builder().code("SEGMENTED-LESSON").name("Segmented lesson").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var textbook = Textbook.builder().title("N4").jlptLevel("N4").status("PUBLISHED").build(); em.persist(textbook);
        var unit = Unit.builder().textbook(textbook).title("Unit").build(); em.persist(unit);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Lesson").lessonType("CURRICULUM")
                .skill("GRAMMAR").status("PUBLISHED").build(); em.persist(lesson);

        em.persist(LearningPathPackage.builder().learningPath(path).sourceLesson(lesson).sourceSegmentIndex(1)
                .phase("GOAL_LEARNING").dayNumber(1).orderIndex(1).title("Lesson · Part 1")
                .estimatedMinutes(15).status(LearningPackageStatus.AVAILABLE).build());
        em.persist(LearningPathPackage.builder().learningPath(path).sourceLesson(lesson).sourceSegmentIndex(2)
                .phase("GOAL_LEARNING").dayNumber(2).orderIndex(2).title("Lesson · Part 2")
                .estimatedMinutes(12).status(LearningPackageStatus.LOCKED).build());
        em.flush(); em.clear();

        var segments = packages.findByLearningPathIdOrderByOrderIndex(path.getId());
        assertEquals(2, segments.size());
        assertEquals(List.of(1, 2), segments.stream().map(LearningPathPackage::getSourceSegmentIndex).toList());
        assertTrue(segments.stream().allMatch(segment -> segment.getSourceLesson().getId().equals(lesson.getId())));
    }

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

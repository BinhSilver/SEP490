package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.ReplanRequest;
import com.jlas.learning.entity.LessonProgress;
import com.jlas.learning.entity.UserKnowledgeWeakness;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.impl.JpaLearningCatalogSource;
import com.jlas.learning.service.impl.LearningReplanService;
import com.jlas.learning.service.impl.LearningScheduleServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:learning-replan-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LearningReplanServiceTest.ReplanTestConfiguration.class)
class LearningReplanServiceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.dictionary.repository", "com.jlas.assessment.repository"})
    @Import({JpaLearningCatalogSource.class, LearningScheduleServiceImpl.class,
            LearningMapper.class, LearningReplanService.class})
    static class ReplanTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LearningReplanService replanService;
    @Autowired UserLearningPathRepository paths;

    @Test
    void remediateAddsReinforcementAndBumpsVersion() {
        var fixture = seed();

        var preview = replanService.preview(fixture.user.getId(), LearningReplanService.MODE_REMEDIATE);
        assertTrue(preview.changesApply());
        assertEquals(1, preview.remediationAdded());
        assertTrue(preview.addedSummaries().get(0).contains("ngữ pháp"));

        var applied = replanService.apply(fixture.user.getId(),
                new ReplanRequest(LearningReplanService.MODE_REMEDIATE, true));
        assertEquals(2, applied.version());
        assertEquals(3, applied.totalLessons());

        var warmUp = applied.phases().get(0).items();
        assertEquals(1, warmUp.size());
        assertTrue(warmUp.get(0).completed(), "warm-up item hoàn thành phải được giữ nguyên");

        boolean hasRemediation = applied.phases().stream()
                .flatMap(group -> group.items().stream())
                .anyMatch(item -> "REMEDIATION_GRAMMAR_POINT".equals(item.reasonCode()));
        assertTrue(hasRemediation);

        assertEquals(2L, em.createQuery("select count(p) from UserLearningPath p").getSingleResult());
        assertEquals(1L, em.createQuery(
                "select count(p) from UserLearningPath p where p.status = 'ACTIVE'").getSingleResult());
    }

    @Test
    void remediateIsIdempotentWhenNoOpenWeakness() {
        var fixture = seed();
        var weakness = em.find(UserKnowledgeWeakness.class, fixture.weakness.getId());
        weakness.setStatus("MASTERED");
        em.flush();
        em.clear();

        var preview = replanService.preview(fixture.user.getId(), LearningReplanService.MODE_REMEDIATE);
        assertFalse(preview.changesApply());

        var applied = replanService.apply(fixture.user.getId(),
                new ReplanRequest(LearningReplanService.MODE_REMEDIATE, true));
        assertEquals(1, applied.version());
        assertEquals(1L, em.createQuery("select count(p) from UserLearningPath p").getSingleResult());
    }

    @Test
    void previewDoesNotPersist() {
        var fixture = seed();
        replanService.preview(fixture.user.getId(), LearningReplanService.MODE_REMEDIATE);
        assertEquals(1L, em.createQuery("select count(p) from UserLearningPath p").getSingleResult());
    }

    @Test
    void applyRequiresConfirmation() {
        var fixture = seed();
        assertError(ErrorCode.VALIDATION_FAILED, () -> replanService.apply(fixture.user.getId(),
                new ReplanRequest(LearningReplanService.MODE_REMEDIATE, false)));
    }

    @Test
    void notFoundForUnknownUser() {
        assertError(ErrorCode.NOT_FOUND, () -> replanService.preview(999L, LearningReplanService.MODE_REMEDIATE));
    }

    @Test
    void accelerateNoOpWithoutReviewItems() {
        var fixture = seed();
        var preview = replanService.preview(fixture.user.getId(), LearningReplanService.MODE_ACCELERATE);
        assertFalse(preview.changesApply());
        assertEquals(0, preview.masteredRemoved());
    }

    @Test
    void replanKeepsSingleActiveGenerationLookup() {
        // [JLAS-W0122] Regression: replan giữ nguyên generation identity qua các version, nên cùng
        // (user, attempt, revision, policy) tồn tại 2 hàng (SUPERSEDED + ACTIVE). Lookup idempotency
        // phải lọc theo ACTIVE để trả đúng 1 hàng, không ném NonUniqueResultException.
        var fixture = seed();
        replanService.apply(fixture.user.getId(),
                new ReplanRequest(LearningReplanService.MODE_REMEDIATE, true));

        var active = paths.findByUserIdAndAssessmentAttemptIdAndAssessmentRevisionAndPolicyVersionAndStatus(
                fixture.user.getId(), 91L, "rev-1", "v1", "ACTIVE");
        assertTrue(active.isPresent());
        assertEquals(2, active.get().getVersion());

        long generationRows = em.createQuery(
                "select count(p) from UserLearningPath p where p.user.id = :uid "
                        + "and p.assessmentAttemptId = 91 and p.assessmentRevision = 'rev-1' "
                        + "and p.policyVersion = 'v1'", Long.class)
                .setParameter("uid", fixture.user.getId()).getSingleResult();
        assertEquals(2L, generationRows, "replan phải giữ 2 hàng cùng generation identity");
    }

    private Fixture seed() {
        var user = seedUser();
        var tag = seedTag("PARTICLE");
        var book = seedTextbook("N4", "Goal book");
        var unit = seedUnit(book);
        var warmLesson = seedLesson(unit, tag, "Warm lesson", 15);
        var goalLesson = seedLesson(unit, tag, "Goal lesson", 20);
        var point = GrammarPoint.builder().lesson(goalLesson).pattern("～てください").meaning("vui lòng hãy làm")
                .orderIndex(1).build();
        em.persist(point);

        var path = UserLearningPath.builder()
                .user(user).title("JLPT N4").goal("JLPT").targetLevel("N4").currentLevel("N4")
                .assessmentAttemptId(91L).assessmentRevision("rev-1").policyVersion("v1")
                .minutesPerDay(30).status("ACTIVE").version(1).build();
        em.persist(path);

        em.persist(UserLearningPathItem.builder()
                .learningPath(path).lesson(warmLesson).orderIndex(1).phase("WARM_UP")
                .reasonCode("WEAK_TAG").reason("review").dayNumber(1).estimatedMinutes(15).build());
        em.persist(UserLearningPathItem.builder()
                .learningPath(path).lesson(goalLesson).orderIndex(2).phase("GOAL_LEARNING")
                .reasonCode("GOAL_TARGET").reason("study").dayNumber(2).estimatedMinutes(20).build());

        em.persist(LessonProgress.builder().user(user).lesson(warmLesson).status("COMPLETED").build());

        var weakness = UserKnowledgeWeakness.builder()
                .user(user).canonicalItem("GP:" + point.getId()).skill("GRAMMAR").jlptLevel("N4")
                .severity("WEAK").status("OPEN").source("SMART_TEST:91").build();
        em.persist(weakness);

        em.flush();
        em.clear();
        return new Fixture(user, weakness);
    }

    private int userSeq = 0;

    private User seedUser() {
        var role = em.createQuery("select r from Role r where r.name = 'LEARNER'", Role.class)
                .getResultStream().findFirst()
                .orElseGet(() -> {
                    var created = Role.builder().name("LEARNER").build();
                    em.persist(created);
                    return created;
                });
        var user = User.builder().role(role)
                .email("replan" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        em.flush();
        return user;
    }

    private Textbook seedTextbook(String level, String title) {
        var book = Textbook.builder().title(title).status("PUBLISHED").jlptLevel(level).build();
        em.persist(book);
        return book;
    }

    private Unit seedUnit(Textbook book) {
        var unit = Unit.builder().textbook(book).title("Unit").orderIndex(1).build();
        em.persist(unit);
        return unit;
    }

    private Tag seedTag(String code) {
        var tag = Tag.builder().code(code).name("Tag " + code).tagType("KNOWLEDGE").build();
        em.persist(tag);
        return tag;
    }

    private Lesson seedLesson(Unit unit, Tag tag, String title, int minutes) {
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title(title)
                .status("PUBLISHED").estimatedMinutes(minutes).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }

    private void assertError(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ApiException.class, action).getErrorCode());
    }

    private record Fixture(User user, UserKnowledgeWeakness weakness) { }
}

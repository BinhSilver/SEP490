package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.dto.LearningCatalogScope;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.WeaknessEvidence;
import com.jlas.learning.entity.LessonProgress;
import com.jlas.learning.enums.LearningPhase;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.LearningService;
import com.jlas.learning.service.impl.JpaLearningCatalogSource;
import com.jlas.learning.service.impl.LearningResourceSelectionServiceImpl;
import com.jlas.learning.service.impl.LearningScheduleServiceImpl;
import com.jlas.learning.service.impl.LearningServiceImpl;
import com.jlas.learning.service.impl.WeakTagPolicy;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:learning-path-api-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "jlas.learning.selection.minimum-assessed-questions=3",
        "jlas.learning.selection.wrong-ratio-threshold=0.5"
})
@ContextConfiguration(classes = LearningPathApiTest.ApiTestConfiguration.class)
class LearningPathApiTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.dictionary.repository", "com.jlas.assessment.repository"})
    @Import({JpaLearningCatalogSource.class, WeakTagPolicy.class, LearningResourceSelectionServiceImpl.class,
            LearningScheduleServiceImpl.class, LearningMapper.class, LearningServiceImpl.class})
    static class ApiTestConfiguration {
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
    @Autowired FakeProfileSource profileSource;
    @Autowired FakeScopeSource scopeSource;

    @Test
    void createsAndReadsTwoPhasesWithDailySchedule() {
        var fixture = seedFixture();
        profileSource.snapshot = profile(fixture.user.getId(), 91L, "rev-1");
        scopeSource.scope = scope(fixture.warmBook, fixture.goalBook);

        var created = service.create(fixture.user.getId(), 91L);
        assertTrue(created.created());
        var response = created.path();
        assertEquals(1, response.version());
        assertEquals(List.of(LearningPhase.WARM_UP, LearningPhase.GOAL_LEARNING),
                response.phases().stream().map(group -> group.phase()).toList());
        assertEquals(fixture.warmLesson.getId(), response.phases().get(0).items().get(0).lessonId());
        assertEquals(fixture.goalLesson.getId(), response.phases().get(1).items().get(0).lessonId());
        assertEquals("WEAK_TAG", response.phases().get(0).items().get(0).reasonCode());
        assertEquals("GOAL_TARGET", response.phases().get(1).items().get(0).reasonCode());
        assertEquals(1, response.phases().get(0).items().get(0).dayNumber());
        assertEquals(2, response.phases().get(1).items().get(0).dayNumber());
        assertEquals(2, response.totalLessons());
        assertEquals(35, response.totalMinutes());
        assertEquals(2, response.totalDays());

        var reloaded = service.current(fixture.user.getId());
        assertEquals(created.path().id(), reloaded.id());
        assertEquals(2, reloaded.phases().size());
        assertEquals(35, reloaded.totalMinutes());
    }

    @Test
    void duplicateSameGenerationIdentityReusesExistingPathWithoutNewVersion() {
        var fixture = seedFixture();
        profileSource.snapshot = profile(fixture.user.getId(), 91L, "rev-1");
        scopeSource.scope = scope(fixture.warmBook, fixture.goalBook);

        var first = service.create(fixture.user.getId(), 91L);
        var second = service.create(fixture.user.getId(), 91L);
        assertFalse(second.created());
        assertEquals(first.path().id(), second.path().id());
        assertEquals(1, second.path().version());
        assertEquals(1L, em.createQuery("select count(p) from UserLearningPath p").getSingleResult());
    }

    @Test
    void newAttemptSupersedesPreviousActivePath() {
        var fixture = seedFixture();
        profileSource.snapshot = profile(fixture.user.getId(), 91L, "rev-1");
        scopeSource.scope = scope(fixture.warmBook, fixture.goalBook);
        service.create(fixture.user.getId(), 91L);

        profileSource.snapshot = profile(fixture.user.getId(), 92L, "rev-2");
        var second = service.create(fixture.user.getId(), 92L);
        assertTrue(second.created());
        assertEquals(2, second.path().version());

        var active = service.current(fixture.user.getId());
        assertEquals(2, active.version());
        assertEquals(second.path().id(), active.id());
    }

    @Test
    void rejectsProfileOwnedByAnotherUser() {
        var fixture = seedFixture();
        profileSource.snapshot = new LearningProfileSnapshot(999L, 91L, 10L, "rev-1", true, true,
                "N4", "N4", "JLPT", 30, List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 5, 4)));
        scopeSource.scope = scope(fixture.warmBook, fixture.goalBook);

        assertError(ErrorCode.FORBIDDEN, () -> service.create(fixture.user.getId(), 91L));
    }

    @Test
    void rejectsNonFinalizedAssessment() {
        var fixture = seedFixture();
        profileSource.snapshot = new LearningProfileSnapshot(fixture.user.getId(), 91L, 10L, "rev-1", true, false,
                "N4", "N4", "JLPT", 30, List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 5, 4)));
        scopeSource.scope = scope(fixture.warmBook, fixture.goalBook);

        assertError(ErrorCode.CONFLICT, () -> service.create(fixture.user.getId(), 91L));
    }

    @Test
    void currentDoesNotLeakAnotherUsersPath() {
        var owner = seedFixture();
        profileSource.snapshot = profile(owner.user.getId(), 91L, "rev-1");
        scopeSource.scope = scope(owner.warmBook, owner.goalBook);
        assertTrue(service.create(owner.user.getId(), 91L).created());

        var other = seedUser();
        assertError(ErrorCode.NOT_FOUND, () -> service.current(other.getId()));
    }

    @Test
    void reloadKeepsStoredScheduleWhenCatalogDurationChanges() {
        var fixture = seedFixture();
        profileSource.snapshot = profile(fixture.user.getId(), 91L, "rev-1");
        scopeSource.scope = scope(fixture.warmBook, fixture.goalBook);
        var created = service.create(fixture.user.getId(), 91L);
        assertEquals(15, created.path().phases().get(0).items().get(0).estimatedMinutes());

        fixture.warmLesson.setEstimatedMinutes(99);
        em.flush();
        em.clear();

        var reloaded = service.current(fixture.user.getId());
        assertEquals(15, reloaded.phases().get(0).items().get(0).estimatedMinutes());
    }

    @Test
    void newVersionDoesNotResetLessonProgress() {
        var fixture = seedFixture();
        profileSource.snapshot = profile(fixture.user.getId(), 91L, "rev-1");
        scopeSource.scope = scope(fixture.warmBook, fixture.goalBook);
        service.create(fixture.user.getId(), 91L);

        em.persist(LessonProgress.builder()
                .user(fixture.user)
                .lesson(fixture.warmLesson)
                .status("COMPLETED")
                .build());
        em.flush();

        profileSource.snapshot = profile(fixture.user.getId(), 92L, "rev-2");
        service.create(fixture.user.getId(), 92L);

        assertEquals(1L, em.createQuery(
                        "select count(p) from LessonProgress p where p.user.id = :uid and p.status = 'COMPLETED'")
                .setParameter("uid", fixture.user.getId()).getSingleResult());

        var active = service.current(fixture.user.getId());
        assertTrue(active.phases().get(0).items().get(0).completed());
    }

    private Fixture seedFixture() {
        var user = seedUser();
        var warmBook = seedTextbook("N5", "Warm-up book");
        var warmUnit = seedUnit(warmBook);
        var warmLesson = seedLesson(warmUnit, seedTag("PARTICLE"), 15);
        var goalBook = seedTextbook("N4", "Goal book");
        var goalUnit = seedUnit(goalBook);
        var goalLesson = seedLesson(goalUnit, seedTag("TARGET"), 20);
        em.flush();
        em.clear();
        return new Fixture(user, warmBook, goalBook, warmLesson, goalLesson);
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
                .email("learner" + (++userSeq) + "@test.local")
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

    private Lesson seedLesson(Unit unit, Tag tag, int minutes) {
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Lesson " + tag.getCode())
                .status("PUBLISHED").estimatedMinutes(minutes).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }

    private LearningProfileSnapshot profile(Long userId, Long attemptId, String revision) {
        return new LearningProfileSnapshot(userId, attemptId, 10L, revision, true, true,
                "N4", "N4", "JLPT", 30, List.of(WeaknessEvidence.grammar(1L, "PARTICLE", 1L, 5, 4)));
    }

    private LearningCatalogScope scope(Textbook warmBook, Textbook goalBook) {
        return new LearningCatalogScope(List.of(warmBook.getId()), List.of(goalBook.getId()));
    }

    private void assertError(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ApiException.class, action).getErrorCode());
    }

    private record Fixture(User user, Textbook warmBook, Textbook goalBook, Lesson warmLesson, Lesson goalLesson) { }
}

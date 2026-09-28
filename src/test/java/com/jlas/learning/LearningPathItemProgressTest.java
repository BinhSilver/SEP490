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
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.LearningService;
import com.jlas.learning.service.impl.JpaLearningCatalogSource;
import com.jlas.learning.service.impl.LearningResourceSelectionServiceImpl;
import com.jlas.learning.service.impl.LearningScheduleServiceImpl;
import com.jlas.learning.service.impl.LearningServiceImpl;
import com.jlas.learning.service.impl.LearningClock;
import com.jlas.learning.service.impl.WeakTagPolicy;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * [JLAS-W0108] P2 — study progress độc lập source lesson: hai item dùng chung một bài,
 * hoàn thành item này không hoàn thành item kia (cũng không hoàn thành cả "sách").
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:learning-path-item-progress-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "jlas.learning.selection.minimum-assessed-questions=3",
        "jlas.learning.selection.wrong-ratio-threshold=0.5"
})
@ContextConfiguration(classes = LearningPathItemProgressTest.ItemProgressConfiguration.class)
class LearningPathItemProgressTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.dictionary.repository", "com.jlas.assessment.repository"})
    @Import({JpaLearningCatalogSource.class, WeakTagPolicy.class, LearningResourceSelectionServiceImpl.class,
            LearningScheduleServiceImpl.class, LearningMapper.class, LearningServiceImpl.class, LearningClock.class})
    static class ItemProgressConfiguration {
        @Bean FakeProfileSource profileSource() {
            return new FakeProfileSource();
        }

        @Bean FakeScopeSource scopeSource() {
            return new FakeScopeSource();
        }
    }

    static class FakeProfileSource implements LearningProfileSource {
        @Override
        public com.jlas.learning.dto.LearningProfileSnapshot loadForUser(Long userId, Long assessmentAttemptId) {
            return null;
        }
    }

    static class FakeScopeSource implements LearningScopeSource {
        @Override
        public com.jlas.learning.dto.LearningCatalogScope resolve(Long userId,
                com.jlas.learning.dto.LearningProfileSnapshot profile) {
            return null;
        }
    }

    @Autowired EntityManager em;
    @Autowired LearningService service;

    @Test
    void completingOneItemDoesNotCompleteSiblingItemSharingTheSameLesson() {
        var fixture = seedSharedLessonPath();

        service.completeItem(fixture.user.getId(), fixture.itemB.getId());

        var response = service.current(fixture.user.getId());
        var items = response.phases().get(0).items();
        var a = items.stream().filter(item -> item.itemId().equals(fixture.itemA.getId())).findFirst().orElseThrow();
        var b = items.stream().filter(item -> item.itemId().equals(fixture.itemB.getId())).findFirst().orElseThrow();

        assertTrue(b.completed(), "Item B phải hoàn thành sau khi gọi completeItem(B)");
        assertFalse(a.completed(), "Item A dùng chung source lesson không được hoàn thành theo");
        assertEquals(1, response.completedLessons(), "Chỉ đúng một activity được ghi nhận hoàn thành");
    }

    @Test
    void startingAnItemMarksItInProgressAndExposedAsStarted() {
        var fixture = seedSharedLessonPath();

        service.startItem(fixture.user.getId(), fixture.itemB.getId());

        var updated = em.find(UserLearningPathItem.class, fixture.itemB.getId());
        assertEquals("IN_PROGRESS", updated.getStudyStatus());
        var response = service.current(fixture.user.getId());
        var items = response.phases().get(0).items();
        var b = items.stream().filter(item -> item.itemId().equals(fixture.itemB.getId())).findFirst().orElseThrow();
        var a = items.stream().filter(item -> item.itemId().equals(fixture.itemA.getId())).findFirst().orElseThrow();
        assertTrue(b.started(), "Item đã start phải báo started=true");
        assertFalse(b.completed(), "Start không được đánh dấu hoàn thành");
        assertFalse(a.started(), "Item chưa động tới vẫn phải là started=false");
    }

    @Test
    void startingAnItemIsIdempotentAndNeverDowngradesFromStudied() {
        var fixture = seedSharedLessonPath();

        service.completeItem(fixture.user.getId(), fixture.itemB.getId());
        service.startItem(fixture.user.getId(), fixture.itemB.getId());

        var updated = em.find(UserLearningPathItem.class, fixture.itemB.getId());
        assertEquals("STUDIED", updated.getStudyStatus(),
                "Gọi start sau khi đã STUDIED không được lùi trạng thái về IN_PROGRESS");
    }

    @Test
    void reloadingAnInProgressItemDoesNotResetOrDuplicateProgress() {
        var fixture = seedSharedLessonPath();

        service.startItem(fixture.user.getId(), fixture.itemB.getId());
        service.startItem(fixture.user.getId(), fixture.itemB.getId()); // giả lập tải lại trang

        var updated = em.find(UserLearningPathItem.class, fixture.itemB.getId());
        assertEquals("IN_PROGRESS", updated.getStudyStatus());
        assertEquals(0, service.current(fixture.user.getId()).completedLessons());
    }

    @Test
    void cannotStartAnotherUsersItem() {
        var fixture = seedSharedLessonPath();
        var other = seedUser();

        ApiException ex = assertThrows(ApiException.class,
                () -> service.startItem(other.getId(), fixture.itemB.getId()));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode(),
                "Item của người khác phải trả NOT_FOUND (không lộ tồn tại)");
    }

    @Test
    void completingItemIsIdempotent() {
        var fixture = seedSharedLessonPath();

        service.completeItem(fixture.user.getId(), fixture.itemB.getId());
        service.completeItem(fixture.user.getId(), fixture.itemB.getId());

        var response = service.current(fixture.user.getId());
        assertEquals(1, response.completedLessons(), "Hoàn thành lại cùng item không cộng thêm tiến độ");
    }

    @Test
    void cannotCompleteAnotherUsersItem() {
        var fixture = seedSharedLessonPath();
        var other = seedUser();

        ApiException ex = assertThrows(ApiException.class,
                () -> service.completeItem(other.getId(), fixture.itemB.getId()));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode(),
                "Item của người khác phải trả NOT_FOUND (không lộ tồn tại)");
    }

    private Fixture seedSharedLessonPath() {
        var user = seedUser();
        var tag = Tag.builder().code("PARTICLE").name("Particle").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var book = Textbook.builder().title("Shared book").status("PUBLISHED").jlptLevel("N5").build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Unit").orderIndex(1).build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Shared lesson")
                .status("PUBLISHED").estimatedMinutes(15).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);

        var path = UserLearningPath.builder().user(user).title("Shared lesson path").status("ACTIVE").version(1).build();
        em.persist(path);

        var itemA = item(path, lesson, 1);
        var itemB = item(path, lesson, 2);
        em.persist(itemA);
        em.persist(itemB);
        em.flush();
        em.clear();
        return new Fixture(user, itemA, itemB);
    }

    private UserLearningPathItem item(UserLearningPath path, Lesson lesson, int order) {
        return UserLearningPathItem.builder()
                .learningPath(path)
                .lesson(lesson)
                .phase("GOAL_LEARNING")
                .reasonCode("GOAL_TARGET")
                .dayNumber(order)
                .orderIndex(order)
                .estimatedMinutes(15)
                .build();
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
                .email("item-progress" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        em.flush();
        return user;
    }

    private record Fixture(User user, UserLearningPathItem itemA, UserLearningPathItem itemB) { }
}

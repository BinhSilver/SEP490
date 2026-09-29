package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.GrammarPoint;
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
 * [JLAS-W0108] P2c — item có lesson_id NULL (activity trỏ grammar_point / vocabulary_entry thay vì
 * bài nguồn) vẫn lưu, load qua left join và map không NPE; completeItem vẫn chạy bình thường.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:learning-path-item-nullable-lesson-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "jlas.learning.selection.minimum-assessed-questions=3",
        "jlas.learning.selection.wrong-ratio-threshold=0.5"
})
@ContextConfiguration(classes = LearningPathItemNullableLessonTest.NullableLessonConfiguration.class)
class LearningPathItemNullableLessonTest {

    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = {"com.jlas.learning.repository", "com.jlas.auth.repository",
            "com.jlas.dictionary.repository", "com.jlas.assessment.repository"})
    @Import({JpaLearningCatalogSource.class, WeakTagPolicy.class, LearningResourceSelectionServiceImpl.class,
            LearningScheduleServiceImpl.class, LearningMapper.class, LearningServiceImpl.class, LearningClock.class})
    static class NullableLessonConfiguration {
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
    void grammarPointItemWithoutLessonPersistsAndMaps() {
        var fixture = seedGrammarPointItem();

        var response = service.current(fixture.user().getId());
        var item = response.phases().get(0).items().get(0);

        assertNull(item.lessonId(), "Item không có bài nguồn phải map lessonId = null");
        assertEquals(fixture.point().getId(), item.grammarPointId(),
                "Item trỏ đúng mẫu ngữ pháp đã chọn");
        assertEquals(fixture.point().getPattern(), item.title(),
                "Title phải là pattern của mẫu ngữ pháp (không cần lesson)");
    }

    @Test
    void completingLessonlessItemDoesNotThrow() {
        var fixture = seedGrammarPointItem();

        service.completeItem(fixture.user().getId(), fixture.item().getId());

        var response = service.current(fixture.user().getId());
        var item = response.phases().get(0).items().get(0);
        assertTrue(item.completed(), "Item không bài nguồn vẫn hoàn thành được (không NPE ở streak/credit)");
        assertEquals(1, response.completedLessons());
    }

    private Fixture seedGrammarPointItem() {
        var user = seedUser();
        var tag = Tag.builder().code("PARTICLE").name("Particle").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var book = Textbook.builder().title("Grammar book").status("PUBLISHED").jlptLevel("N5").build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Unit").orderIndex(1).build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Grammar lesson")
                .status("PUBLISHED").estimatedMinutes(15).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        var point = GrammarPoint.builder().lesson(lesson).pattern("～ことができる")
                .meaning("Có thể làm gì").example("Ví dụ").orderIndex(1).build();
        em.persist(point);

        var path = UserLearningPath.builder().user(user).title("Grammar-only path").status("ACTIVE").version(1).build();
        em.persist(path);

        // [JLAS-W0108] P2c — item trỏ đúng mẫu ngữ pháp, lesson_id để NULL.
        var item = UserLearningPathItem.builder()
                .learningPath(path)
                .lesson(null)
                .grammarPoint(point)
                .phase("WARM_UP")
                .reasonCode("WEAK_GRAMMAR_POINT")
                .dayNumber(1)
                .orderIndex(1)
                .estimatedMinutes(10)
                .build();
        em.persist(item);
        em.flush();
        em.clear();
        return new Fixture(user, item, point);
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
                .email("nullable-lesson" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        em.flush();
        return user;
    }

    private record Fixture(User user, UserLearningPathItem item, GrammarPoint point) { }
}

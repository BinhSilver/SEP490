package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.repository.LessonProgressRepository;
import com.jlas.learning.service.LearningProgressService;
import com.jlas.learning.service.impl.LearningProgressServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * QA scenarios for JLAS-W0121: a page reload re-reads the same persisted progress, and repeat
 * completion / resume stay idempotent while keeping the original timestamps.
 */
@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:progress-qa-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LessonProgressReloadAndRepeatTest.ProgressQaConfiguration.class)
class LessonProgressReloadAndRepeatTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.learning.repository")
    @Import(LearningProgressServiceImpl.class)
    static class ProgressQaConfiguration { }

    @Autowired EntityManager em;
    @Autowired LearningProgressService service;
    @Autowired LessonProgressRepository progressRepository;

    private int userSeq = 0;

    @Test
    void reloadAfterStartShowsInProgressWithOriginalStartedAt() {
        var user = seedUser();
        var lesson = seedLesson();
        em.flush();

        var started = service.start(user.getId(), lesson.getId());
        var reloaded = service.status(user.getId(), lesson.getId());

        assertEquals("IN_PROGRESS", reloaded.status());
        assertEquals(started.startedAt(), reloaded.startedAt());
        assertNull(reloaded.completedAt());
    }

    @Test
    void reloadAfterCompleteShowsCompletedWithCompletedAt() {
        var user = seedUser();
        var lesson = seedLesson();
        em.flush();

        var completed = service.complete(user.getId(), lesson.getId());
        var reloaded = service.status(user.getId(), lesson.getId());

        assertEquals("COMPLETED", reloaded.status());
        assertEquals(completed.completedAt(), reloaded.completedAt());
    }

    @Test
    void repeatCompletionKeepsOriginalCompletedAt() {
        var user = seedUser();
        var lesson = seedLesson();
        em.flush();

        var first = service.complete(user.getId(), lesson.getId());
        var second = service.complete(user.getId(), lesson.getId());

        assertEquals("COMPLETED", second.status());
        assertEquals(first.completedAt(), second.completedAt());
        assertEquals(1, progressRepository.findByUserId(user.getId()).size());
    }

    @Test
    void resumeKeepsOriginalStartedAt() {
        var user = seedUser();
        var lesson = seedLesson();
        em.flush();

        var first = service.start(user.getId(), lesson.getId());
        var resumed = service.start(user.getId(), lesson.getId());

        assertEquals("IN_PROGRESS", resumed.status());
        assertEquals(first.startedAt(), resumed.startedAt());
        assertEquals(1, progressRepository.findByUserId(user.getId()).size());
    }

    private User seedUser() {
        Role role = Role.builder().name("LEARNER").build();
        em.persist(role);
        User user = User.builder().role(role)
                .email("learner" + (++userSeq) + "@test.local")
                .loginType("LOCAL").build();
        em.persist(user);
        return user;
    }

    private Lesson seedLesson() {
        Textbook book = Textbook.builder().title("Minna").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(book);
        Unit unit = Unit.builder().textbook(book).title("Bài 1").orderIndex(1).build();
        em.persist(unit);
        Tag tag = Tag.builder().code("N4-PARTICLE").name("Trợ từ").tagType("KNOWLEDGE").build();
        em.persist(tag);
        Lesson lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Trợ từ cơ bản")
                .description("Học về trợ từ.").status("PUBLISHED").estimatedMinutes(10)
                .orderIndex(1).lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }
}

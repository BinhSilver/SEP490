package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.repository.LessonProgressRepository;
import com.jlas.learning.service.LearningProgressService;
import com.jlas.learning.service.impl.LearningProgressServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = {
        "spring.profiles.include=",
        "spring.datasource.url=jdbc:h2:mem:progress-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LearningProgressServiceTest.ProgressTestConfiguration.class)
class LearningProgressServiceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.learning.repository")
    @Import(LearningProgressServiceImpl.class)
    static class ProgressTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LearningProgressService service;
    @Autowired LessonProgressRepository progressRepository;

    private int userSeq = 0;

    @Test
    void statusDefaultsToNotStarted() {
        var user = seedUser();
        var lesson = seedLesson();
        em.flush();

        var result = service.status(user.getId(), lesson.getId());

        assertEquals(lesson.getId(), result.lessonId());
        assertEquals("NOT_STARTED", result.status());
        assertNull(result.startedAt());
        assertNull(result.completedAt());
    }

    @Test
    void startMovesNotStartedToInProgress() {
        var user = seedUser();
        var lesson = seedLesson();
        em.flush();

        var result = service.start(user.getId(), lesson.getId());

        assertEquals("IN_PROGRESS", result.status());
        assertNotNull(result.startedAt());
        assertNull(result.completedAt());
    }

    @Test
    void startIsIdempotent() {
        var user = seedUser();
        var lesson = seedLesson();
        em.flush();

        service.start(user.getId(), lesson.getId());
        var again = service.start(user.getId(), lesson.getId());

        assertEquals("IN_PROGRESS", again.status());
        assertEquals(1, progressRepository.findByUserId(user.getId()).size());
    }

    @Test
    void completeMovesToCompleted() {
        var user = seedUser();
        var lesson = seedLesson();
        em.flush();

        var result = service.complete(user.getId(), lesson.getId());

        assertEquals("COMPLETED", result.status());
        assertNotNull(result.startedAt());
        assertNotNull(result.completedAt());
    }

    @Test
    void completeIsIdempotent() {
        var user = seedUser();
        var lesson = seedLesson();
        em.flush();

        service.complete(user.getId(), lesson.getId());
        var again = service.complete(user.getId(), lesson.getId());

        assertEquals("COMPLETED", again.status());
        assertEquals(1, progressRepository.findByUserId(user.getId()).size());
    }

    @Test
    void startAfterCompleteDoesNotRegress() {
        var user = seedUser();
        var lesson = seedLesson();
        em.flush();

        service.complete(user.getId(), lesson.getId());
        var result = service.start(user.getId(), lesson.getId());

        assertEquals("COMPLETED", result.status());
    }

    @Test
    void missingOrInvalidLessonThrowsNotFound() {
        var user = seedUser();
        em.flush();

        assertThrowsNotFound(() -> service.status(user.getId(), 999999L));
        assertThrowsNotFound(() -> service.start(user.getId(), 0L));
        assertThrowsNotFound(() -> service.complete(user.getId(), null));
    }

    @Test
    void draftLessonIsNotAccepted() {
        var user = seedUser();
        var draft = seedDraftLesson();
        em.flush();

        assertThrowsNotFound(() -> service.start(user.getId(), draft.getId()));
    }

    private void assertThrowsNotFound(Executable action) {
        ApiException ex = assertThrows(ApiException.class, action);
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
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

    private Lesson seedDraftLesson() {
        Textbook book = Textbook.builder().title("Minna").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(book);
        Unit unit = Unit.builder().textbook(book).title("Bài 1").orderIndex(1).build();
        em.persist(unit);
        Tag tag = Tag.builder().code("N4-DRAFT").name("Draft tag").tagType("KNOWLEDGE").build();
        em.persist(tag);
        Lesson lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Draft lesson")
                .status("DRAFT").orderIndex(2).lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }
}

package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.service.LessonDetailService;
import com.jlas.learning.service.impl.LessonDetailServiceImpl;
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
        "spring.datasource.url=jdbc:h2:mem:lesson-detail-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LessonDetailServiceTest.DetailTestConfiguration.class)
class LessonDetailServiceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.learning.repository")
    @Import(LessonDetailServiceImpl.class)
    static class DetailTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LessonDetailService service;

    @Test
    void mapsPublishedLessonWithCatalogContext() {
        var book = Textbook.builder().title("Minna").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Bài 1").orderIndex(1).build();
        em.persist(unit);
        var tag = Tag.builder().code("N4-PARTICLE").name("Trợ từ").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Trợ từ cơ bản")
                .description("Học về trợ từ.").status("PUBLISHED").estimatedMinutes(10)
                .orderIndex(1).lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        em.flush();
        em.clear();

        var result = service.findPublished(lesson.getId()).orElseThrow();

        assertEquals(lesson.getId(), result.id());
        assertEquals("Trợ từ cơ bản", result.title());
        assertEquals("Học về trợ từ.", result.description());
        assertEquals("GRAMMAR", result.skill());
        assertEquals("N4", result.textbookLevel());
        assertEquals(10, result.estimatedMinutes());
        assertEquals("Trợ từ", result.knowledgeTagName());
        assertEquals("Minna", result.textbookTitle());
        assertEquals("Bài 1", result.unitTitle());
    }

    @Test
    void excludesDraftMissingAndInvalidLessons() {
        var book = Textbook.builder().title("Minna").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Bài 1").orderIndex(1).build();
        em.persist(unit);
        var tag = Tag.builder().code("N4-X").name("X").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var draft = Lesson.builder().unit(unit).knowledgeTag(tag).title("Draft")
                .status("DRAFT").orderIndex(1).lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(draft);
        em.flush();
        em.clear();

        assertTrue(service.findPublished(draft.getId()).isEmpty());
        assertTrue(service.findPublished(999999L).isEmpty());
        assertTrue(service.findPublished(null).isEmpty());
        assertTrue(service.findPublished(0L).isEmpty());
    }
}

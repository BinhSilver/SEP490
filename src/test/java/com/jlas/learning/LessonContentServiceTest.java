package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.learning.service.LessonContentService;
import com.jlas.learning.service.impl.LessonContentServiceImpl;
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
        "spring.datasource.url=jdbc:h2:mem:content-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@ContextConfiguration(classes = LessonContentServiceTest.ContentTestConfiguration.class)
class LessonContentServiceTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackages = "com.jlas.learning.repository")
    @Import(LessonContentServiceImpl.class)
    static class ContentTestConfiguration { }

    @Autowired EntityManager em;
    @Autowired LessonContentService service;

    @Test
    void mapsSectionsItemsAndGrammarPoints() {
        var lesson = seedLesson("PUBLISHED");

        var explanation = LessonSection.builder().lesson(lesson).title("Giải thích")
                .sectionType("EXPLANATION").orderIndex(1).build();
        em.persist(explanation);
        var example = LessonSection.builder().lesson(lesson).title("Ví dụ")
                .sectionType("EXAMPLE").orderIndex(2).build();
        em.persist(example);

        var item1 = ContentItem.builder().section(explanation).contentType("GRAMMAR")
                .title("は").content("Giải thích は").orderIndex(1).build();
        em.persist(item1);
        var item2 = ContentItem.builder().section(example).contentType("READING")
                .title("Ví dụ 1").content("私は学生です。").translatedText("Tôi là học sinh.").orderIndex(1).build();
        em.persist(item2);

        var grammar = GrammarPoint.builder().lesson(lesson).pattern("A は B です")
                .meaning("A là B").example("私は学生です。").note("Chú thích").orderIndex(1).build();
        em.persist(grammar);

        em.flush();

        var result = service.findPublished(lesson.getId()).orElseThrow();

        assertEquals(lesson.getId(), result.lessonId());
        assertEquals(2, result.sections().size());
        assertEquals("EXPLANATION", result.sections().get(0).sectionType());
        assertEquals(1, result.sections().get(0).items().size());
        assertEquals("Giải thích は", result.sections().get(0).items().get(0).content());
        assertEquals("EXAMPLE", result.sections().get(1).sectionType());
        assertEquals("Tôi là học sinh.", result.sections().get(1).items().get(0).translatedText());
        assertEquals(1, result.grammarPoints().size());
        assertEquals("A は B です", result.grammarPoints().get(0).pattern());
    }

    @Test
    void excludesDraftMissingAndInvalidLessons() {
        var draft = seedLesson("DRAFT");
        em.flush();

        assertTrue(service.findPublished(draft.getId()).isEmpty());
        assertTrue(service.findPublished(999999L).isEmpty());
        assertTrue(service.findPublished(null).isEmpty());
        assertTrue(service.findPublished(0L).isEmpty());
    }

    private Lesson seedLesson(String status) {
        var book = Textbook.builder().title("Minna").status("PUBLISHED").jlptLevel("N4").build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Bài 1").orderIndex(1).build();
        em.persist(unit);
        var tag = Tag.builder().code("N4-PARTICLE").name("Trợ từ").tagType("KNOWLEDGE").build();
        em.persist(tag);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).title("Trợ từ cơ bản")
                .status(status).estimatedMinutes(10).orderIndex(1)
                .lessonType("CURRICULUM").skill("GRAMMAR").build();
        em.persist(lesson);
        return lesson;
    }
}

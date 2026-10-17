package com.jlas.learning;

import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.dictionary.repository.TextbookRepository;
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

@DataJpaTest(properties = { "spring.profiles.include=", "spring.datasource.url=jdbc:h2:mem:foundation-content-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop" })
@ContextConfiguration(classes = FoundationContentItemRepositoryTest.Config.class)
class FoundationContentItemRepositoryTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan("com.jlas")
    @EnableJpaRepositories(basePackageClasses = ContentItemRepository.class)
    static class Config { }

    @Autowired EntityManager em;
    @Autowired ContentItemRepository repository;
    @Autowired TextbookRepository textbooks;

    @Test void foundationPathSelectsOnlyPublishedFlaggedKanaBooks() {
        var tag = Tag.builder().code("KANA-TEST").name("Kana test").tagType("KNOWLEDGE").build();
        em.persist(tag);
        persistKanaBook("Visible", "PUBLISHED", true, tag);
        persistKanaBook("Draft", "DRAFT", true, tag);
        persistKanaBook("Unflagged", "PUBLISHED", false, tag);
        em.flush();
        em.clear();

        var selected = textbooks.findKanaFoundationByStatus("PUBLISHED");

        assertEquals(List.of("Visible"), selected.stream().map(Textbook::getTitle).toList());
    }

    private void persistKanaBook(String title, String status, boolean learningSource, Tag tag) {
        var book = Textbook.builder().title(title).status(status).isLearningPathSource(learningSource).build();
        em.persist(book);
        var unit = Unit.builder().textbook(book).title("Kana").build();
        em.persist(unit);
        em.persist(Lesson.builder().unit(unit).knowledgeTag(tag).title("Kana")
                .lessonType("CURRICULUM").skill("READING").build());
    }

    @Test void loadsWholeWordWithItsSourceLessonInOneRepositoryRead() {
        var tag = Tag.builder().code("KANA-TEST").name("Kana test").tagType("KNOWLEDGE").build();
        var book = Textbook.builder().sourceCode("LP-OWNED-KANA-SOUNDS-TEST").title("Kana words")
                .status("DRAFT").build();
        em.persist(tag);
        em.persist(book);
        var unit = Unit.builder().textbook(book).sourceCode("KANA-WORDS-TEST").title("Kana rules").build();
        em.persist(unit);
        var lesson = Lesson.builder().unit(unit).knowledgeTag(tag).sourceCode("KANA-RULE-01")
                .title("Âm ngắt").lessonType("CURRICULUM").skill("READING").build();
        em.persist(lesson);
        var section = LessonSection.builder().lesson(lesson).title("Từ ví dụ").sectionType("EXAMPLE").build();
        em.persist(section);
        var item = ContentItem.builder().section(section).contentType("READING").title("kippu")
                .content("きっぷ").translatedText("vé").build();
        em.persist(item);
        em.flush();
        em.clear();

        List<ContentItem> rows = repository.findAllWithLessonByIdIn(List.of(item.getId()));

        assertEquals(1, rows.size());
        assertEquals("きっぷ", rows.getFirst().getContent());
        assertTrue(em.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(rows.getFirst(), "section"));
        assertTrue(em.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(rows.getFirst().getSection(), "lesson"));
        assertEquals("KANA-RULE-01", rows.getFirst().getSection().getLesson().getSourceCode());
    }
}
